package dev.salt.replica.equalizer

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AndroidEqualizer {
    @Volatile private var instance: EqualizerRepository? = null
    fun get(context: Context): EqualizerRepository = instance ?: synchronized(this) {
        instance ?: EqualizerRepository(File(context.applicationContext.filesDir, "equalizer-presets.json"))
            .also { instance = it }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class EqualizerAudioProcessor(private val repository: EqualizerRepository) : BaseAudioProcessor() {
    private val dsp = EqualizerDsp(repository::reportApplied)
    fun setConfig(config: EqConfig) = dsp.setConfig(config)
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        // Stay installed while bypassed, so enabling EQ cannot silently skip the sink.
        return inputAudioFormat
    }
    override fun onFlush() {
        if (inputAudioFormat != AudioProcessor.AudioFormat.NOT_SET) {
            dsp.configure(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        }
    }
    override fun queueInput(inputBuffer: ByteBuffer) {
        val output = replaceOutputBuffer(inputBuffer.remaining()).order(ByteOrder.nativeOrder())
        dsp.processPcm16(inputBuffer.order(ByteOrder.nativeOrder()), output)
        output.flip()
    }
    override fun onReset() { dsp.flush(); repository.disconnect() }
}
