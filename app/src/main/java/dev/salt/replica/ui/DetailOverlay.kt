package dev.salt.replica.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import dev.salt.buttons.SyncToneIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.salt.replica.R
import dev.salt.replica.playback.PlayerStore
import dev.salt.replica.playback.Playlist
import dev.salt.replica.playback.Track
import dev.salt.replica.ui.theme.HyperPillButton
import dev.salt.replica.ui.theme.HyperRed
import dev.salt.replica.ui.theme.hyperClickable
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Container transform: the tapped card grows into a full-screen surface while the cover travels to
 * the detail header; the detail content cross-fades in once the surface is nearly full screen.
 */
@Composable
fun DetailOverlay(
    store: PlayerStore,
    request: DetailRequest,
    progress: Float,
    viewport: Rect,
    onClose: () -> Unit,
    onPlay: (List<Track>, Int) -> Unit,
    onMore: (Track, String?) -> Unit,
    onRename: (Playlist) -> Unit,
    onDelete: (Playlist) -> Unit
) {
    val density = LocalDensity.current
    val target = request.target
    val playlist = if (target.kind == LibraryKind.PLAYLIST) store.playlists.firstOrNull { it.id == target.key } else null
    val tracks = when (target.kind) {
        LibraryKind.ALBUM -> store.tracks.filter { it.album.ifBlank { "未知专辑" } == target.key }
        LibraryKind.ARTIST -> store.tracks.filter { it.artist.ifBlank { "未知艺人" } == target.key }
        LibraryKind.PLAYLIST -> playlist?.let { store.playlistTracks(it) } ?: emptyList()
        LibraryKind.FAVORITES -> store.favorites
    }
    if (target.kind == LibraryKind.PLAYLIST) {
        LaunchedEffect(playlist) { if (playlist == null) onClose() }
    }

    val cover = tracks.firstOrNull { it.artwork != null }?.artwork
    val title = when (target.kind) {
        LibraryKind.ALBUM -> target.key
        LibraryKind.ARTIST -> target.key
        LibraryKind.PLAYLIST -> playlist?.name ?: ""
        LibraryKind.FAVORITES -> "最爱收藏"
    }
    val subtitle = when (target.kind) {
        LibraryKind.ALBUM -> "${tracks.firstOrNull()?.artist?.ifBlank { "未知艺人" } ?: "未知艺人"} · ${tracks.size} 首歌曲"
        LibraryKind.ARTIST -> "${tracks.size} 首歌曲 · ${tracks.map { it.album }.distinct().size} 张专辑"
        LibraryKind.PLAYLIST -> "歌单 · ${tracks.size} 首歌曲"
        LibraryKind.FAVORITES -> "我喜欢的音乐 · ${tracks.size} 首歌曲"
    }
    val label = when (target.kind) {
        LibraryKind.ALBUM -> "ALBUM"
        LibraryKind.ARTIST -> "ARTIST"
        LibraryKind.PLAYLIST -> "PLAYLIST"
        LibraryKind.FAVORITES -> "FAVORITES"
    }

    val cardSubtitle = when (target.kind) {
        LibraryKind.ALBUM -> "${tracks.firstOrNull()?.artist?.ifBlank { "未知艺人" } ?: "未知艺人"} · ${tracks.size} 首"
        LibraryKind.ARTIST -> "${tracks.size} 首歌曲 · ${tracks.map { it.album }.distinct().size} 张专辑"
        else -> null
    }

    val p = progress.coerceIn(0f, 1f)
    val headerPx = min(viewport.width * .92f, with(density) { 360.dp.toPx() })
    val headerRect = Rect(0f, 0f, viewport.width, headerPx)
    val surface = cardTransitionRect(request.cardRect, viewport, p)
    val coverT = smoothRange(p, .10f, 1f)
    val arc = (sin(Math.PI * p).toFloat()) * with(density) { 26.dp.toPx() }
    val coverRect = cardTransitionRect(request.coverRect, headerRect, coverT).translate(0f, arc)
    val expandedRadius = with(density) { CARD_EXPANDED_RADIUS_DP.dp.toPx() }
    val surfaceRadius = cardTransitionRadius(with(density) { request.startRadius.toPx() }, expandedRadius, p)
    val coverRadius = cardTransitionRadius(with(density) { 14.dp.toPx() }, expandedRadius, coverT)
    val scrimAlpha = detailCoverScrimAlpha(p)
    val contentAlpha = smoothRange(p, .60f, 1f)
    val cardTextAlpha = when {
        target.kind == LibraryKind.FAVORITES -> 1f - smoothRange(p, .08f, .30f)
        cardSubtitle != null -> 1f - smoothRange(coverT, .14f, .30f)
        else -> 0f
    }
    val accentColor = request.accent
    val surfaceColor = lerp(request.homeFavoriteColors?.surface ?: accentColor ?: MaterialTheme.colorScheme.surface,
        MaterialTheme.colorScheme.background, p)
    val coverWidth = with(density) { coverRect.width.toDp() }
    val coverHeight = with(density) { coverRect.height.toDp() }
    val headerHeight = with(density) { headerPx.toDp() }

    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            drawRoundRect(
                color = surfaceColor,
                topLeft = surface.topLeft,
                size = surface.size,
                cornerRadius = CornerRadius(surfaceRadius)
            )
        }

        Box(
            Modifier.fillMaxSize().graphicsLayer {
                shape = MorphSurfaceShape(surface, surfaceRadius)
                clip = true
            }
        ) {
        if (target.kind == LibraryKind.FAVORITES) {
            val accent = request.accent ?: Color(0xFFE5484D)
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = smoothRange(p, .15f, .65f) }
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(headerHeight)
                        .background(
                            Brush.linearGradient(listOf(lerp(accent, Color.White, .18f), accent))
                        )
                ) {
                    Icon(
                        Icons.Default.Favorite, null,
                        tint = Color.White.copy(alpha = .8f),
                        modifier = Modifier.align(Alignment.Center).size(76.dp)
                    )
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = .25f),
                                .30f to Color.Transparent
                            )
                        )
                    )
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                .68f to Color.Transparent,
                                1f to surfaceColor
                            )
                        )
                    )
                }
            }
        } else {
            Box(
                Modifier
                    .offset { IntOffset(coverRect.left.roundToInt(), coverRect.top.roundToInt()) }
                    .size(coverWidth, coverHeight)
                    .clip(RoundedCornerShape(with(density) { coverRadius.toDp() }))
            ) {
                if (cover != null) {
                    Artwork(cover, Modifier.fillMaxSize())
                } else {
                    val tint = request.accent ?: Color(0xFF3482FF)
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.linearGradient(listOf(lerp(tint, Color.White, .28f), tint))
                        ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_nav_songs), null,
                            tint = Color.White.copy(alpha = .72f), modifier = Modifier.size(72.dp)
                        )
                    }
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = .55f * scrimAlpha),
                                .40f to Color.Transparent
                            )
                        )
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                .68f to Color.Transparent,
                                1f to surfaceColor.copy(alpha = scrimAlpha)
                            )
                        )
                )
            }
        }

        if (cardSubtitle != null && cardTextAlpha > .01f) {
            val textLeft = request.cardRect.left + with(density) { 10.dp.toPx() }
            val textTop = request.coverRect.bottom + with(density) { 9.dp.toPx() }
            val textWidth = with(density) { (request.cardRect.width - with(density) { 20.dp.toPx() }).toDp() }
            Column(
                Modifier
                    .offset { IntOffset(textLeft.roundToInt(), textTop.roundToInt()) }
                    .width(textWidth)
                    .graphicsLayer { alpha = cardTextAlpha }
            ) {
                Text(
                    title, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    cardSubtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp)
                )
            }
        }

        if (request.homeFavoriteColors != null && cardTextAlpha > .001f) {
            HomeFavoriteContent(store.favorites.size, request.homeFavoriteColors,
                Modifier.offset { IntOffset(request.cardRect.left.roundToInt(), request.cardRect.top.roundToInt()) }
                    .size(with(density) { request.cardRect.width.toDp() }, with(density) { request.cardRect.height.toDp() })
                    .clip(RoundedCornerShape(request.startRadius))
                    .graphicsLayer { alpha = cardTextAlpha })
        }

        Column(Modifier.fillMaxSize().navigationBarsPadding().graphicsLayer {
            alpha = contentAlpha
            translationY = (1f - contentAlpha) * 14.dp.toPx()
        }) {
            Spacer(Modifier.height(headerHeight + 12.dp))
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text(
                    label, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                    letterSpacing = 1.5.sp, color = MaterialTheme.colorScheme.primary
                )
                Text(
                    title, fontSize = 27.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Text(
                    subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp)
                )
                Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    HyperPillButton("播放全部", icon = Icons.Default.PlayArrow, enabled = tracks.isNotEmpty()) {
                        if (tracks.isNotEmpty()) onPlay(tracks, 0)
                    }
                    Spacer(Modifier.weight(1f))
                    if (playlist != null) PlaylistMenu(playlist, onRename, onDelete)
                }
            }
            Spacer(Modifier.height(12.dp))
            MusicListCard(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                if (tracks.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("这里还没有歌曲", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 6.dp)) {
                        itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
                            SongRow(
                                track, index + 1, store.track?.id == track.id,
                                { onPlay(tracks, index) },
                                { onMore(track, playlist?.id) }
                            )
                            if (index < tracks.lastIndex) {
                                MusicListDivider()
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        }

        Box(
            Modifier
                .statusBarsPadding()
                .padding(start = 12.dp, top = 8.dp)
                .size(40.dp)
                .graphicsLayer { alpha = smoothRange(p, .22f, 1f) }
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = .86f))
                .hyperClickable(onClick = onClose),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.ArrowBack, "返回",
                tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp)
            )
        }
    }
}

private class MorphSurfaceShape(private val rect: Rect, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(rect, CornerRadius(radius)))
}

@Composable
private fun PlaylistMenu(playlist: Playlist, onRename: (Playlist) -> Unit, onDelete: (Playlist) -> Unit) {    var open by remember { mutableStateOf(false) }
    Box {
        IconButton({ open = true }, Modifier.size(42.dp)) {
            Icon(
                Icons.Default.MoreVert, "歌单操作",
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(14.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 10.dp
        ) {
            DropdownMenuItem(
                text = { Text("重命名歌单") },
                onClick = { open = false; onRename(playlist) }
            )
            DropdownMenuItem(
                text = { Text("删除歌单", color = HyperRed) },
                onClick = { open = false; onDelete(playlist) }
            )
        }
    }
}
