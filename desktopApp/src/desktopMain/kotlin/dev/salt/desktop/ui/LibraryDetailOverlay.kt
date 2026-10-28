package dev.salt.desktop.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import dev.salt.desktop.library.DesktopTrack
import dev.salt.replica.ui.smoothRange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.sin
import java.awt.Frame
import dev.salt.desktop.window.windowDragArea

internal class DetailAnchor(val cardRadius: Dp = DesktopCardRadius, val coverRadius: Dp = DesktopCardRadius) {
    var card by mutableStateOf(Rect.Zero)
    var cover by mutableStateOf(Rect.Zero)
    var artwork by mutableStateOf<ImageBitmap?>(null)
    var source: GraphicsLayer? = null
}

/** Retain the actual source drawing, including its text metrics and padding, for the handoff. */
@Composable
internal fun Modifier.detailSource(anchor: DetailAnchor, hidden: Boolean): Modifier {
    val layer = rememberGraphicsLayer()
    SideEffect { anchor.source = layer }
    return drawWithContent {
        layer.record { this@drawWithContent.drawContent() }
        if (!hidden) drawLayer(layer)
    }
}

internal data class LibraryDetailRequest(val title: String, val artist: Boolean, val anchor: DetailAnchor)

internal class LibraryDetailState(private val scope: CoroutineScope) {
    var request by mutableStateOf<LibraryDetailRequest?>(null)
        private set
    val progress = Animatable(0f)
    private var motion: Job? = null

    fun open(title: String, artist: Boolean, anchor: DetailAnchor) {
        if (request != null || anchor.card.isEmpty || anchor.cover.isEmpty) return
        request = LibraryDetailRequest(title, artist, anchor)
        motion?.cancel()
        motion = scope.launch {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(520, easing = CubicBezierEasing(.2f, .9f, .25f, 1f)))
        }
    }

    fun close() {
        if (request == null) return
        motion?.cancel()
        motion = scope.launch {
            progress.animateTo(0f, tween(300, easing = CubicBezierEasing(.4f, 0f, .2f, 1f)))
            request = null
        }
    }

    suspend fun clear() {
        motion?.cancel()
        progress.snapTo(0f)
        request = null
    }
}

/** A single shared cover travels above content clipped to the expanding card surface. */
@Composable
internal fun LibraryDetailOverlay(detail: LibraryDetailState, tracks: List<DesktopTrack>, current: String?,
                                  onSelect: (DesktopTrack) -> Unit, owner: Frame, dragEnabled: Boolean = true) {
    val request = detail.request ?: return
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(request) { focus.requestFocus() }
    BoxWithConstraints(Modifier.fillMaxSize().focusRequester(focus).onPreviewKeyEvent {
        if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) { detail.close(); true } else false
    }.focusable().focusGroup().clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
        indication = null) {}) {
        val p = detail.progress.value.coerceIn(0f, 1f)
        val viewport = with(density) { Rect(0f, 0f, maxWidth.toPx(), maxHeight.toPx()) }
        val coverSize = minOf(180.dp, maxHeight * .28f, (maxWidth - 86.dp) * .4f)
        val headerCover = with(density) { Rect(30.dp.toPx(), 76.dp.toPx(),
            (30.dp + coverSize).toPx(), (76.dp + coverSize).toPx()) }
        val surface = androidx.compose.ui.geometry.lerp(request.anchor.card, viewport, p)
        val coverT = smoothRange(p, .10f, 1f)
        val arc = sin(Math.PI * p).toFloat() * with(density) { 26.dp.toPx() }
        val cover = androidx.compose.ui.geometry.lerp(request.anchor.cover, headerCover, coverT).translate(0f, arc)
        val radius = with(density) { request.anchor.cardRadius.toPx() } * (1 - smoothRange(p, .70f, 1f))
        val contentAlpha = smoothRange(p, .60f, 1f)
        Box(Modifier.fillMaxSize().graphicsLayer {
            shape = DetailSurfaceShape(surface, radius)
            clip = true
        }.background(colors.background)) {
            Column(Modifier.fillMaxSize().padding(horizontal = 30.dp).graphicsLayer { alpha = contentAlpha }) {
                Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                    Tool("返回", Icons.AutoMirrored.Filled.ArrowBack, detail::close)
                    Box(Modifier.weight(1f).fillMaxHeight().windowDragArea(owner, layer = 1, enabled = dragEnabled), contentAlignment = Alignment.CenterStart) {
                    Text(if (request.artist) "艺人" else "专辑", Modifier.padding(start = 8.dp),
                        fontSize = 13.sp, color = colors.onSurfaceVariant)
                    }
                    WindowControls(owner)
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp).height(coverSize), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(coverSize + 26.dp))
                    Column(Modifier.weight(1f)) {
                        Text(request.title, fontSize = 26.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(if (request.artist) "${tracks.size} 首歌曲 · ${tracks.map { it.album }.distinct().size} 张专辑"
                            else "${tracks.firstOrNull()?.artist.orEmpty()} · ${tracks.size} 首歌曲",
                            Modifier.padding(top = 12.dp), fontSize = 13.sp, color = colors.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(24.dp))
                Box(Modifier.weight(1f)) { TrackList(tracks, current, onSelect) }
            }
            ArtworkImage(request.anchor.artwork, Modifier.offset {
                IntOffset(cover.left.roundToInt(), cover.top.roundToInt())
            }.size(with(density) { cover.width.toDp() }, with(density) { cover.height.toDp() })
                .clip(RoundedCornerShape(lerp(request.anchor.coverRadius, DesktopCardRadius, coverT))))
            Canvas(Modifier.fillMaxSize().graphicsLayer {
                alpha = 1 - smoothRange(p, 0f, .25f)
            }) {
                val anchor = request.anchor
                val source = anchor.source ?: return@Canvas
                // The cover has its own travelling layer; everything else is the original drawing.
                clipRect(anchor.cover.left, anchor.cover.top, anchor.cover.right, anchor.cover.bottom, ClipOp.Difference) {
                    translate(anchor.card.left, anchor.card.top) { drawLayer(source) }
                }
            }
        }
    }
}

private class DetailSurfaceShape(private val rect: Rect, private val radius: Float) : Shape {
    override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(rect, CornerRadius(radius)))
}
