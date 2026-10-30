package dev.salt.desktop.playback

import com.sun.jna.Pointer
import dev.salt.replica.equalizer.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.math.min

/** VLC retains decoding and transport. Its S16N callbacks feed the shared DSP
 * and Java's Windows PCM output. Scheduling honors VLC's presentation clock. */
internal class VlcPcmOutput(private val clock: () -> Long) : AutoCloseable {
    val runtime = AtomicReference(EqRuntime())
    @Volatile var volume = 72
    @Volatile private var paused = false
    @Volatile private var interrupted = true
    @Volatile private var closed = false
    @Volatile private var line: SourceDataLine? = null
    private val revision = AtomicInteger()
    private var processedRevision = -1
    private var output = ByteArray(0)
    private var buffer = ByteBuffer.allocate(0)
    private data class Tail(val revision: Int, val bytes: ByteArray)
    @Volatile private var tail: Tail? = null
    private val dsp = EqualizerDsp { model, channels -> runtime.set(EqRuntime(model, channels, true)) }

    init { dsp.configure(48_000, 2) }
    fun setConfig(config: EqConfig) = dsp.setConfig(config)
    fun prepare() {
        interrupted = false; paused = false
        runtime.updateAndGet { it.copy(connected = false, error = null) }
        line?.start()
    }
    fun pause() { paused = true; line?.stop() }
    fun resume() { paused = false; line?.start() }
    fun flush() {
        revision.incrementAndGet(); tail = null
        line?.flush()
    }
    fun interrupt() {
        interrupted = true; revision.incrementAndGet(); tail = null
        line?.stop(); line?.flush()
    }
    fun drain() {
        if (!closed && !interrupted && !paused) line?.drain()
    }
    private fun outputLine(): SourceDataLine {
        line?.takeIf { it.isOpen }?.let { return it }
        val format = AudioFormat(48_000f, 16, 2, true, false)
        val created = AudioSystem.getSourceDataLine(format)
        try {
            created.open(format, 9_600)
            check(!closed && !interrupted) { "音频输出已关闭" }
            line = created
            if (!paused) created.start()
            return created
        } catch (failure: Exception) { created.close(); throw failure }
    }

    /** Only the native audio callback owns buffers and filter memory. Control
     * callbacks interrupt waits/writes without holding the sample-loop lock. */
    fun play(samples: Pointer, frames: Int, presentationUs: Long) {
        if (closed || interrupted || frames <= 0 || runtime.get().error != null) return
        val epoch = revision.get()
        try {
            val sink = outputLine()
            if (processedRevision != epoch) { dsp.flush(); processedRevision = epoch }
            val bytes = Math.multiplyExact(frames, 4)
            if (output.size < bytes) { output = ByteArray(bytes); buffer = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN) }
            buffer.clear(); buffer.limit(bytes)
            val input = samples.getByteBuffer(0, bytes.toLong()).order(ByteOrder.LITTLE_ENDIAN)
            val level = volume.coerceIn(0, 100) / 100.0
            // Preserve VLC's Windows mmdevice cubic volume taper.
            dsp.processPcm16(input, buffer, level * level * level)
            val retained = tail; tail = null
            if (retained?.revision == epoch) write(sink, retained.bytes, retained.bytes.size, epoch)
            // amem has no device clock callback. Account for the Windows buffer
            // before scheduling each block against libvlc_clock(), in microseconds.
            while (presentationUs > 0 && !closed && !interrupted && !paused && epoch == revision.get()) {
                val bufferedUs = (sink.bufferSize - sink.available()).toLong() * 1_000_000 / (48_000 * 4)
                val waitUs = presentationUs - clock() - bufferedUs
                if (waitUs <= 1_000) break
                LockSupport.parkNanos(min(waitUs, 3_000) * 1_000)
            }
            write(sink, output, bytes, epoch)
        } catch (failure: Exception) {
            if (!closed && !interrupted && epoch == revision.get()) {
                runtime.updateAndGet { it.copy(connected = false, error = "无法输出音频：${failure.message}") }
            }
        }
    }
    private fun write(sink: SourceDataLine, data: ByteArray, size: Int, epoch: Int) {
        var offset = 0
        while (offset < size && !closed && !interrupted && epoch == revision.get()) {
            if (paused) {
                // Preserve a partially submitted block across pause, never seek.
                val remaining = data.copyOfRange(offset, size)
                val retained = tail?.takeIf { it.revision == epoch }
                tail = Tail(epoch, if (retained == null) remaining else retained.bytes + remaining)
                return
            }
            val written = sink.write(data, offset, min(1_536, size - offset))
            if (written > 0) offset += written else LockSupport.parkNanos(1_000_000)
        }
    }
    override fun close() {
        closed = true; interrupt()
        line?.close(); line = null
        runtime.updateAndGet { it.copy(connected = false) }
    }
}
