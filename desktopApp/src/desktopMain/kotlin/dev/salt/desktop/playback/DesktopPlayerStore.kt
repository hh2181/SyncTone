package dev.salt.desktop.playback

import dev.salt.desktop.library.DesktopTrack
import dev.salt.desktop.library.DesktopLibrary
import dev.salt.desktop.storage.DesktopSession
import dev.salt.desktop.storage.DesktopSessionRepository
import dev.salt.desktop.storage.PlayMode
import dev.salt.replica.lyrics.LyricsParser
import dev.salt.replica.lyrics.online.*
import dev.salt.desktop.lyrics.DesktopLyricsSettings
import dev.salt.desktop.statistics.DesktopStatistics
import dev.salt.desktop.sync.DesktopSyncRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.logging.Level
import java.util.logging.Logger

data class DesktopPlayerState(
    val tracks: List<DesktopTrack> = emptyList(),
    val currentId: String? = null,
    val status: AudioStatus = AudioStatus.Idle,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val volume: Int = 72,
    val mode: PlayMode = PlayMode.ListLoop,
    val ready: Boolean = false,
    val importing: Boolean = false,
    val error: String? = null,
    val sleepAt: Long? = null,
    val queueIds: List<String> = emptyList(),
    val lyricSettings: DesktopLyricsSettings = DesktopLyricsSettings(),
    val lyricsOffsetMs: Long = 0,
    val seekRevision: Int = 0,
) {
    val track: DesktopTrack? get() = tracks.firstOrNull { it.id == currentId }
    val playing: Boolean get() = status == AudioStatus.Playing
    val queue: List<DesktopTrack> get() {
        val byId = tracks.associateBy { it.id }
        return queueIds.mapNotNull(byId::get)
    }
}

class DesktopPlayerStore(
    private val repository: DesktopSessionRepository = DesktopSessionRepository.forApplication(),
    private val monotonicTime: () -> Long = System::nanoTime,
    private val engineFactory: () -> AudioEngine = ::VlcAudioEngine,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val audio = Executors.newSingleThreadExecutor { r -> Thread(r, "synctone-audio") }.asCoroutineDispatcher()
    private val operations = Mutex()
    private var engine: AudioEngine? = null
    private var pendingSeek: Long? = null
    private var failedReported = false
    private val unavailablePaths = mutableListOf<String>()
    private var storageRetryAt: Long? = null
    private var storageFailureReported = false
    private var storageEnabled = true
    private var sessionRestored = false
    private var closed = false
    private var poll: Job? = null
    private val mutableState = MutableStateFlow(DesktopPlayerState())
    val state = mutableState.asStateFlow()
    val library = DesktopLibrary()
    val timeline = PlaybackTimeline()
    val statistics = DesktopStatistics()
    val sync = DesktopSyncRepository(library, statistics)

    init {
        scope.launch {
            operations.withLock {
                try {
                    val saved = try { withContext(Dispatchers.IO) { repository.read() } }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        Logger.getLogger("dev.salt.desktop.storage").log(Level.WARNING, "Unable to read desktop session", e)
                        storageEnabled = runCatching { withContext(Dispatchers.IO) { repository.preserveUnreadable() } }.isSuccess
                        report("无法读取桌面记录：${e.message}\n" + if (storageEnabled) "原记录已备份。" else "原记录将保留，本次不覆盖保存。")
                        DesktopSession()
                    }
                    var persistentLibrary = storageEnabled
                    if (storageEnabled) {
                        try { withContext(Dispatchers.IO) { repository.write(saved) } }
                        catch (e: java.io.IOException) {
                            persistentLibrary = false
                            storageRetryAt = monotonicTime() + 60_000_000_000L
                            storageFailureReported = true
                            report(e.message ?: "数据目录不可写")
                        }
                    }
                    library.initialize(if (persistentLibrary) repository.directory else null, saved.paths)
                    val cached = library.state.value.tracks
                    unavailablePaths += saved.paths.filter { library.idForPath(it) == null }
                    val currentId = saved.current?.let { id -> cached.firstOrNull { it.id == id }?.id ?: library.idForPath(id) }
                    val queueIds = saved.queueIds ?: cached.map { it.id }
                    mutableState.update { it.copy(tracks = cached, currentId = currentId, positionMs = saved.positionMs,
                        volume = saved.volume, mode = saved.mode,
                        queueIds = queueIds, lyricSettings = saved.lyrics) }
                    sessionRestored = true
                    // Cached rows are already visible while native startup and file checks run.
                    scope.launch {
                        try { library.refreshAvailability() }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { report("无法检查音乐文件：${e.message}") }
                    }
                    statistics.initialize(if (persistentLibrary) repository.directory else null)
                    statistics.metadata(cached)
                    withContext(audio) {
                        try { engine = engineFactory() }
                        catch (e: LinkageError) { throw IllegalStateException("无法加载播放内核：${e.message}", e) }
                    }
                    val tracks = library.state.value.tracks
                    withContext(audio) { engine!!.setVolume(saved.volume) }
                    tracks.firstOrNull { it.id == currentId }?.let { track ->
                        // Check only the selected path before restoring playback, independently of the full-library probe.
                        if (withContext(Dispatchers.IO) { Files.isRegularFile(track.path) && Files.isReadable(track.path) }) {
                            open(if (track.available) track else track.copy(available = true), false, saved.positionMs)
                        }
                    }
                    scope.launch {
                        var lastSavedImport = library.state.value.lastImport
                        var statTracks = tracks.map { listOf(it.id, it.title, it.artist, it.album) }
                        library.state.collect { updated ->
                            operations.withLock {
                                val metadata = updated.tracks.map { listOf(it.id, it.title, it.artist, it.album) }
                                if (metadata != statTracks) {
                                    statistics.metadata(updated.tracks)
                                    statTracks = metadata
                                }
                                mutableState.update { it.copy(tracks = updated.tracks, importing = updated.importing) }
                                if (state.value.currentId == null && state.value.queueIds.isEmpty() && updated.lastImport != null) {
                                    val available = updated.tracks.filter { it.available }
                                    mutableState.update { it.copy(queueIds = available.map { t -> t.id }) }
                                    available.firstOrNull()?.let { open(it, false) }
                                }
                                if (updated.lastImport != null && updated.lastImport !== lastSavedImport && !updated.importing) {
                                    persist()
                                    lastSavedImport = updated.lastImport
                                }
                            }
                        }
                    }
                    mutableState.update { it.copy(tracks = tracks, ready = true) }
                    scope.launch { sync.initialize(repository.directory) }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { report(e.message ?: "播放内核初始化失败。") }
            }
            poll = scope.launch {
                var ticks = 0
                while (isActive) {
                    delay(100)
                    operations.withLock {
                        try {
                            val snapshot = withContext(audio) { engine?.snapshot() } ?: return@withLock
                            statistics.sample(snapshot.status == AudioStatus.Playing, System.currentTimeMillis(), monotonicTime() / 1_000_000)
                            val target = pendingSeek
                            if (target != null && snapshot.status in listOf(AudioStatus.Playing, AudioStatus.Paused)) {
                                withContext(audio) { engine?.seek(target.coerceAtMost(snapshot.durationMs.takeIf { it > 0 } ?: target)) }
                                pendingSeek = null
                            }
                            mutableState.update { it.copy(status = snapshot.status,
                                positionMs = target ?: snapshot.positionMs,
                                durationMs = snapshot.durationMs.takeIf { d -> d > 0 } ?: it.track?.durationMs ?: 0) }
                            timeline.update(state.value.positionMs, snapshot.status == AudioStatus.Playing, state.value.durationMs)
                            if (snapshot.status == AudioStatus.Ended) advance(1, automatic = true)
                            if (snapshot.status == AudioStatus.Failed && !failedReported) {
                                failedReported = true
                                report("无法解码此文件，请尝试其他音频。")
                            }
                            state.value.sleepAt?.let { deadline ->
                                if (System.currentTimeMillis() >= deadline) {
                                    withContext(audio) { engine?.setPaused(true) }
                                    mutableState.update { it.copy(sleepAt = null) }
                                }
                            }
                            if (++ticks % 50 == 0 && state.value.ready) persist()
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { report(e.message ?: "播放失败。") }
                    }
                }
            }
        }
    }

    private fun command(block: suspend () -> Unit) {
        if (closed) return
        scope.launch {
            operations.withLock {
                try { block() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { report(e.message ?: "操作失败。") }
            }
        }
    }

    fun importFiles(paths: List<Path>) { library.importFiles(paths) }

    fun select(track: DesktopTrack) = command {
        mutableState.update { it.copy(queueIds = it.tracks.filter { t -> t.available }.map { t -> t.id }) }
        open(track, true); persist()
    }
    fun selectQueued(track: DesktopTrack) = command { open(track, true); persist() }
    fun toggle() = command {
        val current = state.value
        if (current.track == null) current.tracks.firstOrNull { it.available }?.let {
            mutableState.update { value -> value.copy(queueIds = value.tracks.filter { t -> t.available }.map { t -> t.id }) }
            open(it, true)
        }
        else if (current.status in listOf(AudioStatus.Idle, AudioStatus.Stopped, AudioStatus.Failed))
            open(current.track!!, true, current.positionMs)
        else {
            val pause = current.playing || current.status == AudioStatus.Loading
            withContext(audio) { engine?.setPaused(pause) }
            if (pause) statistics.sample(false, System.currentTimeMillis(), monotonicTime() / 1_000_000)
        }
    }
    fun seek(positionMs: Long) = command {
        val target = positionMs.coerceIn(0, state.value.durationMs.coerceAtLeast(0))
        if (state.value.status in listOf(AudioStatus.Idle, AudioStatus.Stopped)) pendingSeek = target
        else withContext(audio) { engine?.seek(target) }
        mutableState.update { it.copy(positionMs = target, seekRevision = it.seekRevision + 1) }
        timeline.update(target, state.value.playing, state.value.durationMs, discontinuity = true)
    }
    fun volume(value: Int) = command {
        val volume = value.coerceIn(0, 100)
        withContext(audio) { engine?.setVolume(volume) }
        mutableState.update { it.copy(volume = volume) }
    }
    fun next() = command { advance(1); persist() }
    fun previous() = command {
        if (state.value.positionMs > 3000) {
            withContext(audio) { engine?.seek(0) }
            pendingSeek = null
            mutableState.update { it.copy(positionMs = 0) }
        } else advance(-1)
    }
    fun cycleMode() = command {
        mutableState.update { it.copy(mode = PlayMode.entries[(it.mode.ordinal + 1) % PlayMode.entries.size]) }
        persist()
    }
    fun setSleepTimer(minutes: Int?) = command {
        mutableState.update { it.copy(sleepAt = minutes?.let { m -> System.currentTimeMillis() + m * 60_000L }) }
    }
    fun importLyrics(path: Path) = command {
        require(Files.size(path) <= 4 * 1024 * 1024) { "歌词文件过大。" }
        val track = state.value.track ?: return@command
        val lyrics = withContext(Dispatchers.IO) {
            OnlineLyricsCodec.fromLrc(LyricsParser.decode(Files.readAllBytes(path)), track.durationMs)
        }
        require(lyrics.lines.isNotEmpty()) { "没有找到有效的歌词时间戳。" }
        library.persistLyrics(track.id, lyrics)
    }
    fun updateLyricsSettings(value: DesktopLyricsSettings) = command {
        mutableState.update { it.copy(lyricSettings = value.normalized()) }
        persist()
    }
    fun offsetLyrics(value: Long) = command {
        val id = state.value.currentId ?: return@command
        val offset = value.coerceIn(-10_000L, 10_000L)
        library.setLyricsOffset(id, offset)
        mutableState.update { it.copy(lyricsOffsetMs = offset) }
    }
    fun restoreLyrics() { state.value.currentId?.let(library::restoreLyrics) }
    val onlineLyricsPreferenceFile get() = repository.directory.resolve("online-lyrics.preference").toFile()
    suspend fun hasLyrics(song: LyricSong): Boolean = library.hasLyrics(song.id)
    suspend fun saveOnlineLyrics(song: LyricSong, download: LyricDownload, onlyMissing: Boolean) {
        library.persistOnlineLyrics(song.id, download.lyrics, onlyMissing)
        mutableState.update { if (it.currentId == song.id) it.copy(lyricsOffsetMs = 0) else it }
    }
    fun dismissError() { mutableState.update { it.copy(error = null) } }
    private fun report(message: String) { mutableState.update { it.copy(error = message) } }

    private suspend fun open(track: DesktopTrack, play: Boolean, positionMs: Long = 0) {
        val activeEngine = engine ?: error("播放内核尚未就绪。")
        require(track.available) { "音乐文件暂时不可用，请连接磁盘后重新扫描。" }
        statistics.transition(null, System.currentTimeMillis(), monotonicTime() / 1_000_000)
        withContext(audio) { activeEngine.open(track.path, play) }
        statistics.transition(track.id, System.currentTimeMillis(), monotonicTime() / 1_000_000)
        pendingSeek = positionMs.takeIf { it > 0 }
        failedReported = false
        val offset = library.lyricsOffset(track.id)
        mutableState.update { it.copy(currentId = track.id, positionMs = positionMs,
            durationMs = track.durationMs, status = if (play) AudioStatus.Loading else AudioStatus.Idle,
            lyricsOffsetMs = offset, seekRevision = it.seekRevision + 1) }
        timeline.update(positionMs, play, track.durationMs, discontinuity = true)
    }

    private suspend fun advance(direction: Int, automatic: Boolean = false) {
        val current = state.value
        val queue = current.queue.filter { it.available }
        if (queue.isEmpty()) return
        val index = queue.indexOfFirst { it.id == current.currentId }.coerceAtLeast(0)
        val next = when {
            automatic && current.mode == PlayMode.RepeatOne -> index
            current.mode == PlayMode.Shuffle && queue.size > 1 -> queue.indices.filter { it != index }.random()
            else -> Math.floorMod(index + direction, queue.size)
        }
        open(queue[next], true)
    }

    private suspend fun persist() {
        if (!storageEnabled) return
        val now = monotonicTime()
        if (storageRetryAt?.let { now - it < 0 } == true) return
        val current = state.value
        try {
            withContext(Dispatchers.IO) {
                repository.write(DesktopSession((current.tracks.map { it.path.toString() } + unavailablePaths).distinct(), current.currentId,
                    current.positionMs, current.volume, current.mode, current.queueIds, current.lyricSettings))
            }
            storageRetryAt = null
            storageFailureReported = false
            library.persistIfVolatile(repository.directory)
        } catch (e: java.io.IOException) {
            storageRetryAt = now + 60_000_000_000L
            if (!storageFailureReported) {
                storageFailureReported = true
                Logger.getLogger("dev.salt.desktop.storage").log(Level.WARNING, "Unable to save desktop session", e)
                report(e.message ?: "无法保存播放记录，稍后会自动重试。")
            }
        }
    }

    suspend fun close() {
        if (closed) return
        closed = true
        scope.coroutineContext[Job]?.cancelAndJoin()
        withContext(NonCancellable) {
            try { if (sessionRestored) persist() }
            finally {
                try {
                    try { sync.close() }
                    finally {
                        try { statistics.close(System.currentTimeMillis(), monotonicTime() / 1_000_000) }
                        finally { library.close() }
                    }
                }
                finally {
                    withContext(audio) { engine?.close() }
                    audio.close()
                }
            }
        }
    }
}
