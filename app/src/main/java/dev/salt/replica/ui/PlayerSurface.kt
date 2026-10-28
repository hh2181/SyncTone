package dev.salt.replica.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/** Clips the background, content and shared artwork to one interpolated surface. */
class PlayerSurface(private val mini: Rect, private val viewport: Rect, private val miniRadius: Float) {
    fun bounds(progress: Float): Rect {
        val t = progress.coerceIn(0f, 1f)
        return Rect(mini.left * (1 - t), mini.top * (1 - t),
            mini.right + (viewport.right - mini.right) * t,
            mini.bottom + (viewport.bottom - mini.bottom) * t)
    }

    fun shape(progress: Float, offsetY: Float = 0f): Shape {
        val rect = bounds(progress).translate(0f, -offsetY)
        val radius = miniRadius * (1 - progress.coerceIn(0f, 1f))
        return object : Shape {
            override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
                Outline.Rounded(RoundRect(rect, CornerRadius(radius)))
        }
    }
}
