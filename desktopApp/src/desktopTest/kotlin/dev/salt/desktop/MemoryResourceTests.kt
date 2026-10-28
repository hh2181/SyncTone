package dev.salt.desktop

import dev.salt.desktop.lyrics.OwnedResource
import dev.salt.desktop.library.DesktopTrackReader
import dev.salt.desktop.storage.DesktopLibraryRepository
import dev.salt.desktop.ui.ArtworkCache
import dev.salt.desktop.visual.FlowingLightRenderer
import dev.salt.replica.lyrics.LyricsParser
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import org.junit.Test
import java.nio.file.Path
import kotlin.test.*

class MemoryResourceTests {
    @Test fun reusedBackgroundSurfacesPreserveEveryPixelAndHistoryAcrossSettings() {
        val bytes = DesktopTrackReader.read(Path.of(System.getProperty("synctone.reference.path"))).artwork!!
        fun pixels(image: Image) = image.use { source -> Bitmap().use { bitmap ->
            check(bitmap.allocN32Pixels(source.width, source.height)); check(source.readPixels(bitmap))
            IntArray(source.width * source.height) { bitmap.getColor(it % source.width, it / source.width) }
        } }
        FlowingLightRenderer(bytes).use { current -> LegacyFlowingLightRenderer(bytes).use { legacy ->
            for ((width, height) in listOf(1280 to 820, 960 to 640, 1920 to 1080)) {
                for (mesh in 0..4) for (light in listOf(false, true)) for (reduced in listOf(false, true)) {
                    for (time in listOf(0L, 42L, 84L, 8000L, 8000L, 120000L)) {
                        assertContentEquals(pixels(legacy.render(width, height, time, mesh, light, reduced)),
                            pixels(current.render(width, height, time, mesh, light, reduced)),
                            "$width x $height mesh=$mesh light=$light reduced=$reduced time=$time")
                    }
                }
            }
        } }
    }

    @Test fun artworkReadsDoNotParseLyricsAndLibraryRefreshReusesDecodedCovers() = runBlocking {
        DesktopLibraryRepository(null).use { repository ->
            repository.register(Path.of(System.getProperty("synctone.reference.path")))
            val first = repository.tracks().first()
            val cache = ArtworkCache()
            val image = assertNotNull(cache.load(first, 128))
            assertTrue(repository.cachedArtworkBytes > 0)
            assertEquals(0L, repository.cachedLyricsBytes)
            val refreshed = repository.tracks().first()
            assertSame(image, cache.load(refreshed, 128))
            repository.setLyrics(first.id, LyricsParser.parse("[00:01]changed", 2000))
            assertEquals("changed", refreshed.lyrics.lines.first().text)
            assertTrue(repository.cachedLyricsBytes > 0)
            assertSame(image, cache.load(repository.tracks().first(), 128))
            repository.restoreLyrics(first.id)
            assertEquals(0L, repository.cachedLyricsBytes)
        }
    }

    @Test fun disposedCompositionReleasesLateWorkerResultsAndClosesEachResourceOnce() {
        class Resource : AutoCloseable { var closes = 0; override fun close() { closes++ } }
        val current = Resource()
        val slot = OwnedResource<Resource>()
        assertSame(current, slot.install(current))
        slot.close(); slot.close()
        assertEquals(1, current.closes)
        val late = Resource()
        assertNull(slot.install(late))
        assertEquals(1, late.closes)
    }
}
