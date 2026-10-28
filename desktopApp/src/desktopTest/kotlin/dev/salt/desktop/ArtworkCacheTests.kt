package dev.salt.desktop

import dev.salt.desktop.library.DesktopTrack
import dev.salt.desktop.library.DesktopTrackReader
import dev.salt.desktop.library.TrackAssets
import dev.salt.desktop.library.WeightedLruCache
import dev.salt.desktop.ui.ArtworkCache
import dev.salt.replica.lyrics.Lyrics
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class ArtworkCacheTests {
    @Test fun originalEmbeddedArtworkRetainsSkiaFormatSupport() = runBlocking {
        val track = DesktopTrackReader.read(Path.of(System.getProperty("synctone.reference.path")))
        assertNotNull(track.artwork)
        val bitmap = assertNotNull(ArtworkCache().load(track, 128))
        assertTrue(bitmap.width in 1..128)
        assertTrue(bitmap.height in 1..128)
    }

    @Test fun cacheEvictsLeastRecentlyUsedAndNeverExceedsBudget() {
        val cache = WeightedLruCache<String, ByteArray>(10) { it.size.toLong() }
        cache.put("a", ByteArray(4))
        cache.put("b", ByteArray(4))
        assertNotNull(cache["a"])
        cache.put("c", ByteArray(4))
        assertNull(cache["b"])
        assertNotNull(cache["a"])
        assertNotNull(cache["c"])
        cache.put("oversized", ByteArray(11))
        assertNull(cache["oversized"])
        assertEquals(8L, cache.weight)
        cache.put("a", ByteArray(1))
        assertEquals(5L, cache.weight)
        cache.clear()
        assertEquals(0L, cache.weight)
    }

    @Test fun largeArtworkIsDownsampledAndConcurrentRequestsReuseTheBitmap() = runBlocking {
        val image = BufferedImage(2400, 1200, BufferedImage.TYPE_INT_RGB)
        val bytes = ByteArrayOutputStream().use { output ->
            ImageIO.write(image, "png", output)
            output.toByteArray()
        }
        var reads = 0
        val track = DesktopTrack(Path.of("fixture.mp3"), "title", "artist", "album", 1000, null, Lyrics.Empty,
            assetLoader = { reads++; TrackAssets(bytes, Lyrics.Empty) })
        val cache = ArtworkCache()
        val bitmaps = List(8) { async { cache.load(track, 128) } }.awaitAll()
        val bitmap = assertNotNull(bitmaps.first())
        assertEquals(128, bitmap.width)
        assertEquals(64, bitmap.height)
        bitmaps.forEach { assertSame(bitmap, it) }
        assertEquals(1, reads)
        val larger = assertNotNull(cache.load(track, 512))
        assertEquals(512, larger.width)
        assertEquals(256, larger.height)
        cache.clear()
        assertNotSame(bitmap, cache.load(track, 128))
    }

    @Test fun changedAssetsAndMissingArtworkDoNotReuseStaleImages() = runBlocking {
        val cache = ArtworkCache()
        var reads = 0
        val track = DesktopTrack(Path.of("fixture.mp3"), "title", "artist", "album", 1000, null, Lyrics.Empty,
            assetLoader = { reads++; TrackAssets(byteArrayOf(1, 2, 3), Lyrics.Empty) })
        assertNull(cache.load(track, 128))
        assertNull(cache.load(track, 128))
        assertEquals(1, reads)
        assertNull(cache.load(track.copy(), 128))
        assertEquals(2, reads)
    }
}
