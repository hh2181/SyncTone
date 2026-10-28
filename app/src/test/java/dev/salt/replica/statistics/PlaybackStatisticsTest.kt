package dev.salt.replica.statistics

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class PlaybackStatisticsTest {
    private fun utc(value: String) = Instant.parse(value).toEpochMilli()
    private val song = StatTrack("one", "First", "Artist", "Album")

    @Test fun countsAtThirtySecondsOnceAndIncludesShortListeningTime() {
        val batches = mutableListOf<RecordingBatch>()
        val recorder = ListeningRecorder(batches::add)
        val start = utc("2026-10-21T00:00:00Z")
        recorder.transition(song.id, true, start, 1_000)
        recorder.checkpoint(start + 29_999, 30_999)
        assertEquals(0, batches.count { it.play != null })
        assertEquals(29_999L, batches.sumOf { it.span?.listenedMs ?: 0 })
        recorder.checkpoint(start + 31_000, 32_000)
        recorder.checkpoint(start + 90_000, 91_000)
        val plays = batches.mapNotNull { it.play }
        assertEquals(1, plays.size)
        assertEquals(start + 30_000, plays.single().countedUtc)
        assertEquals(90_000L, batches.sumOf { it.span?.listenedMs ?: 0 })
    }

    @Test fun pausesAndBufferingDoNotAccumulateAndResumeDoesNotDoubleCount() {
        val batches = mutableListOf<RecordingBatch>()
        val recorder = ListeningRecorder(batches::add)
        recorder.transition(song.id, true, 100_000, 0)
        recorder.playing(false, 120_000, 20_000)
        recorder.checkpoint(220_000, 120_000)
        recorder.playing(true, 230_000, 130_000)
        recorder.checkpoint(240_000, 140_000)
        recorder.playing(false, 250_000, 150_000)
        recorder.playing(true, 280_000, 180_000)
        recorder.checkpoint(320_000, 220_000)
        assertEquals(80_000L, batches.sumOf { it.span?.listenedMs ?: 0 })
        assertEquals(1, batches.count { it.play != null })
        assertEquals(240_000L, batches.mapNotNull { it.play }.single().countedUtc)
    }

    @Test fun repeatingTheSameSongCreatesANewQualifiedPlay() {
        val batches = mutableListOf<RecordingBatch>()
        val recorder = ListeningRecorder(batches::add)
        recorder.transition(song.id, true, 100_000, 0)
        recorder.transition(song.id, true, 140_000, 40_000)
        recorder.checkpoint(180_000, 80_000)
        val plays = batches.mapNotNull { it.play }
        assertEquals(2, plays.size)
        assertNotEquals(plays[0].session, plays[1].session)
        recorder.transition("two", true, 185_000, 85_000)
        recorder.transition(song.id, true, 190_000, 90_000)
        assertEquals(2, batches.count { it.play != null })
        assertEquals(90_000L, batches.sumOf { it.span?.listenedMs ?: 0 })
    }

    @Test fun wallClockChangesNeverInflateOrRemoveActualListeningTime() {
        val batches = mutableListOf<RecordingBatch>()
        val recorder = ListeningRecorder(batches::add)
        recorder.transition(song.id, true, 1_000_000, 0)
        recorder.checkpoint(1_010_000, 10_000)
        recorder.checkpoint(9_000_000, 20_000)
        recorder.checkpoint(500_000, 30_000)
        assertEquals(30_000L, batches.sumOf { it.span?.listenedMs ?: 0 })
        assertEquals(1, batches.count { it.play != null })
        assertTrue(batches.all { it.span!!.endUtc - it.span.startUtc == it.span.listenedMs })
        assertEquals(500_000L, batches.mapNotNull { it.play }.single().countedUtc)
    }

    @Test fun changingDisplayZoneReassignsMidnightWithoutChangingUTCTotals() {
        val start = utc("2026-10-20T14:59:30Z")
        val end = start + 60_000
        val snapshot = StatisticsSnapshot(listOf(SongStatistic(song, 1, 60_000)),
            listOf(ListeningSpan("a", song.id, start, end, 60_000)), listOf(CountedPlay("a", song.id, start + 30_000)))
        val tokyo = recentDays(end, ZoneId.of("Asia/Tokyo"))
        val utc = recentDays(end, ZoneId.of("UTC"))
        assertEquals(30_000L, snapshot.between(tokyo[5].start, tokyo[5].end).sumOf { it.listenedMs })
        assertEquals(30_000L, snapshot.between(tokyo[6].start, tokyo[6].end).sumOf { it.listenedMs })
        assertEquals(60_000L, snapshot.between(utc[6].start, utc[6].end).sumOf { it.listenedMs })
        assertEquals(1L, snapshot.between(tokyo[6].start, tokyo[6].end).sumOf { it.plays })
        assertEquals(1L, snapshot.totalPlays)
    }

    @Test fun localCalendarDaysRespectBothDaylightSavingTransitions() {
        val zone = ZoneId.of("America/New_York")
        val spring = recentDays(utc("2026-03-08T20:00:00Z"), zone).last()
        val autumn = recentDays(utc("2026-11-01T20:00:00Z"), zone).last()
        assertEquals(23 * HOUR_MS, spring.end - spring.start)
        assertEquals(25 * HOUR_MS, autumn.end - autumn.start)
    }

    @Test fun rollingHoursHaveExactBoundariesAndCountsUseTheQualificationTimestamp() {
        val now = utc("2026-10-21T04:37:23Z")
        val hours = recentHours(now)
        assertEquals(24, hours.size)
        assertEquals(now - 24 * HOUR_MS, hours.first().start)
        assertEquals(now, hours.last().end)
        assertTrue(hours.all { it.end - it.start == HOUR_MS })
        val snapshot = StatisticsSnapshot(listOf(SongStatistic(song, 4, 0)), recentPlays = listOf(
            CountedPlay("a", song.id, hours.first().start - 1),
            CountedPlay("b", song.id, hours.first().start),
            CountedPlay("c", song.id, hours.first().end),
            CountedPlay("d", song.id, now)))
        assertEquals(1L, snapshot.between(hours[0].start, hours[0].end).sumOf { it.plays })
        assertEquals(1L, snapshot.between(hours[1].start, hours[1].end).sumOf { it.plays })
        assertEquals(2L, snapshot.between(hours.first().start, now).sumOf { it.plays })
    }

    @Test fun legacyCountsStayInLifetimeTotalsAndAlbumsWithDifferentArtistsStaySeparate() {
        val other = StatTrack("two", "Second", "Other Artist", "Album")
        val snapshot = StatisticsSnapshot(listOf(SongStatistic(song, 10, 0), SongStatistic(other, 4, 5_000)))
        assertEquals(14L, snapshot.totalPlays)
        assertEquals(2, snapshot.albums().size)
        assertEquals(10L, snapshot.artists().first().plays)
        assertEquals(0, snapshot.between(0, Long.MAX_VALUE).size)
    }
}
