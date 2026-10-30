package dev.salt.replica.equalizer

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.*
import java.util.Locale
import kotlin.math.*

internal val EqShape = RoundedCornerShape(18.dp)
internal val EqEase = CubicBezierEasing(.2f, .8f, .2f, 1f)
internal fun eqGain(db: Double) = String.format(Locale.ROOT, if (db > .049) "+%.1f" else "%.1f", if (abs(db) < .049) 0.0 else db)
internal fun eqFrequency(hz: Double): String = if (hz >= 1000) (if (hz % 1000 < .01) "${(hz / 1000).toInt()}k" else String.format(Locale.ROOT, "%.1fk", hz / 1000)) else
    String.format(Locale.ROOT, if (hz % 1 == 0.0) "%.0f" else if (hz == 31.25) "%.2f" else "%.1f", hz)

private data class EqAxisBounds(val minimum: Float, val maximum: Float)
private fun eqAxisBounds(values: List<Float>, preamp: Float): EqAxisBounds {
    val low = minOf(0f, preamp, values.minOrNull() ?: 0f)
    val high = maxOf(0f, preamp, values.maxOrNull() ?: 0f)
    // A minimum span avoids magnifying tiny fitting ripples. Zero remains a
    // reference, but does not force half the chart to be unused after headroom.
    val span = max(12f, (high - low) * 1.24f)
    val step = if (span <= 18f) 3f else if (span <= 36f) 6f else 12f
    val middle = (low + high) / 2
    return EqAxisBounds(floor((middle - span / 2) / step) * step, ceil((middle + span / 2) / step) * step)
}

@Composable
internal fun EqVerticalBand(index: Int, gain: Int, selected: Boolean, enabled: Boolean, desktop: Boolean,
    onSelect: () -> Unit, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    var dragging by remember { mutableStateOf(false) }
    val animated by animateFloatAsState(gain.toFloat(), if (dragging) snap() else tween(260, easing = EqEase), label = "eq-band")
    val currentChange by rememberUpdatedState(onChange)
    val thumb = if (desktop) 12.dp else 11.dp
    val description = "${eqFrequency(EqFrequencies[index])} Hz 增益"
    Column(modifier.background(if (selected) colors.primary.copy(alpha = .08f) else Color.Transparent,
        RoundedCornerShape(14.dp)).padding(top = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(40.dp).clickable(onClick = onSelect), Alignment.Center) {
            Text(eqGain(gain / 10.0), fontSize = 12.sp, color = if (selected) colors.primary else colors.onSurface)
        }
        Canvas(Modifier.width(44.dp).height(if (desktop) 170.dp else 112.dp).alpha(if (enabled) 1f else .4f)
            .semantics {
                contentDescription = description
                stateDescription = "${eqGain(gain / 10.0)} 分贝"
                progressBarRangeInfo = ProgressBarRangeInfo(gain / 10f, -12f..12f, 239)
                if (enabled) setProgress { currentChange((it * 10).roundToInt().coerceIn(-120, 120)); true } else disabled()
            }.onPreviewKeyEvent { event ->
                if (!enabled || event.type != KeyEventType.KeyDown) false else {
                    val step = if (event.isShiftPressed) 5 else 1
                    val next = when (event.key) {
                        Key.DirectionUp, Key.DirectionRight -> gain + step
                        Key.DirectionDown, Key.DirectionLeft -> gain - step
                        Key.MoveHome -> -120
                        Key.MoveEnd -> 120
                        else -> null
                    }
                    if (next == null) false else { currentChange(next.coerceIn(-120, 120)); true }
                }
            }.focusable(enabled)
            .pointerInput(enabled) {
                if (enabled) detectTapGestures { point ->
                    val radius = thumb.toPx(); val travel = size.height - radius * 2
                    currentChange(((1 - ((point.y - radius) / travel).coerceIn(0f, 1f)) * 240 - 120).roundToInt())
                }
            }.pointerInput(enabled) {
                if (enabled) detectDragGestures(
                    onDragStart = { point -> dragging = true; onSelect()
                        val radius = thumb.toPx()
                        currentChange(((1 - ((point.y - radius) / (size.height - 2 * radius)).coerceIn(0f, 1f)) * 240 - 120).roundToInt()) },
                    onDragEnd = { dragging = false }, onDragCancel = { dragging = false },
                    onDrag = { change, _ -> change.consume()
                        val radius = thumb.toPx()
                        currentChange(((1 - ((change.position.y - radius) / (size.height - 2 * radius)).coerceIn(0f, 1f)) * 240 - 120).roundToInt()) })
            }) {
            val center = size.width / 2; val radius = thumb.toPx()
            val trackWidth = (if (desktop) 16.dp else 8.dp).toPx()
            val y = radius + (size.height - 2 * radius) * (120 - animated.coerceIn(-120f, 120f)) / 240
            drawRoundRect(colors.surfaceVariant, Offset(center - trackWidth / 2, radius),
                Size(trackWidth, size.height - 2 * radius), androidx.compose.ui.geometry.CornerRadius(trackWidth / 2))
            drawLine(colors.onSurfaceVariant.copy(alpha = .2f), Offset(center - trackWidth / 2 - 4.dp.toPx(), size.height / 2),
                Offset(center + trackWidth / 2 + 4.dp.toPx(), size.height / 2), 1.dp.toPx())
            if (abs(y - size.height / 2) > .1f) drawLine(colors.primary, Offset(center, size.height / 2), Offset(center, y), trackWidth, StrokeCap.Round)
            drawCircle(colors.primary, radius, Offset(center, y))
            drawCircle(colors.surface, radius - (if (desktop) 5.dp else 4.dp).toPx(), Offset(center, y))
        }
        Box(Modifier.fillMaxWidth().height(42.dp).clickable(onClick = onSelect), Alignment.Center) {
            Text(eqFrequency(EqFrequencies[index]), fontSize = 11.sp, color = if (selected) colors.primary else colors.onSurfaceVariant)
        }
    }
}

@Composable
internal fun EqCurve(model: EqModel, selected: Int, wide: Boolean, onSelect: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val samples = remember(model.sampleRate) {
        ((0..1024).map { 20 * (model.maxFrequency / 20).pow(it / 1024.0) } + EqFrequencies.filter { it <= model.maxFrequency }).distinct().sorted()
    }
    val target = remember(model) { samples.map { model.responseDb(it).toFloat() } }
    var from by remember(model.sampleRate) { mutableStateOf(target) }; var to by remember(model.sampleRate) { mutableStateOf(target) }
    val targetAxis = remember(target, model.preampDb) { eqAxisBounds(target, model.preampDb.toFloat()) }
    var fromAxis by remember(model.sampleRate) { mutableStateOf(targetAxis) }; var toAxis by remember(model.sampleRate) { mutableStateOf(targetAxis) }
    var fromPreamp by remember(model.sampleRate) { mutableFloatStateOf(model.preampDb.toFloat()) }
    var toPreamp by remember(model.sampleRate) { mutableFloatStateOf(model.preampDb.toFloat()) }
    val progress = remember(model.sampleRate) { Animatable(1f) }
    LaunchedEffect(target) {
        val previous = progress.value
        from = if (from.size == target.size) from.indices.map { from[it] + (to[it] - from[it]) * previous } else target
        fromAxis = EqAxisBounds(fromAxis.minimum + (toAxis.minimum - fromAxis.minimum) * previous,
            fromAxis.maximum + (toAxis.maximum - fromAxis.maximum) * previous)
        toAxis = targetAxis
        fromPreamp += (toPreamp - fromPreamp) * previous
        toPreamp = model.preampDb.toFloat()
        to = target; progress.snapTo(0f); progress.animateTo(1f, tween(300, easing = EqEase))
    }
    var probe by remember(model.sampleRate) { mutableStateOf<Double?>(null) }
    Box {
        Canvas(Modifier.fillMaxWidth().height(if (wide) 260.dp else 206.dp)
            .semantics { contentDescription = "EQ 输出响应，横轴赫兹，纵轴 ${targetAxis.minimum.toInt()} 至 ${targetAxis.maximum.toInt()} 分贝，含前级 ${eqGain(model.preampDb)} 分贝，按 ${model.sampleRate} Hz 实际处理模型计算" }
            .pointerInput(model, samples) { detectTapGestures { point ->
                val left = 44.dp.toPx(); val right = size.width - 12.dp.toPx()
                val frequency = 20 * (model.maxFrequency / 20).pow(((point.x - left) / (right - left)).coerceIn(0f, 1f).toDouble())
                probe = frequency
                onSelect(EqFrequencies.take(model.activeBands).indices.minBy { abs(ln(EqFrequencies[it] / frequency)) })
            } }) {
            val left = 44.dp.toPx(); val right = size.width - 12.dp.toPx(); val top = 23.dp.toPx(); val bottom = size.height - 34.dp.toPx()
            val t = progress.value
            val minimum = fromAxis.minimum + (toAxis.minimum - fromAxis.minimum) * t
            val maximum = fromAxis.maximum + (toAxis.maximum - fromAxis.maximum) * t
            val preamp = fromPreamp + (toPreamp - fromPreamp) * t
            fun x(f: Double) = left + (ln(f / 20) / ln(model.maxFrequency / 20)).toFloat() * (right - left)
            fun y(db: Float) = top + (maximum - db) / (maximum - minimum) * (bottom - top)
            fun label(text: String, position: Offset, anchor: Int = 0, centerVertically: Boolean = false) {
                val measured = measurer.measure(text, TextStyle(color = colors.onSurfaceVariant, fontSize = 11.sp))
                drawText(measured, topLeft = Offset(position.x - when (anchor) { 1 -> measured.size.width / 2f; 2 -> measured.size.width.toFloat(); else -> 0f },
                    position.y - if (centerVertically) measured.size.height / 2f else 0f))
            }
            val tickStep = if (maximum - minimum <= 18.01f) 3f else if (maximum - minimum <= 36.01f) 6f else 12f
            var tick = ceil(minimum / tickStep) * tickStep
            while (tick <= maximum + .01f) {
                drawLine(colors.onSurfaceVariant.copy(alpha = if (tick == 0f) .3f else .12f), Offset(left, y(tick)), Offset(right, y(tick)), .7.dp.toPx())
                label(if (tick > 0) "+${tick.toInt()}" else "${tick.toInt()}", Offset(left - 8.dp.toPx(), y(tick)), 2, centerVertically = true)
                tick += tickStep
            }
            val ticks = (if (wide) listOf(20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0) else listOf(20.0, 200.0, 2000.0))
                .filter { it < model.maxFrequency * .9 } + model.maxFrequency
            ticks.forEachIndexed { i, f ->
                drawLine(colors.onSurfaceVariant.copy(alpha = .10f), Offset(x(f), top), Offset(x(f), bottom), .7.dp.toPx())
                label(eqFrequency(f), Offset(x(f), bottom + 7.dp.toPx()), if (i == 0) 0 else if (i == ticks.lastIndex) 2 else 1)
            }
            label("dB", Offset(8.dp.toPx(), 0f)); label("Hz", Offset(right, size.height - 13.dp.toPx()), 2)
            val values = from.indices.map { from[it] + (to[it] - from[it]) * t }
            val path = Path().apply { values.forEachIndexed { i, v -> if (i == 0) moveTo(x(samples[i]), y(v)) else lineTo(x(samples[i]), y(v)) } }
            clipRect(left, top, right, bottom) {
                val fill = Path().apply { addPath(path); lineTo(right, y(preamp)); lineTo(left, y(preamp)); close() }
                drawPath(fill, colors.primary.copy(alpha = .07f))
                drawPath(path, colors.primary, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                EqFrequencies.take(model.activeBands).forEachIndexed { i, f ->
                    val index = samples.indexOf(f)
                    val value = values.getOrElse(index) { model.responseDb(f).toFloat() }
                    if (i == selected) drawLine(colors.primary.copy(alpha = .25f), Offset(x(f), top), Offset(x(f), bottom), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
                    drawCircle(if (i == selected) colors.surface else colors.primary, if (i == selected) 5.dp.toPx() else 3.dp.toPx(), Offset(x(f), y(value)))
                    drawCircle(if (i == selected) colors.primary else colors.surface, if (i == selected) 3.5.dp.toPx() else 1.5.dp.toPx(), Offset(x(f), y(value)))
                }
            }
        }
        probe?.let { f ->
            Text("${eqFrequency(f)} Hz  ${eqGain(model.responseDb(f))} dB", Modifier.align(Alignment.TopEnd)
                .background(colors.surfaceVariant, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 11.sp)
        }
    }
}
