package dev.salt.replica.sync

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/** Persisted content fingerprints, invalidated by source size/mtime/provider generation. */
internal object SyncFileIndex {
    private data class Entry(val stamp: SyncSourceStamp, val hash: String, val size: Long, val song: SyncSong?)
    private data class Source(val stamp: SyncSourceStamp, val name: String)
    private val mutex = Mutex()
    private val entries = linkedMapOf<String, Entry>()
    private var root = ""
    private var dirty = false

    fun registeredUri(value: String): Uri = if (value.contains("://")) Uri.parse(value) else Uri.fromFile(File(value))
    private fun source(context: Context, uri: Uri): Source {
        if (uri.scheme == "file") {
            val file = File(requireNotNull(uri.path)); check(file.isFile && file.canRead()) { "歌曲文件无法读取" }
            return Source(SyncSourceStamp(file.length(), file.lastModified()), file.name)
        }
        var size = -1L; var modified = 0L; var generation = 0L
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "音乐.mp3"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            check(cursor.moveToFirst()) { "歌曲文件已不可用" }
            fun number(column: String): Long {
                val index = cursor.getColumnIndex(column)
                return if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else 0L
            }
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            modified = maxOf(number("last_modified"), number("date_modified") * 1000)
            generation = number("generation_modified")
        }
        return Source(SyncSourceStamp(size, modified, generation), name)
    }

    private fun load(context: Context) {
        if (root == context.filesDir.absolutePath) return
        root = context.filesDir.absolutePath; entries.clear(); dirty = false
        runCatching {
            val json = AtomicFile(File(context.filesDir, "sync-file-index.json")).openRead().bufferedReader().use { JSONObject(it.readText()) }
            for (key in json.keys()) {
                val row = json.getJSONObject(key)
                val hash = row.getString("hash"); require(hash.matches(Regex("[a-f0-9]{64}")))
                entries[key] = Entry(SyncSourceStamp(row.getLong("sourceSize"), row.getLong("modified"), row.optLong("generation")),
                    hash, row.getLong("size"), row.optJSONObject("song")?.let(SyncSong::parse))
            }
        }.onFailure { entries.clear() }
    }

    private suspend fun read(context: Context, uri: Uri, metadata: Boolean): Entry {
        load(context)
        val key = uri.toString(); val before = source(context, uri)
        val cached = entries[key]?.takeIf { it.stamp.reusableFor(before.stamp) }
        if (cached != null && (!metadata || cached.song != null)) return cached
        val fingerprint = cached ?: run {
            val hash = java.security.MessageDigest.getInstance("SHA-256"); var size = 0L
            open(context, uri).use { input ->
                val buffer = ByteArray(131072)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer); if (count < 0) break
                    hash.update(buffer, 0, count); size += count
                }
            }
            check(size > 0 && (before.stamp.size < 0 || before.stamp.size == size)) { "音乐文件已变化，请重新对比" }
            Entry(before.stamp, hash.digest().joinToString("") { "%02x".format(it) }, size, null)
        }
        var song = fingerprint.song
        if (metadata && song == null) {
            val mime = context.contentResolver.getType(uri).orEmpty()
            val flac = before.name.endsWith(".flac", true) || mime.contains("flac")
            if (flac || before.name.endsWith(".mp3", true) || mime in listOf("audio/mpeg", "audio/mp3")) {
                val retriever = MediaMetadataRetriever()
                try {
                    if (uri.scheme == "file") retriever.setDataSource(uri.path) else retriever.setDataSource(context, uri)
                    song = SyncSong(fingerprint.hash, retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                        ?: before.name.substringBeforeLast('.'), retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty(),
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty().ifBlank { "未知专辑" },
                        before.name, fingerprint.size, flac)
                    SyncSong.parse(song.json())
                } finally { retriever.release() }
            }
        }
        val after = source(context, uri)
        check(before.stamp == after.stamp) { "音乐文件已变化，请重新对比" }
        return fingerprint.copy(song = song).also { entries[key] = it; dirty = true }
    }

    suspend fun fingerprint(context: Context, uri: Uri): String = mutex.withLock { read(context, uri, false).hash }
    suspend fun song(context: Context, uri: Uri): SyncSong? = mutex.withLock { read(context, uri, true).song }
    suspend fun persist(context: Context, validUris: Set<String>? = null) = mutex.withLock {
        load(context)
        if (validUris != null && entries.keys.retainAll(validUris)) dirty = true
        if (!dirty) return@withLock
        val json = JSONObject()
        entries.forEach { (key, entry) -> json.put(key, JSONObject().put("sourceSize", entry.stamp.size).put("modified", entry.stamp.modified)
            .put("generation", entry.stamp.generation).put("hash", entry.hash).put("size", entry.size).apply { entry.song?.let { put("song", it.json()) } }) }
        val file = AtomicFile(File(context.filesDir, "sync-file-index.json")); val output = file.startWrite()
        try { output.write(json.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(output); dirty = false }
        catch (e: Exception) { file.failWrite(output); throw e }
    }
    private fun open(context: Context, uri: Uri): InputStream = if (uri.scheme == "file") FileInputStream(requireNotNull(uri.path))
        else requireNotNull(context.contentResolver.openInputStream(uri)) { "歌曲文件无法读取" }
}
