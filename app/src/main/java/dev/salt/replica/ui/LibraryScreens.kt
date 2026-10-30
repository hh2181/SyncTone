package dev.salt.replica.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import dev.salt.buttons.SyncToneIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import dev.salt.buttons.SyncToneTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.salt.replica.playback.PlayerStore
import dev.salt.replica.playback.Track
import dev.salt.replica.ui.theme.HyperCard
import dev.salt.replica.ui.theme.HyperRed
import dev.salt.replica.ui.theme.HyperSearchField
import dev.salt.replica.ui.theme.hyperClickable

enum class LibraryKind { ALBUM, ARTIST, PLAYLIST, FAVORITES }

data class DetailTarget(val kind: LibraryKind, val key: String)

data class DetailRequest(
    val target: DetailTarget,
    val cardRect: Rect,
    val coverRect: Rect,
    val startRadius: Dp = 20.dp,
    val accent: Color? = null,
    val homeFavoriteColors: HomeCardColors? = null
)

data class SongMenuRequest(val track: Track, val playlistId: String?)

private fun durationText(ms: Long): String = "%02d:%02d".format(ms / 60000, ms / 1000 % 60)

@Composable
fun HomeScreen(
    store: PlayerStore,
    dark: Boolean,
    onMenu: () -> Unit,
    onAdd: () -> Unit,
    onOpenPlayer: () -> Unit,
    onPlayTrack: (Track) -> Unit,
    onOpenAll: () -> Unit,
    onMoreTrack: (Track) -> Unit,
    onOpenFavorites: (Rect, Rect) -> Unit,
    modifier: Modifier
) {
    var query by remember { mutableStateOf("") }
    val recommended = remember(store.tracks.size) { store.tracks.shuffled() }
    val tracks = remember(recommended, query) {
        if (query.isBlank()) recommended
        else recommended.filter {
            it.title.contains(query, true) || it.artist.contains(query, true) || it.album.contains(query, true)
        }
    }
    val hour = remember { java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) }
    val greeting = when (hour) {
        in 5..11 -> "早上好"
        in 12..17 -> "下午好"
        else -> "晚上好"
    }
    val date = remember {
        java.text.SimpleDateFormat("M月d日 · EEEE", java.util.Locale.CHINA).format(java.util.Date())
    }
    LazyColumn(
        modifier.padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 2.dp, bottom = 20.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onMenu, Modifier.size(40.dp)) {
                    Icon(Icons.Default.Menu, "打开侧边栏", tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.weight(1f))
                IconButton(onAdd, Modifier.size(40.dp)) {
                    Icon(Icons.Default.Add, "添加音乐", tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(22.dp))
                }
            }
        }
        item {
            Column(Modifier.padding(top = 10.dp, bottom = 16.dp)) {
                Text(greeting, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
                Text(date, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }
        item {
            HyperSearchField(query, { query = it }, "搜索歌曲、艺人或专辑", Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NowPlayingCard(store, dark, Modifier.weight(1f), onOpenPlayer)
                FavoriteCard(store, dark, Modifier.weight(1f), onOpenFavorites)
            }
            Spacer(Modifier.height(10.dp))
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 2.dp)) {
                Text("随机推荐", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenAll) { Text("查看全部", fontSize = 13.sp) }
            }
        }
        if (tracks.isEmpty()) {
            item {
                MusicListCard {
                    Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("还没有找到音乐", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                        Text("点击右上角 + 添加本地文件，文件不会被复制", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        } else {
            item {
                MusicListCard(Modifier.padding(vertical = 6.dp)) {
                    tracks.take(5).forEachIndexed { index, track ->
                        SongRow(track, index + 1, store.track?.id == track.id, { onPlayTrack(track) }, { onMoreTrack(track) })
                        if (index < minOf(4, tracks.lastIndex)) {
                            MusicListDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NowPlayingCard(store: PlayerStore, dark: Boolean, modifier: Modifier, onOpen: () -> Unit) {
    val colors = homePlayingColors(dark)
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(22.dp))
            .background(colors.surface)
            .hyperClickable(onClick = onOpen)
    ) {
        HomePlayingContent(store.track, colors)
    }
}

@Composable
private fun FavoriteCard(store: PlayerStore, dark: Boolean, modifier: Modifier, onOpen: (Rect, Rect) -> Unit) {
    var rect by remember { mutableStateOf(Rect.Zero) }
    val colors = homeFavoriteColors(dark)
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(22.dp))
            .background(colors.surface)
            .onGloballyPositioned { rect = it.boundsInRoot() }
            .hyperClickable { onOpen(rect, rect) }
    ) {
        HomeFavoriteContent(store.favorites.size, colors)
    }
}

@Composable
fun SongsScreen(
    store: PlayerStore,
    onMenu: () -> Unit,
    onAdd: () -> Unit,
    onPlayTrack: (Track) -> Unit,
    onMoreTrack: (Track) -> Unit,
    modifier: Modifier,
    label: String = "YOUR LIBRARY",
    title: String = "全部歌曲",
    tracks: List<Track> = store.tracks
) {
    var query by remember { mutableStateOf("") }
    val visible = remember(tracks, query) {
        tracks.filter {
            query.isBlank() || it.title.contains(query, true) || it.artist.contains(query, true) || it.album.contains(query, true)
        }
    }
    Column(modifier.padding(horizontal = 18.dp)) {
        PageHeader(label, title, visible.size, onMenu, onAdd)
        Spacer(Modifier.height(10.dp))
        HyperSearchField(query, { query = it }, "搜索歌曲、艺人或专辑", Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        MusicListCard(Modifier.weight(1f)) {
            if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("没有匹配的歌曲", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 6.dp)) {
                    itemsIndexed(visible, key = { _, t -> t.id }) { index, track ->
                        SongRow(track, index + 1, store.track?.id == track.id, { onPlayTrack(track) }, { onMoreTrack(track) })
                        if (index < visible.lastIndex) {
                            MusicListDivider()
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
fun AlbumsScreen(
    store: PlayerStore,
    onMenu: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (DetailTarget, Rect, Rect) -> Unit,
    modifier: Modifier
) {
    val albums = remember(store.tracks) {
        store.tracks.groupBy { it.album.ifBlank { "未知专辑" } }.toList().sortedBy { it.first.lowercase() }
    }
    Column(modifier.padding(horizontal = 18.dp)) {
        PageHeader("COLLECTION", "专辑", albums.size, onMenu, onAdd)
        Spacer(Modifier.height(8.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(top = 6.dp, bottom = 18.dp)
        ) {
            items(albums, key = { it.first }) { (name, list) ->
                LibraryTile(
                    cover = list.firstOrNull { it.artwork != null }?.artwork,
                    title = name,
                    subtitle = "${list.firstOrNull()?.artist?.ifBlank { "未知艺人" } ?: "未知艺人"} · ${list.size} 首",
                    onOpen = { card, coverRect -> onOpen(DetailTarget(LibraryKind.ALBUM, name), card, coverRect) }
                )
            }
        }
    }
}

@Composable
fun ArtistsScreen(
    store: PlayerStore,
    onMenu: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (DetailTarget, Rect, Rect) -> Unit,
    modifier: Modifier
) {
    val artists = remember(store.tracks) {
        store.tracks.groupBy { it.artist.ifBlank { "未知艺人" } }.toList().sortedBy { it.first.lowercase() }
    }
    Column(modifier.padding(horizontal = 18.dp)) {
        PageHeader("ARTISTS", "艺人", artists.size, onMenu, onAdd)
        Spacer(Modifier.height(8.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(top = 6.dp, bottom = 18.dp)
        ) {
            items(artists, key = { it.first }) { (name, list) ->
                LibraryTile(
                    cover = list.firstOrNull { it.artwork != null }?.artwork,
                    title = name,
                    subtitle = "${list.size} 首歌曲 · ${list.map { it.album }.distinct().size} 张专辑",
                    onOpen = { card, coverRect -> onOpen(DetailTarget(LibraryKind.ARTIST, name), card, coverRect) }
                )
            }
        }
    }
}

@Composable
private fun LibraryTile(
    cover: Bitmap?,
    title: String,
    subtitle: String,
    onOpen: (Rect, Rect) -> Unit
) {
    var cardRect by remember { mutableStateOf(Rect.Zero) }
    var coverRect by remember { mutableStateOf(Rect.Zero) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .onGloballyPositioned { cardRect = it.boundsInRoot() }
            // A second press-scale would change the captured shared-element bounds.
            .hyperClickable(pressedScale = 1f) { onOpen(cardRect, coverRect) }
    ) {
        Column(Modifier.padding(8.dp)) {
            Artwork(
                cover,
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .onGloballyPositioned { coverRect = it.boundsInRoot() }
            )
            Text(
                title, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 2.dp, top = 9.dp)
            )
            Text(
                subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 2.dp, top = 3.dp, bottom = 3.dp)
            )
        }
    }
}

@Composable
fun SongRow(
    track: Track,
    index: Int,
    current: Boolean,
    onClick: () -> Unit,
    onMore: (() -> Unit)?
) {
    MusicListRow(track.title, track.artist, index, secondaryMetadata = durationText(track.duration), modifier = Modifier.hyperClickable(onClick = onClick),
        current = current, artwork = { Artwork(track.artwork, it) }) {
        if (onMore != null) {
            IconButton(onMore) {
                Icon(Icons.Default.MoreVert, "更多操作：${track.title}", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun PageHeader(
    label: String,
    title: String,
    count: Int,
    onMenu: () -> Unit,
    onAdd: (() -> Unit)?
) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onMenu, Modifier.size(40.dp)) {
                Icon(Icons.Default.Menu, "打开侧边栏", tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.weight(1f))
            Text("$count", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 4.dp))
            if (onAdd != null) {
                IconButton(onAdd, Modifier.size(40.dp)) {
                    Icon(Icons.Default.Add, "添加音乐", tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(22.dp))
                }
            }
        }
        Column(Modifier.padding(start = 4.dp, bottom = 2.dp)) {
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.4.sp, color = MaterialTheme.colorScheme.primary)
            Text(title, fontSize = 27.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
        }
    }
}

@Composable
fun PlaceholderScreen(title: String, onMenu: () -> Unit, modifier: Modifier) {
    Column(modifier.padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onMenu, Modifier.size(40.dp)) {
                Icon(Icons.Default.Menu, "打开侧边栏", tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(22.dp))
            }
        }
        Text(title, fontSize = 27.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 16.dp))
        HyperCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp)) {
                Text("这个页面还在接入中", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                Text("当前版本先完成音乐库与歌单的交互，稍后补上$title。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
fun SongActionsSheet(
    store: PlayerStore,
    request: SongMenuRequest,
    onDismiss: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRemoveFromPlaylist: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 26.dp)) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(request.track.artwork, Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)))
            Column(Modifier.padding(start = 12.dp)) {
                Text(request.track.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(request.track.artist.ifBlank { "未知艺人" }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(6.dp))
        SheetAction(if (store.isFavorite(request.track.id)) "取消收藏" else "收藏") {
            store.toggleFavorite(request.track.id)
            onDismiss()
        }
        SheetAction("添加到歌单") { onAddToPlaylist() }
        if (request.playlistId != null) {
            SheetAction("从这个歌单移除", tint = HyperRed) { onRemoveFromPlaylist() }
        }
    }
}

@Composable
private fun SheetAction(text: String, tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).hyperClickable(onClick = onClick).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, fontSize = 15.sp, color = tint)
    }
}

@Composable
fun AddToPlaylistSheet(
    store: PlayerStore,
    track: Track,
    onPick: (String) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 26.dp)) {
        Text("添加到歌单", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp))
        Text(track.title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 22.dp, vertical = 2.dp))
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().height(52.dp).hyperClickable { onCreate() }.padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text("新建歌单", fontSize = 15.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 12.dp))
        }
        store.playlists.forEach { playlist ->
            Row(
                Modifier.fillMaxWidth().height(52.dp).hyperClickable { onPick(playlist.id) }.padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(MaterialTheme.colorScheme.primary))
                Text(playlist.name, fontSize = 15.sp, modifier = Modifier.weight(1f).padding(start = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${playlist.trackIds.size}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (store.playlists.isEmpty()) {
            Text("还没有歌单，先新建一个吧", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp))
        }
    }
}
