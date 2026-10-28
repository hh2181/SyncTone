package dev.salt.desktop

import dev.salt.desktop.statistics.StatisticsRepository
import dev.salt.desktop.statistics.DesktopStatistics
import dev.salt.desktop.library.DesktopTrack
import dev.salt.replica.lyrics.Lyrics
import dev.salt.replica.statistics.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import kotlin.test.*

class StatisticsTests {
    private val track = StatTrack("local-file-hash", "Song", "Artist", "Album")

    @Test fun desktopRecorderExcludesProcessSuspensionAndFlushesBeforeTrackChanges() = runBlocking {
        val statistics = DesktopStatistics()
        val directory = Files.createTempDirectory("synctone-recorder")
        val now = System.currentTimeMillis()
        try {
            statistics.initialize(directory)
            statistics.metadata(listOf(DesktopTrack(directory.resolve("song.mp3"), "Song", "Artist", "Album", 100_000,
                null, Lyrics.Empty, track.id)))
            statistics.transition(track.id, now, 0)
            statistics.sample(true, now, 0)
            for (i in 1..29) statistics.sample(true, now + i * 1000, i * 1000L)
            assertEquals(0, statistics.history.snapshot().totalPlays)
            statistics.sample(false, now + 30_000, 30_000)
            assertEquals(1, statistics.history.snapshot().totalPlays)
            statistics.sample(false, now + 70_000, 70_000)
            statistics.sample(true, now + 70_000, 70_000)
            statistics.sample(true, now + 71_000, 71_000)
            statistics.sample(true, now + 200_000, 200_000)
            statistics.sample(true, now + 201_000, 201_000)
            statistics.transition(null, now + 400_000, 400_000)
            assertEquals(32_000, statistics.history.snapshot().songs.single().listenedMs)
        } finally { statistics.close(now + 400_000, 400_000); directory.toFile().deleteRecursively() }
    }

    @Test fun thresholdPausesSeeksAndRestartsRetainOnlyActualListening() {
        val directory = Files.createTempDirectory("synctone-stats")
        val now = System.currentTimeMillis()
        var origin = ""
        var epoch = ""
        StatisticsRepository(directory).use { db ->
            origin = db.origin; epoch = db.epoch()
            db.metadata(listOf(track))
            val recorder = ListeningRecorder(db::record)
            recorder.transition(track.id, true, now, 0)
            recorder.checkpoint(now + 29_999, 29_999)
            assertEquals(0, db.snapshot().totalPlays)
            recorder.playing(false, now + 29_999, 29_999)
            recorder.checkpoint(now + 90_000, 90_000)
            assertEquals(29_999, db.snapshot().songs.single().listenedMs)
            recorder.playing(true, now + 90_000, 90_000)
            recorder.checkpoint(now + 90_001, 90_001)
            assertEquals(1, db.snapshot().totalPlays)
            recorder.checkpoint(now + 95_000, 95_000)
            recorder.transition(track.id, false, now + 95_000, 95_000)
            assertEquals(34_999, db.snapshot().songs.single().listenedMs)
        }
        StatisticsRepository(directory).use { db ->
            assertEquals(origin, db.origin); assertEquals(epoch, db.epoch())
            assertEquals(1, db.snapshot().totalPlays)
            assertEquals(34_999, db.snapshot().songs.single().listenedMs)
        }
    }

    @Test fun historyPagesAndNewerRevisionsMergeIdempotentlyWithoutEcho() {
        StatisticsRepository(null).use { sender -> StatisticsRepository(null).use { receiver ->
            val song = track.copy(id = songIdentity(track.title, track.artist))
            val now = System.currentTimeMillis()
            val rows = (0..205).map { i -> HistoryRecord("row:$i", "another-device", "session:$i", "play", song,
                now + i, now + i, 0, 1) }
            assertEquals(200, sender.merge(rows.take(200)))
            assertEquals(6, sender.merge(rows.drop(200)))
            val first = sender.export(0)
            assertEquals(200, first.records.size)
            val second = sender.export(first.next)
            assertEquals(6, second.records.size)
            assertEquals(200, receiver.merge(first.records)); assertEquals(6, receiver.merge(second.records))
            assertEquals(0, receiver.merge(first.records)); assertEquals(206, receiver.snapshot().totalPlays)
            val span = HistoryRecord("span", sender.origin, "long-play", "span", song, now, now + 1000, 1000, 1)
            assertEquals(1, receiver.merge(listOf(span)))
            assertEquals(0, sender.merge(listOf(span)))
            assertEquals(1, receiver.merge(listOf(span.copy(end = now + 2000, ms = 2000, revision = 2))))
            assertEquals(0, receiver.merge(listOf(span)))
            assertEquals(0, receiver.merge(listOf(span.copy(origin = "impostor", revision = 99))))
            assertEquals(2000, receiver.snapshot().songs.single().listenedMs)
            assertEquals(0, receiver.merge(listOf(span.copy(end = now + 2000, ms = Long.MAX_VALUE, revision = 3))))
        } }
    }

    @Test fun sevenDayWindowIncludesOldMidnightAndSplitsCrossDaySpans() {
        val now = Instant.parse("2026-10-23T10:00:00Z").toEpochMilli()
        val zone = ZoneId.of("Asia/Tokyo")
        val days = recentDays(now, zone)
        val song = track.copy(id = songIdentity(track.title, track.artist))
        StatisticsRepository(null).use { db ->
            val start = days.first().start
            db.merge(listOf(HistoryRecord("old", "other", "session", "span", song, start, start + 3_600_000, 3_600_000, 1)))
            val midnight = days[5].end
            db.merge(listOf(HistoryRecord("cross", "other", "session2", "span", song, midnight - 1000, midnight + 1000, 2000, 1)))
            val snapshot = db.snapshot(now)
            assertEquals(3_600_000, snapshot.between(days.first().start, days.first().end).sumOf { it.listenedMs })
            assertEquals(1000, snapshot.between(days[5].start, days[5].end).sumOf { it.listenedMs })
            assertEquals(1000, snapshot.between(days[6].start, days[6].end).sumOf { it.listenedMs })
        }
    }
}
