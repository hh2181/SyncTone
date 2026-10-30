package dev.salt.replica.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.geometry.Rect

internal const val CARD_OPEN_DURATION_MS = 520
internal const val CARD_CLOSE_DURATION_MS = 300
internal const val CARD_EXPANDED_RADIUS_DP = 28f
internal val CardOpenEasing = CubicBezierEasing(.2f, .9f, .25f, 1f)
internal val CardCloseEasing = CubicBezierEasing(.4f, 0f, .2f, 1f)

internal fun cardTransitionRect(source: Rect, target: Rect, progress: Float): Rect {
    val p = progress.coerceIn(0f, 1f)
    return Rect(
        source.left + (target.left - source.left) * p,
        source.top + (target.top - source.top) * p,
        source.right + (target.right - source.right) * p,
        source.bottom + (target.bottom - source.bottom) * p,
    )
}

// The full-screen surface stays rounded too; no corner straightening at either endpoint.
internal fun cardTransitionRadius(sourceRadius: Float, expandedRadius: Float, progress: Float): Float =
    sourceRadius + (expandedRadius - sourceRadius) * progress.coerceIn(0f, 1f)

internal fun detailCoverScrimAlpha(progress: Float): Float = smoothRange(progress, .12f, .82f)
