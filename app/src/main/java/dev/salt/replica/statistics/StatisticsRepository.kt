package dev.salt.replica.statistics

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import java.util.concurrent.Executors

data class StatisticsState(val loading: Boolean = true, val snapshot: StatisticsSnapshot = StatisticsSnapshot(), val error: Boolean = false)
data class HistoryPage(val epoch: String, val next: Long, val records: List<HistoryRecord>)

class StatisticsRepository private constructor(context: Context) {
    private val app = context.applicationContext
    private val dispatcher = Executors.newSingleThreadExecutor { task -> Thread(task, "playback-statistics") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val helper = Database(app)
    private val mutableState = MutableStateFlow(StatisticsState())
    val state: StateFlow<StatisticsState> = mutableState
    val origin: String = app.getSharedPreferences("sync", Context.MODE_PRIVATE).let { prefs ->
        prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("deviceId", it).commit() }
    }
    init { refresh() }
    fun metadata(tracks: List<StatTrack>) = work {
        transaction { db -> tracks.forEach { track ->
            val canonical = track.copy(id = songIdentity(track.title, track.artist), artist = track.artist.ifBlank { "未知歌手" })
            putTrack(db, canonical)
            db.insertWithOnConflict("aliases", null, ContentValues().apply { put("local_id", track.id); put("song_id", canonical.id) }, SQLiteDatabase.CONFLICT_REPLACE)
        } }
        publish()
    }
    fun record(batch: RecordingBatch) = work {
        transaction { db ->
            val local = batch.span?.trackId ?: batch.play?.trackId ?: return@transaction
            val track = db.rawQuery("SELECT t.id,t.title,t.artist,t.album FROM aliases a JOIN tracks t ON t.id=a.song_id WHERE a.local_id=?", arrayOf(local)).use { c ->
                if (c.moveToFirst()) StatTrack(c.getString(0), c.getString(1), c.getString(2), c.getString(3)) else null
            } ?: return@transaction
            batch.span?.let { span ->
                val previous = db.rawQuery("SELECT * FROM records WHERE origin=? AND session=? AND kind='span' ORDER BY seq DESC LIMIT 1", arrayOf(origin, span.session)).use { c -> if (c.moveToFirst()) read(c) else null }
                val merged = previous?.takeIf { kotlin.math.abs(it.end - span.startUtc) <= 2L }
                put(db, if (merged != null) merged.copy(end = span.endUtc, ms = merged.ms + span.listenedMs, revision = merged.revision + 1)
                    else HistoryRecord(UUID.randomUUID().toString(), origin, span.session, "span", track, span.startUtc, span.endUtc, span.listenedMs, 1))
            }
            batch.play?.let { play ->
                val id = "play:${play.session}"
                val exists = db.rawQuery("SELECT 1 FROM records WHERE id=?", arrayOf(id)).use { it.moveToFirst() }
                if (!exists) put(db, HistoryRecord(id, origin, play.session, "play", track, play.countedUtc, play.countedUtc, 0, 1))
            }
        }
        if (mutableState.subscriptionCount.value > 0) publish()
    }
    suspend fun epoch(): String = withContext(dispatcher) { helper.readableDatabase.rawQuery("SELECT value FROM meta WHERE key='epoch'", null).use { it.moveToFirst(); it.getString(0) } }
    suspend fun export(after: Long): HistoryPage = withContext(dispatcher) {
        val db = helper.readableDatabase
        val rows = mutableListOf<HistoryRecord>(); var next = after
        db.rawQuery("SELECT * FROM records WHERE seq>? ORDER BY seq LIMIT 200", arrayOf(after.coerceAtLeast(0).toString())).use { c ->
            while (c.moveToNext()) { rows += read(c); next = c.getLong(c.getColumnIndexOrThrow("seq")) }
        }
        HistoryPage(epoch(), next, rows)
    }
    suspend fun merge(records: List<HistoryRecord>): Int = withContext(dispatcher) {
        require(records.size <= 200)
        var changed = 0
        transaction { db -> records.forEach { row ->
            val previous = db.rawQuery("SELECT * FROM records WHERE id=?", arrayOf(row.id)).use { if (it.moveToFirst()) read(it) else null }
            if (acceptsHistory(previous, row, origin)) { putTrack(db, row.track); put(db, row); changed++ }
        } }
        publish(); changed
    }
    fun refresh() = work { publish() }
    private fun work(action: () -> Unit) { scope.launch { runCatching(action).onFailure {
        Log.e("SyncTone", "Playback statistics could not be updated", it)
        mutableState.value = mutableState.value.copy(loading = false, error = true)
    } } }
    private fun transaction(action: (SQLiteDatabase) -> Unit) {
        val db = helper.writableDatabase; db.beginTransaction()
        try { action(db); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
    private fun putTrack(db: SQLiteDatabase, track: StatTrack) {
        db.insertWithOnConflict("tracks", null, ContentValues().apply {
            put("id", track.id); put("title", track.title); put("artist", track.artist.ifBlank { "未知歌手" }); put("album", track.album.ifBlank { "未知专辑" })
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }
    private fun put(db: SQLiteDatabase, r: HistoryRecord) {
        val seq = db.rawQuery("SELECT COALESCE(MAX(seq),0)+1 FROM records", null).use { it.moveToFirst(); it.getLong(0) }
        db.insertWithOnConflict("records", null, ContentValues().apply {
            put("id", r.id); put("origin", r.origin); put("session", r.session); put("kind", r.kind); put("track_id", r.track.id)
            put("title", r.track.title); put("artist", r.track.artist); put("album", r.track.album)
            put("start_utc", r.start); put("end_utc", r.end); put("listened_ms", r.ms); put("revision", r.revision); put("seq", seq)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    private fun read(c: Cursor): HistoryRecord {
        fun s(key: String) = c.getString(c.getColumnIndexOrThrow(key))
        fun n(key: String) = c.getLong(c.getColumnIndexOrThrow(key))
        return HistoryRecord(s("id"), s("origin"), s("session"), s("kind"), StatTrack(s("track_id"),s("title"),s("artist"),s("album")), n("start_utc"),n("end_utc"),n("listened_ms"),n("revision"))
    }
    private fun publish() {
        val db = helper.readableDatabase
        val songs = mutableListOf<SongStatistic>()
        db.rawQuery("SELECT t.id,t.title,t.artist,t.album,SUM(CASE WHEN r.kind='play' THEN 1 ELSE 0 END),SUM(r.listened_ms) FROM records r JOIN tracks t ON t.id=r.track_id GROUP BY t.id",null).use { c ->
            while (c.moveToNext()) songs += SongStatistic(StatTrack(c.getString(0),c.getString(1),c.getString(2),c.getString(3)),c.getLong(4),c.getLong(5))
        }
        val cutoff = System.currentTimeMillis() - 8 * 24 * HOUR_MS
        val spans = mutableListOf<ListeningSpan>(); val plays = mutableListOf<CountedPlay>()
        db.rawQuery("SELECT * FROM records WHERE end_utc>=?", arrayOf(cutoff.toString())).use { c -> while (c.moveToNext()) {
            val r = read(c)
            if (r.kind == "span") spans += ListeningSpan(r.session,r.track.id,r.start,r.end,r.ms)
            else plays += CountedPlay(r.session,r.track.id,r.start)
        } }
        mutableState.value = StatisticsState(false, StatisticsSnapshot(rankSongs(songs),spans,plays))
    }
    private class Database(private val app: Context) : SQLiteOpenHelper(app,"playback-statistics.db",null,2) {
        override fun onConfigure(db: SQLiteDatabase) { db.enableWriteAheadLogging() }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE tracks(id TEXT PRIMARY KEY,title TEXT NOT NULL,artist TEXT NOT NULL,album TEXT NOT NULL)")
            db.execSQL("CREATE TABLE aliases(local_id TEXT PRIMARY KEY,song_id TEXT NOT NULL)")
            db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
            db.execSQL("INSERT INTO meta VALUES('epoch','${UUID.randomUUID()}')")
            db.execSQL("CREATE TABLE records(id TEXT PRIMARY KEY,origin TEXT NOT NULL,session TEXT NOT NULL,kind TEXT NOT NULL,track_id TEXT NOT NULL,title TEXT NOT NULL,artist TEXT NOT NULL,album TEXT NOT NULL,start_utc INTEGER NOT NULL,end_utc INTEGER NOT NULL,listened_ms INTEGER NOT NULL,revision INTEGER NOT NULL,seq INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX records_seq ON records(seq)")
            db.execSQL("CREATE INDEX records_session ON records(origin,session,kind,seq)")
            db.execSQL("CREATE INDEX records_time ON records(end_utc)")
            app.getSharedPreferences("player",Context.MODE_PRIVATE).edit().remove("playCounts").apply()
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Development migration explicitly requested: reset statistics once, retain the music library.
            listOf("tracks","legacy_counts","plays","spans").forEach { db.execSQL("DROP TABLE IF EXISTS $it") }
            onCreate(db)
        }
    }
    companion object {
        @Volatile private var instance: StatisticsRepository? = null
        fun get(context: Context): StatisticsRepository = instance ?: synchronized(this) { instance ?: StatisticsRepository(context).also { instance=it } }
    }
}
