package dev.salt.desktop.visual

import org.jetbrains.skia.*
import kotlin.math.max
import kotlin.math.roundToInt

/** Desktop Skia version of FlowingLightView's low-resolution three-layer mesh pipeline. */
class FlowingLightRenderer(bytes: ByteArray) : AutoCloseable {
    private val artwork = Image.makeFromEncoded(bytes)
    private val average: Int
    private var history: Image? = null

    init {
        Bitmap().use { bitmap ->
            check(bitmap.allocN32Pixels(5, 5))
            Surface.makeRasterN32Premul(5, 5).use { surface ->
                surface.canvas.drawImageRect(artwork, Rect.makeWH(5f, 5f))
                surface.readPixels(bitmap, 0, 0)
            }
            var r = 0; var g = 0; var b = 0
            repeat(5) { y -> repeat(5) { x ->
                val color = bitmap.getColor(x, y)
                r += color ushr 16 and 255; g += color ushr 8 and 255; b += color and 255
            } }
            average = Color.makeRGB(r / 25, g / 25, b / 25)
        }
    }

    private data class FrameKey(val width: Int, val height: Int, val mesh: Int, val light: Boolean, val reduced: Boolean)

    /** Working surfaces stay private; returned snapshots retain their own Skia references. */
    private inner class Buffers(val key: FrameKey) : AutoCloseable {
        val raw = Surface.makeRasterN32Premul(key.width, key.height)
        val warped = Surface.makeRasterN32Premul(key.width, key.height)
        val blurred = Array(2) { Surface.makeRasterN32Premul(key.width, key.height) }
        val crop = Surface.makeRasterN32Premul((key.width - 58).coerceAtLeast(1), (key.height - 58).coerceAtLeast(1))
        val colorFilter = ColorFilter.makeMatrix(saturation(if (key.reduced) 3.8f else 3.0f))
        val sigma = ((minOf(key.width, key.height) - 58) * .19f).coerceIn(3f, 12f)
        val blur = ImageFilter.makeBlur(sigma, sigma, FilterTileMode.CLAMP)
        val artworkPaint = Paint().apply { colorFilter = this@Buffers.colorFilter }
        val meshPaint = Paint()
        val blurPaint = Paint().apply { imageFilter = blur }
        val historyPaint = Paint().apply { alpha = 64 }
        val overlay = Paint()
        val rect = Rect.makeWH(key.width.toFloat(), key.height.toFloat())
        val points = meshPresets[key.mesh]
        val positions = FloatArray(points.size) { points[it] * if (it % 2 == 0) key.width else key.height }
        val texture = FloatArray(72) { index ->
            if (index % 2 == 0) (index / 2 % 6) / 5f * key.width else (index / 12) / 5f * key.height
        }
        val indices = ShortArray(150).also { indices ->
            var i = 0
            repeat(5) { y -> repeat(5) { x ->
                val p = y * 6 + x
                for (v in intArrayOf(p, p + 1, p + 6, p + 1, p + 7, p + 6)) indices[i++] = v.toShort()
            } }
        }
        var nextBlur = 0
        override fun close() {
            artworkPaint.close(); meshPaint.close(); blurPaint.close(); historyPaint.close(); overlay.close()
            colorFilter.close(); blur.close()
            raw.close(); warped.close(); blurred.forEach { it.close() }; crop.close()
        }
    }

    private var buffers: Buffers? = null
    private var closed = false

    fun render(width: Int, height: Int, elapsedMs: Long, mesh: Int, light: Boolean, reduced: Boolean): Image {
        check(!closed) { "Renderer is closed" }
        val factor = if (reduced) 48f else 20f
        val w = (width / factor + 58).roundToInt().coerceAtLeast(60)
        val h = (height / factor + 58).roundToInt().coerceAtLeast(60)
        val key = FrameKey(w, h, mesh.coerceIn(0, 4), light, reduced)
        if (buffers?.key != key) {
            history?.close(); history = null
            buffers?.close()
            buffers = Buffers(key)
        }
        val b = checkNotNull(buffers)
        b.raw.canvas.clear(average)
        val side = max(w, h) * 1.3f
        val scale = side / max(artwork.width, artwork.height)
        fun layer(period: Long, reverse: Boolean, dx: Float, dy: Float, extra: Boolean = false) {
            val angle = elapsedMs % period / period.toFloat() * if (reverse) -360f else 360f
            val canvas = b.raw.canvas
            canvas.save()
            if (extra) canvas.rotate(angle, w / 2f, h / 2f)
            canvas.translate(-(side - w) / 2 + dx, -(side - h) / 2 + dy)
            canvas.rotate(angle, side / 2, side / 2)
            canvas.scale(scale, scale)
            canvas.drawImage(artwork, 0f, 0f, b.artworkPaint)
            canvas.restore()
        }
        layer(100_000, false, 0f, 0f)
        layer(70_000, true, -.95f * w, -.7f * h)
        layer(40_000, true, -.5f * w, .7f * h, true)
        b.warped.canvas.clear(average)
        b.raw.makeImageSnapshot().use { image ->
            image.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP).use { shader ->
                b.meshPaint.shader = shader
                try { b.warped.canvas.drawVertices(VertexMode.TRIANGLES, b.positions, null, b.texture, b.indices, BlendMode.SRC, b.meshPaint) }
                finally { b.meshPaint.shader = null }
            }
        }
        b.overlay.color = if (light) 0x52FFFFFF else 0x38000000
        b.warped.canvas.drawRect(b.rect, b.overlay)
        b.overlay.color = if (light) 0x12FFFFFF else 0x10000000
        b.warped.canvas.drawRect(b.rect, b.overlay)
        // Alternate history surfaces: never overwrite pixels still sampled by the previous frame.
        val blurred = b.blurred[b.nextBlur]
        b.nextBlur = 1 - b.nextBlur
        blurred.canvas.clear(Color.TRANSPARENT)
        b.warped.makeImageSnapshot().use { image -> blurred.canvas.drawImage(image, 0f, 0f, b.blurPaint) }
        history?.let { blurred.canvas.drawImage(it, 0f, 0f, b.historyPaint) }
        history?.close()
        history = blurred.makeImageSnapshot()
        b.crop.canvas.clear(Color.TRANSPARENT)
        b.crop.canvas.drawImage(history!!, -29f, -29f)
        return b.crop.makeImageSnapshot()
    }

    override fun close() {
        if (closed) return
        closed = true
        history?.close(); history = null
        buffers?.close(); buffers = null
        artwork.close()
    }

    private fun saturation(s: Float): ColorMatrix {
        val r = .213f * (1 - s); val g = .715f * (1 - s); val b = .072f * (1 - s)
        return ColorMatrix(floatArrayOf(r + s,g,b,0f,0f, r,g + s,b,0f,0f, r,g,b + s,0f,0f, 0f,0f,0f,1f,0f))
    }
}
