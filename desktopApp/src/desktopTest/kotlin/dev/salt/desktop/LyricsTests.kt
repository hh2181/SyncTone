package dev.salt.desktop

import dev.salt.desktop.lyrics.DesktopLyricsSettings
import dev.salt.desktop.playback.PlaybackTimeline
import dev.salt.desktop.storage.DesktopSession
import dev.salt.desktop.storage.DesktopSessionRepository
import dev.salt.desktop.visual.FlowingLightRenderer
import dev.salt.replica.lyrics.*
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class LyricsTests {
    @Test fun frameClockInterpolatesRepeatedSamplesAndHandlesPauseSeekAndStalls() {
        var now = 0L
        val clock = PlaybackTimeline { now }
        clock.update(1000, true, 10_000, true)
        now += 120_000_000
        assertEquals(1120, clock.positionMs())
        clock.update(1000, true, 10_000)
        now += 120_000_000
        assertEquals(1240, clock.positionMs())
        clock.update(1250, true, 10_000)
        now += 120_000_000
        assertEquals(1360, clock.positionMs())
        clock.update(1350, false, 10_000)
        now += 2_000_000_000
        assertEquals(1350, clock.positionMs())
        clock.update(300, true, 10_000, true)
        now += 50_000_000
        assertEquals(350, clock.positionMs())
        now += 5_000_000_000
        assertEquals(1050, clock.positionMs())
        clock.update(9950, true, 10_000, true)
        now += 1_000_000_000
        assertEquals(10_000, clock.positionMs())
    }

    @Test fun visualSettingsRoundTripWithActualVariableAxisAndSafeBounds() {
        val directory = Files.createTempDirectory("synctone-lyric-settings")
        val repository = DesktopSessionRepository(directory)
        val settings = DesktopLyricsSettings(fontWeight = 473, fontSize = 34f, speed = 1.4f, curve = 2,
            translation = false, center = true, blur = false, stagger = false, reduced = true, light = true, mesh = 4)
        repository.write(DesktopSession(lyrics = settings))
        assertEquals(settings, repository.read().lyrics)
        val normalized = DesktopLyricsSettings(fontWeight = 999, fontSize = Float.NaN, speed = Float.POSITIVE_INFINITY).normalized()
        assertEquals(700, normalized.fontWeight)
        assertEquals(30f, normalized.fontSize)
        assertEquals(1f, normalized.speed)
    }

    @Test fun originalTimingModelsRetainInterruptedStaggerAndSecondaryLongNotes() {
        val start = 10_000_000_000L
        val first = StaggerMotion(160f, 4, start)
        val carried = first.offset(7, start + 300_000_000)
        val interrupted = StaggerMotion(120f, 5, start + 300_000_000, mapOf(7 to carried))
        assertEquals(120 + carried, interrupted.offset(7, start + 300_000_000), .001f)
        assertEquals(0f, interrupted.offset(7, start + 4_000_000_000), .11f)
        val envelope = LongNoteEnvelope(listOf(LyricCell(1000, 3000, "long")))
        assertEquals(.592f, envelope.alpha(0, 1500), .001f)
        assertEquals(.296f, envelope.alpha(0, 3500), .001f)
        assertEquals(0f, envelope.alpha(0, 3800), .001f)
    }

    @Test fun coverMeshRendersMovesAndSupportsAllOriginalPresets() {
        val bytes = dev.salt.desktop.library.DesktopTrackReader.read(Path.of(System.getProperty("synctone.reference.path"))).artwork!!
        fun pixels(image: Image): IntArray = image.use { source -> Bitmap().use { bitmap ->
            check(bitmap.allocN32Pixels(source.width, source.height)); check(source.readPixels(bitmap))
            IntArray(source.width * source.height) { bitmap.getColor(it % source.width, it / source.width) }
        } }
        FlowingLightRenderer(bytes).use { renderer ->
            val first = pixels(renderer.render(1280, 820, 0, 0, false, false))
            assertTrue(first.toSet().size > 50)
            val moved = pixels(renderer.render(1280, 820, 8000, 0, false, false))
            assertTrue(first.indices.count { first[it] != moved[it] } > first.size / 2)
            for (mesh in 0..4) assertTrue(pixels(renderer.render(960, 640, 5000, mesh, true, true)).toSet().size > 20)
        }
    }
}
