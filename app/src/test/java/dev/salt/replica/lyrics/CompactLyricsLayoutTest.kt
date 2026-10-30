package dev.salt.replica.lyrics

import org.junit.Assert.*
import org.junit.Test

class CompactLyricsLayoutTest {
    @Test fun dpiDoesNotChangeTypeAtTheSameLogicalWidth() {
        assertEquals(compactLyricsTypography(1080f / 3f - 60f), compactLyricsTypography(1440f / 4f - 60f))
        val mix = compactLyricsTypography(1080f / (444f / 160f) - 60f)
        val current = compactLyricsTypography(1220f / 3f - 60f)
        assertTrue(mix.originalSp >= 16f && current.originalSp >= 16f)
        assertTrue(kotlin.math.abs(mix.originalSp - current.originalSp) < 1f)
        assertEquals(20f, compactLyricsTypography(1000f).originalSp, 0f)
    }

    @Test fun shortScreensAndLargerTextKeepSpaceForLyricsAndControls() {
        val normal = compactLyricsMinimumHeight(22.4f, 19.6f)
        val larger = compactLyricsMinimumHeight(33.6f, 29.4f)
        for ((width, height) in listOf(320f to 640f, 390f to 780f, 407f to 904f)) {
            val top = minOf(143f, height * .19f)
            val controls = 206f
            val cover = playerCoverSize(width, height, top, normal, controls)
            val scaledControls = controls + 12f
            val scaledCover = playerCoverSize(width, height, top, larger, scaledControls)
            assertTrue(cover > 0f && cover <= width - 60f)
            assertTrue(top + cover + 16f + normal + controls <= height + .001f)
            assertTrue(top + scaledCover + 16f + larger + scaledControls <= height + .001f)
            assertTrue(scaledCover <= cover)
        }
    }

    @Test fun tallScreensKeepTheirFullWidthCover() {
        assertEquals(347f, playerCoverSize(407f, 904f, 143f, 92f, 206f), .001f)
    }
}
