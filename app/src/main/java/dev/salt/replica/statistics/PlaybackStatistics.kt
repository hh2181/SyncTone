package dev.salt.replica.statistics

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

const val PLAY_THRESHOLD_MS = 30_000L
const val HOUR_MS = 3_600_000L

data class StatTrack(val id: String, val title: String, val artist: String, val album: String)
data class ListeningSpan(val session: String, val trackId: String, val startUtc: Long, val endUtc: Long, val listenedMs: Long)
data class CountedPlay(val session: String, val trackId: String, val countedUtc: Long)
data class RecordingBatch(val span: ListeningSpan?, val play: CountedPlay?)
data class SongStatistic(val track: StatTrack, val plays: Long, val listenedMs: Long)
data class EntityStatistic(val key: String, val title: String, val subtitle: String, val songs: List<SongStatistic>) {
    val plays: Long get() = songs.sumOf { it.plays }
}
data class StatisticsSnapshot(
    val songs: List<SongStatistic> = emptyList(),
    val spans: List<ListeningSpan> = emptyList(),
    val recentPlays: List<CountedPlay> = emptyList()
) {
    val totalPlays: Long get() = songs.sumOf { it.plays }
    fun between(start: Long, end: Long): List<SongStatistic> {
        val counts = recentPlays.filter { it.countedUtc >= start && it.countedUtc < end }.groupingBy { it.trackId }.eachCount()
        val duration = mutableMapOf<String, Long>()
        spans.forEach { span ->
            val overlap = (minOf(end, span.endUtc) - maxOf(start, span.startUtc)).coerceAtLeast(0)
            if (overlap > 0) {
                val ms = (span.listenedMs.toDouble() * overlap / (span.endUtc - span.startUtc).coerceAtLeast(1)).toLong()
                duration[span.trackId] = (duration[span.trackId] ?: 0) + ms
            }
        }
        return rankSongs(songs.mapNotNull { song ->
            val plays = counts[song.track.id]?.toLong() ?: 0
            val ms = duration[song.track.id] ?: 0
            if (plays == 0L && ms == 0L) null else SongStatistic(song.track, plays, ms)
        })
    }
    fun artists(): List<EntityStatistic> = entities(false)
    fun albums(): List<EntityStatistic> = entities(true)
    private fun entities(albums: Boolean): List<EntityStatistic> = songs.filter { it.plays > 0 }.groupBy {
        if (albums) it.track.artist + "\u0000" + it.track.album else it.track.artist
    }.map { (key, rows) ->
        EntityStatistic(key, if (albums) rows.first().track.album else key,
            if (albums) rows.first().track.artist else "${rows.size} 首歌曲", rankSongs(rows))
    }.sortedWith(compareByDescending<EntityStatistic> { it.plays }.thenBy { it.title })
}

fun rankSongs(songs: List<SongStatistic>): List<SongStatistic> = songs.sortedWith(
    compareByDescending<SongStatistic> { it.plays }.thenByDescending { it.listenedMs }.thenBy { it.track.title }
)

data class StatWindow(val start: Long, val end: Long, val date: LocalDate? = null)
fun recentDays(nowUtc: Long, zone: ZoneId): List<StatWindow> {
    val today = Instant.ofEpochMilli(nowUtc).atZone(zone).toLocalDate()
    return (6 downTo 0).map { ago ->
        val day = today.minusDays(ago.toLong())
        StatWindow(day.atStartOfDay(zone).toInstant().toEpochMilli(), day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), day)
    }
}
fun recentHours(nowUtc: Long): List<StatWindow> = (24 downTo 1).map {
    StatWindow(nowUtc - it * HOUR_MS, nowUtc - (it - 1) * HOUR_MS)
}

/** Uses the monotonic clock for duration; UTC is only used to place an interval on the calendar. */
class ListeningRecorder(private val emit: (RecordingBatch) -> Unit) {
    private var trackId: String? = null
    private var session = ""
    private var heard = 0L
    private var counted = false
    private var active = false
    private var lastElapsed = 0L
    private var lastUtc = 0L

    fun transition(id: String?, isPlaying: Boolean, utc: Long, elapsed: Long) {
        checkpoint(utc, elapsed)
        trackId = id
        session = UUID.randomUUID().toString()
        heard = 0L
        counted = false
        active = isPlaying && id != null
        lastUtc = utc
        lastElapsed = elapsed
    }

    fun playing(isPlaying: Boolean, utc: Long, elapsed: Long) {
        checkpoint(utc, elapsed)
        active = isPlaying && trackId != null
    }

    fun checkpoint(utc: Long, elapsed: Long) {
        val duration = (elapsed - lastElapsed).coerceAtLeast(0)
        val id = trackId
        if (active && id != null && duration > 0) {
            // A system-clock adjustment must never become hours of fabricated listening time.
            val stableClock = kotlin.math.abs((utc - lastUtc) - duration) < 2_000L
            val start = if (stableClock) lastUtc else utc - duration
            val oldHeard = heard
            heard += duration
            val play = if (!counted && heard >= PLAY_THRESHOLD_MS) {
                counted = true
                CountedPlay(session, id, start + (PLAY_THRESHOLD_MS - oldHeard).coerceAtLeast(0))
            } else null
            emit(RecordingBatch(ListeningSpan(session, id, start, maxOf(start + 1, utc), duration), play))
        }
        lastUtc = utc
        lastElapsed = elapsed
    }
}
