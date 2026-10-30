package dev.salt.replica.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.*
import org.junit.Test

class StatisticsMotionTest {
    @Test fun landsExactlyOnBothEndpointsAndStaysRoundedAtFullScreen() {
        val source = Rect(16f, 500f, 180f, 664f)
        val viewport = Rect(0f, 0f, 390f, 740f)
        assertEquals(source, statisticsGeometry(source, viewport, 0f, 26f).rect)
        assertEquals(viewport, statisticsGeometry(source, viewport, 1f, 26f, 28f).rect)
        for (step in 0..100) {
            val result = statisticsGeometry(source, viewport, step / 100f, 26f, 28f)
            assertTrue(result.rect.width > 0 && result.rect.height > 0)
            assertTrue(result.radius in 26f..28f)
            assertTrue(result.rect.left >= -.01f && result.rect.right <= viewport.right + .01f)
        }
        assertEquals(28f, statisticsGeometry(source, viewport, 1f, 26f, 28f).radius, 0f)
        assertEquals(14f, cardTransitionRadius(14f, 28f, 0f), 0f)
        assertEquals(28f, cardTransitionRadius(14f, 28f, 1f), 0f)
    }

    @Test fun wideCardsOnlyTranslateTowardCenterAndGrowMonotonically() {
        val viewport = Rect(0f, 0f, 320f, 660f)
        listOf(Rect(16f, 240f, 304f, 384f), Rect(16f, 450f, 304f, 594f), Rect(15f, 100f, 303f, 172f)).forEach { source ->
            var previous = source
            for (step in 0..100) {
                val p = step / 100f
                val actual = statisticsGeometry(source, viewport, p, 26f).rect
                assertEquals(source.center.x + (viewport.center.x - source.center.x) * p, actual.center.x, .001f)
                assertEquals(source.center.y + (viewport.center.y - source.center.y) * p, actual.center.y, .001f)
                assertTrue(actual.width >= previous.width - .001f)
                assertTrue(actual.height >= previous.height - .001f)
                assertTrue(actual.left >= -.001f && actual.right <= viewport.right + .001f)
                previous = actual
            }
        }
    }

    @Test fun squareCardsUseTheLibraryCardsDirectPathAtNarrowWidths() {
        val viewport = Rect(0f, 0f, 320f, 660f)
        listOf(Rect(16f, 450f, 154f, 588f), Rect(166f, 450f, 304f, 588f)).forEach { source ->
            val p = .4f
            val actual = statisticsGeometry(source, viewport, p, 26f).rect.center.x
            val straight = source.center.x + (viewport.center.x - source.center.x) * p
            assertEquals(straight, actual, .001f)
        }
    }

    @Test fun squareCardsAndArtworkApproachTheirHorizontalTargetWithoutReversing() {
        val viewport = Rect(0f, 0f, 320f, 660f)
        listOf(Rect(16f, 450f, 154f, 588f), Rect(166f, 450f, 304f, 588f)).forEach { source ->
            var previousDistance = kotlin.math.abs(source.center.x - viewport.center.x)
            var previousWidth = source.width
            for (step in 0..100) {
                val actual = statisticsGeometry(source, viewport, step / 100f, 26f).rect
                val distance = kotlin.math.abs(actual.center.x - viewport.center.x)
                assertTrue(distance <= previousDistance + .001f)
                assertTrue(actual.center.x in minOf(source.center.x, viewport.center.x)..maxOf(source.center.x, viewport.center.x))
                assertTrue(actual.width >= previousWidth - .001f)
                previousDistance = distance
                previousWidth = actual.width
            }
        }
        listOf(Offset(52f, 500f), Offset(202f, 500f)).forEach { source ->
            val destination = Offset(66f, 180f)
            var previousDistance = kotlin.math.abs(source.x - destination.x)
            for (step in 0..100) {
                val actual = statisticsPath(source, destination, step / 100f, 24f)
                val distance = kotlin.math.abs(actual.x - destination.x)
                assertTrue(distance <= previousDistance + .001f)
                assertTrue(actual.x in minOf(source.x, destination.x)..maxOf(source.x, destination.x))
                previousDistance = distance
            }
            assertEquals(destination, statisticsPath(source, destination, 1f, 24f))
        }
    }

    @Test fun libraryCurveSettlesWithoutOvershootAndInterruptedReverseKeepsVelocity() {
        val easing = CardOpenEasing
        var previous = 0f
        for (step in 0..100) {
            val value = easing.transform(step / 100f)
            assertTrue(value in previous..1f)
            previous = value
        }
        assertTrue(easing.transform(.4f) - easing.transform(.3f) > easing.transform(.9f) - easing.transform(.8f))
        val reversed = statisticsReverseEasing(.5f, 0f, 1.1f, 400)
        assertEquals(0f, reversed.transform(0f), .0001f)
        assertEquals(1f, reversed.transform(1f), .0001f)
        // Retains forward velocity briefly before changing direction.
        assertTrue(reversed.transform(.01f) < 0f)
    }

    @Test fun headerScrimFadesToZeroBeforeTheReturningCoverHandsOff() {
        assertEquals(0f, detailCoverScrimAlpha(0f), 0f)
        assertEquals(0f, detailCoverScrimAlpha(.1f), 0f)
        assertEquals(1f, detailCoverScrimAlpha(1f), 0f)
        var previous = 0f
        for (step in 0..100) {
            val value = detailCoverScrimAlpha(step / 100f)
            assertTrue(value >= previous)
            assertTrue(value - previous < .025f)
            previous = value
        }
    }
}
