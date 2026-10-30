package dev.salt.desktop.playback

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Callback
import dev.salt.replica.equalizer.EqConfig
import dev.salt.replica.equalizer.EqRuntime
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

// libVLC 3's stable C API. All calls and disposal belong to the audio worker.
internal fun interface VlcPlayCallback : Callback { fun invoke(opaque: Pointer?, samples: Pointer, count: Int, pts: Long) }
internal fun interface VlcPauseCallback : Callback { fun invoke(opaque: Pointer?, pts: Long) }
internal fun interface VlcFlushCallback : Callback { fun invoke(opaque: Pointer?) }
internal interface LibVlc : Library {
    fun libvlc_clock(): Long
    fun libvlc_audio_set_callbacks(player: Pointer, play: VlcPlayCallback, pause: VlcPauseCallback,
        resume: VlcPauseCallback, flush: VlcFlushCallback, drain: VlcFlushCallback, opaque: Pointer?)
    fun libvlc_audio_set_format(player: Pointer, format: String, rate: Int, channels: Int)
    fun libvlc_new(argc: Int, argv: Array<String>): Pointer?
    fun libvlc_release(instance: Pointer)
    fun libvlc_media_new_location(instance: Pointer, location: String): Pointer?
    fun libvlc_media_release(media: Pointer)
    fun libvlc_media_player_new(instance: Pointer): Pointer?
    fun libvlc_media_player_release(player: Pointer)
    fun libvlc_media_player_set_media(player: Pointer, media: Pointer)
    fun libvlc_media_player_play(player: Pointer): Int
    fun libvlc_media_player_set_pause(player: Pointer, pause: Int)
    fun libvlc_media_player_stop(player: Pointer)
    fun libvlc_media_player_set_time(player: Pointer, time: Long)
    fun libvlc_media_player_get_time(player: Pointer): Long
    fun libvlc_media_player_get_length(player: Pointer): Long
    fun libvlc_media_player_get_state(player: Pointer): Int
    fun libvlc_audio_set_volume(player: Pointer, volume: Int): Int
    fun libvlc_audio_get_volume(player: Pointer): Int
}

class VlcAudioEngine : AudioEngine {
    private val api: LibVlc
    private val instance: Pointer
    private val player: Pointer
    private var closed = false
    private lateinit var pcm: VlcPcmOutput
    // Native code does not retain Java references: keep every callback alive.
    private val playCallback = VlcPlayCallback { _, samples, count, pts -> pcm.play(samples, count, pts) }
    private val pauseCallback = VlcPauseCallback { _, _ -> pcm.pause() }
    private val resumeCallback = VlcPauseCallback { _, _ -> pcm.resume() }
    private val flushCallback = VlcFlushCallback { pcm.flush() }
    private val drainCallback = VlcFlushCallback { pcm.drain() }

    init {
        require(System.getProperty("os.name").startsWith("Windows")) { "This runtime targets Windows x64." }
        val resources = System.getProperty("compose.application.resources.dir")
        val candidates = listOfNotNull(
            System.getProperty("synctone.vlc.path"),
            resources?.let { "$it/vlc" },
            resources?.let { "$it/windows-x64/vlc" },
            "runtime/windows-x64/vlc", "desktopApp/runtime/windows-x64/vlc",
        )
        val directory = candidates.map(::File).firstOrNull { File(it, "libvlc.dll").isFile }
            ?: error("找不到播放内核，请运行 desktopApp/tools/prepare-vlc.ps1。")
        resources?.let {
            val jna = File(it, "jna")
            if (File(jna, "jnidispatch.dll").isFile) System.setProperty("jna.boot.library.path", jna.absolutePath)
        }
        NativeLibrary.addSearchPath("libvlccore", directory.absolutePath)
        NativeLibrary.addSearchPath("libvlc", directory.absolutePath)
        NativeLibrary.getInstance(File(directory, "libvlccore.dll").absolutePath)
        api = Native.load(File(directory, "libvlc.dll").absolutePath, LibVlc::class.java,
            mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
        val options = arrayOf("--no-video", "--no-video-title-show", "--no-media-library", "--ignore-config",
            "--no-metadata-network-access", "--no-plugins-cache", "--quiet")
        instance = api.libvlc_new(options.size, options) ?: error("无法初始化播放内核。")
        player = api.libvlc_media_player_new(instance) ?: run {
            api.libvlc_release(instance)
            error("无法创建播放器。")
        }
        pcm = VlcPcmOutput(api::libvlc_clock)
        // VLC 3 amem supports S16N; both the DSP and visual curve use this
        // negotiated 48 kHz processing rate, including any decoder resampling.
        api.libvlc_audio_set_callbacks(player, playCallback, pauseCallback, resumeCallback, flushCallback, drainCallback, null)
        api.libvlc_audio_set_format(player, "S16N", 48_000, 2)
    }

    override fun setEqualizer(config: EqConfig) { check(!closed); pcm.setConfig(config) }
    override fun equalizerRuntime(): EqRuntime = pcm.runtime.get()

    override fun open(path: Path, play: Boolean) {
        check(!closed)
        require(Files.isRegularFile(path)) { "文件不存在：$path" }
        val media = api.libvlc_media_new_location(instance, path.toUri().toASCIIString())
            ?: error("无法打开音频：$path")
        try {
            pcm.interrupt()
            api.libvlc_media_player_stop(player)
            api.libvlc_media_player_set_media(player, media)
            pcm.prepare()
            if (play) check(api.libvlc_media_player_play(player) == 0) { "无法播放：$path" }
        } finally {
            api.libvlc_media_release(media)
        }
    }

    override fun setPaused(paused: Boolean) {
        check(!closed)
        if (!paused && snapshot().status in listOf(AudioStatus.Idle, AudioStatus.Stopped, AudioStatus.Ended)) {
            pcm.prepare()
            check(api.libvlc_media_player_play(player) == 0) { "无法开始播放。" }
        } else api.libvlc_media_player_set_pause(player, if (paused) 1 else 0)
    }

    override fun seek(positionMs: Long) {
        check(!closed)
        pcm.flush()
        api.libvlc_media_player_set_time(player, positionMs.coerceAtLeast(0))
    }

    override fun setVolume(volume: Int) {
        check(!closed)
        pcm.volume = volume.coerceIn(0, 100)
    }

    override fun snapshot(): AudioSnapshot {
        check(!closed)
        val status = if (pcm.runtime.get().error != null) AudioStatus.Failed else when (api.libvlc_media_player_get_state(player)) {
            1, 2 -> AudioStatus.Loading
            3 -> AudioStatus.Playing
            4 -> AudioStatus.Paused
            5 -> AudioStatus.Stopped
            6 -> AudioStatus.Ended
            7 -> AudioStatus.Failed
            else -> AudioStatus.Idle
        }
        return AudioSnapshot(status, api.libvlc_media_player_get_time(player).coerceAtLeast(0),
            api.libvlc_media_player_get_length(player).coerceAtLeast(0),
            pcm.volume)
    }

    override fun stop() {
        check(!closed)
        pcm.interrupt()
        api.libvlc_media_player_stop(player)
    }

    override fun close() {
        if (closed) return
        closed = true
        pcm.interrupt()
        api.libvlc_media_player_stop(player)
        api.libvlc_media_player_release(player)
        pcm.close()
        api.libvlc_release(instance)
    }
}
