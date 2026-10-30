package dev.salt.replica.playback

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import dev.salt.replica.statistics.ListeningRecorder
import dev.salt.replica.statistics.StatisticsRepository
import dev.salt.replica.equalizer.AndroidEqualizer
import dev.salt.replica.equalizer.EqualizerAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private val equalizerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var session: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var recorder: ListeningRecorder
    private val checkpoint = object : Runnable {
        override fun run() {
            recorder.checkpoint(System.currentTimeMillis(), SystemClock.elapsedRealtime())
            handler.postDelayed(this, 1_000L)
        }
    }
    override fun onCreate() {
        super.onCreate()
        recorder = ListeningRecorder { StatisticsRepository.get(this).record(it) }
        val equalizer = AndroidEqualizer.get(this)
        val processor = EqualizerAudioProcessor(equalizer)
        processor.setConfig(equalizer.state.value.config)
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean): AudioSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(false)
                .setAudioProcessors(arrayOf(processor)).build()
        }
        equalizerScope.launch { equalizer.state.map { it.config }.distinctUntilChanged().collect(processor::setConfig) }
        val player = ExoPlayer.Builder(this, renderers).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
        }
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                recorder.transition(mediaItem?.mediaId, player.isPlaying, System.currentTimeMillis(), SystemClock.elapsedRealtime())
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                recorder.playing(isPlaying, System.currentTimeMillis(), SystemClock.elapsedRealtime())
            }
        })
        handler.post(checkpoint)
        session = MediaSession.Builder(this, player).build()
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onDestroy() {
        equalizerScope.cancel()
        AndroidEqualizer.get(this).disconnect()
        handler.removeCallbacks(checkpoint)
        recorder.playing(false, System.currentTimeMillis(), SystemClock.elapsedRealtime())
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}
