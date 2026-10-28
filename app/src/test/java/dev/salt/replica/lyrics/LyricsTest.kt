package dev.salt.replica.lyrics

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FilterInputStream

class LyricsTest {
    @Test fun enhancedWordsKeepExplicitEndAndTranslations() {
        val lyrics = LyricsParser.parse("[00:01.00]<00:01.00>first <00:01.20>word<00:01.25>\n" +
            "[00:01.00]translation\n[00:03.00]next", 5000)
        val line = lyrics.lines.first()
        assertEquals(1250L, line.end)
        assertEquals("first word", line.text)
        assertEquals("translation", line.translation)
        assertEquals(1200L, line.cells[1].start)
        assertEquals(-1, lyrics.indexAt(999))
        assertEquals(0, lyrics.indexAt(1250))
        assertEquals(1, lyrics.indexAt(3000))
    }

    @Test fun repeatedPlainTimestampsProduceRepeatedRows() {
        val lyrics = LyricsParser.parse("[offset:-100]\n[00:01.0][00:03.00]chorus\n[00:04.000]", 5000)
        assertEquals(listOf(900L, 2900L, 3900L), lyrics.lines.map { it.start })
        assertEquals(listOf("chorus", "chorus", ""), lyrics.lines.map { it.text })
        assertEquals(2900L, lyrics.lines.first().end)
    }

    @Test fun embeddedReferenceHasTimedJapaneseAndTwoTranslationRows() {
        val lyrics = LyricsParser.fromMp3(File("src/main/assets/reference.mp3"), 208000)
        assertTrue(lyrics.lines.size > 30)
        assertTrue(lyrics.lines.any { it.cells.size > 10 })
        assertTrue(lyrics.lines.any { it.translation.contains('\n') })
        assertTrue(lyrics.lines.zipWithNext().all { (a, b) -> a.start < b.start })
        assertTrue(lyrics.lines.flatMap { it.cells }.all { it.end >= it.start })
        val sung = lyrics.lines.first { it.start == 968L }
        assertEquals(2, sung.subLines.size)
        val romaji = sung.subLines[0]
        assertTrue(romaji.wordTimed)
        assertEquals("ro", romaji.cells.first().text.trim())
        assertEquals(1050L, romaji.cells[1].start)
        assertEquals(4745L, romaji.cells.last().end)
        assertFalse(sung.subLines[1].wordTimed)
        assertEquals("要与Rock的你说再见了", sung.subLines[1].text)
    }

    @Test fun embeddedLyricsCanBeReadFromAContentResolverStyleStream() {
        val file = File("src/main/assets/reference.mp3")
        val fromFile = LyricsParser.fromMp3(file, 208000)
        val fromStream = object : FilterInputStream(file.inputStream()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, minOf(length, 7))
        }.use { LyricsParser.fromMp3(it, 208000) }
        assertEquals(fromFile, fromStream)
        assertTrue(fromStream.lines.any { it.subLines.firstOrNull()?.wordTimed == true })
    }

    @Test fun secondaryWordsKeepTheirOwnTimingAndSegmentation() {
        val line = LyricsParser.parse("[offset:-100]\n" +
            "[00:01.00]君<00:02.00>へ<00:03.00>\n" +
            "[00:01.00]  ki <00:01.40>mi <00:02.10>e  <00:02.80>\n" +
            "[00:01.00]  致你  <00:03.00>\n[00:04.00]next", 5000).lines.first()
        val romaji = line.subLines.first()
        assertTrue(romaji.wordTimed)
        assertEquals("ki mi e", romaji.text)
        assertEquals(listOf(900L, 1300L, 2000L), romaji.cells.map { it.start })
        assertEquals(listOf(1300L, 2000L, 2700L), romaji.cells.map { it.end })
        assertEquals(2900L, line.end)
        assertFalse(line.subLines[1].wordTimed)
        assertEquals("ki mi e\n致你", line.translation)
    }

    @Test fun plainSecondaryLinesAndEndOnlyTimestampsRemainStatic() {
        val lyrics = LyricsParser.parse("[00:01.00]main\n[00:01.00]plain romaji\n" +
            "[00:01.00]translated line<00:02.50>\n[00:03.00]next", 5000)
        val line = lyrics.lines.first()
        assertTrue(line.subLines.none { it.wordTimed })
        assertEquals("plain romaji\ntranslated line", line.translation)
        assertEquals(3000L, line.subLines[0].cells.single().end)
        assertEquals(2500L, line.subLines[1].cells.single().end)
    }

    @Test fun staggerStartsWithoutAJumpAndCarriesInterruptedOffsets() {
        val start = 10_000_000_000L
        val first = StaggerMotion(160f, 4, start)
        assertEquals(160f, first.offset(7, start), .001f)
        assertEquals(0L, StaggerMotion.delayMillis(0))
        assertEquals(50L, StaggerMotion.delayMillis(1))
        val carried = first.offset(7, start + 300_000_000)
        val next = StaggerMotion(120f, 5, start + 300_000_000, mapOf(7 to carried))
        assertEquals(120f + carried, next.offset(7, start + 300_000_000), .001f)
        assertEquals(0f, next.offset(7, start + 4_000_000_000), .11f)
    }

    @Test fun animationSpeedScalesStaggerDelaysAndSpringsTogether() {
        val start = 10_000_000_000L
        val normal = StaggerMotion(160f, 4, start)
        val fast = StaggerMotion(160f, 4, start, speed = 2f)
        val slow = StaggerMotion(160f, 4, start, speed = .5f)
        assertEquals(normal.offset(7, start + 400_000_000), fast.offset(7, start + 200_000_000), .001f)
        assertEquals(normal.offset(7, start + 400_000_000), slow.offset(7, start + 800_000_000), .001f)
        assertEquals(350, lyricDuration(700, 2f))
        for (curve in 0..2) {
            assertEquals(0f, lyricEasing(curve).transform(0f), .001f)
            assertEquals(1f, lyricEasing(curve).transform(1f), .001f)
            val motion = StaggerMotion(120f, 4, start, curve = curve)
            assertEquals(120f, motion.offset(7, start), .001f)
            assertEquals(0f, motion.offset(7, start + 4_000_000_000), .11f)
        }
    }

    @Test fun longNotesUseQuarterDurationAttackAndDelayedSmoothDecay() {
        val envelope = LongNoteEnvelope(listOf(LyricCell(1000, 3000, "long")))
        assertEquals(0f, envelope.alpha(0, 1000), .001f)
        assertEquals(.592f, envelope.alpha(0, 1500), .001f)
        assertEquals(.592f, envelope.alpha(0, 3200), .001f)
        assertEquals(.296f, envelope.alpha(0, 3500), .001f)
        assertEquals(0f, envelope.alpha(0, 3800), .001f)
    }

    @Test fun contiguousShortRowsDisableStaggerButDistantShortRowsDoNot() {
        fun row(start: Long, duration: Long) = LyricLine(start, start + duration, emptyList())
        val dense = List(6) { row(it * 200L, 200) }
        assertFalse(allowsStagger(dense, 2, 1))
        val sparse = listOf(row(0, 200), row(1000, 1000), row(2000, 1000), row(3000, 200))
        assertTrue(allowsStagger(sparse, 2, 1))
        assertFalse(allowsStagger(sparse, 2, -1))
    }
}
