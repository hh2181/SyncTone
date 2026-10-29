package dev.salt.desktop

import dev.salt.desktop.storage.decodeLyrics
import dev.salt.desktop.storage.encodeLyrics
import dev.salt.replica.lyrics.*
import kotlin.test.*

class LyricsUpdateStorageTest {
    @Test fun desktopStorageKeepsKindsAndStillReadsOlderLibraries() {
        val line = LyricLine(1000, 2000, listOf(LyricCell(1000, 2000, "roman")),
            listOf(LyricSubLine(listOf(LyricCell(1000, 2000, "原文")), LyricContent.Original)), LyricContent.Romanization)
        val lyrics = Lyrics(listOf(line), "ONLINE_KUGOU")
        assertEquals(lyrics, decodeLyrics(encodeLyrics(lyrics)))
        val old = """{"source":"LRC","lines":[{"start":1000,"end":2000,"cells":[{"start":1000,"end":2000,"text":"legacy"}],"secondary":[[{"start":1000,"end":2000,"text":"translation"}]]}]}"""
        val restored = decodeLyrics(old).lines.single()
        assertEquals(LyricContent.Original, restored.kind)
        assertEquals(LyricContent.Translation, restored.subLines.single().kind)
    }
}
