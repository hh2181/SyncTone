package dev.salt.desktop

import dev.salt.desktop.visual.meshPresets

import org.jetbrains.skia.*
import kotlin.math.max
import kotlin.math.roundToInt

/** Desktop Skia version of FlowingLightView's low-resolution three-layer mesh pipeline. */
class LegacyFlowingLightRenderer(bytes: ByteArray) : AutoCloseable {
    private val artwork = Image.makeFromEncoded(bytes)
    private val average: Int
    private var history: Image? = null
    private var historyKey: List<Any>? = null

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

    fun render(width: Int, height: Int, elapsedMs: Long, mesh: Int, light: Boolean, reduced: Boolean): Image {
        val factor = if (reduced) 48f else 20f
        val w = (width / factor + 58).roundToInt().coerceAtLeast(60)
        val h = (height / factor + 58).roundToInt().coerceAtLeast(60)
        val key = listOf(w, h, mesh, light, reduced)
        if (historyKey != key) { history?.close(); history = null; historyKey = key }
        return Surface.makeRasterN32Premul(w, h).use { raw ->
            raw.canvas.clear(average)
            val side = max(w, h) * 1.3f
            val scale = side / max(artwork.width, artwork.height)
            ColorFilter.makeMatrix(saturation(if (reduced) 3.8f else 3.0f)).use { filter ->
                Paint().use { paint ->
                    paint.colorFilter = filter
                    fun layer(period: Long, reverse: Boolean, dx: Float, dy: Float, extra: Boolean = false) {
                        val angle = elapsedMs % period / period.toFloat() * if (reverse) -360f else 360f
                        val canvas = raw.canvas
                        canvas.save()
                        if (extra) canvas.rotate(angle, w / 2f, h / 2f)
                        canvas.translate(-(side - w) / 2 + dx, -(side - h) / 2 + dy)
                        canvas.rotate(angle, side / 2, side / 2)
                        canvas.scale(scale, scale)
                        canvas.drawImage(artwork, 0f, 0f, paint)
                        canvas.restore()
                    }
                    layer(100_000, false, 0f, 0f)
                    layer(70_000, true, -.95f * w, -.7f * h)
                    layer(40_000, true, -.5f * w, .7f * h, true)
                }
            }
            Surface.makeRasterN32Premul(w, h).use { warped ->
                warped.canvas.clear(average)
                raw.makeImageSnapshot().use { image ->
                    image.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP).use { shader ->
                        Paint().use { paint ->
                            paint.shader = shader
                            val points = meshPresets[mesh.coerceIn(0, 4)]
                            val positions = FloatArray(points.size) { points[it] * if (it % 2 == 0) w else h }
                            val texture = FloatArray(72) { index ->
                                if (index % 2 == 0) (index / 2 % 6) / 5f * w else (index / 12) / 5f * h
                            }
                            val indices = ArrayList<Short>(150)
                            repeat(5) { y -> repeat(5) { x ->
                                val p = y * 6 + x
                                listOf(p, p + 1, p + 6, p + 1, p + 7, p + 6).forEach { indices += it.toShort() }
                            } }
                            warped.canvas.drawVertices(VertexMode.TRIANGLES, positions, null, texture, indices.toShortArray(), BlendMode.SRC, paint)
                        }
                    }
                }
                Paint().use { overlay ->
                    overlay.color = if (light) 0x52FFFFFF else 0x38000000
                    warped.canvas.drawRect(Rect.makeWH(w.toFloat(), h.toFloat()), overlay)
                    overlay.color = if (light) 0x12FFFFFF else 0x10000000
                    warped.canvas.drawRect(Rect.makeWH(w.toFloat(), h.toFloat()), overlay)
                }
                Surface.makeRasterN32Premul(w, h).use { blurred ->
                    warped.makeImageSnapshot().use { image ->
                        // Scale softness to the low-resolution crop, retaining broad moving color regions.
                        val sigma = ((minOf(w, h) - 58) * .19f).coerceIn(3f, 12f)
                        ImageFilter.makeBlur(sigma, sigma, FilterTileMode.CLAMP).use { filter ->
                            Paint().use { paint -> paint.imageFilter = filter; blurred.canvas.drawImage(image, 0f, 0f, paint) }
                        }
                    }
                    history?.let { old -> Paint().use { paint -> paint.alpha = 64; blurred.canvas.drawImage(old, 0f, 0f, paint) } }
                    history?.close()
                    history = blurred.makeImageSnapshot()
                    // Same overscan crop as the Android bitmap shader's centered factor scaling.
                    Surface.makeRasterN32Premul((w - 58).coerceAtLeast(1), (h - 58).coerceAtLeast(1)).use { crop ->
                        crop.canvas.drawImage(history!!, -29f, -29f)
                        crop.makeImageSnapshot()
                    }
                }
            }
        }
    }

    override fun close() { history?.close(); artwork.close() }

    private fun saturation(s: Float): ColorMatrix {
        val r = .213f * (1 - s); val g = .715f * (1 - s); val b = .072f * (1 - s)
        return ColorMatrix(floatArrayOf(r + s,g,b,0f,0f, r,g + s,b,0f,0f, r,g,b + s,0f,0f, 0f,0f,0f,1f,0f))
    }
}
