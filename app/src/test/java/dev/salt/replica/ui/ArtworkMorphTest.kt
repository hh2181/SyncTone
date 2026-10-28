package dev.salt.replica.ui

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class ArtworkMorphTest {
    private fun morph(width: Float, height: Float): ArtworkMorph {
        val miniY = height - 74f
        return ArtworkMorph(Rect(32f, miniY + 17f, 66f, miniY + 51f),
            Rect(30f, 143f, width - 30f, 143f + width - 60f),
            Rect(16f, miniY + 6f, width - 16f, miniY + 62f),
            Rect(0f, 0f, width, height), 5f, 8f, miniY)
    }

    @Test fun endpointsKeepArtworkAlignedWithMiniAndFullPlayer() {
        val animation = morph(390f, 780f)
        val result = FloatArray(6)
        animation.evaluate(-1f, result)
        assertArrayEquals(floatArrayOf(32f, 723f, 34f, 34f, 5f, 1f), result, .001f)
        animation.evaluate(2f, result)
        assertArrayEquals(floatArrayOf(30f, 143f, 330f, 330f, 8f, 1.01f), result, .001f)
    }

    @Test fun intermediateArtworkStaysFiniteAndInsidePortraitViewport() {
        for ((w, h) in listOf(320f to 640f, 390f to 780f, 600f to 960f)) {
            val animation = morph(w, h)
            val result = FloatArray(6)
            for (step in 0..100) {
                animation.evaluate(step / 100f, result)
                assertTrue(result.all { it.isFinite() })
                assertTrue(result[0] >= -.01f && result[1] >= -.01f)
                assertTrue(result[0] + result[2] <= w + .01f)
                assertTrue(result[1] + result[3] <= h + .01f)
                assertTrue(result[2] > 0f && result[3] > 0f && result[4] >= 0f)
            }
        }
    }
}
