package dev.salt.replica.equalizer

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.*

class EqualizerTest {
    private val feedback = EqConfig(true, listOf(-8, 0, 0, -68, -65, -26, 0, 0, 0, 0), false)

    @Test fun calibratedCentersAndIntervalsFollowTheApprovedModel() {
        val model = EqModel.design(feedback)
        assertEquals(-6.8298147754, model.responseDb(250.0), 1e-7)
        assertEquals(-6.5246745701, model.responseDb(500.0), 1e-7)
        val interval = (0..100).map { model.responseDb(250 * 2.0.pow(it / 100.0)) }
        assertTrue("Adjacent cuts must form one broad attenuation region", interval.maxOrNull()!! - interval.minOrNull()!! < .6)
    }

    @Test fun actualStereoPcmProcessingMatchesTheDisplayedResponse() {
        for (rate in listOf(44_100, 48_000)) for (frequency in listOf(350.0, 500.0)) {
            val model = EqModel.design(feedback, rate)
            val dsp = EqualizerDsp()
            dsp.setConfig(feedback); dsp.configure(rate, 2)
            val input = ByteBuffer.allocate(rate * 4).order(ByteOrder.LITTLE_ENDIAN)
            repeat(rate) { n -> input.putShort((sin(2 * PI * frequency * n / rate) * 8_000).roundToInt().toShort()); input.putShort(0) }
            input.flip()
            val output = ByteBuffer.allocate(input.remaining()).order(ByteOrder.LITTLE_ENDIAN)
            dsp.processPcm16(input, output); output.flip()
            var inputPower = 0.0; var outputPower = 0.0
            repeat(rate) { n ->
                val left = output.short.toDouble(); assertEquals("Independent channel state", 0, output.short.toInt())
                if (n >= rate / 4) {
                    val original = (sin(2 * PI * frequency * n / rate) * 8_000).roundToInt().toDouble()
                    inputPower += original * original; outputPower += left * left
                }
            }
            val measured = 10 * log10(outputPower / inputPower)
            assertEquals("PCM and graph disagree at $rate / $frequency", model.responseDb(frequency), measured, .04)
        }
    }

    @Test fun bypassIsBitExactAndMuteHasNoInitialLeak() {
        val source = ByteArray(4096) { ((it * 73 + 19) % 256).toByte() }
        val dsp = EqualizerDsp(); dsp.configure(48_000, 2)
        val input = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN)
        val output = ByteBuffer.allocate(source.size).order(ByteOrder.LITTLE_ENDIAN)
        dsp.processPcm16(input, output)
        assertArrayEquals(source, output.array())
        input.rewind(); output.clear(); dsp.processPcm16(input, output, 0.0)
        assertTrue(output.array().all { it == 0.toByte() })
    }

    @Test fun sampleRatesRemainStableAndHeadroomIncludesTheWholeCurve() {
        val boost = EqConfig(true, listOf(120, -120, -120, 120, -120, -120, -120, 120, -120, -120))
        for (rate in listOf(7_350, 8_000, 22_050, 32_000, 44_100, 48_000, 96_000)) {
            val model = EqModel.design(boost, rate)
            assertEquals(EqFrequencies.count { it < rate * .49 }, model.activeBands)
            model.sections.forEach { c -> assertTrue(abs(c.a2) < 1 && 1 + c.a1 + c.a2 > 0 && 1 - c.a1 + c.a2 > 0) }
            val points = (0..2048).map { model.responseDb(20 * (model.maxFrequency / 20).pow(it / 2048.0)) }
            assertTrue(points.all { it.isFinite() })
            assertTrue("Headroom must cover inter-band peaks", points.maxOrNull()!! < -.9)
        }
        assertEquals(0.0, EqModel.design(EqConfig(true)).preampDb, 0.0)
    }

    @Test fun multiplePresetsAndCurrentDraftSurviveRestartWithoutTouchingMusic() {
        val directory = Files.createTempDirectory("synctone-eq-")
        val file = directory.resolve("equalizer-presets.json").toFile()
        try {
            val first = EqualizerRepository(file)
            first.setEnabled(true); first.setGain(3, -68); first.save("低频微调")
            first.setGain(4, -65); first.save("第二个方案")
            first.reset(); first.close()
            val second = EqualizerRepository(file)
            assertEquals(2, second.state.value.presets.size)
            assertEquals(List(10) { 0 }, second.state.value.config.gains)
            second.apply(second.state.value.presets.last().id)
            assertEquals(-65, second.state.value.config.gains[4])
            second.delete(second.state.value.presets.first().id)
            assertEquals(1, second.state.value.presets.size)
            second.close()
            assertEquals(setOf("equalizer-presets.json"), directory.toFile().listFiles()!!.map { it.name }.toSet())
        } finally { file.delete(); directory.resolve("equalizer-presets.json.tmp").toFile().delete(); directory.toFile().delete() }
    }

    @Test fun unsupportedOrDamagedPresetFilesArePreserved() {
        val directory = Files.createTempDirectory("synctone-eq-unreadable-")
        val file = directory.resolve("equalizer-presets.json").toFile()
        try {
            file.writeText("{broken")
            val repository = EqualizerRepository(file)
            assertNotNull(repository.state.value.error)
            repository.setGain(0, 10); repository.close()
            assertEquals("{broken", file.readText())
        } finally { file.delete(); directory.toFile().delete() }
    }
}
