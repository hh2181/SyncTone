package dev.salt.replica.ui

import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerStateTest {
    @Test fun releaseContinuesAcrossFramesInsteadOfSnappingToEitherEndpoint() {
        for (width in listOf(312f, 866f, 1248f)) {
            for (opening in listOf(true, false)) {
                val clock = BroadcastFrameClock()
                val scope = CoroutineScope(Dispatchers.Unconfined + clock)
                try {
                    val drawer = DrawerState(scope).apply { widthPx = width }
                    val start = if (opening) .3f else .2f
                    drawer.beginDrag()
                    drawer.drag(start * width)
                    drawer.settle(if (opening) 600f else -600f)
                    assertEquals(opening, drawer.open)
                    clock.sendFrame(0L)
                    assertEquals(start, drawer.progress, .0001f)
                    clock.sendFrame(16_666_667L)
                    val first = drawer.progress
                    assertTrue("Release must retain intermediate frames at width $width", first > 0f && first < 1f)
                    assertTrue("Release must continue in its initial direction", if (opening) first > start else first < start)
                    assertTrue("One frame must not cross most of the drawer", kotlin.math.abs(first - start) < .12f)
                    var previous = first
                    for (frame in 2..90) {
                        clock.sendFrame(frame * 16_666_667L)
                        assertTrue(if (opening) drawer.progress >= previous else drawer.progress <= previous)
                        previous = drawer.progress
                    }
                    assertEquals(if (opening) 1f else 0f, drawer.progress, .0005f)
                } finally {
                    scope.cancel()
                }
            }
        }
    }
}
