package dev.salt.replica.lyrics.online

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

enum class LyricsBatchPhase { Idle, Running, Paused, Complete, Stopped }
enum class LyricsBatchStatus(val label: String) {
    Pending("等待匹配"), Searching("匹配中"), Applied("已更新"), Kept("已保留"), Review("待确认"), Failed("失败")
}
data class LyricsBatchEntry(val song: LyricSong, val status: LyricsBatchStatus = LyricsBatchStatus.Pending, val detail: String = "")
data class LyricsBatch(val phase: LyricsBatchPhase = LyricsBatchPhase.Idle, val entries: List<LyricsBatchEntry> = emptyList(),
                       val settings: LyricsMatchSettings? = null) {
    val active get() = phase == LyricsBatchPhase.Running || phase == LyricsBatchPhase.Paused
    val processed get() = entries.count { it.status !in listOf(LyricsBatchStatus.Pending, LyricsBatchStatus.Searching) }
}
data class OnlineLyricsState(
    val busy: Boolean = false,
    val results: List<LyricCandidate> = emptyList(),
    val searched: Boolean = false,
    val warnings: List<String> = emptyList(),
    val preview: LyricDownload? = null,
    val loadingCandidate: String? = null,
    val saved: Boolean = false,
    val message: String? = null,
    val settings: LyricsMatchSettings = LyricsMatchSettings(),
    val song: LyricSong? = null,
    val songs: List<LyricSong> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val batch: LyricsBatch = LyricsBatch(),
) { val automatic get() = settings.automatic }

/** Player-owned scope: navigation preserves work; destroying the player cancels requests. */
class OnlineLyricsController(
    private val scope: CoroutineScope,
    private val preferenceFile: File,
    private val save: suspend (LyricSong, LyricDownload, Boolean) -> Unit,
    private val service: OnlineLyricsService = OnlineLyricsService(),
    private val hasLyrics: suspend (LyricSong) -> Boolean = { false },
) {
    private val mutable = MutableStateFlow(OnlineLyricsState(settings = LyricsMatchSettings.read(preferenceFile)))
    val state = mutable.asStateFlow()
    private var manual: Job? = null
    private var automatic: Job? = null
    private var automaticSong: String? = null
    private var batchJob: Job? = null
    private var batchRun = 0L
    private val preferenceWrites = Mutex()
    private val attempted = LinkedHashSet<String>()

    fun updateLibrary(songs: List<LyricSong>, preferredId: String?) {
        val ids = songs.mapTo(HashSet()) { it.id }
        val current = state.value
        mutable.update { it.copy(songs = songs, selectedIds = it.selectedIds.filterTo(linkedSetOf()) { id -> id in ids }) }
        if (current.song?.id !in ids) {
            val target = songs.firstOrNull { it.id == preferredId } ?: songs.firstOrNull()
            bind(target)
            if (target != null && current.selectedIds.isEmpty()) mutable.update { it.copy(selectedIds = setOf(target.id)) }
        }
    }

    fun bind(value: LyricSong?) {
        if (state.value.song == value) return
        manual?.cancel()
        mutable.update { it.copy(song = value, busy = false, results = emptyList(), searched = false,
            warnings = emptyList(), preview = null, loadingCandidate = null, saved = false, message = null) }
    }

    fun setSelection(ids: Set<String>) {
        if (state.value.batch.active) return
        val available = state.value.songs.mapTo(HashSet()) { it.id }
        mutable.update { it.copy(selectedIds = ids.intersect(available), batch = LyricsBatch()) }
    }

    fun updateSettings(value: LyricsMatchSettings) {
        val settings = value.normalized()
        mutable.update { it.copy(settings = settings, saved = false,
            message = if (it.batch.active) "设置已保存，将用于下一次批量任务" else null) }
        automatic?.cancel(); attempted.clear()
        scope.launch {
            try {
                preferenceWrites.withLock {
                    withContext(Dispatchers.IO) {
                        preferenceFile.parentFile?.mkdirs()
                        val temp = File.createTempFile("online-lyrics-", ".tmp", preferenceFile.parentFile)
                        try {
                            temp.writeText(state.value.settings.encode())
                            Files.move(temp.toPath(), preferenceFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        } finally { temp.delete() }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(message = "匹配设置未能保存，请检查数据目录") } }
        }
    }
    fun setAutomatic(value: Boolean) = updateSettings(state.value.settings.copy(automatic = value))

    fun search(query: String, source: LyricSource?) {
        val target = state.value.song ?: return
        val settings = state.value.settings
        if (settings.sources.isEmpty()) { mutable.update { it.copy(message = "请先启用至少一个歌词来源") }; return }
        automatic?.cancel(); manual?.cancel()
        mutable.update { it.copy(busy = true, results = emptyList(), searched = false, preview = null,
            loadingCandidate = null, saved = false, warnings = emptyList(), message = null) }
        manual = scope.launch {
            try {
                val result = service.search(query, target, source, settings.sources)
                mutable.update { it.copy(results = result.candidates, warnings = result.warnings, searched = true) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(message = "搜索失败，请检查关键词和网络后重试") } }
            finally { if (isActive) mutable.update { it.copy(busy = false) } }
        }
    }

    fun preview(candidate: LyricCandidate) {
        val target = state.value.song ?: return
        automatic?.cancel(); manual?.cancel()
        mutable.update { it.copy(busy = true, loadingCandidate = candidate.key, saved = false, message = null) }
        manual = scope.launch {
            try { val download = service.download(candidate, target); mutable.update { it.copy(preview = download) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(message = "该版本下载失败或没有有效歌词，请重试或选择其他版本") } }
            finally { if (isActive) mutable.update { it.copy(busy = false, loadingCandidate = null) } }
        }
    }
    fun back() { manual?.cancel(); mutable.update { it.copy(preview = null, loadingCandidate = null, busy = false, saved = false, message = null) } }
    fun dismiss() { manual?.cancel(); mutable.update { it.copy(busy = false, loadingCandidate = null) } }
    fun dismissMessage() { mutable.update { it.copy(message = null) } }

    fun savePreview() {
        val target = state.value.song ?: return
        val raw = state.value.preview ?: return
        val settings = state.value.settings
        if (state.value.busy || state.value.saved) return
        mutable.update { it.copy(busy = true, message = null) }
        manual = scope.launch {
            try {
                val download = LyricsMatching.arrange(raw, settings)
                withContext(NonCancellable) { save(target, download, false) }
                if (state.value.song?.id == target.id) mutable.update { it.copy(saved = true, message = "已保存并应用，下次打开可离线使用",
                    batch = it.batch.copy(entries = it.batch.entries.map { entry ->
                        if (entry.song.id == target.id && entry.status in listOf(LyricsBatchStatus.Review, LyricsBatchStatus.Failed))
                            entry.copy(status = LyricsBatchStatus.Applied, detail = "${raw.candidate.source.label} · 手动选择") else entry
                    })) }
            } catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { mutable.update { it.copy(message = e.message ?: "此版本没有所选歌词内容") } }
            catch (_: Exception) { mutable.update { it.copy(message = "保存失败，请检查歌曲及数据目录后重试") } }
            finally { if (isActive) mutable.update { it.copy(busy = false) } }
        }
    }

    private data class Match(val download: LyricDownload? = null, val reason: String = "", val networkFailure: Boolean = false)
    private suspend fun match(song: LyricSong, settings: LyricsMatchSettings): Match {
        val results = service.search(song.query.take(200), song, sources = settings.sources)
        if (results.candidates.isEmpty()) return Match(reason = if (results.warnings.isNotEmpty()) "来源暂不可用，可稍后重试" else "没有找到同步歌词",
            networkFailure = results.warnings.isNotEmpty())
        val eligible = results.candidates.filter { LyricsMatching.metadataFailure(song, it, settings) == null }
            .sortedWith(compareBy<LyricCandidate> { settings.priority.indexOf(it.source) }.thenByDescending { LyricsMatching.score(song, it) })
        if (eligible.isEmpty()) return Match(reason = LyricsMatching.metadataFailure(song, results.candidates.first(), settings) ?: "没有符合规则的版本")
        var reason = "没有符合规则的版本"; var downloaded = false
        val bounded = settings.priority.filter { it in settings.sources }.flatMap { source -> eligible.filter { it.source == source }.take(2) }
        for (candidate in bounded) {
            currentCoroutineContext().ensureActive()
            try {
                val raw = service.download(candidate, song)
                downloaded = true
                val failure = LyricsMatching.contentFailure(raw, settings)
                if (failure != null) { reason = failure; continue }
                return Match(download = LyricsMatching.arrange(raw, settings))
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (!downloaded) reason = "歌词下载失败，可稍后重试" }
        }
        return Match(reason = reason, networkFailure = !downloaded)
    }

    fun startBatch() {
        val oldJob = batchJob
        val current = state.value
        if (current.batch.phase == LyricsBatchPhase.Running) return
        if (current.batch.phase != LyricsBatchPhase.Paused) {
            val songs = current.songs.filter { it.id in current.selectedIds }
            if (songs.isEmpty() || current.settings.sources.isEmpty()) {
                mutable.update { it.copy(message = "请选择音乐，并启用至少一个歌词来源") }; return
            }
            batchRun++
            mutable.update { it.copy(batch = LyricsBatch(LyricsBatchPhase.Running, songs.map(::LyricsBatchEntry), current.settings), message = null) }
        } else mutable.update { it.copy(batch = it.batch.copy(phase = LyricsBatchPhase.Running), message = null) }
        automatic?.cancel()
        val run = batchRun
        batchJob = scope.launch {
            oldJob?.join()
            val settings = requireNotNull(state.value.batch.settings)
            val entries = state.value.batch.entries
            for (entry in entries) {
                ensureActive()
                if (state.value.batch.entries.firstOrNull { it.song.id == entry.song.id }?.status != LyricsBatchStatus.Pending) continue
                updateEntry(run, entry.copy(status = LyricsBatchStatus.Searching))
                try {
                    if (!settings.overwrite && hasLyrics(entry.song)) {
                        updateEntry(run, entry.copy(status = LyricsBatchStatus.Kept, detail = "已保留本地歌词")); continue
                    }
                    val result = match(entry.song, settings)
                    if (result.download == null) {
                        updateEntry(run, entry.copy(status = if (result.networkFailure) LyricsBatchStatus.Failed else LyricsBatchStatus.Review, detail = result.reason))
                    } else withContext(NonCancellable) {
                        save(entry.song, result.download, !settings.overwrite)
                        updateEntry(run, entry.copy(status = LyricsBatchStatus.Applied,
                            detail = "${result.download.candidate.source.label} · ${if (result.download.wordTimed) "逐字" else "逐行"} · ${LyricsMatching.score(entry.song, result.download.candidate)}%"))
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: LyricsAlreadyPresentException) { updateEntry(run, entry.copy(status = LyricsBatchStatus.Kept, detail = "已保留刚更新的本地歌词")) }
                catch (_: Exception) { updateEntry(run, entry.copy(status = LyricsBatchStatus.Failed, detail = "无法读取或保存这首歌曲")) }
                delay(180)
            }
            if (run == batchRun) mutable.update { it.copy(batch = it.batch.copy(phase = LyricsBatchPhase.Complete)) }
        }
    }
    private fun updateEntry(run: Long, entry: LyricsBatchEntry) {
        if (run == batchRun) mutable.update { it.copy(batch = it.batch.copy(entries = it.batch.entries.map { old -> if (old.song.id == entry.song.id) entry else old })) }
    }
    fun pauseBatch() {
        if (state.value.batch.phase != LyricsBatchPhase.Running) return
        batchJob?.cancel()
        mutable.update { it.copy(batch = it.batch.copy(phase = LyricsBatchPhase.Paused,
            entries = it.batch.entries.map { entry -> if (entry.status == LyricsBatchStatus.Searching) entry.copy(status = LyricsBatchStatus.Pending) else entry })) }
    }
    fun stopBatch() {
        batchJob?.cancel()
        mutable.update { it.copy(batch = it.batch.copy(phase = LyricsBatchPhase.Stopped,
            entries = it.batch.entries.map { entry -> if (entry.status == LyricsBatchStatus.Searching) entry.copy(status = LyricsBatchStatus.Pending) else entry })) }
    }
    fun autoMatch(hasLyrics: Boolean) { autoMatchFor(state.value.song, hasLyrics) }
    fun autoMatchFor(song: LyricSong?, hasLyrics: Boolean) {
        if (automaticSong != song?.id) { automatic?.cancel(); automaticSong = song?.id }
        if (song == null) return
        if (hasLyrics || !state.value.automatic) { automatic?.cancel(); return }
        if (manual?.isActive == true || state.value.batch.active || automatic?.isActive == true || song.id in attempted) return
        val settings = state.value.settings
        if (settings.sources.isEmpty()) return
        attempted.add(song.id); if (attempted.size > 64) attempted.remove(attempted.first())
        automatic = scope.launch {
            try {
                delay(600)
                val result = match(song, settings)
                result.download?.let { download -> withContext(NonCancellable) { save(song, download, true) } }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Automatic matching never interrupts local playback. */ }
        }
    }
}
