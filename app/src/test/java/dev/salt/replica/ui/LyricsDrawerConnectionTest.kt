package dev.salt.replica.ui

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LyricsDrawerConnectionTest {
    @Test fun otherPagesAndProgrammaticScrollDoNotOpenTheDrawer() {
        val scope = CoroutineScope(Dispatchers.Unconfined + BroadcastFrameClock())
        try {
            val drawer = DrawerState(scope).apply { widthPx = 936f }
            var atStart = false
            val connection = LyricsDrawerConnection(drawer) { atStart }
            assertEquals(Offset.Zero, connection.onPostScroll(Offset.Zero, Offset(80f, 0f), NestedScrollSource.UserInput))
            atStart = true
            assertEquals(Offset.Zero, connection.onPostScroll(Offset.Zero, Offset(80f, 0f), NestedScrollSource.SideEffect))
            assertEquals(0f, drawer.progress, 0f)
        } finally { scope.cancel() }
    }

    @Test fun onlyRemainingRightwardDragAtTheStartIsTransferred() {
        val scope = CoroutineScope(Dispatchers.Unconfined + BroadcastFrameClock())
        try {
            val drawer = DrawerState(scope).apply { widthPx = 936f }
            var atStart = false
            val connection = LyricsDrawerConnection(drawer) { atStart }
            connection.onPostScroll(Offset(180f, 0f), Offset.Zero, NestedScrollSource.UserInput)
            atStart = true
            val consumed = connection.onPostScroll(Offset(180f, 0f), Offset(36f, 0f), NestedScrollSource.UserInput)
            assertEquals(36f, consumed.x, .001f)
            assertEquals(36f / 936f, drawer.progress, .0001f)
            assertEquals(-36f, connection.onPreScroll(Offset(-80f, 0f), NestedScrollSource.UserInput).x, .001f)
            assertEquals(0f, drawer.progress, 0f)
            assertEquals(Offset.Zero, connection.onPreScroll(Offset(-20f, 0f), NestedScrollSource.UserInput))
        } finally { scope.cancel() }
    }

    @Test fun releasingTheEdgeDragSettlesTheDrawerWithoutTakingVerticalVelocity() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined + BroadcastFrameClock())
        try {
            val drawer = DrawerState(scope).apply { widthPx = 936f }
            val connection = LyricsDrawerConnection(drawer) { true }
            connection.onPostScroll(Offset.Zero, Offset(100f, 0f), NestedScrollSource.UserInput)
            assertEquals(Velocity(800f, 0f), connection.onPreFling(Velocity(800f, 120f)))
            assertTrue(drawer.open)
            assertEquals(Velocity.Zero, connection.onPostFling(Velocity.Zero, Velocity(800f, 0f)))
        } finally { scope.cancel() }
    }
}
