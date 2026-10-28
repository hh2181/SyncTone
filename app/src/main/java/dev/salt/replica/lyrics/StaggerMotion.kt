package dev.salt.replica.lyrics

import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import kotlin.math.abs
import kotlin.math.pow

/** Offsets exist before the list's requested remeasure; no coroutine per row. */
class StaggerMotion(
    private val shift: Float,
    private val firstVisible: Int,
    private val startedAt: Long,
    private val carried: Map<Int, Float> = emptyMap(),
    private val speed: Float = 1f,
    private val curve: Int = 0,
) {
    private val animations = HashMap<Int, TargetBasedAnimation<Float, AnimationVector1D>>()

    fun offset(index: Int, now: Long): Float {
        val animation = animations.getOrPut(index) {
            TargetBasedAnimation(if (curve == 0) spring(dampingRatio = 1.1f, stiffness = 200f, visibilityThreshold = .1f)
                else tween(700, easing = lyricEasing(curve)),
                Float.VectorConverter, shift + (carried[index] ?: 0f), 0f)
        }
        val elapsed = ((now - startedAt) * speed.coerceIn(.5f, 2f)).toLong()
            .minus(delayMillis(abs(index - firstVisible)) * 1_000_000).coerceAtLeast(0)
        return animation.getValueFromNanos(elapsed)
    }

    fun settled(now: Long): Boolean = ((now - startedAt) * speed.coerceIn(.5f, 2f)).toLong() >= 2_800_000_000L

    companion object {
        fun delayMillis(distance: Int): Long = (1050.0 * (1 - (1 / 1.05).pow(distance))).toLong()
    }
}

internal fun allowsStagger(lines: List<LyricLine>, active: Int, previous: Int): Boolean {
    if (previous < 0 || abs(active - previous) !in 1..10) return false
    var short = 0
    var i = active
    while (i < lines.size && lines[i].end - lines[i].start < 300) { short++; i++ }
    i = active - 1
    while (i >= 0 && lines[i].end - lines[i].start < 300) { short++; i-- }
    return short <= 3
}
