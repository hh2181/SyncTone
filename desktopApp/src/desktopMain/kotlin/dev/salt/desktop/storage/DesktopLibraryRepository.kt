package dev.salt.desktop.storage

import dev.salt.desktop.library.*
import dev.salt.replica.lyrics.Lyrics
import dev.salt.replica.sync.fileDigest
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.nio.file.attribute.BasicFileAttributes

class DesktopLibraryRepository(directory: Path?) : AutoCloseable {
    private var database: Connection
    private data class Artwork(val bytes: ByteArray?)
    private val artworkCache = WeightedLruCache<String, Artwork>(12L * 1024 * 1024) { (it.bytes?.size?.toLong() ?: 0L) + 64L }
    private val lyricsCache = WeightedLruCache<String, Lyrics>(4L * 1024 * 1024) { lyrics ->
        lyrics.lines.sumOf { line ->
            128L + line.cells.sumOf { 64L + it.text.length * 2 } +
                line.subLines.sumOf { sub -> 64L + sub.cells.sumOf { 64L + it.text.length * 2 } }
        } + 64L
    }
    private val artworkIdentities = mutableMapOf<String, Any>()
    internal val cachedArtworkBytes: Long get() = artworkCache.weight
    internal val cachedLyricsBytes: Long get() = lyricsCache.weight

    init {
        System.getProperty("compose.application.resources.dir")?.let {
            val native = Path.of(it, "sqlite")
            if (Files.isRegularFile(native.resolve("sqlitejdbc.dll"))) {
                System.setProperty("org.sqlite.lib.path", native.toString())
            }
        }
        Class.forName("org.sqlite.JDBC")
        directory?.let { Files.createDirectories(it) }
        database = DriverManager.getConnection(if (directory == null) "jdbc:sqlite::memory:" else
            "jdbc:sqlite:${directory.resolve("library.db").toAbsolutePath()}")
        try {
            database.createStatement().use { statement ->
                statement.execute("PRAGMA foreign_keys=ON")
                statement.execute("PRAGMA busy_timeout=5000")
                statement.execute("PRAGMA temp_store=MEMORY")
                statement.execute("PRAGMA journal_mode=WAL")
                val version = statement.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) }
                require(version <= 2) { "曲库由更新版本创建，请使用对应版本打开。" }
            }
            transaction {
                database.createStatement().use { sql ->
                    sql.executeUpdate("""CREATE TABLE IF NOT EXISTS tracks(
                        id TEXT PRIMARY KEY, title TEXT NOT NULL, artist TEXT NOT NULL, album TEXT NOT NULL,
                        duration_ms INTEGER NOT NULL, artwork BLOB, lyrics TEXT NOT NULL, lyric_override TEXT, original_path TEXT NOT NULL,
                        added_utc INTEGER NOT NULL)""")
                    sql.executeUpdate("""CREATE TABLE IF NOT EXISTS files(
                        path TEXT PRIMARY KEY, track_id TEXT NOT NULL REFERENCES tracks(id),
                        size INTEGER NOT NULL, modified TEXT NOT NULL, file_key TEXT NOT NULL,
                        sidecar_stamp TEXT NOT NULL, available INTEGER NOT NULL DEFAULT 1)""")
                    sql.executeUpdate("CREATE INDEX IF NOT EXISTS files_track ON files(track_id)")
                    sql.executeUpdate("CREATE INDEX IF NOT EXISTS tracks_album ON tracks(album,artist)")
                    sql.executeUpdate("CREATE TABLE IF NOT EXISTS folders(path TEXT PRIMARY KEY, issue TEXT)")
                    sql.executeUpdate("CREATE TABLE IF NOT EXISTS properties(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
                    val columns = sql.executeQuery("PRAGMA table_info(tracks)").use { rows ->
                        buildSet { while (rows.next()) add(rows.getString("name")) }
                    }
                    if ("original_path" !in columns) {
                        sql.executeUpdate("ALTER TABLE tracks ADD COLUMN original_path TEXT NOT NULL DEFAULT ''")
                        sql.executeUpdate("UPDATE tracks SET original_path=COALESCE((SELECT path FROM files WHERE track_id=tracks.id LIMIT 1),'')")
                    }
                    sql.executeUpdate("PRAGMA user_version=2")
                }
            }
            directory?.let { rebaseManagedMusic(it) }
        } catch (e: Exception) { database.close(); throw e }
    }

    private fun rebaseManagedMusic(directory: Path) {
        val current = canonicalKey(directory)
        val previous = property("data_directory")
        if (previous == current) return
        val files = database.createStatement().use { sql ->
            sql.executeQuery("SELECT path,track_id FROM files").use { rows ->
                buildList { while (rows.next()) add(rows.getString(1) to rows.getString(2)) }
            }
        }
        transaction {
            for ((stored, id) in files) {
                val old = Path.of(stored)
                if (old.parent?.fileName?.toString() != "synced-music") continue
                if (previous != null && !old.startsWith(Path.of(previous))) continue
                val relocated = directory.resolve("synced-music").resolve(old.fileName)
                if (canonicalKey(relocated) == stored || !Files.isRegularFile(relocated)) continue
                // A matching filename alone must never redirect a library entry to different audio.
                if (Files.newInputStream(relocated).use(::fileDigest) != id) continue
                val key = canonicalKey(relocated)
                database.prepareStatement("""INSERT OR IGNORE INTO files(path,track_id,size,modified,file_key,sidecar_stamp,available)
                    SELECT ?,track_id,size,modified,file_key,sidecar_stamp,available FROM files WHERE path=?""").use {
                    it.setString(1, key); it.setString(2, stored); it.executeUpdate()
                }
                database.prepareStatement("UPDATE tracks SET original_path=? WHERE original_path=?").use {
                    it.setString(1, key); it.setString(2, stored); it.executeUpdate()
                }
                database.prepareStatement("DELETE FROM files WHERE path=? AND track_id=(SELECT track_id FROM files WHERE path=?)").use {
                    it.setString(1, stored); it.setString(2, key); it.executeUpdate()
                }
            }
            setProperty("data_directory", current)
        }
    }

    // A connection is shared by the library worker and asynchronous artwork/lyric reads.
    @Synchronized private fun <T> transaction(block: () -> T): T {
        database.autoCommit = false
        try {
            val result = block()
            database.commit()
            return result
        } catch (e: Throwable) { database.rollback(); throw e }
        finally { database.autoCommit = true }
    }

    @Synchronized fun property(key: String): String? = database.prepareStatement("SELECT value FROM properties WHERE key=?").use {
        it.setString(1, key)
        it.executeQuery().use { row -> if (row.next()) row.getString(1) else null }
    }

    @Synchronized fun setProperty(key: String, value: String) {
        database.prepareStatement("INSERT OR REPLACE INTO properties(key,value) VALUES(?,?)").use {
            it.setString(1, key); it.setString(2, value); it.executeUpdate()
        }
    }

    @Synchronized fun folders(): List<MusicFolder> = database.createStatement().use {
        it.executeQuery("SELECT path,issue FROM folders ORDER BY rowid").use { rows -> buildList {
            while (rows.next()) add(MusicFolder(rows.getString(1), rows.getString(2)))
        } }
    }

    @Synchronized fun addFolder(path: Path) {
        database.prepareStatement("INSERT OR IGNORE INTO folders(path) VALUES(?)").use {
            it.setString(1, canonicalKey(path)); it.executeUpdate()
        }
    }

    @Synchronized fun removeFolder(path: String) {
        database.prepareStatement("DELETE FROM folders WHERE path=?").use { it.setString(1, path); it.executeUpdate() }
    }

    @Synchronized fun folderIssue(path: String, issue: String?) {
        database.prepareStatement("UPDATE folders SET issue=? WHERE path=?").use {
            it.setString(1, issue); it.setString(2, path); it.executeUpdate()
        }
    }

    @Synchronized fun tracks(): List<DesktopTrack> = database.createStatement().use {
        it.executeQuery("""SELECT t.id,t.title,t.artist,t.album,t.duration_ms,
            COALESCE((SELECT path FROM files f WHERE f.track_id=t.id ORDER BY available DESC,rowid LIMIT 1),t.original_path) path,
            EXISTS(SELECT 1 FROM files f WHERE f.track_id=t.id AND available=1) available
            FROM tracks t ORDER BY added_utc,t.rowid""").use { rows -> buildList {
            while (rows.next()) {
                val id = rows.getString("id")
                add(DesktopTrack(Path.of(rows.getString("path")), rows.getString("title"), rows.getString("artist"),
                    rows.getString("album"), rows.getLong("duration_ms"), null, Lyrics.Empty, id,
                    rows.getBoolean("available"), artworkLoader = { artwork(id) }, lyricsLoader = { lyrics(id) },
                    artworkIdentity = artworkIdentities.getOrPut(id) { Any() }))
            }
        } }
    }

    @Synchronized private fun artwork(id: String): ByteArray? {
        if (database.isClosed) return null
        artworkCache[id]?.let { return it.bytes }
        val bytes = database.prepareStatement("SELECT artwork FROM tracks WHERE id=?").use {
            it.setString(1, id)
            it.executeQuery().use { rows -> if (rows.next()) rows.getBytes(1) else null }
        }
        artworkCache.put(id, Artwork(bytes))
        return bytes
    }

    @Synchronized private fun lyrics(id: String): Lyrics {
        if (database.isClosed) return Lyrics.Empty
        lyricsCache[id]?.let { return it }
        val lyrics = database.prepareStatement("SELECT COALESCE(lyric_override,lyrics) FROM tracks WHERE id=?").use {
            it.setString(1, id)
            it.executeQuery().use { rows -> if (rows.next()) decodeLyrics(rows.getString(1)) else Lyrics.Empty }
        }
        lyricsCache.put(id, lyrics)
        return lyrics
    }

    @Synchronized fun setLyrics(id: String, lyrics: Lyrics) {
        database.prepareStatement("UPDATE tracks SET lyric_override=? WHERE id=?").use {
            it.setString(1, encodeLyrics(lyrics)); it.setString(2, id); it.executeUpdate()
        }
        lyricsCache.remove(id)
    }
    @Synchronized fun restoreLyrics(id: String) {
        database.prepareStatement("UPDATE tracks SET lyric_override=NULL WHERE id=?").use {
            it.setString(1, id); it.executeUpdate()
        }
        lyricsCache.remove(id)
    }

    @Synchronized fun idForPath(path: String): String? = database.prepareStatement("SELECT track_id FROM files WHERE path=?").use {
        val key = runCatching { canonicalKey(Path.of(path)) }.getOrDefault(path)
        it.setString(1, key)
        it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
    }

    @Synchronized fun register(path: Path, force: Boolean = false, checkCancelled: () -> Unit = {}): Registration {
        val real = path.toRealPath()
        val key = canonicalKey(real)
        val before = Files.readAttributes(real, BasicFileAttributes::class.java)
        require(before.isRegularFile) { "不是有效文件" }
        val sidecar = sidecarStamp(real)
        val existing = database.prepareStatement("SELECT * FROM files WHERE path=?").use {
            it.setString(1, key)
            it.executeQuery().use { row -> if (row.next()) CachedFile(row.getString("track_id"), row.getLong("size"),
                row.getString("modified"), row.getString("file_key"), row.getString("sidecar_stamp")) else null }
        }
        if (!force && existing != null && existing.size == before.size() && existing.modified == before.lastModifiedTime().toString() &&
            existing.fileKey == before.fileKey()?.toString().orEmpty() && existing.sidecar == sidecar) {
            database.prepareStatement("UPDATE files SET available=1 WHERE path=?").use {
                it.setString(1, key); it.executeUpdate()
            }
            return Registration(existing.id, added = false, cached = true)
        }
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(real).use { stream ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                checkCancelled()
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val id = digest.digest().joinToString("") { "%02x".format(it) }
        checkCancelled()
        val metadata = DesktopTrackReader.read(real)
        lyricsCache.remove(id)
        artworkCache.remove(id)
        artworkIdentities.remove(id)
        val after = Files.readAttributes(real, BasicFileAttributes::class.java)
        require(before.size() == after.size() && before.lastModifiedTime() == after.lastModifiedTime() &&
            before.fileKey() == after.fileKey() && sidecar == sidecarStamp(real)) { "文件正在变化，请稍后重试" }
        return transaction {
            val added = database.prepareStatement("SELECT 1 FROM tracks WHERE id=?").use {
                it.setString(1, id); it.executeQuery().use { row -> !row.next() }
            }
            database.prepareStatement("""INSERT INTO tracks(id,title,artist,album,duration_ms,artwork,lyrics,added_utc,original_path)
                VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET title=excluded.title,artist=excluded.artist,
                album=excluded.album,duration_ms=excluded.duration_ms,artwork=excluded.artwork,lyrics=excluded.lyrics""").use {
                it.setString(1, id); it.setString(2, metadata.title); it.setString(3, metadata.artist)
                it.setString(4, metadata.album); it.setLong(5, metadata.durationMs); it.setBytes(6, metadata.artwork)
                it.setString(7, encodeLyrics(metadata.lyrics)); it.setLong(8, System.currentTimeMillis())
                it.setString(9, key); it.executeUpdate()
            }
            database.prepareStatement("""INSERT INTO files(path,track_id,size,modified,file_key,sidecar_stamp,available)
                VALUES(?,?,?,?,?,?,1) ON CONFLICT(path) DO UPDATE SET track_id=excluded.track_id,size=excluded.size,
                modified=excluded.modified,file_key=excluded.file_key,sidecar_stamp=excluded.sidecar_stamp,available=1""").use {
                it.setString(1, key); it.setString(2, id); it.setLong(3, after.size()); it.setString(4, after.lastModifiedTime().toString())
                it.setString(5, after.fileKey()?.toString().orEmpty()); it.setString(6, sidecar); it.executeUpdate()
            }
            Registration(id, added, cached = false)
        }
    }

    fun refreshAvailability(checkCancelled: () -> Unit = {}) {
        val files = synchronized(this) { database.createStatement().use { sql -> sql.executeQuery("SELECT path,size,modified FROM files").use { rows ->
            buildList { while (rows.next()) add(Triple(rows.getString(1), rows.getLong(2), rows.getString(3))) }
        } } }
        // Do filesystem IO outside the connection monitor so covers/lyrics stay readable.
        val availability = files.map { (path, size, modified) ->
            checkCancelled()
            val valid = runCatching { Files.readAttributes(Path.of(path), BasicFileAttributes::class.java) }
                .getOrNull()?.let { it.isRegularFile && it.size() == size && it.lastModifiedTime().toString() == modified } ?: false
            valid
        }
        checkCancelled()
        transaction {
            database.prepareStatement("UPDATE files SET available=? WHERE path=? AND size=? AND modified=?").use { sql ->
                files.forEachIndexed { index, (path, size, modified) ->
                    sql.setBoolean(1, availability[index]); sql.setString(2, path)
                    sql.setLong(3, size); sql.setString(4, modified); sql.addBatch()
                }
                sql.executeBatch()
            }
        }
    }

    @Synchronized fun saveScan(progress: ScanProgress) {
        val json = kotlinx.serialization.json.buildJsonObject {
            put("phase", kotlinx.serialization.json.JsonPrimitive(progress.phase.name))
            put("found", kotlinx.serialization.json.JsonPrimitive(progress.found))
            put("checked", kotlinx.serialization.json.JsonPrimitive(progress.checked))
            put("added", kotlinx.serialization.json.JsonPrimitive(progress.added))
            put("duplicates", kotlinx.serialization.json.JsonPrimitive(progress.duplicates))
            put("unreadable", kotlinx.serialization.json.JsonPrimitive(progress.unreadable))
            put("cached", kotlinx.serialization.json.JsonPrimitive(progress.cached))
            put("lastSuccessUtc", kotlinx.serialization.json.JsonPrimitive(progress.lastSuccessUtc))
            progress.error?.let { put("error", kotlinx.serialization.json.JsonPrimitive(it)) }
        }
        setProperty("scan", json.toString())
    }

    @Synchronized fun readScan(): ScanProgress {
        val text = property("scan") ?: return ScanProgress()
        val json = kotlinx.serialization.json.Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
        fun value(key: String) = (json[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
        val phase = value("phase")?.let(ScanPhase::valueOf) ?: ScanPhase.NotScanned
        return ScanProgress(if (phase == ScanPhase.Scanning) ScanPhase.Cancelled else phase,
            value("found")?.toInt() ?: 0, value("checked")?.toInt() ?: 0, value("added")?.toInt() ?: 0,
            value("duplicates")?.toInt() ?: 0, value("unreadable")?.toInt() ?: 0, value("cached")?.toInt() ?: 0,
            lastSuccessUtc = value("lastSuccessUtc")?.toLong() ?: 0,
            error = if (phase == ScanPhase.Scanning) "上次扫描未完成，可重新扫描" else value("error"))
    }

    @Synchronized override fun close() { artworkCache.clear(); lyricsCache.clear(); artworkIdentities.clear(); database.close() }

    @Synchronized fun persistTo(directory: Path) {
        Files.createDirectories(directory)
        val target = directory.resolve("library.db")
        require(!Files.exists(target)) { "数据目录已有曲库，请重启后加载，当前内存曲库仍保留。" }
        database.prepareStatement("VACUUM main INTO ?").use {
            it.setString(1, target.toAbsolutePath().toString()); it.execute()
        }
        val persisted = DesktopLibraryRepository(directory)
        val previous = database
        database = persisted.database
        previous.close()
    }

    private data class CachedFile(val id: String, val size: Long, val modified: String, val fileKey: String, val sidecar: String)
    private fun sidecarStamp(path: Path): String {
        val name = path.fileName.toString().substringBeforeLast('.') + ".lrc"
        val sidecar = path.resolveSibling(name)
        return if (Files.isRegularFile(sidecar)) {
            val attrs = Files.readAttributes(sidecar, BasicFileAttributes::class.java)
            "${attrs.size()}:${attrs.lastModifiedTime()}"
        } else ""
    }

    companion object {
        fun canonicalKey(path: Path): String {
            val real = try { path.toRealPath().toString() }
            catch (_: java.io.IOException) { path.toAbsolutePath().normalize().toString() }
            return if (System.getProperty("os.name").startsWith("Windows")) real.lowercase(java.util.Locale.ROOT) else real
        }
    }
}
