package dev.salt.desktop.library

import dev.salt.desktop.storage.DesktopLibraryRepository
import dev.salt.replica.lyrics.Lyrics
import dev.salt.replica.sync.fileDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicBoolean

class DesktopLibrary {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operations = Mutex()
    private var repository: DesktopLibraryRepository? = null
    private var scanning: Job? = null
    private val scanCancelled = AtomicBoolean(false)
    private var closed = false
    private var persistenceFailureReported = false
    private val mutable = MutableStateFlow(DesktopLibraryState())
    val state = mutable.asStateFlow()

    suspend fun initialize(directory: Path?, legacyPaths: List<String>) = withContext(Dispatchers.IO) {
        operations.withLock {
            var storageError: String? = null
            val database = try { DesktopLibraryRepository(directory) }
            catch (e: Exception) {
                storageError = "无法打开曲库：${e.message}。本次新增内容暂存内存，原数据库保留。"
                DesktopLibraryRepository(null)
            }
            repository = database
            val pending = database.property("legacy_pending")?.let { text ->
                Json.parseToJsonElement(text).jsonArray.map { it.jsonPrimitive.content }
            }.orEmpty()
            val migrationPaths = if (database.property("legacy_imported") != "true") legacyPaths else pending
            if (migrationPaths.isNotEmpty() || database.property("legacy_imported") != "true") {
                val unavailable = mutableListOf<String>()
                migrationPaths.distinct().forEach { path ->
                    currentCoroutineContext().ensureActive()
                    try { database.register(Path.of(path)) }
                    catch (e: CancellationException) { throw e }
                    catch (e: java.sql.SQLException) { throw e }
                    catch (_: Exception) { unavailable += path }
                }
                // Retain paths that were offline during the one-time migration.
                database.setProperty("legacy_pending", JsonArray(unavailable.map(::JsonPrimitive)).toString())
                database.setProperty("legacy_imported", "true")
            }
            mutable.value = DesktopLibraryState(database.tracks(), database.folders(), database.readScan(),
                ready = true, directory = if (storageError == null) directory?.toString().orEmpty() else "",
                error = storageError ?: if (directory == null) "曲库当前仅保存在内存中，退出后新增内容将无法保留。" else null)
        }
    }

    // Publish cached metadata first. Probing disconnected disks must not delay the library UI.
    suspend fun refreshAvailability() = withContext(Dispatchers.IO) {
        operations.withLock {
            val db = repository ?: return@withLock
            val context = currentCoroutineContext()
            db.refreshAvailability { context.ensureActive() }
            val previous = state.value.tracks.associateBy { it.id }
            val tracks = db.tracks().map { track ->
                previous[track.id]?.takeIf { it.path == track.path && it.available == track.available } ?: track
            }
            mutable.update { it.copy(tracks = tracks) }
        }
    }

    private fun command(block: suspend (DesktopLibraryRepository) -> Unit) {
        if (closed || !state.value.ready) return
        scope.launch {
            operations.withLock {
                try { repository?.let { block(it) } }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { mutable.update { it.copy(error = e.message ?: "曲库操作失败") } }
            }
        }
    }

    fun importFiles(paths: List<Path>) {
        if (!state.value.ready || state.value.importing || state.value.scan.phase == ScanPhase.Scanning) return
        mutable.update { it.copy(importing = true) }
        command { db ->
            try {
                var added = 0; var duplicates = 0; var unreadable = 0
                for (path in paths) {
                    currentCoroutineContext().ensureActive()
                    try {
                        val context = currentCoroutineContext()
                        val result = db.register(path) { context.ensureActive() }
                        if (result.added) added++ else duplicates++
                    } catch (e: CancellationException) { throw e }
                    catch (e: java.sql.SQLException) { throw e }
                    catch (_: Exception) { unreadable++ }
                }
                refresh(db)
                mutable.update { it.copy(lastImport = ImportResult(added, duplicates, unreadable)) }
            } finally { mutable.update { it.copy(importing = false) } }
        }
    }

    suspend fun registerSynced(path: Path, expectedHash: String) = withContext(Dispatchers.IO) {
        operations.withLock {
            check(!closed && state.value.ready) { "曲库尚未就绪" }
            val db = requireNotNull(repository)
            check(Files.newInputStream(path).use(::fileDigest) == expectedHash) { "文件校验失败" }
            db.register(path, true)
            refresh(db)
        }
    }

    /** Refresh only syncable sources, reusing the repository's size/mtime/file-key cache. */
    suspend fun refreshSyncSources() = withContext(Dispatchers.IO) {
        operations.withLock {
            val db = requireNotNull(repository) { "曲库尚未就绪" }
            val context = currentCoroutineContext()
            db.refreshAvailability { context.ensureActive() }
            val paths = db.tracks().map { it.path }.distinct().filter {
                it.fileName.toString().substringAfterLast('.', "").lowercase() in setOf("mp3", "flac") && Files.isRegularFile(it)
            }
            for (path in paths) {
                context.ensureActive()
                try { db.register(path) { context.ensureActive() } }
                catch (e: CancellationException) { throw e }
                catch (e: java.sql.SQLException) { throw e }
                catch (_: Exception) { /* A source changed or became unreadable after the stat check. */ }
            }
            refresh(db)
        }
    }

    fun addFolder(path: Path) {
        if (state.value.scan.phase == ScanPhase.Scanning) return
        command { db ->
            require(Files.isDirectory(path) && Files.isReadable(path)) { "目录不可用，请重新选择" }
            db.addFolder(path)
            refresh(db)
            scan()
        }
    }

    fun removeFolder(path: String) {
        if (state.value.scan.phase == ScanPhase.Scanning) return
        command { db -> db.removeFolder(path); refresh(db) }
    }

    fun scan(force: Boolean = false) {
        if (closed || !state.value.ready || state.value.importing || state.value.folders.isEmpty() || scanning?.isActive == true) return
        scanCancelled.set(false)
        mutable.update { it.copy(scan = ScanProgress(ScanPhase.Scanning, lastSuccessUtc = it.scan.lastSuccessUtc)) }
        scanning = scope.launch {
            operations.withLock {
                val db = repository ?: return@withLock
                var progress = state.value.scan
                var lastPublish = 0L
                fun publish(force: Boolean = false) {
                    val now = System.nanoTime()
                    if (force || now - lastPublish > 100_000_000) {
                        lastPublish = now
                        mutable.update { it.copy(scan = progress) }
                    }
                }
                try {
                    db.saveScan(progress)
                    val visited = hashSetOf<String>()
                    val context = currentCoroutineContext()
                    fun checkCancelled() {
                        context.ensureActive()
                        if (scanCancelled.get()) throw CancellationException("Scan cancelled")
                    }
                    for (folder in db.folders()) {
                        checkCancelled()
                        db.folderIssue(folder.path, null)
                        val problems = mutableListOf<String>()
                        try {
                            // Default walk does not follow symlinks/junctions into recursion cycles.
                            Files.walkFileTree(Path.of(folder.path), object : SimpleFileVisitor<Path>() {
                                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                                    checkCancelled()
                                    return if (visited.add("dir:${DesktopLibraryRepository.canonicalKey(dir)}"))
                                        FileVisitResult.CONTINUE else FileVisitResult.SKIP_SUBTREE
                                }
                                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                                    checkCancelled()
                                    if (!attrs.isRegularFile || file.fileName.toString().substringAfterLast('.', "").lowercase() !in DesktopTrackReader.extensions)
                                        return FileVisitResult.CONTINUE
                                    val key = DesktopLibraryRepository.canonicalKey(file)
                                    if (!visited.add("file:$key")) return FileVisitResult.CONTINUE
                                    progress = progress.copy(found = progress.found + 1, current = file.fileName.toString())
                                    try {
                                        val result = db.register(file, force, ::checkCancelled)
                                        progress = progress.copy(added = progress.added + if (result.added) 1 else 0,
                                            duplicates = progress.duplicates + if (result.added) 0 else 1,
                                            cached = progress.cached + if (result.cached) 1 else 0)
                                    } catch (e: CancellationException) { throw e }
                                    catch (e: java.sql.SQLException) { throw e }
                                    catch (_: Exception) { progress = progress.copy(unreadable = progress.unreadable + 1) }
                                    progress = progress.copy(checked = progress.checked + 1)
                                    publish()
                                    return FileVisitResult.CONTINUE
                                }
                                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                                    checkCancelled()
                                    if (problems.size < 3) problems += file.toString()
                                    return FileVisitResult.CONTINUE
                                }
                                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                                    if (exc != null && problems.size < 3) problems += dir.toString()
                                    return FileVisitResult.CONTINUE
                                }
                            })
                        } catch (e: CancellationException) { throw e }
                        catch (e: java.sql.SQLException) { throw e }
                        catch (e: Exception) { problems += e.message ?: folder.path }
                        if (problems.isNotEmpty()) db.folderIssue(folder.path, "部分目录无法读取：${problems.joinToString("；")}")
                    }
                    checkCancelled()
                    db.refreshAvailability()
                    val failed = db.folders().count { it.issue != null }
                    progress = progress.copy(phase = if (failed == 0) ScanPhase.Complete else ScanPhase.Failed,
                        lastSuccessUtc = if (failed == 0) System.currentTimeMillis() else progress.lastSuccessUtc,
                        current = "", error = if (failed == 0) null else "$failed 个目录存在读取问题，可重新选择或重试")
                } catch (e: CancellationException) {
                    progress = progress.copy(phase = ScanPhase.Cancelled, current = "", error = "扫描已取消，已入库歌曲保留")
                } catch (e: Exception) {
                    progress = progress.copy(phase = ScanPhase.Failed, current = "", error = e.message ?: "扫描失败")
                } finally {
                    withContext(NonCancellable) {
                        try { db.saveScan(progress); refresh(db) }
                        catch (e: Exception) { mutable.update { it.copy(error = "无法保存扫描记录：${e.message}") } }
                        publish(true)
                    }
                }
            }
        }
    }

    fun cancelScan() { scanCancelled.set(true) }
    fun dismissError() { mutable.update { it.copy(error = null) } }
    fun persistLyrics(id: String, lyrics: Lyrics) = command { db -> db.setLyrics(id, lyrics); refresh(db) }
    fun restoreLyrics(id: String) = command { db -> db.restoreLyrics(id); refresh(db) }
    suspend fun lyricsOffset(id: String): Long = withContext(Dispatchers.IO) {
        repository?.property("lyric_offset:$id")?.toLongOrNull()?.coerceIn(-10_000L, 10_000L) ?: 0
    }
    suspend fun setLyricsOffset(id: String, offset: Long) = withContext(Dispatchers.IO) {
        operations.withLock { repository?.setProperty("lyric_offset:$id", offset.toString()) }
    }

    private fun refresh(db: DesktopLibraryRepository) {
        mutable.update { it.copy(tracks = db.tracks(), folders = db.folders()) }
    }

    suspend fun idForPath(path: String): String? = withContext(Dispatchers.IO) { repository?.idForPath(path) }

    suspend fun persistIfVolatile(directory: Path) {
        if (!state.value.ready || state.value.directory.isNotEmpty()) return
        withContext(Dispatchers.IO) {
            operations.withLock {
                if (!state.value.ready || state.value.directory.isNotEmpty()) return@withLock
                try {
                    repository?.persistTo(directory)
                    persistenceFailureReported = false
                    mutable.update { it.copy(directory = directory.toString(), error = null) }
                } catch (e: Exception) {
                    if (!persistenceFailureReported) {
                        persistenceFailureReported = true
                        mutable.update { it.copy(error = "无法保留内存曲库：${e.message}") }
                    }
                }
            }
        }
    }

    suspend fun close() {
        closed = true
        scope.coroutineContext[Job]?.cancelAndJoin()
        withContext(Dispatchers.IO) { repository?.close() }
    }
}
