package dev.salt.replica.library

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import dev.salt.replica.sync.LibraryRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class MusicScanner private constructor(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("music_scan", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow(restore())
    val state: StateFlow<MusicScanState> = mutable
    private var scanning: Job? = null

    fun addFolder(uri: Uri) {
        if (state.value.phase == MusicScanPhase.SCANNING) return
        scope.launch {
            try {
                app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val root = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
                val name = app.contentResolver.query(root, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                    check(it.moveToFirst()) { "目录已不可用" }; it.getString(0)
                } ?: error("无法读取目录")
                val id = DocumentsContract.getTreeDocumentId(uri)
                val path = id.substringAfter(':', id).let { if (it.isBlank()) name else "/$it" }
                mutable.update { old ->
                    val folder = ScanFolder(uri.toString(), name, path)
                    old.copy(folders = old.folders.filterNot { sameFolder(Uri.parse(it.uri), uri) } + folder, error = null)
                }
                persist()
                scan()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                mutable.update { it.copy(phase = MusicScanPhase.FAILED, error = e.message ?: "无法授权目录，请重新选择") }
                persist()
            }
        }
    }

    fun removeFolder(folder: ScanFolder) {
        if (state.value.phase == MusicScanPhase.SCANNING) return
        // Imported songs still need the tree grant after its scan entry is removed.
        mutable.update {
            val remaining = it.folders.filterNot { item -> item.uri == folder.uri }
            val failures = remaining.count { item -> item.issue != null }
            it.copy(folders = remaining, error = if (failures > 0) "$failures 个目录无法读取，请重新授权" else null,
                phase = if (it.phase == MusicScanPhase.FAILED && failures == 0)
                    if (it.lastScanUtc > 0) MusicScanPhase.COMPLETE else MusicScanPhase.NOT_SCANNED else it.phase)
        }
        persist()
    }

    @Synchronized fun scan() {
        if (scanning?.isActive == true || state.value.folders.isEmpty()) return
        mutable.update { it.copy(phase = MusicScanPhase.SCANNING, found = 0, checked = 0, added = 0,
            duplicates = 0, unreadable = 0, current = "正在读取目录", error = null,
            folders = it.folders.map { folder -> folder.copy(issue = null) }) }
        prefs.edit().putBoolean("interrupted", true).apply()
        scanning = scope.launch {
            try {
                val visited = hashSetOf<String>()
                val candidates = mutableListOf<Pair<Uri, String>>()
                for (folder in state.value.folders) {
                    try { collectFolder(folder, visited, candidates) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) {
                        mutable.update { old -> old.copy(folders = old.folders.map {
                            if (it.uri == folder.uri) it.copy(issue = "目录无法读取，点击重新授权") else it
                        }) }
                    }
                }
                val readable = mutableListOf<Uri>()
                for ((index, entry) in candidates.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    mutable.update { it.copy(current = entry.second, checked = index) }
                    if (validAudio(entry.first)) readable += entry.first
                    else mutable.update { it.copy(unreadable = it.unreadable + 1) }
                }
                mutable.update { it.copy(current = "正在校验音乐内容", checked = 0) }
                val names = candidates.toMap()
                val result = LibraryRegistry.add(app, readable) { checked, uri ->
                    mutable.update { it.copy(checked = checked, current = names[uri].orEmpty()) }
                }
                if (result.added.isEmpty()) LibraryRegistry.revision.value++
                val failedFolders = state.value.folders.count { it.issue != null }
                mutable.update { it.copy(
                    phase = if (failedFolders == it.folders.size) MusicScanPhase.FAILED else MusicScanPhase.COMPLETE,
                    lastScanUtc = if (failedFolders == it.folders.size) it.lastScanUtc else System.currentTimeMillis(),
                    checked = it.found, added = result.added.size, duplicates = result.duplicates,
                    unreadable = it.unreadable + result.unreadable, current = "",
                    error = if (failedFolders > 0) "$failedFolders 个目录无法读取，请重新授权" else null
                ) }
                persist()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                mutable.update { it.copy(phase = MusicScanPhase.FAILED, current = "", error = e.message ?: "扫描未完成，请重试") }
                persist()
            } finally { prefs.edit().putBoolean("interrupted", false).apply() }
        }
    }

    private suspend fun collectFolder(folder: ScanFolder, visited: MutableSet<String>, candidates: MutableList<Pair<Uri, String>>) {
        val tree = Uri.parse(folder.uri)
        val queue = ArrayDeque<String>()
        queue.add(DocumentsContract.getTreeDocumentId(tree))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE)
        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val id = queue.removeFirst()
            if (!visited.add("${tree.authority}:$id")) continue
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
            val cursor = app.contentResolver.query(children, columns, null, null, null) ?: error("目录无法读取")
            cursor.use {
                while (it.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val childId = it.getString(0); val name = it.getString(1).orEmpty(); val mime = it.getString(2).orEmpty()
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) queue.add(childId)
                    else if (isMusicDocument(name, mime) && visited.add("file:${tree.authority}:$childId")) {
                        candidates += DocumentsContract.buildDocumentUriUsingTree(tree, childId) to name
                        mutable.update { old -> old.copy(found = candidates.size, current = name) }
                    }
                }
            }
        }
    }

    private fun validAudio(uri: Uri): Boolean {
        val metadata = MediaMetadataRetriever()
        return try {
            metadata.setDataSource(app, uri)
            (metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) > 0 &&
                metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
        } catch (_: Exception) { false }
        finally { metadata.release() }
    }

    private fun sameFolder(a: Uri, b: Uri): Boolean = a.authority == b.authority &&
        DocumentsContract.getTreeDocumentId(a) == DocumentsContract.getTreeDocumentId(b)

    private fun persist() {
        val value = state.value
        val folders = JSONArray(value.folders.map { JSONObject().put("uri", it.uri).put("name", it.name).put("path", it.path)
            .put("issue", it.issue ?: JSONObject.NULL) })
        check(prefs.edit().putString("folders", folders.toString()).putLong("lastScanUtc", value.lastScanUtc)
            .putInt("found", value.found).putInt("added", value.added).putInt("duplicates", value.duplicates)
            .putInt("unreadable", value.unreadable).putString("phase", value.phase.name).putString("error", value.error).commit())
    }

    private fun restore(): MusicScanState {
        val folders = runCatching {
            val json = JSONArray(prefs.getString("folders", "[]"))
            (0 until json.length()).map { val item = json.getJSONObject(it)
                ScanFolder(item.getString("uri"), item.getString("name"), item.getString("path"),
                    if (item.isNull("issue")) null else item.optString("issue").ifBlank { null }) }
        }.getOrDefault(emptyList())
        val interrupted = prefs.getBoolean("interrupted", false)
        val last = prefs.getLong("lastScanUtc", 0)
        val savedPhase = runCatching { MusicScanPhase.valueOf(prefs.getString("phase", "").orEmpty()) }.getOrNull()
        return MusicScanState(folders = folders, lastScanUtc = last, found = prefs.getInt("found", 0),
            added = prefs.getInt("added", 0), duplicates = prefs.getInt("duplicates", 0), unreadable = prefs.getInt("unreadable", 0),
            phase = if (interrupted) MusicScanPhase.FAILED else savedPhase ?: if (last > 0) MusicScanPhase.COMPLETE else MusicScanPhase.NOT_SCANNED,
            error = if (interrupted) "上次扫描未完成，点击可重新扫描" else prefs.getString("error", null))
    }

    companion object {
        @Volatile private var instance: MusicScanner? = null
        fun get(context: Context): MusicScanner = instance ?: synchronized(this) {
            instance ?: MusicScanner(context).also { instance = it }
        }
    }
}
