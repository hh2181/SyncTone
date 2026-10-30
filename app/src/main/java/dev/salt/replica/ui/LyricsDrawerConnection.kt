package dev.salt.replica.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity

/** The pager consumes its own movement first; only a drag past its start opens the drawer. */
internal class LyricsDrawerConnection(
    private val drawer: DrawerState,
    private val canOpen: () -> Boolean,
) : NestedScrollConnection {
    private var dragging = false

    private fun move(delta: Float): Offset {
        val before = drawer.progress
        drawer.drag(delta)
        return Offset((drawer.progress - before) * drawer.widthPx, 0f)
    }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
        if (dragging && source == NestedScrollSource.UserInput) move(available.x) else Offset.Zero

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || available.x <= 0f || !canOpen()) return Offset.Zero
        if (!dragging) {
            dragging = true
            drawer.beginDrag()
        }
        return move(available.x)
    }

    private fun finish(velocity: Velocity): Velocity {
        if (!dragging) return Velocity.Zero
        dragging = false
        drawer.settle(velocity.x)
        return Velocity(velocity.x, 0f)
    }

    override suspend fun onPreFling(available: Velocity): Velocity = finish(available)
    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = finish(available)
}
