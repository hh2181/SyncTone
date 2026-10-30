package dev.salt.replica.ui

import androidx.compose.animation.core.Easing
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

internal data class StatisticsGeometry(val rect: Rect, val radius: Float)

internal const val STATISTICS_OPEN_DURATION_MS = CARD_OPEN_DURATION_MS
internal const val STATISTICS_CLOSE_DURATION_MS = CARD_CLOSE_DURATION_MS

internal fun statisticsPath(source: Offset, destination: Offset, progress: Float, lift: Float): Offset {
    val p = progress.coerceIn(0f, 1f)
    if (p == 0f) return source
    if (p == 1f) return destination
    return Offset(source.x + (destination.x - source.x) * p,
        source.y + (destination.y - source.y) * p + lift * kotlin.math.sin(Math.PI * p).toFloat())
}

internal fun statisticsGeometry(source: Rect, viewport: Rect, progress: Float, radius: Float, expandedRadius: Float = radius): StatisticsGeometry =
    StatisticsGeometry(cardTransitionRect(source, viewport, progress), cardTransitionRadius(radius, expandedRadius, progress))

internal fun statisticsEasing(opening: Boolean): Easing = Easing { t ->
    val peak = if (opening) .12f else .18f
    if (t < peak) t * t / peak else 1f - (1f - t) * (1f - t) / (1f - peak)
}

internal fun statisticsReverseEasing(start: Float, target: Float, velocity: Float, durationMs: Int): Easing {
    if (kotlin.math.abs(velocity) < .001f || start == target) return if (target > start) CardOpenEasing else CardCloseEasing
    val tangent = (velocity * durationMs / 1_000f).coerceIn(-3f * start, 3f * (1f - start))
    return Easing { t ->
        val value = (2*t*t*t - 3*t*t + 1)*start + (t*t*t - 2*t*t + t)*tangent + (-2*t*t*t + 3*t*t)*target
        (value - start) / (target - start)
    }
}
