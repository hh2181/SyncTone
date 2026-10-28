package dev.salt.replica.ui

import androidx.compose.animation.core.Easing
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

internal data class StatisticsGeometry(val rect: Rect, val radius: Float)

internal const val STATISTICS_OPEN_DURATION_MS = 350
internal const val STATISTICS_CLOSE_DURATION_MS = 300

internal fun statisticsPath(source: Offset, destination: Offset, progress: Float, lift: Float): Offset {
    val p = progress.coerceIn(0f, 1f)
    // The horizontal control point is the destination, so X cannot pass it.
    val horizontal = p * (2f - p)
    return Offset(source.x + (destination.x - source.x) * horizontal,
        source.y + (destination.y - source.y) * p - lift * 4f * p * (1f - p))
}

internal fun statisticsGeometry(source: Rect, viewport: Rect, progress: Float, radius: Float, arc: Float): StatisticsGeometry {
    val p = progress.coerceIn(0f, 1f)
    // Full-width cards already share the destination's horizontal center.
    val curved = source.width < viewport.width * .8f
    val width = source.width + (viewport.width - source.width) * p
    val height = source.height + (viewport.height - source.height) * p
    val center = if (curved) statisticsPath(source.center, viewport.center, p, arc * .48f)
        else Offset(source.center.x + (viewport.center.x - source.center.x) * p,
            source.center.y + (viewport.center.y - source.center.y) * p)
    return StatisticsGeometry(Rect(center.x - width / 2, center.y - height / 2, center.x + width / 2, center.y + height / 2),
        radius * (1f - smoothRange(p, .52f, 1f)))
}

internal fun statisticsEasing(opening: Boolean): Easing = Easing { t ->
    val peak = if (opening) .12f else .18f
    if (t < peak) t * t / peak else 1f - (1f - t) * (1f - t) / (1f - peak)
}

internal fun statisticsReverseEasing(start: Float, target: Float, velocity: Float, durationMs: Int): Easing {
    if (kotlin.math.abs(velocity) < .001f || start == target) return statisticsEasing(target > start)
    val tangent = (velocity * durationMs / 1_000f).coerceIn(-3f * start, 3f * (1f - start))
    return Easing { t ->
        val value = (2*t*t*t - 3*t*t + 1)*start + (t*t*t - 2*t*t + t)*tangent + (-2*t*t*t + 3*t*t)*target
        (value - start) / (target - start)
    }
}
