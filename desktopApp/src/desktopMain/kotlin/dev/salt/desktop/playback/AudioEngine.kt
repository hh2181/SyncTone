package dev.salt.desktop.playback

import java.nio.file.Path

enum class AudioStatus { Idle, Loading, Playing, Paused, Stopped, Ended, Failed }

data class AudioSnapshot(
    val status: AudioStatus = AudioStatus.Idle,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val volume: Int = 72,
)

interface AudioEngine : AutoCloseable {
    fun open(path: Path, play: Boolean = true)
    fun setPaused(paused: Boolean)
    fun seek(positionMs: Long)
    fun setVolume(volume: Int)
    fun snapshot(): AudioSnapshot
    fun stop()
}
