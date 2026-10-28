package dev.salt.replica.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

val DrawerWidth = 312.dp

@Stable
class DrawerState(private val scope: CoroutineScope) {
    var progress by mutableFloatStateOf(0f); private set
    var open by mutableStateOf(false); private set
    var widthPx = 1f
    private var job: Job? = null

    fun open() {
        open = true
        animateTo(1f)
    }

    fun close() {
        open = false
        animateTo(0f)
    }

    fun beginDrag() {
        job?.cancel()
    }

    fun drag(dx: Float) {
        progress = (progress + dx / widthPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
    }

    fun settle(velocityX: Float) {
        val target = when {
            velocityX > 420f -> 1f
            velocityX < -420f -> 0f
            progress > .38f -> 1f
            else -> 0f
        }
        open = target > .5f
        animateTo(target, velocityX)
    }

    private fun animateTo(target: Float, velocityPx: Float = 0f) {
        job?.cancel()
        val opening = target > progress
        job = scope.launch {
            animate(
                // The spring animates unit progress, while the gesture reports pixels/second.
                progress, target, velocityPx / widthPx.coerceAtLeast(1f),
                spring(dampingRatio = .9f, stiffness = 420f, visibilityThreshold = .0005f)
            ) { value, _ ->
                val v = value.coerceIn(0f, 1f)
                // The spring may overshoot numerically; keep the rendered progress
                // monotonic so the drawer never visibly rebounds off a limit.
                progress = if (opening) maxOf(progress, v) else minOf(progress, v)
            }
        }
    }
}
