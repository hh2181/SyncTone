package dev.salt.desktop.playback

import kotlin.math.abs

/** Interpolate libVLC's coarse clock, but never run more than 750 ms past a sample. */
class PlaybackTimeline(private val nanoTime: () -> Long = System::nanoTime) {
    private var anchorMs = 0L
    private var anchorNanos = 0L
    private var sampleMs = 0L
    private var durationMs = 0L
    private var running = false

    @Synchronized fun update(positionMs: Long, playing: Boolean, duration: Long, discontinuity: Boolean = false) {
        val now = nanoTime()
        val predicted = valueAt(now)
        if (discontinuity || playing != running || !playing ||
            (positionMs != sampleMs && abs(predicted - positionMs) > 200)) {
            anchorMs = positionMs.coerceAtLeast(0)
            anchorNanos = now
        }
        sampleMs = positionMs.coerceAtLeast(0)
        durationMs = duration.coerceAtLeast(0)
        running = playing
    }

    @Synchronized fun positionMs(): Long = valueAt(nanoTime())

    private fun valueAt(now: Long): Long {
        val elapsed = if (running) ((now - anchorNanos) / 1_000_000).coerceAtLeast(0) else 0
        return (anchorMs + elapsed).coerceAtMost(sampleMs + if (running) 750 else 0)
            .coerceIn(0, durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
    }
}
