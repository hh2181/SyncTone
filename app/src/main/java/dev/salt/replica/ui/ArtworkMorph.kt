package dev.salt.replica.ui

import androidx.compose.ui.geometry.Rect
import kotlin.math.max
import kotlin.math.min

/** Fits the artwork path controls inside the intermediate player surfaces. */
class ArtworkMorph(private val from: Rect, private val to: Rect, private val surface: Rect, private val viewport: Rect,
                   private val fromRadius: Float, private val toRadius: Float, travel: Float) {
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun bounds(t: Float) = Rect(lerp(surface.left, viewport.left, t), lerp(surface.top, viewport.top, t),
        lerp(surface.right, viewport.right, t), lerp(surface.bottom, viewport.bottom, t))
    private val maxWidth = max(to.width, viewport.width)
    private val maxHeight = max(to.height, viewport.height)
    private val firstWidth = lerp(from.width, maxWidth, 2 / 3f)
    private val firstHeight = lerp(from.height, maxHeight, 2 / 3f)
    private val maxBottom = max(from.bottom, to.bottom)
    private fun fit(centerX: Float, bottom: Float, width: Float, height: Float, bounds: Rect): Rect {
        val limit = min(bounds.bottom, maxBottom).coerceAtLeast(bounds.top)
        val scale = min(1f, min(bounds.width / width, (limit - bounds.top) / height)).coerceAtLeast(0f)
        val w = width * scale
        val h = height * scale
        val x = (centerX - w / 2).coerceIn(bounds.left, max(bounds.left, bounds.right - w))
        val y = (bottom - h).coerceIn(bounds.top, max(bounds.top, limit - h))
        return Rect(x, y, x + w, y + h)
    }
    private val c1 = fit(to.center.x, from.bottom, firstWidth, firstHeight, bounds(1 / 3f))
    private val c2 = fit(to.center.x, to.center.y + travel / 3 + maxHeight / 2, maxWidth, maxHeight, bounds(2 / 3f))
    private val ratio1 = c1.width / firstWidth
    private val ratio2 = c2.width / maxWidth
    private val width1 = lerp(from.width, to.width, 2 / 3f) * ratio1
    private val width2 = to.width * ratio2
    private val height1 = lerp(from.height, to.height, 2 / 3f) * ratio1
    private val height2 = to.height * ratio2
    private val radius1 = lerp(fromRadius, toRadius, 2 / 3f) * ratio1
    private val radius2 = toRadius * ratio2

    /** x, y, width, height, physical corner radius, optical scale. Reuses the caller's array. */
    fun evaluate(progress: Float, out: FloatArray) {
        val t = progress.coerceIn(0f, 1f)
        val q = 1 - t
        val a = q * q * q; val b = 3 * q * q * t; val c = 3 * q * t * t; val d = t * t * t
        fun bez(start: Float, first: Float, second: Float, end: Float) = a * start + b * first + c * second + d * end
        out[2] = bez(from.width, width1, width2, to.width)
        out[3] = bez(from.height, height1, height2, to.height)
        out[0] = bez(from.center.x, c1.center.x, c2.center.x, to.center.x) - out[2] / 2
        out[1] = bez(from.center.y, c1.center.y, c2.center.y, to.center.y) - out[3] / 2
        out[4] = bez(fromRadius, radius1, radius2, toRadius)
        out[5] = lerp(1f, 1.01f, 1 - q * q)
    }
}
