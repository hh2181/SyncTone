package dev.salt.desktop.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.salt.desktop.library.DesktopTrack
import dev.salt.desktop.library.WeightedLruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SamplingMode
import kotlin.math.max
import kotlin.math.roundToInt

internal val LocalArtworkCache = staticCompositionLocalOf<ArtworkCache> { error("Artwork cache is not installed") }

internal class ArtworkCache {
    private data class Key(val asset: Any, val pixels: Int)
    private data class Entry(val bitmap: ImageBitmap?)
    private val cache = WeightedLruCache<Key, Entry>(32L * 1024 * 1024) {
        it.bitmap?.let { image -> image.width.toLong() * image.height * 4 } ?: 1L
    }
    private val decoder = Mutex()

    suspend fun load(track: DesktopTrack, pixels: Int): ImageBitmap? = decoder.withLock {
        val key = Key(track.assetKey, pixels)
        cache[key]?.let { return@withLock it.bitmap }
        val bitmap = withContext(Dispatchers.IO) { track.artwork?.let { decode(it, pixels) ?: decodeWithSkia(it, pixels) } }
        cache.put(key, Entry(bitmap))
        bitmap
    }

    fun clear() = cache.clear()

    private fun decode(bytes: ByteArray, pixels: Int): ImageBitmap? = runCatching {
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return@use null
            try {
                reader.input = input
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                val sample = (max(width, height) / pixels).coerceAtLeast(1)
                val parameters = reader.defaultReadParam.apply { setSourceSubsampling(sample, sample, 0, 0) }
                val source = reader.read(0, parameters)
                try {
                    val ratio = (pixels.toDouble() / max(source.width, source.height)).coerceAtMost(1.0)
                    val scaled = BufferedImage((source.width * ratio).roundToInt().coerceAtLeast(1),
                        (source.height * ratio).roundToInt().coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB)
                    try {
                        scaled.createGraphics().let { graphics ->
                            try {
                                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                                graphics.drawImage(source, 0, 0, scaled.width, scaled.height, null)
                            } finally { graphics.dispose() }
                        }
                        scaled.toComposeImageBitmap()
                    } finally { scaled.flush() }
                } finally { source.flush() }
            } finally { reader.dispose() }
        }
    }.getOrNull()

    private fun decodeWithSkia(bytes: ByteArray, pixels: Int): ImageBitmap? = runCatching {
        Image.makeFromEncoded(bytes).use { source ->
            val ratio = (pixels.toDouble() / max(source.width, source.height)).coerceAtMost(1.0)
            val width = (source.width * ratio).roundToInt().coerceAtLeast(1)
            val height = (source.height * ratio).roundToInt().coerceAtLeast(1)
            Surface.makeRasterN32Premul(width, height).use { surface ->
                surface.canvas.drawImageRect(source, Rect.makeWH(source.width.toFloat(), source.height.toFloat()),
                    Rect.makeWH(width.toFloat(), height.toFloat()), SamplingMode.LINEAR, null, true)
                surface.makeImageSnapshot().use { it.toComposeImageBitmap() }
            }
        }
    }.getOrNull()
}
