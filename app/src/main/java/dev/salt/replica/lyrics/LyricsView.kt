package dev.salt.replica.lyrics

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Paint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import dev.salt.replica.playback.PlayerStore
import dev.salt.replica.ui.ReplicaSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.*

private data class Glyph(val image: ImageBitmap, val glow: ImageBitmap, val x: Float, val y: Float,
                         val center: Float, val cell: Int, val pad: Int)
private class LineGeometry(val glyphs: List<Glyph>, val total: Float,
                           val cellStarts: FloatArray, val cellWidths: FloatArray)
private data class GlyphCrop(val left: Int, val top: Int, val right: Int, val bottom: Int,
                             val center: Float, val cell: Int)
private class LineRaster(val bitmap: Bitmap, val crops: List<GlyphCrop>, val total: Float,
                         val cellStarts: FloatArray, val cellWidths: FloatArray, val glowRadius: Float)

@OptIn(ExperimentalTextApi::class)
private fun rasterizeLine(cells: List<LyricCell>, result: TextLayoutResult, density: Density): LineRaster {
    val w = result.size.width.coerceAtLeast(1)
    val h = result.size.height.coerceAtLeast(1)
    val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(full.asImageBitmap()), Size(w.toFloat(), h.toFloat())) {
        drawText(result, color = Color.White)
    }
    val cellStarts = FloatArray(cells.size)
    val cellWidths = FloatArray(cells.size)
    val crops = ArrayList<GlyphCrop>()
    var offset = 0
    var cumulative = 0f
    var visualLine = -1
    val fontPx = with(density) { result.layoutInput.style.fontSize.toPx() }
    val glowRadius = fontPx * .32f
    cells.forEachIndexed { cellIndex, cell ->
        cellStarts[cellIndex] = cumulative
        var i = 0
        while (i < cell.text.length) {
            val index = offset + i
            val charCount = Character.charCount(cell.text.codePointAt(i))
            val nextVisualLine = result.getLineForOffset(index)
            if (nextVisualLine != visualLine) {
                cumulative += fontPx * .25f
                visualLine = nextVisualLine
            }
            val box = result.getBoundingBox(index)
            val advance = box.width.coerceAtLeast(0f)
            if (!cell.text[i].isWhitespace() && advance > 0f) {
                val l = floor(box.left).toInt().coerceIn(0, w - 1)
                val t = floor(box.top).toInt().coerceIn(0, h - 1)
                val r = ceil(box.right).toInt().coerceIn(l + 1, w)
                val b = ceil(box.bottom).toInt().coerceIn(t + 1, h)
                crops += GlyphCrop(l, t, r, b, cumulative + advance / 2, cellIndex)
            }
            cumulative += advance
            i += charCount
        }
        cellWidths[cellIndex] = cumulative - cellStarts[cellIndex]
        offset += cell.text.length
    }
    return LineRaster(full, crops, cumulative, cellStarts, cellWidths, glowRadius)
}

private fun buildGeometry(raster: LineRaster): LineGeometry {
    val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    val pad = ceil(raster.glowRadius * 3 + 2).toInt()
    val glyphs = raster.crops.map { crop ->
        val image = Bitmap.createBitmap(raster.bitmap, crop.left, crop.top, crop.right - crop.left, crop.bottom - crop.top)
        val glow = Bitmap.createBitmap(image.width + pad * 2, image.height + pad * 2, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(glow)
        glowPaint.maskFilter = BlurMaskFilter(raster.glowRadius, BlurMaskFilter.Blur.NORMAL)
        canvas.drawBitmap(image, pad.toFloat(), pad.toFloat(), glowPaint)
        glowPaint.maskFilter = BlurMaskFilter(raster.glowRadius * .4f, BlurMaskFilter.Blur.NORMAL)
        canvas.drawBitmap(image, pad.toFloat(), pad.toFloat(), glowPaint)
        Glyph(image.asImageBitmap(), glow.asImageBitmap(), crop.left.toFloat(), crop.top.toFloat(), crop.center, crop.cell, pad)
    }
    return LineGeometry(glyphs, raster.total, raster.cellStarts, raster.cellWidths)
}

@Composable
fun LyricsView(store: PlayerStore, settings: ReplicaSettings, modifier: Modifier = Modifier,
               compact: Boolean = false, componentOffset: Dp = 0.dp,
               onLongPress: () -> Unit = {}, onTap: () -> Unit = {}, visible: Boolean = true,
               contentColor: Color = Color.White) {
    val lyrics = store.track?.lyrics ?: Lyrics.Empty
    val position: State<Long> = remember(store, visible) {
        if (visible) store.framePosition else mutableLongStateOf(store.framePosition.longValue)
    }
    val active by remember(lyrics, position) { derivedStateOf { lyrics.indexAt(position.value) } }
    if (compact) {
        BoxWithConstraints(modifier) {
            val typography = compactLyricsTypography(maxWidth.value)
            lyrics.lines.getOrNull(active)?.let { line ->
                KaraokeLine(line, position, true, false, settings,
                    Modifier.fillMaxWidth().padding(top = 8.dp), true, onLongPress, onTap,
                    contentColor = contentColor, compactTypography = typography)
            }
        }
        return
    }
    val state = rememberLazyListState()
    var browsing by remember { mutableStateOf(false) }
    var motion by remember(lyrics) { mutableStateOf<StaggerMotion?>(null) }
    val motionTime = remember { mutableLongStateOf(0) }
    var previousIndex by remember(lyrics) { mutableIntStateOf(-1) }
    val density = LocalDensity.current
    val rowHeight = remember(lyrics) { mutableStateMapOf<Int, Int>() }
    var seekRequest by remember(lyrics) { mutableStateOf<Pair<Int, Int>?>(null) }
    var handledSeek by remember(lyrics) { mutableIntStateOf(0) }

    // Drag state stays separate from programmatic list movement.
    val dragged by state.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged, browsing) {
        if (dragged) { browsing = true; motion = null }
        else if (browsing) { delay(3000); browsing = false }
    }
    LaunchedEffect(motion, visible) {
        if (!visible) return@LaunchedEffect
        val current = motion ?: return@LaunchedEffect
        do { withFrameNanos { motionTime.longValue = it } } while (!current.settled(motionTime.longValue))
    }
    BoxWithConstraints(modifier) {
        val viewport = with(density) { maxHeight.toPx() }
        val componentPx = with(density) { componentOffset.toPx() }
        val padTop = viewport / 2 + max(0f, componentPx)
        val padBottom = viewport / 2 + max(0f, -componentPx)
        val anchor = viewport / 2 + componentPx
        LaunchedEffect(lyrics, active, browsing, viewport, componentPx, visible, seekRequest) {
            if (!visible) { motion = null; previousIndex = -1; return@LaunchedEffect }
            if (browsing || active < 0 || lyrics.lines.isEmpty()) return@LaunchedEffect
            val request = seekRequest
            val seeking = request != null && request.second != handledSeek
            if (seeking && request!!.first != active) return@LaunchedEffect
            if (seeking) handledSeek = request!!.second
            val targetHeight = rowHeight[active] ?: with(density) { (if (compact) 40 else 88).dp.roundToPx() }
            val targetOffset = (padTop + targetHeight / 2f - anchor).roundToInt()
            val old = previousIndex
            previousIndex = active
            val visible = state.layoutInfo.visibleItemsInfo
            val target = visible.firstOrNull { it.index == active }
            val stagger = settings.stagger && target != null &&
                (seeking || allowsStagger(lyrics.lines, active, old))
            if (stagger) {
                val shift = target!!.offset + target.size / 2f + state.layoutInfo.beforeContentPadding - anchor
                val now = withFrameNanos { it }
                val carried = visible.associate { it.index to (motion?.offset(it.index, now) ?: 0f) }
                motionTime.longValue = now
                motion = StaggerMotion(shift, visible.firstOrNull()?.index ?: active, now, carried,
                    settings.lyricSpeed, settings.lyricCurve)
                // Both mutations are consumed in the next remeasure, before its first draw.
                state.requestScrollToItem(active, targetOffset)
            } else if (!seeking && (old < 0 || abs(active - old) > 10)) {
                motion = null
                state.scrollToItem(active, targetOffset)
            } else {
                motion = null
                if (target != null) state.animateScrollBy(
                    target.offset + target.size / 2f + state.layoutInfo.beforeContentPadding - anchor,
                    tween(lyricDuration(700, settings.lyricSpeed), easing = lyricEasing(settings.lyricCurve)))
                else state.animateScrollToItem(active, targetOffset)
            }
        }
        if (lyrics.lines.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text("暂无歌词", color = contentColor.copy(alpha = .4f), fontSize = 18.sp)
            }
        } else LazyColumn(
            state = state, modifier = Modifier.fillMaxSize().graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
            }.drawWithContent {
                drawContent()
                drawRect(Brush.verticalGradient(0f to Color.Transparent, .09f to Color.Black,
                    .86f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
            }, contentPadding = PaddingValues(top = with(density) { padTop.toDp() }, bottom = with(density) { padBottom.toDp() })
        ) {
            itemsIndexed(lyrics.lines, key = { i, line -> "${line.start}-$i" }) { i, line ->
                val distance = abs(i - active)
                val emphasis = animateFloatAsState(when (distance) {
                    0 -> 1f
                    1 -> .55f
                    2 -> .36f
                    3 -> .26f
                    else -> .20f
                }, tween(lyricDuration(700, settings.lyricSpeed), easing = lyricEasing(settings.lyricCurve)),
                    label = "lyricEmphasis")
                val scale = animateFloatAsState(if (i == active) 1f else .95f,
                    tween(lyricDuration(if (i < active) 500 else 600, settings.lyricSpeed),
                        delayMillis = if (i < active) lyricDuration(100, settings.lyricSpeed) else 0,
                        easing = lyricEasing(settings.lyricCurve)), label = "lyricScale")
                val blurDp = if (compact || !settings.blur || browsing || distance == 0) 0f else min(6f, distance * 1.6f)
                val blur = remember(blurDp, density.density) {
                    if (blurDp > 0 && android.os.Build.VERSION.SDK_INT >= 31)
                        BlurEffect(blurDp * density.density, blurDp * density.density, TileMode.Decal) else null
                }
                KaraokeLine(line, position, i == active, i < active, settings,
                    Modifier.fillMaxWidth().zIndex(if (i == active) 1f else 0f).onSizeChanged {
                        val previousHeight = rowHeight.put(i, it.height)
                        // Keep the current row centered while its secondary lines change height.
                        if (previousHeight != null && previousHeight != it.height &&
                            i == active && previousIndex == active && !browsing) {
                            state.requestScrollToItem(i, (padTop + it.height / 2f - anchor).roundToInt())
                        }
                    }.graphicsLayer {
                        translationY = motion?.offset(i, motionTime.longValue) ?: 0f
                        alpha = emphasis.value
                        scaleX = scale.value; scaleY = scale.value
                        transformOrigin = TransformOrigin(if (settings.center) .5f else 0f, .5f)
                        renderEffect = blur
                    }.padding(vertical = if (compact) 4.dp else 12.dp), compact, onLongPress,
                    onClick = {
                        seekRequest = i to ((seekRequest?.second ?: 0) + 1)
                        browsing = false
                        store.seek(line.start)
                    }, prepareGlyphs = visible && distance <= 1, contentColor = contentColor)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalTextApi::class)
@Composable
private fun KaraokeLine(line: LyricLine, position: State<Long>, active: Boolean, played: Boolean, settings: ReplicaSettings,
                        modifier: Modifier, compact: Boolean, onLongPress: () -> Unit, onClick: () -> Unit,
                        prepareGlyphs: Boolean = false,
                        contentColor: Color = Color.White,
                        compactTypography: CompactLyricsTypography? = null) {
    val size = if (compact) compactTypography?.originalSp ?: 16f else settings.fontSize
    val highlight = animateFloatAsState(if (active || compact) 1f else 0f,
        tween(lyricDuration(700, settings.lyricSpeed), easing = lyricEasing(settings.lyricCurve)), label = "lyricHighlight")
    val baseline = animateFloatAsState(if (played || compact) 0f else 1f,
        tween(lyricDuration(700, settings.lyricSpeed), easing = lyricEasing(settings.lyricCurve)), label = "lyricBaseline")
    val style = TextStyle(fontSize = size.sp, fontWeight = FontWeight(if (compact) 400 else settings.fontWeight),
        fontFamily = FontFamily.SansSerif,
        color = contentColor, lineHeight = (size * 1.4f).sp, textAlign = if (settings.center) TextAlign.Center else TextAlign.Start,
        platformStyle = PlatformTextStyle(includeFontPadding = false))
    val interaction = remember { MutableInteractionSource() }
    Column(modifier.combinedClickable(interactionSource = interaction, indication = null,
        onClick = onClick, onLongClick = onLongPress)) {
        KaraokeText(line.cells, line.text, position, active, played, style, highlight, baseline,
            prepareGlyphs && !compact && line.cells.size > 1, if (compact) 0f else .1f, settings.glow,
            maxLines = if (compact) 2 else Int.MAX_VALUE)
        AnimatedVisibility(
            visible = settings.translation && line.translation.isNotEmpty(),
            enter = fadeIn(tween(lyricDuration(300, settings.lyricSpeed), easing = lyricEasing(settings.lyricCurve))) +
                expandVertically(tween(lyricDuration(300, settings.lyricSpeed), easing = lyricEasing(settings.lyricCurve)), expandFrom = Alignment.Top),
            exit = fadeOut(tween(lyricDuration(300, settings.lyricSpeed), easing = lyricEasing(settings.lyricCurve))) +
                shrinkVertically(tween(lyricDuration(300, settings.lyricSpeed), easing = lyricEasing(settings.lyricCurve)), shrinkTowards = Alignment.Top),
            label = "lyricTranslation"
        ) {
            val secondarySize = if (compact) compactTypography?.secondarySp ?: 14f else size * .8f
            val subStyle = style.copy(fontSize = secondarySize.sp,
                lineHeight = (if (compact) secondarySize * 1.4f else size * 1.04f).sp)
            val subModifier = Modifier.fillMaxWidth().padding(top = if (compact) 0.dp else 4.dp)
            if (compact) {
                val lines = line.subLines.map { it.text }.ifEmpty { line.translation.lines() }
                Column(subModifier) {
                    lines.filter { it.isNotBlank() }.take(2).forEach { text ->
                        Text(text, Modifier.fillMaxWidth(), color = contentColor, style = subStyle,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            } else if (line.subLines.none { it.wordTimed }) {
                Text(line.translation, subModifier, color = contentColor.copy(alpha = if (compact) 1f else .30f), style = subStyle)
            } else Column(subModifier) {
                line.subLines.forEach { sub ->
                    if (sub.wordTimed) KaraokeText(sub.cells, sub.text, position, active, played,
                        subStyle, highlight, baseline, prepareGlyphs, .05f, settings.glow)
                    else Text(sub.text, Modifier.fillMaxWidth(), color = contentColor.copy(alpha = .30f), style = subStyle)
                }
            }
        }
    }
}

@OptIn(ExperimentalTextApi::class)
@Composable
private fun KaraokeText(cells: List<LyricCell>, text: String, position: State<Long>, active: Boolean,
                        played: Boolean, style: TextStyle, highlight: State<Float>, baseline: State<Float>,
                        prepareGlyphs: Boolean, liftFraction: Float, glow: Boolean, maxLines: Int = Int.MAX_VALUE) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val longNote = remember(cells) { LongNoteEnvelope(cells) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widthPx = with(density) { maxWidth.roundToPx() }
        val layout = remember(text, style, widthPx, density, maxLines) {
            measurer.measure(AnnotatedString(text), style.copy(color = Color.White), constraints = Constraints(maxWidth = widthPx),
                maxLines = maxLines, overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis)
        }
        // Compose's Android text painter shares scratch state; only bitmap processing is concurrent.
        val raster = if (prepareGlyphs)
            remember(layout, cells) { rasterizeLine(cells, layout, density) } else null
        val prepared by key(raster) {
            produceState<LineGeometry?>(null) {
                value = if (raster != null) withContext(Dispatchers.Default) { buildGeometry(raster) } else null
            }
        }
        val tailVisible by remember(cells, played) {
            derivedStateOf { played && position.value < (cells.lastOrNull()?.end ?: 0) + 800 }
        }
        val geometry = prepared.takeIf { active || tailVisible }
        val tint = remember(style.color) { ColorFilter.tint(style.color) }
        val floatUp = with(density) { (style.fontSize.toPx() * liftFraction).roundToInt().toFloat() }
        Canvas(Modifier.fillMaxWidth().height(with(density) { (layout.size.height + floatUp).toDp() })) {
            val highlightValue = highlight.value
            val baselineValue = baseline.value
            if (geometry == null) {
                val restingAlpha = if (active && cells.size > 1 && liftFraction > 0) .30f else .30f + .70f * highlightValue
                drawText(layout, topLeft = Offset(0f, floatUp * baselineValue),
                    color = style.color.copy(alpha = restingAlpha))
            } else {
                val now = position.value
                val cellIndex = cells.indexOfLast { it.start <= now }.coerceAtLeast(0)
                val cell = cells[cellIndex]
                val p = ((now - cell.start).toFloat() / maxOf(1L, cell.end - cell.start)).coerceIn(0f, 1f)
                val cursor = geometry.cellStarts[cellIndex] + geometry.cellWidths[cellIndex] * p
                val feather = style.fontSize.toPx() * 3
                val half = feather / 2
                val close = (1 - (geometry.total - cursor) / half).coerceIn(0f, 1f).let { it * it }
                geometry.glyphs.forEach { glyph ->
                    val edge = ((glyph.center - cursor + half) / feather).coerceIn(0f, 1f)
                    val strength = if (edge <= 0) 1f else if (edge >= 1) 0f else
                    cos(edge * PI.toFloat()) * (1 - close) * .5f + .5f + close * .5f
                    val lift = floatUp * (1 - strength)
                    val glowAlpha = longNote.alpha(glyph.cell, now)
                    if (glow && glowAlpha > 0f) {
                        drawImage(glyph.glow, Offset(glyph.x - glyph.pad, glyph.y - glyph.pad + lift),
                            alpha = glowAlpha * highlightValue, colorFilter = tint)
                    }
                    drawImage(glyph.image, Offset(glyph.x, glyph.y + lift),
                        alpha = .30f + strength * .70f * highlightValue, colorFilter = tint)
                }
            }
        }
    }
}
