@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package dev.salt.desktop.lyrics

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.*
import dev.salt.replica.lyrics.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.*

private data class Glyph(val image: ImageBitmap, val glow: ImageBitmap?, val x: Float, val y: Float,
                         val center: Float, val cell: Int, val pad: Int)
private class Geometry(val glyphs: List<Glyph>, val starts: FloatArray, val widths: FloatArray, val total: Float) : AutoCloseable {
    override fun close() { glyphs.forEach { it.image.asSkiaBitmap().close(); it.glow?.asSkiaBitmap()?.close() } }
}
private val glyphPreparation = Semaphore(2)
private data class Crop(val left: Int, val top: Int, val width: Int, val height: Int, val center: Float, val cell: Int)
private data class Raster(val image: ImageBitmap, val crops: List<Crop>, val starts: FloatArray,
                          val widths: FloatArray, val total: Float, val radius: Float) : AutoCloseable {
    override fun close() = image.asSkiaBitmap().close()
}

private fun rasterize(cells: List<LyricCell>, layout: TextLayoutResult, density: Density): Raster {
    val image = ImageBitmap(layout.size.width.coerceAtLeast(1), layout.size.height.coerceAtLeast(1))
    val canvas = Canvas(image)
    try {
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, canvas, Size(image.width.toFloat(), image.height.toFloat())) {
            drawText(layout, color = Color.White)
        }
    } finally { canvas.nativeCanvas.close() }
    val starts = FloatArray(cells.size)
    val widths = FloatArray(cells.size)
    val crops = arrayListOf<Crop>()
    val fontPx = with(density) { layout.layoutInput.style.fontSize.toPx() }
    var offset = 0
    var cumulative = 0f
    var visualLine = -1
    cells.forEachIndexed { cellIndex, cell ->
        starts[cellIndex] = cumulative
        val breaks = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(cell.text) }
        var start = breaks.first()
        var end = breaks.next()
        while (end != BreakIterator.DONE) {
            val index = offset + start
            val nextLine = layout.getLineForOffset(index)
            if (nextLine != visualLine) { cumulative += fontPx * .25f; visualLine = nextLine }
            val boxes = (start until end).map { layout.getBoundingBox(offset + it) }
            val left = boxes.minOf { it.left }; val right = boxes.maxOf { it.right }
            val top = boxes.minOf { it.top }; val bottom = boxes.maxOf { it.bottom }
            val advance = (right - left).coerceAtLeast(0f)
            if (cell.text.substring(start, end).isNotBlank() && advance > 0) {
                val l = floor(left).toInt().coerceIn(0, image.width - 1)
                val t = floor(top).toInt().coerceIn(0, image.height - 1)
                val r = ceil(right).toInt().coerceIn(l + 1, image.width)
                val b = ceil(bottom).toInt().coerceIn(t + 1, image.height)
                crops += Crop(l, t, r - l, b - t, cumulative + advance / 2, cellIndex)
            }
            cumulative += advance
            start = end; end = breaks.next()
        }
        widths[cellIndex] = cumulative - starts[cellIndex]
        offset += cell.text.length
    }
    return Raster(image, crops, starts, widths, cumulative, fontPx * .32f)
}

private fun prepare(raster: Raster, glow: Boolean, checkCancelled: () -> Unit): Geometry {
    val pad = ceil(raster.radius * 3 + 2).toInt()
    val glyphs = arrayListOf<Glyph>()
    val cropPaint = Paint()
    val filters = if (glow) listOf(raster.radius, raster.radius * .4f).map {
        org.jetbrains.skia.ImageFilter.makeBlur(it, it, org.jetbrains.skia.FilterTileMode.DECAL)
    } else emptyList()
    try {
        org.jetbrains.skia.Paint().use { glowPaint ->
            for (crop in raster.crops) {
                checkCancelled()
                val image = ImageBitmap(crop.width, crop.height)
                var halo: ImageBitmap? = null
                try {
                    val canvas = Canvas(image)
                    try { canvas.drawImageRect(raster.image, IntOffset(crop.left, crop.top), IntSize(crop.width, crop.height),
                        IntOffset.Zero, IntSize(crop.width, crop.height), cropPaint) }
                    finally { canvas.nativeCanvas.close() }
                    halo = if (glow) org.jetbrains.skia.Surface.makeRasterN32Premul(crop.width + pad * 2, crop.height + pad * 2).use { surface ->
                        org.jetbrains.skia.Image.makeFromBitmap(image.asSkiaBitmap()).use { source ->
                            for (filter in filters) {
                                glowPaint.imageFilter = filter
                                surface.canvas.drawImage(source, pad.toFloat(), pad.toFloat(), glowPaint)
                            }
                        }
                        surface.makeImageSnapshot().use { it.toComposeImageBitmap() }
                    } else null
                    glyphs += Glyph(image, halo, crop.left.toFloat(), crop.top.toFloat(), crop.center, crop.cell, pad)
                } catch (e: Throwable) { image.asSkiaBitmap().close(); halo?.asSkiaBitmap()?.close(); throw e }
            }
        }
        return Geometry(glyphs, raster.starts, raster.widths, raster.total)
    } catch (e: Throwable) {
        glyphs.forEach { it.image.asSkiaBitmap().close(); it.glow?.asSkiaBitmap()?.close() }
        throw e
    } finally { cropPaint.skiaPaint.close(); filters.forEach { it.close() } }
}

@Composable
internal fun KaraokeText(cells: List<LyricCell>, position: State<Long>, active: Boolean, played: Boolean,
                         style: TextStyle, settings: DesktopLyricsSettings, prepareGlyphs: Boolean,
                         modifier: Modifier = Modifier, liftFraction: Float = .1f) {
    val text = remember(cells) { cells.joinToString("") { it.text }.ifBlank { "♪" } }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val envelope = remember(cells) { LongNoteEnvelope(cells) }
    val highlight = animateFloatAsState(if (active) 1f else 0f,
        tween(lyricDuration(700, settings.speed), easing = lyricEasing(settings.curve)))
    val baseline = animateFloatAsState(if (played) 0f else 1f,
        tween(lyricDuration(700, settings.speed), easing = lyricEasing(settings.curve)))
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val widthPx = with(density) { maxWidth.roundToPx().coerceAtLeast(1) }
        val layout = remember(text, style, widthPx, density) {
            measurer.measure(AnnotatedString(text), style.copy(color = Color.White),
                constraints = Constraints(minWidth = widthPx, maxWidth = widthPx))
        }
        val timed = cells.size > 1 && cells.any { it.end > it.start }
        // Layout is already remembered above. A line owns only its current native glyph geometry.
        val resources = remember(layout, cells, prepareGlyphs && timed, settings.glow) { OwnedResource<Geometry>() }
        DisposableEffect(resources) { onDispose { resources.close() } }
        val prepared by key(resources) { produceState<Geometry?>(null, resources) {
            if (prepareGlyphs && timed) {
                glyphPreparation.withPermit {
                    coroutineContext.ensureActive()
                    // Paragraph painting shares the UI layout object: keep that access on
                    // the UI thread, then prepare independent glyph pixels on the worker.
                    rasterize(cells, layout, density).use { raster ->
                        value = withContext(Dispatchers.Default) {
                            val geometry = prepare(raster, settings.glow) { coroutineContext.ensureActive() }
                            resources.install(geometry)
                        }
                    }
                }
            }
        } }
        val tailVisible by remember(cells, played, position) {
            derivedStateOf { played && position.value < (cells.lastOrNull()?.end ?: 0) + 800 }
        }
        val geometry = prepared.takeIf { active || tailVisible }
        val tint = remember(style.color) { ColorFilter.tint(style.color) }
        val lift = with(density) { style.fontSize.toPx() * liftFraction }
        Canvas(Modifier.fillMaxWidth().height(with(density) { (layout.size.height + lift).toDp() })) {
            if (geometry == null) {
                drawText(layout, topLeft = Offset(0f, lift * baseline.value),
                    color = style.color.copy(alpha = if (active && timed) .3f else .3f + .7f * highlight.value))
            } else {
                val now = position.value
                val index = cells.indexOfLast { it.start <= now }.coerceAtLeast(0)
                val cell = cells[index]
                val progress = ((now - cell.start).toFloat() / (cell.end - cell.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                val cursor = geometry.starts[index] + geometry.widths[index] * progress
                val feather = style.fontSize.toPx() * 3
                val half = feather / 2
                val close = (1 - (geometry.total - cursor) / half).coerceIn(0f, 1f).let { it * it }
                geometry.glyphs.forEach { glyph ->
                    val edge = ((glyph.center - cursor + half) / feather).coerceIn(0f, 1f)
                    val strength = when {
                        edge <= 0 -> 1f
                        edge >= 1 -> 0f
                        else -> cos(edge * PI.toFloat()) * (1 - close) * .5f + .5f + close * .5f
                    }
                    val y = glyph.y + lift * (1 - strength)
                    val alpha = envelope.alpha(glyph.cell, now) * highlight.value
                    glyph.glow?.takeIf { alpha > 0f }?.let {
                        drawImage(it, Offset(glyph.x - glyph.pad, y - glyph.pad), alpha = alpha, colorFilter = tint)
                    }
                    drawImage(glyph.image, Offset(glyph.x, y), alpha = .3f + strength * .7f * highlight.value, colorFilter = tint)
                }
            }
        }
    }
}
