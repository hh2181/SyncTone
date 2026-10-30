package dev.salt.replica.equalizer

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/** One owner processes PCM; parameter design happens on the caller's control worker. */
class EqualizerDsp(private val onApplied: (EqModel, Int) -> Unit = { _, _ -> }) {
    private class Chain(val model: EqModel, val channels: Int) {
        val memory = DoubleArray(model.sections.size * channels * 2)
        fun filter(input: Double, channel: Int): Double {
            if (!model.config.enabled) return input
            var value = input
            model.sections.forEachIndexed { i, c ->
                val index = (channel * model.sections.size + i) * 2
                val y = c.b0 * value + memory[index]
                memory[index] = c.b1 * value - c.a1 * y + memory[index + 1]
                memory[index + 1] = c.b2 * value - c.a2 * y
                value = y
            }
            return value * model.preamp
        }
    }
    private val control = Any()
    private var config = EqConfig()
    private var rate = 48_000
    private var channels = 2
    private var active = Chain(EqModel.design(config, rate), channels)
    private val pending = AtomicReference<Chain?>()
    private var fading: Chain? = null
    private var fadeFrame = 0
    private var report = true
    private var volume = Double.NaN

    fun setConfig(value: EqConfig) = synchronized(control) {
        config = value.sanitized()
        pending.set(Chain(EqModel.design(config, rate), channels))
    }

    /** Format changes and flush belong to the PCM owner, never a Compose callback. */
    fun configure(sampleRate: Int, channelCount: Int) = synchronized(control) {
        require(channelCount in 1..32)
        rate = sampleRate; channels = channelCount
        active = Chain(EqModel.design(config, rate), channels)
        pending.set(null); fading = null; fadeFrame = 0; report = true
    }

    fun flush() {
        fading?.let { active = it }; fading = null; fadeFrame = 0
        active.memory.fill(0.0); report = true
    }

    /** S16 native/little endian is negotiated by both playback adapters.
     * No allocations, coefficient design, locks or disk access in the sample loop. */
    fun processPcm16(input: ByteBuffer, output: ByteBuffer, outputVolume: Double = 1.0) {
        (if (fading == null) pending.getAndSet(null) else null)?.let {
            if (it.channels == channels && it.model.sampleRate == rate) {
                fading = it; fadeFrame = 0
            }
        }
        val transitionFrames = (rate * .04).toInt().coerceAtLeast(1)
        if (!volume.isFinite() || outputVolume == 0.0) volume = outputVolume
        while (input.remaining() >= channels * 2) {
            val next = fading
            val blend = if (next == null) 0.0 else (fadeFrame.toDouble() / transitionFrames).coerceIn(0.0, 1.0)
            volume += (outputVolume - volume) * .004
            for (channel in 0 until channels) {
                val dry = input.short / 32768.0
                val old = active.filter(dry, channel)
                val value = if (next == null) old else old * (1 - blend) + next.filter(dry, channel) * blend
                val sample = (value * volume * 32768.0).roundToInt().coerceIn(-32768, 32767)
                output.putShort(sample.toShort())
            }
            if (next != null && ++fadeFrame >= transitionFrames) {
                active = next; fading = null; report = true
            }
        }
        if (report && fading == null) { report = false; onApplied(active.model, channels) }
    }
}
