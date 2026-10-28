package dev.salt.replica.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Stable
class PlayerSheetState(private val scope: CoroutineScope) {
    var progress by mutableFloatStateOf(0f); private set
    var travel = 1f
    var density = 1f
    var targetExpanded by mutableStateOf(false); private set
    private var animation: Job? = null
    private var dragStart = 0f
    fun begin() { animation?.cancel(); dragStart = progress }
    fun drag(delta: Float) { progress = (progress - delta / travel.coerceAtLeast(1f)).coerceIn(0f, 1f) }
    fun settle(velocity: Float) {
        val destination = when {
            velocity < -125 * density -> true
            velocity > 125 * density -> false
            (progress - dragStart) * travel > 56 * density -> true
            (dragStart - progress) * travel > 56 * density -> false
            else -> targetExpanded
        }
        to(destination, -velocity / travel.coerceAtLeast(1f))
    }
    fun to(expanded: Boolean, velocity: Float = 0f) {
        animation?.cancel()
        targetExpanded = expanded
        animation = scope.launch {
            animate(progress, if (expanded) 1f else 0f, velocity,
                spring(dampingRatio = 1.1f, stiffness = 438.64905f, visibilityThreshold = .0001f)) { value, _ ->
                progress = value.coerceIn(0f, 1f)
            }
        }
    }
}

fun smoothRange(p: Float, start: Float, end: Float): Float {
    val t = ((p - start) / (end - start)).coerceIn(0f, 1f)
    return t * t * (3 - 2 * t)
}
