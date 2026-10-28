package dev.salt.desktop.sync

import dev.salt.desktop.library.DesktopLibrary
import dev.salt.desktop.statistics.DesktopStatistics
import dev.salt.replica.statistics.HistoryRecord
import dev.salt.replica.sync.*
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.FilterInputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class DesktopSyncRepository(
    private val desktopLibrary: DesktopLibrary,
    private val statistics: DesktopStatistics,
    private val bindAddress: InetAddress? = null,
    private val discoveryEnabled: Boolean = true,
    private val allowLoopback: Boolean = false,
    private val addressProvider: (() -> InetAddress)? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycle = Mutex()
    private val coordination = Any()
    private val mutable = MutableStateFlow(LanSyncState(message = "正在初始化"))
    val state = mutable.asStateFlow()
    private lateinit var preferences: SyncPreferences
    private lateinit var identity: SyncIdentity
    private lateinit var library: SyncLibrary
    private lateinit var directory: Path
    @Volatile private var initialized = false
    @Volatile private var pageOpen = false
    @Volatile private var closed = false
    @Volatile private var server: Server? = null
    private val discoveries = linkedMapOf<InetAddress, LanDiscovery>()
    private val discoveryTokens = ConcurrentHashMap<InetAddress, Long>()
    @Volatile private var discoveryGeneration = 0L
    private val services = ConcurrentHashMap<String, SyncPeer>()
    private val clients = ConcurrentHashMap<String, OkHttpClient>()
    @Volatile private var pairing: Job? = null
    @Volatile private var transfer: Job? = null
    @Volatile private var cancelling: Job? = null
    @Volatile private var currentCall: Call? = null
    @Volatile private var remoteTask: String? = null
    @Volatile private var remotePeer: String? = null
    @Volatile private var leaseTime = 0L
    private var lastPair = 0L
    private val streams = Semaphore(2)
    private data class Pending(
        val id: String, val name: String, val pin: String, val nonce: String, val back: String,
        val token: String = token(), val created: Long = elapsed(), @Volatile var approved: Boolean = false,
    )
    @Volatile private var pending: Pending? = null
    private data class Manifest(val token: String, val songs: List<SyncSong>)
    private val manifests = ConcurrentHashMap<String, Manifest>()

    suspend fun initialize(path: Path) = withContext(Dispatchers.IO) {
        try {
            check(statistics.persistent) { "统计数据目录不可写，暂时无法保存设备配对" }
            directory = path
            preferences = SyncPreferences(path)
            identity = SyncIdentity(statistics.history.origin, preferences)
            library = SyncLibrary(desktopLibrary, path)
            change { it.copy(localName = preferences.get("name") ?: System.getenv("COMPUTERNAME") ?: "Windows",
                message = "", error = null) }
            restorePlan()
            initialized = true
            scope.launch {
                desktopLibrary.state.map { value -> value.tracks.filter { it.available }.map { it.id }.toSet() }
                    .distinctUntilChanged().drop(1).collect {
                        val root = syncHashRoot(desktopLibrary.state.value.tracks.filter { track -> track.available }.map { track -> track.id })
                        change { state -> if (state.comparison?.localRoot != root) state.copy(comparison = null) else state }
                    }
            }
            scope.launch {
                while (isActive) {
                    delay(5_000)
                    synchronized(coordination) {
                        if (remoteTask != null && elapsed() - leaseTime > 120_000) {
                            remoteTask = null; remotePeer = null
                            change { it.copy(remoteSending = false, message = "对方连接已结束") }
                        }
                    }
                    // JmDNS remains bound to its original interface after DHCP/Wi-Fi changes.
                    // Rebind only while idle; an active transfer keeps its existing session.
                    if (pageOpen && !busy() && pairing?.isActive != true && pending == null) {
                        try { ensureServer() } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            lifecycle.withLock {
                                stopDiscovery()
                                services.clear()
                                change { it.copy(serving = false, address = "", peers = emptyList(),
                                    error = e.message?.take(180)) }
                            }
                        }
                    }
                    stopIfIdle()
                }
            }
            if (pageOpen) start()
        } catch (e: Exception) { failure(e) }
    }

    fun setPageOpen(open: Boolean) {
        pageOpen = open
        if (open && initialized) start() else stopIfIdle()
    }

    private fun change(block: (LanSyncState) -> LanSyncState) { mutable.update(block) }
    private fun busy() = transfer?.isActive == true || cancelling?.isActive == true || remoteTask != null || state.value.phase == SyncPhase.TRANSFERRING
    private fun trusted(id: String) = preferences.get("peer.$id.pin")
    private fun outbound(id: String) = preferences.get("peer.$id.out")
    private fun inbound(id: String) = preferences.get("peer.$id.in")
    private fun trust(id: String, pin: String, incoming: String, outgoing: String) {
        preferences.edit("peer.$id.pin" to pin, "peer.$id.in" to incoming, "peer.$id.out" to outgoing)
    }

    private fun addresses(): List<InetAddress> {
        val supplied = bindAddress ?: addressProvider?.invoke()
        if (supplied != null) return listOf(supplied)
        return NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filter { it is Inet4Address && it.isSiteLocalAddress }.distinct()
            .also { check(it.isNotEmpty()) { "尚未连接局域网" } }
    }

    private fun validateHost(host: String) {
        require(host.matches(Regex("[0-9.]+")) || host.contains(':')) { "请输入局域网 IP 地址" }
        val ip = InetAddress.getByName(host)
        require(ip.isSiteLocalAddress || allowLoopback && ip.isLoopbackAddress) { "仅支持局域网地址" }
    }

    fun start(): Job = scope.launch {
        try { ensureServer() } catch (e: Exception) { failure(e) }
    }

    private suspend fun ensureServer() = lifecycle.withLock {
        check(initialized && !closed) { "同步服务尚未就绪" }
        val ips = addresses()
        if (server == null) {
            val candidate = Server()
            try {
                candidate.makeSecure(identity.serverFactory, null)
                candidate.start(15_000, true)
            } catch (e: Exception) { candidate.stop(); throw e }
            server = candidate
        }
        val nextAddress = ips.joinToString("\n") { "${it.hostAddress}:${requireNotNull(server).listeningPort}" }
        if (state.value.address != nextAddress || !state.value.serving) {
            change { it.copy(serving = true, address = nextAddress,
                error = if (!it.serving) null else it.error,
                phase = if (!it.serving && it.phase == SyncPhase.FAILED && it.error == "尚未连接局域网") SyncPhase.READY else it.phase) }
        }
        startDiscovery(ips)
    }

    private fun stopDiscovery() {
        discoveryGeneration++
        discoveryTokens.clear()
        val old = discoveries.values.toList(); discoveries.clear()
        old.forEach { runCatching { it.close() } }
    }

    private fun startDiscovery(ips: List<InetAddress> = addresses()) {
        if (!discoveryEnabled || !pageOpen && !busy()) return
        val removed = discoveries.keys.filter { it !in ips }
        for (ip in removed) {
            discoveryTokens.remove(ip)
            discoveries.remove(ip)?.let { runCatching { it.close() } }
            services.keys.removeAll { it.startsWith("${ip.hostAddress}/") }
        }
        if (removed.isNotEmpty()) change { it.copy(peers = services.values.distinctBy { peer -> peer.id }, message = "网络已切换，正在重新发现设备") }
        for (ip in ips.filter { it !in discoveries }) {
        val generation = ++discoveryGeneration; discoveryTokens[ip] = generation
        try {
            discoveries[ip] = LanDiscovery(ip, identity, state.value.localName, requireNotNull(server).listeningPort) { name, peer ->
                if (discoveryTokens[ip] != generation || closed) return@LanDiscovery
                val key = "${ip.hostAddress}/$name"
                if (peer == null) services.remove(key) else services[key] = peer
                change { it.copy(peers = services.values.distinctBy { p -> p.id }.sortedBy { p -> p.name },
                    peer = if (peer != null && it.peer?.id == peer.id && it.peer.pin == peer.pin) peer else it.peer) }
            }
        } catch (e: Exception) {
            discoveryTokens.remove(ip)
            change { it.copy(error = "${ip.hostAddress} 设备发现不可用：${e.message?.take(60)}。可使用地址连接") }
        }
        }
    }

    private fun stopIfIdle() {
        if (closed) return
        scope.launch {
            delay(700)
            lifecycle.withLock {
                if (!pageOpen && !busy() && pairing?.isActive != true && pending == null) {
                    stopDiscovery()
                    server?.stop(); server = null
                    services.clear(); manifests.clear()
                    change { it.copy(serving = false, address = "", peers = emptyList()) }
                }
            }
        }
    }

    fun scan() {
        if (!initialized || busy()) return
        scope.launch {
            try {
                change { it.copy(phase = SyncPhase.SCANNING, error = null) }
                ensureServer()
                lifecycle.withLock {
                    stopDiscovery(); services.clear()
                    change { it.copy(peers = emptyList()) }
                    startDiscovery()
                }
                delay(8_000)
                change { if (it.phase == SyncPhase.SCANNING) it.copy(phase = SyncPhase.READY,
                    message = if (it.peers.isEmpty()) "暂未发现设备" else "扫描完成") else it }
            } catch (e: Exception) { if (e !is CancellationException) failure(e) }
        }
    }

    fun rename(name: String) {
        if (!initialized) return
        scope.launch {
            try {
                val next = name.trim().take(32).ifBlank { "Windows" }
                preferences.edit("name" to next)
                change { it.copy(localName = next) }
                lifecycle.withLock { stopDiscovery(); if (server != null) startDiscovery() }
            } catch (e: Exception) { failure(e) }
        }
    }

    private fun client(peer: SyncPeer) = clients.getOrPut(peer.pin) { identity.client(peer.pin) }
    private fun url(peer: SyncPeer, path: String, params: Map<String, String> = emptyMap()): HttpUrl {
        validateHost(peer.host)
        return HttpUrl.Builder().scheme("https").host(peer.host).port(peer.port).addPathSegments(path.removePrefix("/"))
            .apply { params.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
    }

    private suspend fun execute(client: OkHttpClient, request: Request): JSONObject = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use { r ->
                        val json = JSONObject(requireNotNull(r.body).byteStream().readBoundedSyncJson())
                        check(r.isSuccessful) { json.optString("error", "连接失败 (${r.code})") }
                        json
                    }
                }
                if (continuation.isActive) result.fold(continuation::resume, continuation::resumeWithException)
            }
        })
    }

    private suspend fun request(peer: SyncPeer, path: String, body: JSONObject? = null,
                                params: Map<String, String> = emptyMap(), auth: Boolean = true): JSONObject {
        val builder = Request.Builder().url(url(peer, path, params)).header("X-Sync-Device", identity.id)
        if (auth) builder.header("Authorization", "Bearer ${outbound(peer.id) ?: error("请先配对设备")}")
        if (body != null) builder.post(body.toString().toRequestBody("application/json".toMediaType()))
        return execute(client(peer), builder.build())
    }

    fun connectAddress(address: String) = synchronized(coordination) {
        if (!initialized || busy() || pending != null || pairing?.isActive == true) return@synchronized
        change { it.copy(phase = SyncPhase.COMPARING, comparison = null, error = null) }
        pairing = scope.launch {
            try {
                ensureServer()
                val target = parseSyncAddress(address)
                val host = target.host
                validateHost(host)
                var observed: String? = null
                val bootstrap = identity.client(null) { observed = it }
                val peer = try {
                    val result = execute(bootstrap, Request.Builder().url(HttpUrl.Builder().scheme("https").host(host)
                        .port(target.port).addPathSegment("identity").build()).build())
                    parseSyncIdentity(result, observed, identity.id, target)
                } finally { dispose(bootstrap) }
                require(peer.id != identity.id) { "这是本机地址" }
                services["manual:${peer.id}"] = peer
                change { it.copy(peers = services.values.distinctBy { p -> p.id }) }
                synchronized(coordination) { pairing = null; select(peer) }
            } catch (e: Exception) { if (e !is CancellationException) failure(e) }
            finally { if (pairing === currentCoroutineContext()[Job]) pairing = null; stopIfIdle() }
        }
    }

    fun select(peer: SyncPeer) = synchronized(coordination) {
        if (!initialized || busy() || pending != null || pairing?.isActive == true) return@synchronized
        pairing?.cancel()
        change { it.copy(peer = peer, songs = emptyList(), selected = emptySet(), completed = emptySet(),
            phase = SyncPhase.COMPARING, error = null, historyChanges = 0, comparison = null) }
        pairing = scope.launch {
            try {
                ensureServer()
                val old = trusted(peer.id)
                check(old == null || old == peer.pin) { "设备身份已变化，请移除配对后重新连接" }
                if (old != peer.pin || outbound(peer.id) == null) {
                    val nonce = UUID.randomUUID().toString()
                    val back = token()
                    val result = requestSyncPair { request(peer, "pair", JSONObject().put("id", identity.id).put("name", state.value.localName)
                        .put("pin", identity.pin).put("nonce", nonce).put("backToken", back), auth = false) }
                    val code = pairCode(peer.pin, nonce)
                    check(result.getString("code") == code) { "配对码不匹配" }
                    change { it.copy(prompt = PairPrompt(peer.name, code, false), phase = SyncPhase.WAITING) }
                    withTimeout(120_000) {
                        while (true) {
                            delay(350)
                            if (state.value.prompt?.localConfirmed == true) {
                                val status = request(peer, "pair/status", JSONObject().put("nonce", nonce), auth = false)
                                if (status.optBoolean("approved")) {
                                    val outgoing = status.getString("token")
                                    require(outgoing.length == 72)
                                    request(peer, "pair/finish", JSONObject().put("nonce", nonce).put("token", outgoing), auth = false)
                                    trust(peer.id, peer.pin, back, outgoing)
                                    change { it.copy(prompt = null) }
                                    break
                                }
                            }
                        }
                    }
                }
                compare(peer)
            } catch (e: Exception) {
                if (e is TimeoutCancellationException) failure(IllegalStateException("配对已超时，请重试"))
                else if (e !is CancellationException) failure(e)
            } finally { stopIfIdle() }
        }
    }

    private fun pairCode(otherPin: String, nonce: String): String =
        (digest("${listOf(identity.pin, otherPin).sorted().joinToString(":")}:$nonce".toByteArray())
            .take(12).toLong(16) % 1_000_000).toString().padStart(6, '0')

    fun confirmPair() {
        val prompt = state.value.prompt ?: return
        synchronized(coordination) { if (prompt.incoming) pending?.approved = true }
        change { it.copy(prompt = prompt.copy(localConfirmed = true), message = "等待对方确认") }
    }

    fun cancelPair() {
        pairing?.cancel()
        synchronized(coordination) { pending = null }
        change { it.copy(prompt = null, phase = SyncPhase.READY, message = "配对已取消") }
        stopIfIdle()
    }

    fun forgetPeer() {
        if (!initialized || busy() || state.value.prompt != null) return
        val peer = state.value.peer ?: return
        pairing?.cancel()
        scope.launch {
            try {
                val oldPin = trusted(peer.id)
                preferences.edit("peer.${peer.id}.pin" to null, "peer.${peer.id}.in" to null, "peer.${peer.id}.out" to null,
                    "plan" to null)
                listOfNotNull(oldPin, peer.pin).distinct().forEach { clients.remove(it)?.let(::dispose) }
                change { it.copy(peer = null, songs = emptyList(), selected = emptySet(), completed = emptySet(),
                    phase = SyncPhase.READY, error = null, message = "已移除配对", comparison = null) }
            } catch (e: Exception) { failure(e) }
        }
    }

    fun refreshComparison() { state.value.peer?.let(::select) }

    private suspend fun compare(peer: SyncPeer) {
        change { it.copy(phase = SyncPhase.COMPARING, prompt = null, message = "正在对比音乐库") }
        val difference = compareAndReport(peer)
        val missing = difference.receive
        change { it.copy(peer = peer, phase = SyncPhase.READY, songs = missing, selected = missing.map { s -> s.hash }.toSet(),
            completed = emptySet(), skipped = difference.summary.commonCount, message = "已连接 ${peer.name}", error = null,
            bytes = 0, totalBytes = 0, historyChanges = 0, comparison = difference.summary) }
    }

    private suspend fun compareAndReport(peer: SyncPeer): SyncLibraryDifference {
        val remote = readSyncManifest { offset, snapshot ->
            request(peer, "manifest", params = mapOf("offset" to offset.toString(), "snapshot" to snapshot))
        }
        val difference = compareSyncLibraries(library.refresh(), remote)
        request(peer, "comparison", JSONObject().put("comparison", difference.summary.json()).put("device",
            JSONObject().put("id", identity.id).put("name", state.value.localName).put("pin", identity.pin)
                .put("port", requireNotNull(server).listeningPort)))
        return difference
    }

    fun toggle(hash: String) { if (!busy()) change { it.copy(selected = if (hash in it.selected) it.selected - hash else it.selected + hash, phase = SyncPhase.READY) } }
    fun selectAll(visible: Set<String>? = null) { if (!busy()) change { it.copy(selected = toggleSyncSelection(it.selected, it.songs, visible), phase = SyncPhase.READY) } }

    fun begin() = synchronized(coordination) {
        if (!initialized || busy() || pairing?.isActive == true || pending != null) return@synchronized
        val initial = state.value
        val peer = initial.peer ?: return@synchronized
        if (initial.songs.isEmpty() && (initial.comparison?.missingLocal ?: 0) > 0) {
            refreshComparison(); return@synchronized
        }
        val songs = initial.songs.filter { it.hash in initial.selected }
        val previous = transfer
        change { it.copy(phase = SyncPhase.TRANSFERRING, error = null, message = "正在建立传输",
            totalBytes = songs.sumOf { s -> s.size }, completed = it.completed.intersect(initial.selected), historyChanges = 0) }
        transfer = scope.launch {
            val task = UUID.randomUUID().toString()
            try {
                previous?.join()
                check(trusted(peer.id) == peer.pin && outbound(peer.id) != null) { "请先重新连接并配对设备" }
                persistPlan(peer, songs)
                ensureServer()
                val currentIdentity = parseSyncIdentity(request(peer, "identity", auth = false), peer.pin, identity.id, SyncAddress(peer.host, peer.port))
                require(currentIdentity.id == peer.id) { "设备身份已变化，请重新配对" }
                request(peer, "transfer/begin", JSONObject().put("task", task))
                val own = library.refresh().map { it.hash }.toMutableSet()
                for (song in songs) {
                    ensureActive()
                    if (song.hash !in own) {
                        val part = library.part(song)
                        if (Files.exists(part) && Files.size(part) > song.size) Files.delete(part)
                        val offset = if (Files.exists(part)) Files.size(part) else 0
                        check(Files.getFileStore(directory).usableSpace >= song.size - offset + 8_000_000) { "磁盘剩余空间不足" }
                        val completedBytes = songs.filter { it.hash in own }.sumOf { it.size }
                        change { it.copy(currentHash = song.hash, bytes = completedBytes + offset, message = "正在接收 ${song.title}") }
                        receiveFile(peer, song, task, part, completedBytes)
                        library.publish(song, part)
                        own += song.hash
                    }
                    change { it.copy(completed = it.completed + song.hash, bytes = songs.filter { s -> s.hash in own }.sumOf { s -> s.size }) }
                    persistPlan(peer, songs)
                }
                change { it.copy(currentHash = null, message = "正在双向合并播放记录") }
                val changes = exchangeHistory(peer)
                statistics.refresh()
                val comparison = compareAndReport(peer).summary
                preferences.edit("plan" to null)
                change { it.copy(phase = SyncPhase.COMPLETE, historyChanges = changes, bytes = it.totalBytes,
                    message = "同步完成", comparison = comparison) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure(e) }
            finally {
                currentCall = null
                withContext(NonCancellable) {
                    withTimeoutOrNull(3_000) { runCatching { request(peer, "transfer/end", JSONObject().put("task", task)) } }
                }
                if (transfer === currentCoroutineContext()[Job]) transfer = null
                stopIfIdle()
            }
        }
    }

    private suspend fun receiveFile(peer: SyncPeer, song: SyncSong, task: String, part: Path, completedBytes: Long) {
        var failures = 0
        while (!Files.exists(part) || Files.size(part) < song.size) {
            currentCoroutineContext().ensureActive()
            val offset = if (Files.exists(part)) Files.size(part) else 0
            val call = client(peer).newCall(Request.Builder().url(url(peer, "file", mapOf("hash" to song.hash,
                "offset" to offset.toString(), "task" to task))).header("X-Sync-Device", identity.id)
                .header("Authorization", "Bearer ${outbound(peer.id)}").build())
            currentCall = call
            try {
                call.execute().use { response ->
                    check(response.isSuccessful) { "文件传输失败 (${response.code})" }
                    check(response.header("X-Sync-Hash") == song.hash && response.header("X-Sync-Offset") == offset.toString()) { "文件响应不匹配" }
                    val body = requireNotNull(response.body)
                    check(body.contentLength() == song.size - offset) { "文件响应长度不匹配" }
                    java.io.FileOutputStream(part.toFile(), true).use { output ->
                        val buffer = ByteArray(131072)
                        var position = offset
                        var last = 0L
                        val input = body.byteStream()
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            check(position + count <= song.size) { "文件长度不匹配" }
                            output.write(buffer, 0, count)
                            position += count
                            if (elapsed() - last >= 90) { last = elapsed(); change { it.copy(bytes = completedBytes + position) } }
                        }
                        output.fd.sync()
                    }
                }
                if (Files.size(part) < song.size) throw java.io.EOFException("连接中断，可继续接收")
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                if (++failures > 3) throw e
                change { it.copy(message = "连接中断，正在重连 ($failures/3)") }
                delay(failures * 1_000L)
            } finally { currentCall = null }
        }
    }

    fun pause() {
        currentCall?.cancel(); transfer?.cancel()
        change { it.copy(phase = SyncPhase.PAUSED, currentHash = null, error = null, message = "同步已暂停，可继续接收") }
    }

    fun cancelTransfer() = synchronized(coordination) {
        if (cancelling?.isActive == true) return@synchronized
        val previous = transfer
        pause()
        cancelling = scope.launch {
            previous?.join()
            try {
                preferences.edit("plan" to null)
                change { it.copy(phase = SyncPhase.READY, message = "同步已取消，已完成歌曲已保留", selected = it.selected - it.completed) }
            } catch (e: Exception) { if (e !is CancellationException) failure(e) }
            finally { cancelling = null; stopIfIdle() }
        }
    }

    private fun persistPlan(peer: SyncPeer, songs: List<SyncSong>) {
        preferences.edit("plan" to JSONObject().put("peer", peer.json()).put("songs", JSONArray(songs.map { it.json() }))
            .put("done", JSONArray(state.value.completed.toList())).toString())
    }

    private fun restorePlan() {
        val raw = preferences.get("plan") ?: return
        val json = JSONObject(raw)
        val rows = json.getJSONArray("songs")
        require(rows.length() <= 100_000)
        val songs = (0 until rows.length()).map { SyncSong.parse(rows.getJSONObject(it)) }
        val done = json.getJSONArray("done")
        change { it.copy(peer = SyncPeer.parse(json.getJSONObject("peer")), songs = songs, selected = songs.map { s -> s.hash }.toSet(),
            completed = (0 until done.length()).map { done.getString(it) }.toSet(), phase = SyncPhase.PAUSED, message = "上次同步尚未完成，可继续") }
    }

    private suspend fun exchangeHistory(peer: SyncPeer): Int {
        var changed = 0
        val info = request(peer, "history/info")
        val epoch = info.getString("epoch")
        require(epoch.length in 1..80)
        val key = "cursor.${peer.id}"
        var after = if (preferences.get("$key.epoch") == epoch) preferences.get("$key.value")?.toLongOrNull() ?: 0 else 0
        while (true) {
            val batch = parseSyncHistoryBatch(request(peer, "history", params = mapOf("after" to after.toString())), epoch, after)
            changed += statistics.history.merge(batch.records)
            after = batch.next
            preferences.edit("$key.epoch" to epoch, "$key.value" to after.toString())
            if (batch.records.size < SYNC_PAGE_SIZE) break
        }
        val localEpoch = statistics.history.epoch()
        var pushAfter = if (info.optString("receivedEpoch") == localEpoch) info.optLong("receivedCursor") else 0
        require(pushAfter >= 0)
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = statistics.history.export(pushAfter)
            val result = request(peer, "history/import", JSONObject().put("epoch", page.epoch).put("next", page.next)
                .put("records", JSONArray(page.records.map { it.json() })))
            changed += result.getInt("changed")
            pushAfter = page.next
            if (page.records.size < 200) break
        }
        return changed
    }

    private fun failure(e: Throwable) {
        Logger.getLogger("dev.salt.desktop.sync").log(Level.WARNING, "LAN synchronization failed", e)
        change { it.copy(phase = SyncPhase.FAILED, prompt = null, currentHash = null,
            error = e.message?.take(180) ?: "连接失败，请重试", message = "同步未完成") }
    }

    private inner class Server : NanoHTTPD(bindAddress?.hostAddress, 0) {
        override fun serve(session: IHTTPSession): Response { return try {
            val source = InetAddress.getByName(session.remoteIpAddress)
            require(source.isSiteLocalAddress || allowLoopback && source.isLoopbackAddress) { "仅接受局域网连接" }
            val peer = session.headers["x-sync-device"].orEmpty()
            val public = session.uri in listOf("/identity", "/pair", "/pair/status", "/pair/finish")
            if (!public) {
                val expected = inbound(peer)
                val actual = session.headers["authorization"].orEmpty().removePrefix("Bearer ")
                if (expected == null || !MessageDigest.isEqual(expected.toByteArray(), actual.toByteArray())) {
                    return json(401, JSONObject().put("error", "请先配对设备"))
                }
                leaseTime = elapsed()
            }
            val post = session.uri in listOf("/pair", "/pair/status", "/pair/finish", "/comparison", "/transfer/begin", "/transfer/end", "/history/import")
            require(session.method == if (post) Method.POST else Method.GET) { "不支持的请求方法" }
            val body = if (post) {
                val length = session.headers["content-length"]?.toLongOrNull() ?: 0
                require(length in 1..1_048_576) { "请求过大" }
                // JSON endpoints are bounded; NanoHTTPD's multipart spool needs a writable system temp directory.
                val bytes = session.inputStream.readNBytes(length.toInt())
                require(bytes.size.toLong() == length) { "请求数据不完整" }
                JSONObject(bytes.toString(Charsets.UTF_8))
            } else JSONObject()
            when (session.uri) {
                "/identity" -> json(200, JSONObject().put("v", SYNC_PROTOCOL).put("id", identity.id).put("name", state.value.localName).put("pin", identity.pin))
                "/pair" -> synchronized(coordination) {
                    check(pageOpen && !busy() && pairing?.isActive != true) { "对方暂不可配对" }
                    check(elapsed() - lastPair > 1_000) { "请稍后重试" }
                    lastPair = elapsed()
                    val next = Pending(body.getString("id"), body.getString("name").take(40), body.getString("pin"), body.getString("nonce"), body.getString("backToken"))
                    require(next.id.length in 1..80 && next.id != identity.id && next.nonce.length == 36 &&
                        next.pin.matches(Regex("[a-f0-9]{64}")) && next.back.length == 72)
                    check(pending == null || elapsed() - pending!!.created > 120_000) { "对方正在处理另一项配对" }
                    pending = next
                    change { it.copy(prompt = PairPrompt(next.name, pairCode(next.pin, next.nonce), true), error = null) }
                    scope.launch {
                        delay(120_000)
                        synchronized(coordination) { if (pending === next) { pending = null; change { it.copy(prompt = null) } } }
                        stopIfIdle()
                    }
                    json(200, JSONObject().put("code", pairCode(next.pin, next.nonce)))
                }
                "/pair/status" -> synchronized(coordination) {
                    val pair = requireNotNull(pending)
                    require(body.getString("nonce") == pair.nonce && elapsed() - pair.created < 120_000)
                    json(200, JSONObject().put("approved", pair.approved).apply { if (pair.approved) put("token", pair.token) })
                }
                "/pair/finish" -> synchronized(coordination) {
                    val pair = requireNotNull(pending)
                    require(pair.approved && elapsed() - pair.created < 120_000 && body.getString("nonce") == pair.nonce && body.getString("token") == pair.token)
                    trust(pair.id, pair.pin, pair.token, pair.back)
                    pending = null
                    change { it.copy(prompt = null, message = "已配对 ${pair.name}") }
                    json(200, JSONObject().put("ok", true))
                }
                "/manifest" -> {
                    val offset = session.parameters["offset"]?.firstOrNull()?.toIntOrNull() ?: 0
                    require(offset >= 0)
                    val manifest = if (offset == 0) Manifest(UUID.randomUUID().toString(), runBlocking { library.refresh() }).also { manifests[peer] = it }
                    else requireNotNull(manifests[peer]).also { require(it.token == session.parameters["snapshot"]?.firstOrNull()) }
                    json(200, JSONObject().put("v", SYNC_PROTOCOL).put("snapshot", manifest.token).put("total", manifest.songs.size)
                        .put("songs", JSONArray(manifest.songs.drop(offset).take(200).map { it.json() })))
                }
                "/comparison" -> {
                    val summary = SyncComparison.parse(body.getJSONObject("comparison")).reversed()
                    val local = runBlocking { library.refresh() }
                    check(summary.localCount == local.size && summary.localRoot == syncCatalogRoot(local)) { "曲库已变化，请重新对比" }
                    val device = SyncPeer.parse(body.getJSONObject("device").put("host", session.remoteIpAddress))
                    require(device.id == peer && device.pin == trusted(peer)) { "设备身份不匹配" }
                    synchronized(coordination) {
                        check(remoteTask == null || remotePeer == peer) { "正在与其他设备同步" }
                        change { it.copy(peer = device, comparison = summary,
                            songs = if (it.peer?.id == peer && it.comparison?.remoteRoot == summary.remoteRoot) it.songs else emptyList(),
                            selected = if (it.peer?.id == peer && it.comparison?.remoteRoot == summary.remoteRoot) it.selected else emptySet()) }
                    }
                    json(200, JSONObject().put("ok", true))
                }
                "/transfer/begin" -> synchronized(coordination) {
                    check(remoteTask == null || remotePeer == peer) { "对方正在同步" }
                    check(transfer?.isActive != true && state.value.phase != SyncPhase.TRANSFERRING && pairing?.isActive != true && pending == null) { "对方正在处理其他任务，请稍后重试" }
                    val task = body.getString("task")
                    require(task.length in 1..80)
                    remoteTask = task; remotePeer = peer; leaseTime = elapsed()
                    change { it.copy(remoteSending = true, message = "正在向已配对设备发送音乐") }
                    json(200, JSONObject().put("ok", true))
                }
                "/transfer/end" -> synchronized(coordination) {
                    if (remotePeer == peer && remoteTask == body.optString("task")) {
                        remoteTask = null; remotePeer = null
                        change { it.copy(remoteSending = false, message = "对方同步已结束") }
                        stopIfIdle()
                    }
                    json(200, JSONObject().put("ok", true))
                }
                "/file" -> {
                    require(remotePeer == peer && remoteTask != null && remoteTask == session.parameters["task"]?.firstOrNull())
                    val hash = session.parameters["hash"]?.firstOrNull().orEmpty()
                    val offset = session.parameters["offset"]?.firstOrNull()?.toLongOrNull() ?: 0
                    val song = library.songs.firstOrNull { it.hash == hash } ?: error("歌曲已不可用")
                    require(offset in 0 until song.size)
                    check(streams.tryAcquire()) { "传输忙，请稍后重试" }
                    val input = try { library.open(hash, offset) } catch (e: Exception) { streams.release(); throw e }
                    val stream = object : FilterInputStream(input) {
                        private var released = false
                        override fun read(bytes: ByteArray, off: Int, length: Int): Int { leaseTime = elapsed(); return super.read(bytes, off, length) }
                        override fun close() { if (!released) { released = true; try { super.close() } finally { streams.release() } } }
                    }
                    newFixedLengthResponse(Response.Status.OK, "application/octet-stream", stream, song.size - offset).apply {
                        addHeader("X-Sync-Hash", hash); addHeader("X-Sync-Offset", offset.toString())
                    }
                }
                "/history/info" -> json(200, JSONObject().put("epoch", statistics.history.epoch())
                    .put("receivedEpoch", preferences.get("incoming.$peer.epoch") ?: "")
                    .put("receivedCursor", preferences.get("incoming.$peer.cursor")?.toLongOrNull() ?: 0))
                "/history" -> {
                    val after = session.parameters["after"]?.firstOrNull()?.toLongOrNull() ?: 0
                    require(after >= 0)
                    val page = statistics.history.export(after)
                    json(200, JSONObject().put("epoch", page.epoch).put("next", page.next).put("records", JSONArray(page.records.map { it.json() })))
                }
                "/history/import" -> {
                    val rows = body.getJSONArray("records")
                    val epoch = body.getString("epoch")
                    val next = body.getLong("next")
                    require(rows.length() <= 200 && epoch.length in 1..80 && next >= 0)
                    val changed = statistics.history.merge((0 until rows.length()).map { HistoryRecord.parse(rows.getJSONObject(it)) })
                    preferences.edit("incoming.$peer.epoch" to epoch, "incoming.$peer.cursor" to next.toString())
                    json(200, JSONObject().put("changed", changed))
                }
                else -> json(404, JSONObject().put("error", "不支持的请求"))
            }
        } catch (e: Exception) { json(400, JSONObject().put("error", e.message?.take(160) ?: "请求失败")) } }

        private fun json(code: Int, body: JSONObject) = newFixedLengthResponse(Response.Status.lookup(code), "application/json; charset=utf-8", body.toString())
    }

    suspend fun close() {
        closed = true
        currentCall?.cancel()
        scope.coroutineContext[Job]?.cancelAndJoin()
        withContext(Dispatchers.IO) {
            lifecycle.withLock {
                stopDiscovery()
                server?.stop(); server = null
                clients.values.forEach(::dispose); clients.clear()
            }
        }
    }

    companion object {
        private fun token() = UUID.randomUUID().toString() + UUID.randomUUID().toString()
        private fun elapsed() = System.nanoTime() / 1_000_000
        private fun dispose(client: OkHttpClient) { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }
}
