package dev.salt.replica.lyrics

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import kotlin.math.roundToInt

private val standardEasing = CubicBezierEasing(.25f, 0f, .2f, 1f)
private val softEasing = CubicBezierEasing(.22f, 1f, .36f, 1f)

internal fun lyricEasing(curve: Int): Easing = when (curve) {
    1 -> softEasing
    2 -> LinearEasing
    else -> standardEasing
}

internal fun lyricDuration(base: Int, speed: Float): Int =
    (base / speed.coerceIn(.5f, 2f)).roundToInt().coerceAtLeast(1)
