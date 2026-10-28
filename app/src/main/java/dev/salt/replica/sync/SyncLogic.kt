package dev.salt.replica.sync

import dev.salt.replica.statistics.HistoryRecord
import org.json.JSONObject
import java.net.URI
import java.io.InputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.delay

const val SYNC_PAGE_SIZE = 200
const val SYNC_MAX_SONGS = 100_000
const val SYNC_MAX_JSON = 1_048_576

/** A fast, user-initiated re-pair can overlap the peer's one-second request throttle. */
suspend fun requestSyncPair(request: suspend () -> JSONObject): JSONObject = try { request() }
catch (e: IllegalStateException) {
    if (e.message !in listOf("请稍后重试", "请稍后再试")) throw e
    delay(1100)
    request()
}

data class SyncSourceStamp(val size: Long, val modified: Long, val generation: Long = 0) {
    fun reusableFor(other: SyncSourceStamp) = size > 0 && (modified > 0 || generation > 0) && this == other
}

fun InputStream.readBoundedSyncJson(): String {
    val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer, 0, minOf(buffer.size, SYNC_MAX_JSON + 1 - output.size()))
        if (count < 0) break
        output.write(buffer, 0, count)
        check(output.size() <= SYNC_MAX_JSON) { "对方响应过大" }
    }
    return output.toString(Charsets.UTF_8.name())
}

/** A content comparison, independent of filenames, storage paths and device UI. */
data class SyncComparison(
    val localCount: Int, val remoteCount: Int, val commonCount: Int,
    val localRoot: String, val remoteRoot: String,
) {
    val missingLocal get() = remoteCount - commonCount
    val missingRemote get() = localCount - commonCount
    val identical get() = missingLocal == 0 && missingRemote == 0 && localRoot == remoteRoot
    fun reversed() = SyncComparison(remoteCount, localCount, commonCount, remoteRoot, localRoot)
    fun json() = JSONObject().put("localCount", localCount).put("remoteCount", remoteCount)
        .put("commonCount", commonCount).put("localRoot", localRoot).put("remoteRoot", remoteRoot)
    companion object {
        fun parse(o: JSONObject) = SyncComparison(o.getInt("localCount"), o.getInt("remoteCount"),
            o.getInt("commonCount"), o.getString("localRoot"), o.getString("remoteRoot")).also {
            require(it.localCount in 0..SYNC_MAX_SONGS && it.remoteCount in 0..SYNC_MAX_SONGS &&
                it.commonCount in 0..minOf(it.localCount, it.remoteCount) &&
                listOf(it.localRoot, it.remoteRoot).all { root -> root.matches(Regex("[a-f0-9]{64}")) }) { "曲库比对数据无效" }
            require((it.localRoot == it.remoteRoot) == (it.localCount == it.remoteCount && it.commonCount == it.localCount)) { "曲库比对数据不一致" }
        }
    }
}

data class SyncLibraryDifference(val summary: SyncComparison, val receive: List<SyncSong>, val send: List<SyncSong>)

fun syncHashRoot(hashes: Collection<String>): String = digest(hashes.distinct().sorted().joinToString("\n").toByteArray(Charsets.UTF_8))
fun syncCatalogRoot(songs: List<SyncSong>): String = syncHashRoot(songs.map { it.hash })

fun compareSyncLibraries(local: List<SyncSong>, remote: List<SyncSong>): SyncLibraryDifference {
    val own = local.associateBy { it.hash }; val other = remote.associateBy { it.hash }
    return SyncLibraryDifference(SyncComparison(own.size, other.size, own.keys.count { it in other },
        syncCatalogRoot(local), syncCatalogRoot(remote)), other.values.filter { it.hash !in own }, own.values.filter { it.hash !in other })
}

fun SyncComparison.statusText() = if (identical) "两端曲库一致 · 共 $commonCount 首"
    else "共同 $commonCount 首 · 本机缺 $missingLocal 首 · 对方缺 $missingRemote 首"

fun syncEmptyLibraryText(comparison: SyncComparison?): String = when {
    comparison == null -> "尚未对比音乐库"
    comparison.identical -> "两端曲库一致"
    comparison.missingLocal == 0 -> "本机无需接收，对方还缺 ${comparison.missingRemote} 首"
    else -> "本机缺 ${comparison.missingLocal} 首，重新对比可载入歌曲"
}

fun toggleSyncSelection(current: Set<String>, all: List<SyncSong>, visible: Set<String>?): Set<String> {
    val valid = all.map { it.hash }.toSet(); val targets = visible?.intersect(valid) ?: valid
    return if (current.containsAll(targets)) current - targets else current + targets
}

suspend fun readSyncManifest(request: suspend (Int, String) -> JSONObject): List<SyncSong> {
    val result = mutableListOf<SyncSong>(); val hashes = hashSetOf<String>()
    var snapshot = ""; var expectedTotal: Int? = null
    do {
        val offset = result.size
        val response = request(offset, snapshot)
        require(response.getInt("v") == SYNC_PROTOCOL) { "两端同步协议不同，请更新两端应用" }
        val total = response.getInt("total"); val rows = response.getJSONArray("songs")
        val token = response.getString("snapshot")
        require(total in 0..SYNC_MAX_SONGS && rows.length() <= SYNC_PAGE_SIZE && offset + rows.length() <= total && token.length in 1..80) { "曲库分页无效" }
        check(expectedTotal == null || expectedTotal == total) { "对方音乐库已变化，请重新对比" }
        check(snapshot.isEmpty() || snapshot == token) { "音乐库快照已变化，请重新对比" }
        check(rows.length() > 0 || offset == total) { "对方音乐库数据不完整" }
        expectedTotal = total; snapshot = token
        for (i in 0 until rows.length()) {
            val song = SyncSong.parse(rows.getJSONObject(i))
            check(hashes.add(song.hash)) { "曲库分页包含重复歌曲，请重新对比" }
            result += song
        }
    } while (result.size < requireNotNull(expectedTotal))
    return result
}

data class SyncAddress(val host: String, val port: Int)
fun parseSyncAddress(raw: String): SyncAddress {
    val value = raw.trim()
    val uri = runCatching { URI(if (value.startsWith("https://")) value else "https://$value") }.getOrElse { error("请输入局域网 IP:端口") }
    require(uri.scheme == "https" && uri.userInfo == null && uri.port in 1..65535 &&
        uri.path.orEmpty() in listOf("", "/") && uri.query == null && uri.fragment == null) { "请输入局域网 IP:端口" }
    val host = requireNotNull(uri.host) { "请输入局域网 IP:端口" }.removeSurrounding("[", "]")
    require(host.matches(Regex("[0-9.]+")) || host.contains(':')) { "请输入局域网 IP 地址" }
    return SyncAddress(host, uri.port)
}

fun parseSyncIdentity(json: JSONObject, observedPin: String?, localId: String, address: SyncAddress): SyncPeer {
    require(json.getInt("v") == SYNC_PROTOCOL) { "两端同步协议不同，请更新两端应用" }
    require(json.getString("pin") == observedPin) { "设备证书不匹配" }
    val peer = SyncPeer.parse(json.put("host", address.host).put("port", address.port))
    require(peer.id != localId) { "这是本机地址" }
    return peer
}

data class SyncHistoryBatch(val records: List<HistoryRecord>, val next: Long)
fun parseSyncHistoryBatch(json: JSONObject, epoch: String, after: Long): SyncHistoryBatch {
    require(epoch.length in 1..80 && after >= 0)
    check(json.getString("epoch") == epoch) { "对方播放记录已重置，请重新同步" }
    val rows = json.getJSONArray("records"); val next = json.getLong("next")
    require(rows.length() <= SYNC_PAGE_SIZE) { "历史分页过大" }
    check(next >= after && (rows.length() == 0 || next > after)) { "播放记录游标无效" }
    return SyncHistoryBatch((0 until rows.length()).map { HistoryRecord.parse(rows.getJSONObject(it)) }, next)
}
