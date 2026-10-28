package dev.salt.desktop.statistics

import dev.salt.replica.statistics.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class StatisticsState(
    val loading: Boolean = true,
    val snapshot: StatisticsSnapshot = StatisticsSnapshot(),
    val error: Boolean = false,
)

data class HistoryPage(val epoch: String, val next: Long, val records: List<HistoryRecord>)

class StatisticsRepository(directory: Path?) : AutoCloseable {
    private val database: Connection
    private val mutableState = MutableStateFlow(StatisticsState())
    val state = mutableState.asStateFlow()
    val origin: String

    init {
        directory?.let { Files.createDirectories(it) }
        database = DriverManager.getConnection(if (directory == null) "jdbc:sqlite::memory:"
        else "jdbc:sqlite:${directory.resolve("playback-statistics.db").toAbsolutePath()}")
        try {
        database.createStatement().use { statement ->
            statement.execute("PRAGMA busy_timeout=5000")
            statement.execute("PRAGMA temp_store=MEMORY")
            val version = statement.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) }
            require(version in 0..1) { "不支持的统计数据版本，原数据库已保留" }
            statement.execute("PRAGMA journal_mode=WAL")
            statement.executeUpdate("""CREATE TABLE IF NOT EXISTS tracks(id TEXT PRIMARY KEY,title TEXT NOT NULL,artist TEXT NOT NULL,album TEXT NOT NULL)""")
            statement.executeUpdate("""CREATE TABLE IF NOT EXISTS aliases(local_id TEXT PRIMARY KEY,song_id TEXT NOT NULL)""")
            statement.executeUpdate("""CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)""")
            statement.executeUpdate("""CREATE TABLE IF NOT EXISTS records(id TEXT PRIMARY KEY,origin TEXT NOT NULL,session TEXT NOT NULL,kind TEXT NOT NULL,track_id TEXT NOT NULL,title TEXT NOT NULL,artist TEXT NOT NULL,album TEXT NOT NULL,start_utc INTEGER NOT NULL,end_utc INTEGER NOT NULL,listened_ms INTEGER NOT NULL,revision INTEGER NOT NULL,seq INTEGER NOT NULL)""")
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS records_seq ON records(seq)")
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS records_time ON records(end_utc)")
            statement.execute("PRAGMA user_version=1")
        }
        origin = property("origin") ?: UUID.randomUUID().toString().also { setProperty("origin", it) }
        if (property("epoch") == null) setProperty("epoch", UUID.randomUUID().toString())
        refresh()
        } catch (e: Exception) { database.close(); throw e }
    }

    @Synchronized fun metadata(tracks: List<StatTrack>) {
        transaction {
            tracks.forEach { track ->
                val canonical = track.copy(id = songIdentity(track.title, track.artist), artist = track.artist.ifBlank { "未知歌手" })
                putTrack(canonical)
                prepareStatement("INSERT OR REPLACE INTO aliases(local_id,song_id) VALUES(?,?)").use {
                    it.setString(1, track.id); it.setString(2, canonical.id); it.executeUpdate()
                }
            }
        }
        refresh()
    }

    @Synchronized fun record(batch: RecordingBatch) {
        transaction {
            val local = batch.span?.trackId ?: batch.play?.trackId ?: return@transaction
            val track = prepareStatement("SELECT t.id,t.title,t.artist,t.album FROM aliases a JOIN tracks t ON t.id=a.song_id WHERE a.local_id=?").use {
                it.setString(1, local); it.executeQuery().use { row ->
                    if (row.next()) StatTrack(row.getString(1), row.getString(2), row.getString(3), row.getString(4)) else null
                }
            } ?: return@transaction
            batch.span?.let { span ->
                val previous = prepareStatement("SELECT * FROM records WHERE origin=? AND session=? AND kind='span' ORDER BY seq DESC LIMIT 1").use {
                    it.setString(1, origin); it.setString(2, span.session); it.executeQuery().use { row -> if (row.next()) read(row) else null }
                }
                val merged = previous?.takeIf { kotlin.math.abs(it.end - span.startUtc) <= 2L }
                put(merged?.copy(end = span.endUtc, ms = merged.ms + span.listenedMs, revision = merged.revision + 1)
                    ?: HistoryRecord(UUID.randomUUID().toString(), origin, span.session, "span", track, span.startUtc, span.endUtc, span.listenedMs, 1))
            }
            batch.play?.let { play ->
                val id = "play:${play.session}"
                prepareStatement("SELECT 1 FROM records WHERE id=?").use {
                    it.setString(1, id); it.executeQuery().use { row ->
                        if (!row.next()) put(HistoryRecord(id, origin, play.session, "play", track, play.countedUtc, play.countedUtc, 0, 1))
                    }
                }
            }
        }
        refresh()
    }

    @Synchronized fun snapshot(nowUtc: Long = System.currentTimeMillis()): StatisticsSnapshot {
        val songs = mutableListOf<SongStatistic>()
        database.createStatement().use { it.executeQuery("SELECT t.id,t.title,t.artist,t.album,SUM(CASE WHEN r.kind='play' THEN 1 ELSE 0 END),SUM(r.listened_ms) FROM records r JOIN tracks t ON t.id=r.track_id GROUP BY t.id").use { row ->
            while (row.next()) songs += SongStatistic(StatTrack(row.getString(1), row.getString(2), row.getString(3), row.getString(4)), row.getLong(5), row.getLong(6))
        } }
        val spans = mutableListOf<ListeningSpan>(); val plays = mutableListOf<CountedPlay>()
        val cutoff = nowUtc - 8 * 24 * HOUR_MS
        database.prepareStatement("SELECT * FROM records WHERE end_utc>=?").use {
            it.setLong(1, cutoff); it.executeQuery().use { row -> while (row.next()) {
                val record = read(row)
                if (record.kind == "span") spans += ListeningSpan(record.session, record.track.id, record.start, record.end, record.ms)
                else plays += CountedPlay(record.session, record.track.id, record.start)
            } }
        }
        return StatisticsSnapshot(rankSongs(songs), spans, plays)
    }

    @Synchronized fun refresh() {
        runCatching { snapshot() }
            .onSuccess { mutableState.value = StatisticsState(loading = false, snapshot = it) }
            .onFailure { mutableState.value = StatisticsState(loading = false, error = true) }
    }

    @Synchronized fun epoch(): String = property("epoch") ?: error("统计 epoch 缺失")

    @Synchronized fun export(after: Long): HistoryPage {
        val rows = mutableListOf<HistoryRecord>(); var next = after
        prepareStatement("SELECT * FROM records WHERE seq>? ORDER BY seq LIMIT 200").use {
            it.setLong(1, after.coerceAtLeast(0)); it.executeQuery().use { row -> while (row.next()) { rows += read(row); next = row.getLong(row.findColumn("seq")) } }
        }
        return HistoryPage(epoch(), next, rows)
    }

    @Synchronized fun merge(records: List<HistoryRecord>): Int {
        require(records.size <= 200)
        var changed = 0
        transaction { records.forEach { row ->
            val previous = prepareStatement("SELECT * FROM records WHERE id=?").use {
                it.setString(1, row.id); it.executeQuery().use { cursor -> if (cursor.next()) read(cursor) else null }
            }
            if (acceptsHistory(previous, row, origin)) { putTrack(row.track); put(row); changed++ }
        } }
        refresh()
        return changed
    }

    @Synchronized fun property(key: String): String? = database.prepareStatement("SELECT value FROM meta WHERE key=?").use {
        it.setString(1, key); it.executeQuery().use { row -> if (row.next()) row.getString(1) else null }
    }
    @Synchronized fun setProperty(key: String, value: String) { database.prepareStatement("INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)").use { it.setString(1, key); it.setString(2, value); it.executeUpdate() } }

    private fun transaction(block: () -> Unit) { database.autoCommit = false; try { block(); database.commit() } catch (e: Throwable) { database.rollback(); throw e } finally { database.autoCommit = true } }
    private fun putTrack(track: StatTrack) { prepareStatement("INSERT OR IGNORE INTO tracks(id,title,artist,album) VALUES(?,?,?,?)").use { it.setString(1,track.id);it.setString(2,track.title);it.setString(3,track.artist);it.setString(4,track.album);it.executeUpdate() } }
    private fun put(r: HistoryRecord) { val seq = database.createStatement().use { it.executeQuery("SELECT COALESCE(MAX(seq),0)+1 FROM records").use { row -> row.next(); row.getLong(1) } }; prepareStatement("INSERT OR REPLACE INTO records(id,origin,session,kind,track_id,title,artist,album,start_utc,end_utc,listened_ms,revision,seq) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)").use { it.setString(1,r.id);it.setString(2,r.origin);it.setString(3,r.session);it.setString(4,r.kind);it.setString(5,r.track.id);it.setString(6,r.track.title);it.setString(7,r.track.artist);it.setString(8,r.track.album);it.setLong(9,r.start);it.setLong(10,r.end);it.setLong(11,r.ms);it.setLong(12,r.revision);it.setLong(13,seq);it.executeUpdate() } }
    private fun read(row: java.sql.ResultSet) = HistoryRecord(row.getString("id"),row.getString("origin"),row.getString("session"),row.getString("kind"),StatTrack(row.getString("track_id"),row.getString("title"),row.getString("artist"),row.getString("album")),row.getLong("start_utc"),row.getLong("end_utc"),row.getLong("listened_ms"),row.getLong("revision"))
    private fun prepareStatement(sql: String) = database.prepareStatement(sql)
    @Synchronized override fun close() { database.close() }
}
