@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.salt.desktop.lyrics

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.*
import androidx.compose.ui.zIndex
import dev.salt.desktop.playback.DesktopPlayerStore
import dev.salt.desktop.ui.MiSans
import dev.salt.desktop.ui.Tool
import dev.salt.desktop.ui.desktopScrollbarStyle
import dev.salt.replica.lyrics.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.*

@Stable
class LyricsViewportState {
    var browsing by mutableStateOf(false); internal set
    var activeIndex by mutableIntStateOf(-1); internal set
    internal var interaction by mutableLongStateOf(0)
    internal var followRequest by mutableIntStateOf(0)
    fun follow() { browsing = false; followRequest++ }
    internal fun browse() { browsing = true; interaction = System.nanoTime() }
}

@Composable
internal fun rememberLyricsPosition(store: DesktopPlayerStore, visible: Boolean): State<Long> {
    return produceState(store.timeline.positionMs() + store.state.value.lyricsOffsetMs, store, visible) {
        if (!visible) return@produceState
        store.state.map { LyricsClockInput(it.currentId, it.seekRevision, it.playing, it.lyricsOffsetMs) }
            .distinctUntilChanged().collectLatest { clock ->
                value = store.timeline.positionMs() + clock.offset
                if (clock.playing) while (true) withFrameNanos {
                    value = store.timeline.positionMs() + clock.offset
                }
        }
    }
}

private data class LyricsClockInput(val track: String?, val seek: Int, val playing: Boolean, val offset: Long)

@Composable
fun DesktopLyricsPanel(lyrics: Lyrics, position: State<Long>, settings: DesktopLyricsSettings,
                       trackKey: String?, seekRevision: Int, onSeek: (Long) -> Unit, onSettings: () -> Unit,
                       modifier: Modifier = Modifier, visible: Boolean = true,
                       contentColor: Color = Color.White,
                       viewportState: LyricsViewportState = remember { LyricsViewportState() }) {
    val active by remember(lyrics, position) { derivedStateOf { lyrics.indexAt(position.value) } }
    val list = rememberLazyListState()
    val listDragged by list.interactionSource.collectIsDraggedAsState()
    val scrollbarInteraction = remember { MutableInteractionSource() }
    val scrollbarDragged by scrollbarInteraction.collectIsDraggedAsState()
    val dragged = listDragged || scrollbarDragged
    val density = LocalDensity.current
    var motion by remember(lyrics) { mutableStateOf<StaggerMotion?>(null) }
    val motionTime = remember { mutableLongStateOf(0) }
    val heights = remember(lyrics) { mutableStateMapOf<Int, Int>() }
    var previous by remember(lyrics) { mutableIntStateOf(-1) }
    var requested by remember(lyrics) { mutableStateOf<Int?>(null) }
    SideEffect { viewportState.activeIndex = active }
    LaunchedEffect(trackKey, lyrics, seekRevision) { viewportState.follow(); requested = null }
    LaunchedEffect(dragged) { if (dragged) { viewportState.browse(); motion = null } }
    LaunchedEffect(viewportState.interaction, viewportState.browsing, dragged) {
        if (viewportState.browsing && !dragged) { delay(3000); viewportState.follow() }
    }
    LaunchedEffect(motion, visible) {
        val current = motion ?: return@LaunchedEffect
        if (!visible) return@LaunchedEffect
        do { withFrameNanos { motionTime.longValue = it } } while (!current.settled(motionTime.longValue))
    }
    BoxWithConstraints(modifier.onPointerEvent(PointerEventType.Press) {
        if (it.buttons.isSecondaryPressed) onSettings()
    }) {
        val viewport = with(density) { maxHeight.toPx() }
        val pad = viewport / 2
        val anchor = viewport * .44f
        LaunchedEffect(lyrics, active, viewportState.browsing, viewportState.followRequest, viewport,
            settings.translation, settings.fontSize, settings.fontWeight, settings.center, settings.stagger, visible) {
            if (!visible) { motion = null; previous = -1; return@LaunchedEffect }
            if (viewportState.browsing || lyrics.lines.isEmpty()) return@LaunchedEffect
            if (active < 0) { list.scrollToItem(0); previous = -1; return@LaunchedEffect }
            if (requested != null && requested != active) return@LaunchedEffect
            val seeking = requested != null
            requested = null
            val height = heights[active] ?: with(density) { 100.dp.roundToPx() }
            val offset = (pad + height / 2 - anchor).roundToInt()
            val old = previous
            previous = active
            val items = list.layoutInfo.visibleItemsInfo
            val target = items.firstOrNull { it.index == active }
            if (settings.stagger && target != null && (seeking || allowsStagger(lyrics.lines, active, old))) {
                val shift = target.offset + target.size / 2f + list.layoutInfo.beforeContentPadding - anchor
                val now = withFrameNanos { it }
                val carried = items.associate { it.index to (motion?.offset(it.index, now) ?: 0f) }
                motionTime.longValue = now
                motion = StaggerMotion(shift, items.firstOrNull()?.index ?: active, now, carried, settings.speed, settings.curve)
                list.requestScrollToItem(active, offset)
            } else if (old < 0 || abs(active - old) > 10) {
                motion = null
                list.scrollToItem(active, offset)
            } else {
                motion = null
                if (target != null) list.animateScrollBy(target.offset + target.size / 2f +
                    list.layoutInfo.beforeContentPadding - anchor,
                    tween(lyricDuration(700, settings.speed), easing = lyricEasing(settings.curve)))
                else list.animateScrollToItem(active, offset)
            }
        }
        if (lyrics.lines.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无歌词", color = contentColor.copy(alpha = .45f))
        } else LazyColumn(state = list, modifier = Modifier.fillMaxSize().padding(end = 14.dp).onPointerEvent(PointerEventType.Scroll) {
            viewportState.browse(); motion = null
        }.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
            drawContent()
            drawRect(Brush.verticalGradient(0f to Color.Transparent, .09f to Color.Black,
                .86f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
        }, contentPadding = PaddingValues(top = with(density) { pad.toDp() }, bottom = with(density) { pad.toDp() })) {
            itemsIndexed(lyrics.lines, key = { i, line -> "${line.start}-$i" }) { index, line ->
                val distance = abs(index - active)
                val emphasis = animateFloatAsState(if (viewportState.browsing) 1f else when (distance) {
                    0 -> 1f; 1 -> .65f; 2 -> .45f; else -> .32f
                }, tween(lyricDuration(700, settings.speed), easing = lyricEasing(settings.curve)))
                val scale = animateFloatAsState(if (index == active || viewportState.browsing) 1f else .95f,
                    tween(lyricDuration(600, settings.speed), easing = lyricEasing(settings.curve)))
                val blur = animateFloatAsState(if (settings.blur && !viewportState.browsing && distance > 0)
                    min(5f, distance * 1.4f) else 0f, tween(lyricDuration(500, settings.speed)))
                val effect = remember(blur.value, density.density) {
                    blur.value.takeIf { it > .01f }?.let { BlurEffect(it * density.density, it * density.density, TileMode.Decal) }
                }
                val style = TextStyle(fontFamily = MiSans.family(settings.fontWeight), fontSynthesis = FontSynthesis.None,
                    fontSize = settings.fontSize.sp, lineHeight = (settings.fontSize * 1.4f).sp,
                    textAlign = if (settings.center) TextAlign.Center else TextAlign.Start, color = contentColor, letterSpacing = 0.sp)
                Column(Modifier.fillMaxWidth().zIndex(if (index == active) 1f else 0f).onSizeChanged {
                    val old = heights.put(index, it.height)
                    if (old != it.height && index == active && !viewportState.browsing)
                        list.requestScrollToItem(index, (pad + it.height / 2f - anchor).roundToInt())
                }.graphicsLayer {
                    translationY = motion?.offset(index, motionTime.longValue) ?: 0f
                    alpha = emphasis.value
                    scaleX = scale.value; scaleY = scale.value
                    transformOrigin = TransformOrigin(if (settings.center) .5f else 0f, .5f)
                    renderEffect = effect
                }.semantics { contentDescription = line.text }.combinedClickable(onLongClick = onSettings, onClickLabel = "从这里播放", onClick = {
                    requested = index
                    viewportState.follow()
                    onSeek(line.start)
                }).padding(vertical = 14.dp, horizontal = 10.dp)) {
                    KaraokeText(line.cells, position, index == active, index < active, style, settings,
                        prepareGlyphs = visible && distance <= 1)
                    AnimatedVisibility(settings.translation && line.subLines.isNotEmpty(),
                        enter = fadeIn(tween(300)) + expandVertically(expandFrom = Alignment.Top),
                        exit = fadeOut(tween(300)) + shrinkVertically(shrinkTowards = Alignment.Top)) {
                        Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                            line.subLines.forEach { sub ->
                                val subStyle = style.copy(fontSize = (settings.fontSize * .62f).sp,
                                    lineHeight = (settings.fontSize * .9f).sp)
                                if (sub.wordTimed) KaraokeText(sub.cells, position, index == active, index < active,
                                    subStyle, settings, visible && distance <= 1, liftFraction = .05f)
                                else Text(sub.text, Modifier.fillMaxWidth(), style = subStyle,
                                    color = contentColor.copy(alpha = .45f))
                            }
                        }
                    }
                }
            }
        }
        if (lyrics.lines.isNotEmpty()) VerticalScrollbar(rememberScrollbarAdapter(list),
            Modifier.align(Alignment.CenterEnd).fillMaxHeight(), interactionSource = scrollbarInteraction,
            style = desktopScrollbarStyle().copy(unhoverColor = contentColor.copy(alpha = .28f),
                hoverColor = contentColor.copy(alpha = .55f)))
        AnimatedVisibility(viewportState.browsing, modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
            enter = fadeIn(), exit = fadeOut()) {
            Tool("回到当前歌词", Icons.Default.MyLocation, viewportState::follow)
        }
    }
}
