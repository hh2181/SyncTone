@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.salt.desktop.ui

import dev.salt.buttons.SyncToneIconButton
import dev.salt.buttons.SyncToneButtonDensity
import dev.salt.replica.lyrics.online.*
import dev.salt.replica.equalizer.EqualizerScreen

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import dev.salt.desktop.library.DesktopTrack
import dev.salt.desktop.library.ScanPhase
import dev.salt.replica.lyrics.Lyrics
import dev.salt.desktop.lyrics.DesktopLyricsPanel
import dev.salt.desktop.lyrics.rememberLyricsPosition
import dev.salt.desktop.playback.*
import dev.salt.desktop.storage.PlayMode
import dev.salt.replica.ui.PlayerSheetState
import dev.salt.replica.ui.smoothRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import dev.salt.desktop.window.windowDragArea
import java.awt.FileDialog
import java.awt.Frame
import java.awt.event.WindowEvent
import java.nio.file.Path
import kotlin.math.roundToInt

private val Blue = Color(0xFF3482FF)
private val Red = Color(0xFFF05A63)

@Composable
fun DesktopApp(store: DesktopPlayerStore, sheet: PlayerSheetState, owner: Frame, roundedWindow: Boolean = true) {
    val renderingActive = rememberWindowRendering(owner)
    val artworkCache = remember { ArtworkCache() }
    DisposableEffect(artworkCache) { onDispose { artworkCache.clear() } }
    // Position ticks belong to the seek bar, not every library item and overlay.
    val state by remember(store) { store.state.map { it.copy(positionMs = 0) }.distinctUntilChanged() }
        .collectAsState(store.state.value.copy(positionMs = 0))
    val library by store.library.state.collectAsState()
    val lyrics by produceState(Lyrics.Empty, state.track) {
        value = withContext(Dispatchers.IO) { state.track?.lyrics ?: Lyrics.Empty }
    }
    var dark by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf("首页") }
    var search by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val onlineLyrics = remember(store, scope) {
        OnlineLyricsController(scope, store.onlineLyricsPreferenceFile, store::saveOnlineLyrics, hasLyrics = store::hasLyrics)
    }
    val onlineState by onlineLyrics.state.collectAsState()
    val lyricSong = state.track?.let { LyricSong(it.id, it.title, it.artist, it.durationMs) }
    LaunchedEffect(library.tracks) {
        onlineLyrics.updateLibrary(library.tracks.map { LyricSong(it.id, it.title, it.artist, it.durationMs) }, state.currentId)
    }
    LaunchedEffect(lyricSong, lyrics, onlineState.settings) {
        onlineLyrics.autoMatchFor(lyricSong, lyrics.lines.isNotEmpty())
    }
    val detail = remember { LibraryDetailState(scope) }
    var paneCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    LaunchedEffect(page) { detail.clear() }
    DisposableEffect(page) {
        store.sync.setPageOpen(page == "连接")
        onDispose { store.sync.setPageOpen(false) }
    }
    var queue by remember { mutableStateOf(false) }
    var lyricsSettings by remember { mutableStateOf(false) }
    var timer by remember { mutableStateOf(false) }
    val lyricSettings = state.lyricSettings
    val lyricPosition = rememberLyricsPosition(store, renderingActive && sheet.progress > .2f)
    val scheme = desktopColorScheme(dark)
    // Preserve the seek bar's original foreground independently of the new control palette.
    val miniSeekInk = remember(dark) { if (dark) darkColorScheme().onBackground else lightColorScheme().onBackground }
    fun importAudio() = chooseFiles(owner, false) { store.importFiles(it) }
    fun navigate(target: String) {
        if (page == target) detail.close() else page = target
    }

    CompositionLocalProvider(LocalArtworkCache provides artworkCache) {
    MaterialTheme(colorScheme = scheme, typography = MiSans.typography, shapes = DesktopShapes) {
        Surface(Modifier.fillMaxSize().then(if (roundedWindow) Modifier.clip(RoundedCornerShape(12.dp)) else Modifier), color = scheme.background) {
            BoxWithConstraints {
                val width = maxWidth
                val height = maxHeight
                val p = sheet.progress
                val miniHeight = 94.dp
                val density = androidx.compose.ui.platform.LocalDensity.current
                SideEffect {
                    sheet.travel = with(density) { (height - miniHeight).toPx() }
                    sheet.density = density.density
                }
                // The full player is opaque. Keep library layout/state, without retaining an
                // offscreen blur target for a page that is entirely covered or iconified.
                val libraryDrawing = if (p < 1f && renderingActive) Modifier.graphicsLayer {
                    scaleX = 1f - .035f * p
                    scaleY = 1f - .035f * p
                    alpha = 1f - .35f * p
                }.blur(12.dp * p) else Modifier.drawWithContent { }
                Row(Modifier.fillMaxSize().padding(bottom = miniHeight).then(libraryDrawing)) {
                    Column(Modifier.width(206.dp).fillMaxHeight().background(scheme.surface)
                        .padding(horizontal = 12.dp, vertical = 24.dp)) {
                        Row(Modifier.padding(horizontal = 10.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Image(painterResource("app-icon.png"), null,
                                Modifier.size(26.dp).clip(RoundedCornerShape(7.dp)))
                            Text("SyncTone", Modifier.padding(start = 10.dp), fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(24.dp))
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        listOf("首页" to Icons.Default.Home, "全部歌曲" to Icons.Default.MusicNote,
                            "扫描音乐" to Icons.Default.FolderOpen,
                            "专辑" to Icons.Default.Album, "艺人" to Icons.Default.Person,
                            "统计" to appIcon("ic_nav_stats"), "连接" to appIcon("ic_nav_cast"),
                            "歌词更新" to Icons.Default.Subtitles, "均衡器" to Icons.Default.Equalizer).forEach { (label, icon) ->
                            val interaction = remember(label) { MutableInteractionSource() }
                            val hovered by interaction.collectIsHoveredAsState()
                            Row(Modifier.fillMaxWidth().height(46.dp).clip(DesktopCardShape)
                                .background(when {
                                    page == label -> scheme.primary.copy(alpha = .1f)
                                    hovered -> scheme.onSurface.copy(alpha = .07f)
                                    else -> Color.Transparent
                                })
                                .hoverable(interaction)
                                .clickable(interactionSource = interaction, indication = null) { navigate(label) }
                                .padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(icon, null, Modifier.size(20.dp), tint = if (page == label) scheme.primary else scheme.onSurfaceVariant)
                                Text(label, Modifier.padding(start = 14.dp), fontSize = 14.sp,
                                    color = if (page == label) scheme.primary else scheme.onSurface)
                            }
                            Spacer(Modifier.height(5.dp))
                        }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth().height(46.dp).clip(DesktopCardShape)
                            .background(if (page == "设置") scheme.primary.copy(alpha = .1f) else Color.Transparent)
                            .desktopClickable { navigate("设置") }.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Settings, null, Modifier.size(20.dp), tint = if (page == "设置") scheme.primary else scheme.onSurfaceVariant)
                            Text("设置", Modifier.padding(start = 14.dp), fontSize = 14.sp, color = if (page == "设置") scheme.primary else scheme.onSurface)
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight().clipToBounds().onGloballyPositioned { paneCoordinates = it }) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 30.dp)) {
                        Row(Modifier.fillMaxWidth().height(78.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f).fillMaxHeight().windowDragArea(owner, enabled = !lyricsSettings), contentAlignment = Alignment.CenterStart) {
                                Text("音乐库  /  $page", fontSize = 13.sp, color = scheme.onSurfaceVariant)
                            }
                            SearchField(search, { search = it }, scheme, Modifier.width(250.dp).height(44.dp))
                            Tool("切换外观", Icons.Default.Contrast, { dark = !dark })
                            WindowControls(owner)
                        }
                        AnimatedContent(page, transitionSpec = {
                            desktopPageTransition(DesktopPageOrder.indexOf(targetState) > DesktopPageOrder.indexOf(initialState))
                        }, modifier = Modifier.weight(1f)) { target ->
                            Column(Modifier.fillMaxSize()) {
                                if (target !in listOf("歌词更新", "均衡器")) Row(Modifier.fillMaxWidth().height(66.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(target, Modifier.weight(1f), fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                                    if (target !in listOf("设置", "扫描音乐", "统计", "连接")) Tool("导入音乐", Icons.Default.Add, ::importAudio,
                                        state.ready && !library.importing && library.scan.phase != ScanPhase.Scanning)
                                }
                                if (target == "歌词更新") {
                                    LyricsUpdateScreen(onlineLyrics, Modifier.fillMaxSize())
                                } else if (target == "均衡器") {
                                    EqualizerScreen(store.equalizer, Modifier.fillMaxSize(), desktop = true)
                                } else if (target == "扫描音乐") {
                                    MusicScanScreen(library, store.library) { chooseMusicFolder(owner) { store.library.addFolder(it) } }
                                } else if (target == "统计") {
                                    StatisticsScreen(store, search, paneCoordinates, detail.request?.anchor) { title, artist, anchor -> detail.open(title, artist, anchor) }
                                } else if (target == "连接") {
                                    SyncScreen(store.sync, search)
                                } else if (target == "设置") {
                                    DesktopSettingsGroup { DesktopSettingToggle("深色外观", dark, { dark = it }) }
                                } else {
                                    library.lastImport?.let { result ->
                                        Text("导入：新增 ${result.added} · 重复 ${result.duplicates} · 不可读 ${result.unreadable}",
                                            Modifier.padding(bottom = 12.dp), fontSize = 12.sp, color = scheme.onSurfaceVariant)
                                    }
                                    val tracks = remember(library.tracks, search) {
                                        library.tracks.filter {
                                            search.isBlank() || "${it.title} ${it.artist} ${it.album}".contains(search, ignoreCase = true)
                                        }
                                    }
                                    if (tracks.isEmpty()) {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                if (!library.ready) CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 2.dp)
                                                else Icon(Icons.Default.LibraryMusic, null, Modifier.size(52.dp), tint = scheme.onSurfaceVariant.copy(alpha = .5f))
                                                Text(if (!library.ready) "正在加载音乐库…" else if (state.importing) "正在导入…" else if (search.isNotBlank()) "没有匹配的歌曲" else "音乐库为空",
                                                    Modifier.padding(top = 18.dp, bottom = 14.dp), color = scheme.onSurfaceVariant)
                                                if (library.ready && search.isBlank()) DesktopButton(::importAudio, enabled = state.ready && !library.importing && library.scan.phase != ScanPhase.Scanning) {
                                                    Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("导入音乐")
                                                }
                                            }
                                        }
                                    } else if (target in listOf("首页", "专辑", "艺人")) {
                                        val groups = remember(tracks, target) {
                                            (if (target == "艺人") tracks.groupBy { it.artist } else tracks.groupBy { it.album }).entries.toList()
                                        }
                                        val gridState = rememberLazyGridState()
                                        Box(Modifier.fillMaxSize()) {
                                        LazyVerticalGrid(GridCells.Adaptive(170.dp), state = gridState,
                                            modifier = Modifier.fillMaxSize().padding(end = 14.dp),
                                            verticalArrangement = Arrangement.spacedBy(12.dp),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 28.dp)) {
                                            items(groups, key = { it.key }, contentType = { "entity" }) { entry ->
                                                val anchor = remember { DetailAnchor() }
                                                val selected = detail.request?.anchor === anchor
                                                Column(Modifier.fillMaxWidth().onGloballyPositioned { child ->
                                                    paneCoordinates?.takeIf { it.isAttached && child.isAttached }?.let {
                                                        anchor.card = it.localBoundingBoxOf(child, clipBounds = false)
                                                    }
                                                }.detailSource(anchor, selected)
                                                    .clip(DesktopCardShape)
                                                    .desktopClickable { detail.open(entry.key, target == "艺人", anchor) }
                                                    .padding(10.dp)) {
                                                    Artwork(entry.value.first(), Modifier.fillMaxWidth().aspectRatio(1f)
                                                        .onGloballyPositioned { child ->
                                                            paneCoordinates?.takeIf { it.isAttached && child.isAttached }?.let {
                                                                anchor.cover = it.localBoundingBoxOf(child, clipBounds = false)
                                                            }
                                                        }.clip(DesktopCardShape), onReady = { anchor.artwork = it })
                                                    Text(entry.key, Modifier.padding(top = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                                    Text(if (target == "艺人") "${entry.value.size} 首歌曲" else entry.value.first().artist,
                                                        Modifier.padding(top = 4.dp), fontSize = 12.sp, color = scheme.onSurfaceVariant,
                                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                            }
                                        }
                                        VerticalScrollbar(rememberScrollbarAdapter(gridState), Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                                            style = desktopScrollbarStyle())
                                        }
                                    } else {
                                        TrackList(tracks, state.currentId) { store.select(it) }
                                    }
                                }
                            }
                        }
                    }
                    detail.request?.let { request ->
                        val tracks = remember(state.tracks, request.title, request.artist) {
                            state.tracks.filter { if (request.artist) it.artist == request.title else it.album == request.title }
                        }
                        LibraryDetailOverlay(detail, tracks, state.currentId, store::select, owner, dragEnabled = !lyricsSettings)
                    }
                    }
                }

                // One surface and one artwork layer persist across the entire drag.
                val surfaceHeight = miniHeight + (height - miniHeight) * p
                val playerColor = lerp(scheme.surface, Color(0xFF22272A), p)
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(surfaceHeight).clip(RoundedCornerShape(DesktopCardRadius * p))
                    .background(playerColor)) {
                    PlayerBackdrop(state.track, lyricSettings, renderingActive && p > .001f,
                        with(density) { IntSize(width.roundToPx(), height.roundToPx()) },
                        Modifier.fillMaxWidth().height(height).align(Alignment.BottomCenter).alpha(p))
                    val drag = Modifier.pointerInput(sheet) {
                        detectVerticalDragGestures(onDragStart = { sheet.begin() }, onDragEnd = { sheet.settle(0f) },
                            onDragCancel = { sheet.settle(0f) }) { change, amount -> change.consume(); sheet.drag(amount) }
                    }
                    if (p < .98f) {
                        Row(Modifier.fillMaxWidth().height(miniHeight).align(Alignment.BottomCenter).alpha(1 - smoothRange(p, 0f, .24f)),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(304.dp).fillMaxHeight().clip(RoundedCornerShape(topEnd = DesktopCardRadius, bottomEnd = DesktopCardRadius))
                                .then(drag).desktopClickable { sheet.to(true) }.padding(start = 94.dp, end = 12.dp)) {
                            Column(Modifier.align(Alignment.CenterStart)) {
                                Text(state.track?.title ?: "SyncTone", lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                Text(state.track?.artist ?: "", Modifier.padding(top = 5.dp), fontSize = 12.sp,
                                    lineHeight = 16.sp, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            }
                            Transport(state, store, compact = true)
                            CompositionLocalProvider(LocalContentColor provides miniSeekInk) {
                                SeekBar(state, store, Modifier.weight(1f).padding(horizontal = 20.dp))
                            }
                            Tool("播放队列", appIcon("ic_play_queue"), { queue = true })
                            Tool(if (state.volume == 0) "取消静音" else "静音", if (state.volume == 0) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                { store.volume(if (state.volume == 0) 72 else 0) })
                            DesktopVolumeSlider(state.volume, store::volume, Modifier.width(90.dp))
                            Tool("展开播放页", Icons.Default.KeyboardArrowUp, { sheet.to(true) })
                            Spacer(Modifier.width(16.dp))
                        }
                    }
                    if (p > .2f) {
                        val playerInk = if (lyricSettings.light) Color(0xFF202127) else Color.White
                        CompositionLocalProvider(LocalContentColor provides playerInk) {
                            Box(Modifier.fillMaxSize().alpha(smoothRange(p, .2f, .85f))) {
                                Box(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(end = 180.dp).height(64.dp)
                                    .windowDragArea(owner, layer = 2, enabled = !lyricsSettings))
                                Row(Modifier.align(Alignment.TopEnd).padding(20.dp)) {
                                    Tool("收起播放页", Icons.Default.KeyboardArrowDown, { sheet.to(false) })
                                    WindowControls(owner)
                                }
                                val artSize = minOf(350.dp, width * .30f, height * .46f)
                                Column(Modifier.offset(x = width * .07f, y = height * .62f)
                                    .width(artSize).then(drag)) {
                                    Text(state.track?.title ?: "", fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(state.track?.artist ?: "", Modifier.padding(top = 12.dp), fontSize = 15.sp, color = playerInk.copy(alpha = .65f))
                                    Text(state.track?.album ?: "", Modifier.padding(top = 5.dp), fontSize = 12.sp, color = playerInk.copy(alpha = .4f))
                                }
                                Column(Modifier.offset(x = width * .47f, y = 90.dp).width(width * .46f).height(height - 250.dp)) {
                                    DesktopLyricsPanel(lyrics, lyricPosition, lyricSettings, state.currentId, state.seekRevision,
                                        onSeek = { store.seek((it - state.lyricsOffsetMs).coerceAtLeast(0)) },
                                        onSettings = { lyricsSettings = true }, modifier = Modifier.weight(1f),
                                        visible = renderingActive, contentColor = playerInk)
                                    Row(Modifier.fillMaxWidth().height(50.dp), verticalAlignment = Alignment.CenterVertically) {
                                        DesktopTextButton({ lyricsSettings = true }, contentColor = playerInk.copy(alpha = .65f)) {
                                            Icon(appIcon("ic_player_lyrics"), null, Modifier.size(25.dp))
                                            Text(lyrics.source, Modifier.padding(start = 4.dp), fontSize = 12.sp)
                                        }
                                        Spacer(Modifier.weight(1f))
                                        Tool("翻译", appIcon("ic_translation"), { store.updateLyricsSettings(lyricSettings.copy(translation = !lyricSettings.translation)) })
                                    }
                                }
                                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 50.dp, vertical = 22.dp)) {
                                    SeekBar(state, store, Modifier.fillMaxWidth())
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Tool("播放模式", appIcon(when (state.mode) {
                                            PlayMode.RepeatOne -> "ic_player_repeat_one"
                                            PlayMode.Shuffle -> "ic_player_random"
                                            else -> "ic_player_circle"
                                        }), store::cycleMode)
                                        Tool("睡眠定时", appIcon("ic_sleep_timer"), { timer = true })
                                        Tool("音效", appIcon("ic_audio_effect"), { page = "均衡器"; sheet.to(false) }, enabled = state.ready)
                                        Spacer(Modifier.weight(1f))
                                        Transport(state, store, compact = false)
                                        Spacer(Modifier.weight(1f))
                                        Tool("播放队列", appIcon("ic_play_queue"), { queue = true })
                                        Tool("静音", if (state.volume == 0) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                            { store.volume(if (state.volume == 0) 72 else 0) })
                                        DesktopVolumeSlider(state.volume, store::volume, Modifier.width(100.dp))
                                    }
                                }
                            }
                        }
                    }
                    val artSize = minOf(350.dp, width * .30f, height * .46f)
                    val artworkSize = 54.dp + (artSize - 54.dp) * p
                    val artworkX = 24.dp + (width * .07f - 24.dp) * p
                    val miniY = surfaceHeight - miniHeight + 20.dp
                    val artworkY = miniY + (height * .14f - miniY) * p
                    Artwork(state.track, Modifier.offset(artworkX, artworkY).size(artworkSize)
                        .clip(DesktopCardShape).then(drag).desktopClickable { sheet.to(p < .5f) })
                }
                if (state.importing) DesktopTaskProgress(Modifier.align(Alignment.TopCenter).fillMaxWidth())
                LyricsSettingsDialog(lyricsSettings, lyricSettings, state.lyricsOffsetMs, state.track != null,
                    store::updateLyricsSettings, store::offsetLyrics,
                    onImport = { chooseFiles(owner, true) { it.firstOrNull()?.let(store::importLyrics) } },
                    onRestore = store::restoreLyrics, onDismiss = { lyricsSettings = false },
                    onOnline = { lyricsSettings = false; page = "歌词更新"; sheet.to(false) })
            }
        }
        if (queue) DesktopAlertDialog(onDismissRequest = { queue = false }, title = { Text("播放队列 · ${state.queue.size}") },
            text = { Box(Modifier.width(520.dp).heightIn(max = 450.dp)) { TrackList(state.queue, state.currentId) { store.selectQueued(it) } } },
            confirmButton = { DesktopButton({ queue = false }) { Text("关闭") } })
        if (timer) DesktopAlertDialog(onDismissRequest = { timer = false }, title = { Text("睡眠定时") }, text = {
            Column { listOf(15, 30, 60).forEach { minutes ->
                DesktopButton({ store.setSleepTimer(minutes); timer = false }, Modifier.fillMaxWidth().padding(bottom = 8.dp), style = DesktopButtonStyle.Secondary) { Text("$minutes 分钟") }
            }; DesktopTextButton({ store.setSleepTimer(null); timer = false }) { Text("关闭定时") } }
        }, confirmButton = { DesktopButton({ timer = false }, style = DesktopButtonStyle.Secondary) { Text("取消") } })
        (state.error ?: library.error)?.let { message ->
            val dismiss = { store.dismissError(); store.library.dismissError() }
            DesktopAlertDialog(onDismissRequest = dismiss, title = { Text("无法完成操作") },
                text = { Text(message) }, confirmButton = { DesktopButton(dismiss) { Text("确定") } })
        }
    }
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, scheme: ColorScheme, modifier: Modifier) {
    val shape = DesktopCardShape
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.onFocusChanged { focused = it.isFocused }.clip(shape)
            .background(scheme.surfaceVariant, shape)
            .then(if (focused) Modifier.border(1.5.dp, scheme.primary, shape) else Modifier).padding(horizontal = 12.dp),
        singleLine = true,
        textStyle = LocalTextStyle.current.copy(color = scheme.onSurface, fontSize = 13.sp),
        cursorBrush = SolidColor(scheme.primary),
        decorationBox = { innerTextField ->
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, null, Modifier.size(19.dp), tint = scheme.onSurfaceVariant)
                Spacer(Modifier.width(9.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isBlank()) Text("搜索歌曲、艺人或专辑", fontSize = 13.sp, color = scheme.onSurfaceVariant)
                    innerTextField()
                }
            }
        },
    )
}

@Composable
internal fun TrackList(tracks: List<DesktopTrack>, current: String?, onSelect: (DesktopTrack) -> Unit) {
    val colors = MaterialTheme.colorScheme
    ScrollableList(Modifier.fillMaxSize()) {
        items(tracks, key = { it.id }, contentType = { "track" }) { track ->
            Row(Modifier.fillMaxWidth().height(76.dp).clip(DesktopCardShape)
                .background(if (track.id == current) colors.primary.copy(alpha = .08f) else Color.Transparent)
                .desktopClickable { onSelect(track) }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(track, Modifier.size(48.dp).clip(DesktopCardShape))
                Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (track.id == current) colors.primary else colors.onSurface)
                    Text(if (track.available) "${track.artist} · ${track.album}" else "文件暂不可用 · ${track.artist}", Modifier.padding(top = 4.dp), fontSize = 12.sp,
                        color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(time(track.durationMs), fontSize = 12.sp, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun Artwork(track: DesktopTrack?, modifier: Modifier, onReady: ((ImageBitmap?) -> Unit)? = null) {
    val cache = LocalArtworkCache.current
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    var pixels by remember { mutableIntStateOf(0) }
    val bitmap by produceState<ImageBitmap?>(null, track?.assetKey, pixels) {
        value = null
        if (track != null && pixels > 0) value = cache.load(track, pixels)
    }
    SideEffect { onReady?.invoke(bitmap) }
    ArtworkImage(bitmap, modifier.onGloballyPositioned {
        // Fixed size buckets avoid decoding a new image on each player animation frame.
        val requested = maxOf(it.size.width, it.size.height)
        pixels = if (requested <= 64 * density) 128 else 512
    })
}

@Composable
internal fun ArtworkImage(bitmap: ImageBitmap?, modifier: Modifier) {
    if (bitmap != null) Image(bitmap, "专辑封面", modifier, contentScale = ContentScale.Crop)
    else Box(modifier.background(Color(0xFFD6DEE8)), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Album, null, Modifier.fillMaxSize(.4f), tint = Color.White)
    }
}

@Composable
private fun Transport(state: DesktopPlayerState, store: DesktopPlayerStore, compact: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Tool("上一首", appIcon("ic_round_skip_previous_24"), store::previous, state.track != null,
            if (compact) 40.dp else 64.dp, if (compact) 26.dp else 38.dp)
        Tool(if (state.playing) "暂停" else "播放", appIcon(if (state.playing) "ic_round_pause_24" else "ic_round_play_arrow_24"),
            store::toggle, state.ready && state.track != null, if (compact) 48.dp else 72.dp, if (compact) 36.dp else 52.dp)
        Tool("下一首", appIcon("ic_round_skip_next_24"), store::next, state.track != null,
            if (compact) 40.dp else 64.dp, if (compact) 26.dp else 38.dp)
    }
}

@Composable
private fun SeekBar(state: DesktopPlayerState, store: DesktopPlayerStore, modifier: Modifier) {
    val position by remember(store) { store.state.map { it.positionMs }.distinctUntilChanged() }
        .collectAsState(store.state.value.positionMs)
    var seek by remember(state.currentId) { mutableStateOf<Float?>(null) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(time(seek?.toLong() ?: position), Modifier.width(44.dp), fontSize = 11.sp, color = LocalContentColor.current.copy(alpha = .6f))
        ThinSlider(seek ?: position.toFloat(), state.durationMs.coerceAtLeast(1).toFloat(), { seek = it },
            { seek?.let { store.seek(it.toLong()) }; seek = null }, Modifier.weight(1f), state.track != null)
        Text(time(state.durationMs), Modifier.width(42.dp).padding(start = 8.dp), fontSize = 11.sp, color = LocalContentColor.current.copy(alpha = .6f))
    }
}

@Composable
private fun ThinSlider(value: Float, maximum: Float, change: (Float) -> Unit, finish: () -> Unit,
                       modifier: Modifier, enabled: Boolean = true) {
    val color = LocalContentColor.current
    val currentChange by rememberUpdatedState(change)
    val currentFinish by rememberUpdatedState(finish)
    var dragging by remember { mutableStateOf(false) }
    Canvas(modifier.height(32.dp).pointerInput(maximum, enabled) {
        if (enabled) detectTapGestures { currentChange((it.x / size.width).coerceIn(0f, 1f) * maximum); currentFinish() }
    }.pointerInput(maximum, enabled) {
        if (enabled) detectDragGestures(onDragStart = {
            dragging = true
            currentChange((it.x / size.width).coerceIn(0f, 1f) * maximum)
        }, onDragEnd = { dragging = false; currentFinish() }, onDragCancel = { dragging = false; currentFinish() }) { pointer, _ ->
            pointer.consume()
            currentChange((pointer.position.x / size.width).coerceIn(0f, 1f) * maximum)
        }
    }) {
        val end = (value / maximum).coerceIn(0f, 1f) * size.width
        val center = size.height / 2
        drawLine(color.copy(alpha = .18f), Offset(0f, center), Offset(size.width, center), 2.dp.toPx(), StrokeCap.Round)
        drawLine(color.copy(alpha = .8f), Offset(0f, center), Offset(end, center),
            (if (dragging) 4.dp else 2.dp).toPx(), StrokeCap.Round)
    }
}

@Composable
internal fun Tool(label: String, icon: ImageVector, action: () -> Unit, enabled: Boolean = true,
                 size: Dp = 40.dp, iconSize: Dp = 22.dp) {
    TooltipArea(tooltip = { Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface, shadowElevation = 4.dp) {
        Text(label, Modifier.padding(horizontal = 10.dp, vertical = 7.dp), fontSize = 12.sp)
    } }) {
        SyncToneIconButton(action, Modifier.size(size), enabled, SyncToneButtonDensity.Desktop) { Icon(icon, label, Modifier.size(iconSize),
                tint = LocalContentColor.current.copy(alpha = if (enabled) 1f else .38f)) }
    }
}

@Composable
internal fun WindowControls(owner: Frame) {
    var maximized by remember { mutableStateOf(owner.extendedState and Frame.MAXIMIZED_BOTH == Frame.MAXIMIZED_BOTH) }
    DisposableEffect(owner) {
        val listener = java.awt.event.WindowStateListener {
            maximized = it.newState and Frame.MAXIMIZED_BOTH == Frame.MAXIMIZED_BOTH
        }
        owner.addWindowStateListener(listener)
        onDispose { owner.removeWindowStateListener(listener) }
    }
    Row(Modifier.padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Tool("最小化", Icons.Default.Remove, { owner.extendedState = owner.extendedState or Frame.ICONIFIED }, size = 34.dp, iconSize = 17.dp)
        Tool(if (maximized) "还原窗口" else "最大化", if (maximized) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
            {
                owner.extendedState = if (maximized) owner.extendedState and Frame.MAXIMIZED_BOTH.inv()
                else owner.extendedState or Frame.MAXIMIZED_BOTH
                maximized = !maximized
            }, size = 34.dp, iconSize = 17.dp)
        Tool("关闭", Icons.Default.Close, { owner.dispatchEvent(WindowEvent(owner, WindowEvent.WINDOW_CLOSING)) }, size = 34.dp, iconSize = 17.dp)
    }
}

private fun chooseFiles(owner: Frame, lyrics: Boolean, selected: (List<Path>) -> Unit) {
    val dialog = FileDialog(owner, if (lyrics) "打开 LRC 歌词" else "导入音乐", FileDialog.LOAD)
    dialog.isMultipleMode = !lyrics
    dialog.setFilenameFilter { _, name -> if (lyrics) name.endsWith(".lrc", true) else
        name.substringAfterLast('.', "").lowercase() in dev.salt.desktop.library.DesktopTrackReader.extensions }
    try {
        dialog.isVisible = true
        dialog.files.takeIf { it.isNotEmpty() }?.let { selected(it.map { f -> f.toPath() }) }
    } finally { dialog.dispose() }
}

private fun chooseMusicFolder(owner: Frame, selected: (Path) -> Unit) {
    val chooser = javax.swing.JFileChooser().apply {
        dialogTitle = "添加音乐目录"
        fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
        isAcceptAllFileFilterUsed = false
    }
    if (chooser.showOpenDialog(owner) == javax.swing.JFileChooser.APPROVE_OPTION) selected(chooser.selectedFile.toPath())
}

private fun time(ms: Long): String = "%d:%02d".format(ms.coerceAtLeast(0) / 60000, ms.coerceAtLeast(0) / 1000 % 60)
