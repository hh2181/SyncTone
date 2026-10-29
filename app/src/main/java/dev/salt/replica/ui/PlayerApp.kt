package dev.salt.replica.ui

import dev.salt.buttons.SyncToneIconButton as IconButton
import dev.salt.buttons.SyncToneTextButton as TextButton

import android.media.AudioManager
import android.content.Context
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.automirrored.filled.ManageSearch
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.view.WindowCompat
import dev.salt.replica.QaRequest
import dev.salt.replica.R
import dev.salt.replica.lyrics.LyricsView
import dev.salt.replica.lyrics.online.*
import dev.salt.replica.playback.PlayerStore
import dev.salt.replica.playback.Playlist
import dev.salt.replica.playback.Track
import dev.salt.replica.ui.theme.HyperTheme
import dev.salt.replica.ui.theme.HyperRed
import dev.salt.replica.ui.theme.hyperClickable
import dev.salt.replica.visual.FlowingLightView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.*

private val Ink = Color(0xFF191817)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PlayerApp(store: PlayerStore, settings: ReplicaSettings, qa: QaRequest? = null, syncRequest: Long = 0) {
    val scope = rememberCoroutineScope()
    val sheet = remember { PlayerSheetState(scope) }
    val pager = rememberPagerState { 2 }
    var panel by remember { mutableStateOf<String?>(null) }
    val drawer = remember { DrawerState(scope) }
    val drawerDensity = LocalDensity.current
    LaunchedEffect(drawerDensity) { drawer.widthPx = with(drawerDensity) { DrawerWidth.toPx() } }
    var section by remember { mutableStateOf("首页") }
    LaunchedEffect(syncRequest) { if (syncRequest > 0) section = "连接" }
    var statisticsDetailActive by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<DetailRequest?>(null) }
    val detailAnim = remember { Animatable(0f) }
    var songMenu by remember { mutableStateOf<SongMenuRequest?>(null) }
    var addSheetTrack by remember { mutableStateOf<Track?>(null) }
    var playlistDialog by remember { mutableStateOf<PlaylistDialogState?>(null) }
    var timerMinutes by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val onlineLyrics = remember(store, scope) {
        OnlineLyricsController(scope, java.io.File(context.filesDir, "online-lyrics.preference"), store::saveOnlineLyrics,
            hasLyrics = store::hasLyrics)
    }
    val onlineState by onlineLyrics.state.collectAsState()
    val lyricSong = store.track?.let { LyricSong(it.id, it.title, it.artist, it.duration) }
    LaunchedEffect(store.tracks) {
        onlineLyrics.updateLibrary(store.tracks.map { LyricSong(it.id, it.title, it.artist, it.duration) }, store.track?.id)
    }
    LaunchedEffect(lyricSong, store.track?.lyrics, onlineState.settings) {
        onlineLyrics.autoMatchFor(lyricSong, store.track?.lyrics?.lines?.isNotEmpty() != false)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val view = LocalView.current
    val expanded by remember { derivedStateOf { sheet.progress > .5f } }
    val surfaceVisible by remember { derivedStateOf { sheet.progress > 0f } }
    val pagerEnabled by remember { derivedStateOf { sheet.progress > .98f } }
    val lyricsVisible by remember { derivedStateOf { pager.currentPage + pager.currentPageOffsetFraction > 0f } }

    fun openDetail(target: DetailTarget, card: Rect, cover: Rect,
                   startRadius: Dp = 20.dp, accent: Color? = null, homeColors: HomeCardColors? = null) {
        if (detail != null) return
        detail = DetailRequest(target, card, cover, startRadius, accent, homeColors)
        scope.launch {
            detailAnim.snapTo(0f)
            detailAnim.animateTo(
                1f,
                tween(520, easing = CubicBezierEasing(0.2f, 0.9f, 0.25f, 1f))
            )
        }
    }

    fun closeDetail() {
        if (detail == null) return
        scope.launch {
            detailAnim.animateTo(0f, tween(300, easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)))
            detail = null
        }
    }

    val darkTheme = when (settings.themeMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val playerInk = if (settings.lightFlowing) Ink else Color.White
    val lightSystemBars = if (expanded) settings.lightFlowing else !darkTheme
    val barsOverHeader = detail?.let { d ->
        detailAnim.value > if (d.target.kind == LibraryKind.FAVORITES) .45f else .72f
    } ?: false
    SideEffect {
        (context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = lightSystemBars && !barsOverHeader
                isAppearanceLightNavigationBars = lightSystemBars
            }
        }
    }
    LaunchedEffect(qa?.sequence) { qa?.let { sheet.to(it.expanded); pager.scrollToPage(it.page.coerceIn(0, 1)) } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch { store.importAudio(uris) }
    }
    val lyricPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { store.importLyrics(uri) }
    }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            snapshotFlow { store.playing to surfaceVisible }.collectLatest { (playing, visible) ->
                if (playing && visible) while (true) withFrameNanos { store.onFrame() }
                else if (playing) while (true) { store.synchronize(); delay(200) }
                else store.synchronize()
            }
        }
    }
    LaunchedEffect(timerMinutes) {
        if (timerMinutes > 0) { delay(timerMinutes * 60000L); if (store.playing) store.toggle(); timerMinutes = 0 }
    }
    BackHandler(surfaceVisible && panel == null) { sheet.to(false) }
    BackHandler(section != "首页" && detail == null && !statisticsDetailActive && !surfaceVisible) { section = "首页" }
    BackHandler(drawer.open) { drawer.close() }
    BackHandler(detail != null) { closeDetail() }

    val pageBackground = if (darkTheme) Color(0xFF101217) else Color(0xFFF4F6FA)
    val pageSurface = if (darkTheme) Color(0xFF1A1D24) else Color.White
    val miniText = if (darkTheme) Color(0xFFF2F4F8) else Ink
    HyperTheme(darkTheme) {
        BoxWithConstraints(Modifier.fillMaxSize().background(pageBackground)) {
            val density = LocalDensity.current
            val h = maxHeight
            val w = maxWidth
            val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding().coerceAtLeast(6.dp)
            val miniHeight = 68.dp + nav
            val travel = with(density) { (h - miniHeight).toPx() }
            sheet.travel = travel
            sheet.density = density.density
            val p = { sheet.progress }
            val contentAlpha = { smoothRange(p(), .5f, .96f) }
            val headerY = 57.dp
            val coverSize = (w - 60.dp).coerceAtMost(480.dp)
            val coverTop = 143.dp.coerceAtMost(h * .19f)
            val controlBottom = nav + 6.dp
            val fullW = with(density) { w.toPx() }
            val fullH = with(density) { h.toPx() }
            val miniSurface = remember(fullW, travel, density.density) {
                Rect(with(density) { 16.dp.toPx() }, travel + with(density) { 6.dp.toPx() },
                    fullW - with(density) { 16.dp.toPx() }, travel + with(density) { 62.dp.toPx() })
            }
            val surface = remember(miniSurface, fullW, fullH, density.density) {
                PlayerSurface(miniSurface, Rect(0f, 0f, fullW, fullH), with(density) { 28.dp.toPx() })
            }
            val drag = Modifier.draggable(
                rememberDraggableState { sheet.drag(it) }, Orientation.Vertical,
                onDragStarted = { sheet.begin() }, onDragStopped = { sheet.settle(it) })
            val pagePosition = { pager.currentPage + pager.currentPageOffsetFraction }
            val nested = remember(sheet) {
                object : NestedScrollConnection {
                    var dragging = false
                    fun move(y: Float): Offset {
                        if (!dragging) { dragging = true; sheet.begin() }
                        val before = sheet.progress
                        sheet.drag(y)
                        return Offset(0f, (before - sheet.progress) * sheet.travel)
                    }
                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                        if (source == NestedScrollSource.UserInput && sheet.progress < 1f) move(available.y) else Offset.Zero
                    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                        if (source == NestedScrollSource.UserInput && available.y > 0f) move(available.y) else Offset.Zero
                    override suspend fun onPreFling(available: Velocity): Velocity {
                        if (!dragging) return Velocity.Zero
                        dragging = false; sheet.settle(available.y)
                        return available
                    }
                }
            }

            val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val dashboardModifier = remember(miniHeight, topInset) {
                Modifier.fillMaxSize().padding(top = topInset, bottom = miniHeight)
            }
            val swipeEnabled = !expanded && detail == null && !drawer.open && !statisticsDetailActive
            DrawerBlurHost(drawer) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(swipeEnabled, section) {
                        if (!swipeEnabled) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (section == "歌词更新" && down.position.x > 24.dp.toPx()) return@awaitEachGesture
                            val tracker = VelocityTracker()
                            tracker.addPosition(down.uptimeMillis, down.position)
                            var dx = 0f
                            var dy = 0f
                            var active = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                tracker.addPosition(change.uptimeMillis, change.position)
                                if (!change.pressed) break
                                val delta = change.positionChange()
                                dx += delta.x
                                dy += delta.y
                                if (!active && dx > 18.dp.toPx() && dx > abs(dy) * 1.3f) {
                                    active = true
                                    drawer.beginDrag()
                                    drawer.drag(dx)
                                } else if (active) {
                                    drawer.drag(delta.x)
                                }
                                if (active) change.consume()
                            }
                            if (active) drawer.settle(tracker.calculateVelocity().x)
                        }
                    }
            ) {
            AnimatedContent(
                targetState = section,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    (fadeIn(tween(220)) + scaleIn(initialScale = .94f, animationSpec = tween(260, easing = FastOutSlowInEasing)))
                        .togetherWith(fadeOut(tween(150)) + scaleOut(targetScale = .96f, animationSpec = tween(170)))
                },
                label = "section"
            ) { current ->
                when (current) {
                    "全部歌曲" -> SongsScreen(
                        store, { drawer.open() }, { picker.launch(arrayOf("audio/*")) },
                        { track -> store.playTrack(track.id) },
                        { track -> songMenu = SongMenuRequest(track, null) },
                        dashboardModifier
                    )
                    "专辑" -> AlbumsScreen(
                        store, { drawer.open() }, { picker.launch(arrayOf("audio/*")) },
                        { target, card, cover -> openDetail(target, card, cover) },
                        dashboardModifier
                    )
                    "艺人" -> ArtistsScreen(
                        store, { drawer.open() }, { picker.launch(arrayOf("audio/*")) },
                        { target, card, cover -> openDetail(target, card, cover) },
                        dashboardModifier
                    )
                    "设置" -> SettingsScreen(settings, { drawer.open() }, { panel = "lyrics" }, dashboardModifier)
                    "统计" -> StatisticsScreen(store, { drawer.open() }, surfaceVisible, { statisticsDetailActive = it }, dashboardModifier)
                    "连接" -> SyncScreen(store, { drawer.open() }, dashboardModifier)
                    "扫描音乐" -> MusicScanScreen({ drawer.open() }, dashboardModifier)
                    "歌词更新" -> LyricsUpdateScreen(onlineLyrics, dashboardModifier, { drawer.open() })
                    else -> HomeScreen(
                        store, darkTheme, { drawer.open() }, { picker.launch(arrayOf("audio/*")) },
                        { sheet.to(true) }, { track -> store.playTrack(track.id) },
                        { section = "全部歌曲" },
                        { track -> songMenu = SongMenuRequest(track, null) },
                        { card, cover ->
                            val colors = homeFavoriteColors(darkTheme)
                            openDetail(DetailTarget(LibraryKind.FAVORITES, ""), card, cover,
                                startRadius = 22.dp, accent = homeFavoriteHeaderColor(darkTheme),
                                homeColors = colors)
                        },
                        dashboardModifier
                    )
                }
            }
            }
            }
            Box(Modifier.fillMaxSize().graphicsLayer {
                val t = p()
                translationY = travel * (1 - t)
                shape = surface.shape(t, translationY)
                clip = true
            }.background(pageSurface)) {
                AndroidView(factory = { FlowingLightView(it) }, modifier = Modifier.fillMaxSize().graphicsLayer {
                    alpha = smoothRange(p(), .12f, .5f)
                }, update = {
                    it.dark = !settings.lightFlowing
                    it.setArtwork(store.track?.artwork)
                    it.configure(p() > 0 && settings.flowing && lifecycleState.isAtLeast(Lifecycle.State.STARTED), settings.reduced, settings.mesh)
                })
                // Mini-player and full player share this fixed-size layer.
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().height(56.dp)
                    .graphicsLayer {
                        alpha = (1 - smoothRange(p(), 0f, .16f)) *
                            (1 - smoothRange(detailAnim.value, 0f, .20f))
                    }
                    .then(drag)
                    .clickable(enabled = !expanded) { sheet.to(true) }.padding(start = 62.dp, end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(store.track?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, color = miniText, fontSize = 15.sp)
                        val miniIndex by remember(store.track) { derivedStateOf { store.track?.lyrics?.indexAt(store.framePosition.longValue) ?: -1 } }
                        Text(store.track?.lyrics?.lines?.getOrNull(miniIndex)?.text ?: store.track?.artist.orEmpty(),
                            maxLines = 1, overflow = TextOverflow.Ellipsis, color = miniText.copy(alpha = .58f), fontSize = 12.sp)
                    }
                    PlayerIcon(if (store.playing) R.drawable.ic_mini_player_pause else R.drawable.ic_mini_player_play,
                        "播放或暂停", 40.dp, 26.dp, miniText, store::toggle)
                    PlayerIcon(R.drawable.ic_play_queue, "播放队列", 40.dp, 26.dp, miniText) { panel = "queue" }
                }

                if (surfaceVisible) CompositionLocalProvider(LocalContentColor provides playerInk) {
                Column(Modifier.fillMaxSize().nestedScroll(nested).graphicsLayer { alpha = contentAlpha() }) {
                    Row(Modifier.fillMaxWidth().padding(start = 30.dp, end = 24.dp, top = headerY).height(54.dp).then(drag),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(store.track?.title.orEmpty(), Modifier.basicMarquee(), fontSize = 24.sp, fontWeight = FontWeight.Medium,
                                color = playerInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(store.track?.artist.orEmpty(), Modifier.padding(top = 2.dp), fontSize = 14.sp,
                                color = playerInk.copy(alpha = .5f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        OutputIcon { panel = "volume" }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth().graphicsLayer { clip = true }) {
                        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1,
                            userScrollEnabled = pagerEnabled, key = { it }) { page ->
                            Column(Modifier.fillMaxSize()) {
                                if (page == 0) Column(Modifier.weight(1f).fillMaxWidth().then(drag).padding(horizontal = 30.dp)) {
                                    Spacer(Modifier.height((coverTop - headerY - 54.dp).coerceAtLeast(0.dp)))
                                    Spacer(Modifier.size(coverSize).align(Alignment.CenterHorizontally)
                                        .clickable { scope.launch { pager.animateScrollToPage(1) } })
                                    Spacer(Modifier.height(16.dp))
                                    LyricsView(store, settings, Modifier.fillMaxWidth().height(78.dp), compact = true,
                                        contentColor = playerInk,
                                        onTap = { scope.launch { pager.animateScrollToPage(1) } })
                                    Spacer(Modifier.height(182.dp + controlBottom))
                                } else {
                                    LyricsView(store, settings,
                                        Modifier.weight(1f).fillMaxWidth().padding(start = 30.dp, end = 30.dp, top = 12.dp),
                                        componentOffset = (-91).dp,
                                        contentColor = playerInk,
                                        visible = lyricsVisible,
                                        onLongPress = { panel = "lyrics" })
                                    Spacer(Modifier.height(76.dp))
                                }
                            }
                        }
                        PlayerControls(store, Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .padding(horizontal = 30.dp).graphicsLayer {
                                translationX = -pagePosition().coerceIn(0f, 1f) * fullW
                            },
                            onMode = store::cycleMode, onTimer = { panel = "timer" }, onEffects = { panel = "volume" },
                            onQueue = { panel = "queue" }, onMore = { panel = "lyrics" }, bottom = controlBottom)
                        LyricsFooter(store, settings, nav, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(76.dp)
                            .graphicsLayer { translationX = (1f - pagePosition().coerceIn(0f, 1f)) * fullW },
                            onOpenSettings = { panel = "lyrics" })
                    }
                }
                }
            }
            val coverPx = with(density) { coverSize.toPx() }
            val dstX = with(density) { ((w - coverSize) / 2).toPx() }
            val dstY = with(density) { coverTop.toPx() }
            val srcX = with(density) { 32.dp.toPx() }
            val srcY = travel + with(density) { 17.dp.toPx() }
            val srcSize = with(density) { 34.dp.toPx() }
            val morph = remember(fullW, fullH, travel, coverPx, srcX, srcY, dstX, dstY) {
                ArtworkMorph(Rect(srcX, srcY, srcX + srcSize, srcY + srcSize),
                    Rect(dstX, dstY, dstX + coverPx, dstY + coverPx),
                    miniSurface,
                    Rect(0f, 0f, fullW, fullH), with(density) { 5.dp.toPx() }, with(density) { 8.dp.toPx() }, travel)
            }
            val morphValues = remember { FloatArray(6) }
            // One artwork instance persists across the entire sheet transition.
            Box(Modifier.fillMaxSize().graphicsLayer { shape = surface.shape(p()); clip = true }) {
                Box(Modifier.size(coverSize).graphicsLayer {
                val t = p()
                morph.evaluate(t, morphValues)
                val sx = morphValues[2] / coverPx
                val sy = morphValues[3] / coverPx
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = sx; scaleY = sy
                translationX = morphValues[0] - pagePosition() * fullW * t
                translationY = morphValues[1]
                alpha = 1 - pagePosition().coerceIn(0f, 1f) * smoothRange(t, 0f, .85f)
                shape = RoundedCornerShape(with(density) { (morphValues[4] / sx.coerceAtLeast(.001f)).toDp() })
                clip = true
                shadowElevation = 12.dp.toPx() * t
                }) {
                    Artwork(store.track?.artwork, Modifier.fillMaxSize().graphicsLayer {
                        val q = 1 - p()
                        val opticalScale = 1 + .01f * (1 - q * q)
                        scaleX = opticalScale; scaleY = opticalScale
                    })
                }
            }
            if (panel != null) ModalBottomSheet(onDismissRequest = { panel = null },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = panel == "lyrics"),
                containerColor = MaterialTheme.colorScheme.surface) {
                when (panel) {
                    "queue" -> {
                        PanelTitle("播放队列") { picker.launch(arrayOf("audio/*")) }
                        MusicListCard(Modifier.padding(horizontal = 16.dp).padding(bottom = 30.dp)) {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                            itemsIndexed(store.activeQueue, key = { _, track -> track.id }) { i, track ->
                                SongRow(track, i + 1, store.track?.id == track.id, { store.select(i); panel = null }, null)
                                if (i < store.activeQueue.lastIndex) MusicListDivider()
                            }
                        }
                        }
                    }
                    "lyrics" -> SettingsPanel(settings,
                        onImport = { lyricPicker.launch(arrayOf("*/*")); panel = null },
                        onOnline = { panel = null; section = "歌词更新"; sheet.to(false) },
                        onRestore = { scope.launch {
                            try { store.restoreLyrics() }
                            catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (_: Exception) { android.widget.Toast.makeText(context, "恢复失败，请检查音乐文件是否可读", android.widget.Toast.LENGTH_SHORT).show() }
                        } },
                        hasTrack = store.track != null)
                    "timer" -> {
                        PanelTitle("睡眠定时")
                        listOf(0, 15, 30, 45, 60, 90).forEach { minutes ->
                            ListItem(headlineContent = { Text(if (minutes == 0) "关闭" else "$minutes 分钟") },
                                trailingContent = { RadioButton(timerMinutes == minutes, onClick = null) },
                                modifier = Modifier.clickable { timerMinutes = minutes; panel = null })
                        }
                    }
                    "volume" -> {
                        PanelTitle("音频输出")
                        val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
                        var volume by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()) }
                        Text("媒体音量", Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
                        Slider(volume, { volume = it; audio.setStreamVolume(AudioManager.STREAM_MUSIC, it.roundToInt(), 0) },
                            valueRange = 0f..audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat(), modifier = Modifier.padding(horizontal = 24.dp))
                        Spacer(Modifier.height(40.dp))
                    }
                }
            }
            NavigationDrawer(
                state = drawer,
                selected = section,
                playlists = store.playlists,
                favoriteCount = store.favorites.size,
                onDismiss = { drawer.close() },
                onSelect = { section = it; drawer.close() },
                onOpenPlaylist = { target, card, cover ->
                    openDetail(
                        target, card, cover, startRadius = 14.dp,
                        accent = if (target.kind == LibraryKind.FAVORITES) HyperRed else null
                    )
                },
                onCreatePlaylist = { playlistDialog = PlaylistDialogState.Create(null) }
            )

            detail?.let { request ->
                DetailOverlay(
                    store = store,
                    request = request,
                    progress = detailAnim.value,
                    viewport = Rect(0f, 0f, fullW, fullH),
                    onClose = { closeDetail() },
                    onPlay = { list, index -> store.playQueue(list, index) },
                    onMore = { track, playlistId -> songMenu = SongMenuRequest(track, playlistId) },
                    onRename = { playlistDialog = PlaylistDialogState.Rename(it) },
                    onDelete = { playlistDialog = PlaylistDialogState.Delete(it) }
                )
            }

            songMenu?.let { request ->
                ModalBottomSheet(
                    onDismissRequest = { songMenu = null },
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    SongActionsSheet(
                        store, request,
                        onDismiss = { songMenu = null },
                        onAddToPlaylist = { songMenu = null; addSheetTrack = request.track },
                        onRemoveFromPlaylist = {
                            request.playlistId?.let { store.removeFromPlaylist(it, request.track.id) }
                            songMenu = null
                        }
                    )
                }
            }

            addSheetTrack?.let { track ->
                ModalBottomSheet(
                    onDismissRequest = { addSheetTrack = null },
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    AddToPlaylistSheet(
                        store, track,
                        onPick = { id -> store.addToPlaylist(id, track.id); addSheetTrack = null },
                        onCreate = { addSheetTrack = null; playlistDialog = PlaylistDialogState.Create(track.id) },
                        onDismiss = { addSheetTrack = null }
                    )
                }
            }

            when (val dialog = playlistDialog) {
                is PlaylistDialogState.Create -> PlaylistNameDialog(
                    title = "新建歌单", initial = "",
                    onConfirm = { name ->
                        val created = store.createPlaylist(name)
                        dialog.trackId?.let { store.addToPlaylist(created.id, it) }
                        playlistDialog = null
                    },
                    onDismiss = { playlistDialog = null }
                )
                is PlaylistDialogState.Rename -> PlaylistNameDialog(
                    title = "重命名歌单", initial = dialog.playlist.name,
                    onConfirm = { name -> store.renamePlaylist(dialog.playlist.id, name); playlistDialog = null },
                    onDismiss = { playlistDialog = null }
                )
                is PlaylistDialogState.Delete -> ConfirmDialog(
                    title = "删除歌单",
                    message = "「${dialog.playlist.name}」将被删除，音乐文件不会受影响。",
                    onConfirm = { store.deletePlaylist(dialog.playlist.id); playlistDialog = null },
                    onDismiss = { playlistDialog = null }
                )
                null -> Unit
            }
        }
    }
}

@Composable
private fun DrawerBlurHost(state: DrawerState, content: @Composable () -> Unit) {
    val p = state.progress
    val stepped = (p * 10f).roundToInt() / 10f
    val radius = if (stepped > .02f) (stepped * 20f).dp else 0.dp
    Box(Modifier.fillMaxSize().blur(radius)) { content() }
}

@Composable
private fun NavigationDrawer(
    state: DrawerState,
    selected: String,
    playlists: List<Playlist>,
    favoriteCount: Int,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onOpenPlaylist: (DetailTarget, Rect, Rect) -> Unit,
    onCreatePlaylist: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val entries = listOf(
        "首页" to R.drawable.ic_nav_home,
        "全部歌曲" to R.drawable.ic_nav_songs,
        "扫描音乐" to R.drawable.ic_nav_songs,
        "专辑" to R.drawable.ic_nav_album,
        "艺人" to R.drawable.ic_nav_artist,
        "统计" to R.drawable.ic_nav_stats,
        "设置" to R.drawable.ic_nav_settings,
        "连接" to R.drawable.ic_nav_cast,
        "歌词更新" to R.drawable.ic_nav_lyrics
    )
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = .34f * state.progress }
                .background(Color.Black)
                .then(if (state.open) Modifier.clickable(onClick = onDismiss) else Modifier)
        )
        Column(
            Modifier
                .fillMaxHeight()
                .width(DrawerWidth)
                .graphicsLayer {
                    translationX = -size.width * (1f - state.progress)
                }
                .clip(RoundedCornerShape(topEnd = 26.dp, bottomEnd = 26.dp))
                .background(colors.surface)
                .clickable(enabled = false, onClick = {})
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                "音乐库", fontSize = 11.sp, letterSpacing = 1.4.sp, color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp)
            )
            entries.forEach { (label, icon) ->
                NavigationItem(label, icon, label == selected) { onSelect(label) }
            }
            Spacer(Modifier.height(22.dp))
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "我的歌单", fontSize = 11.sp, letterSpacing = 1.4.sp,
                    color = colors.onSurfaceVariant, modifier = Modifier.weight(1f)
                )
                IconButton(onCreatePlaylist, Modifier.size(32.dp)) {
                    Icon(Icons.Default.Add, "新建歌单", tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
            PlaylistDrawerRow(
                "最爱收藏", favoriteCount, Color(0xFFF05A63),
                DetailTarget(LibraryKind.FAVORITES, ""), onOpenPlaylist
            )
            playlists.forEach { playlist ->
                PlaylistDrawerRow(
                    playlist.name, playlist.trackIds.size, playlistDot(playlist.name),
                    DetailTarget(LibraryKind.PLAYLIST, playlist.id), onOpenPlaylist
                )
            }
            if (playlists.isEmpty()) {
                Text(
                    "还没有歌单，点 + 新建", fontSize = 12.sp, color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 14.dp, top = 10.dp)
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private val playlistDots = listOf(
    Color(0xFF8B5CF6), Color(0xFF3482FF), Color(0xFF34A853), Color(0xFFF59E0B), Color(0xFFEC4899)
)

private fun playlistDot(name: String): Color = playlistDots[(name.hashCode() and 0x7fffffff) % playlistDots.size]

@Composable
private fun PlaylistDrawerRow(
    name: String,
    count: Int,
    dot: Color,
    target: DetailTarget,
    onOpen: (DetailTarget, Rect, Rect) -> Unit
) {
    val density = LocalDensity.current
    var rowRect by remember { mutableStateOf(Rect.Zero) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .onGloballyPositioned { rowRect = it.boundsInRoot() }
            .clip(RoundedCornerShape(14.dp))
            .hyperClickable {
                val card = rowRect
                val side = with(density) { 38.dp.toPx() }
                val left = card.left + with(density) { 12.dp.toPx() }
                val cover = Rect(left, card.center.y - side / 2, left + side, card.center.y + side / 2)
                onOpen(target, card, cover)
            }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Text(
            name, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).padding(start = 14.dp),
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Text("$count", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NavigationItem(label: String, icon: Int, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) colors.primaryContainer else Color.Transparent)
            .hyperClickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (label == "扫描音乐") Icon(Icons.AutoMirrored.Filled.ManageSearch, label,
            tint = if (selected) colors.primary else colors.onSurfaceVariant, modifier = Modifier.size(21.dp))
        else Icon(
            painterResource(icon), label,
            tint = if (selected) colors.primary else colors.onSurfaceVariant,
            modifier = Modifier.size(21.dp)
        )
        Text(
            label, fontSize = 15.sp,
            color = if (selected) colors.primary else colors.onSurface,
            modifier = Modifier.padding(start = 14.dp)
        )
    }
}

@Composable
internal fun Artwork(bitmap: android.graphics.Bitmap?, modifier: Modifier) {
    if (bitmap != null) Image(bitmap.asImageBitmap(), "专辑封面", modifier, contentScale = ContentScale.Crop)
    else Box(modifier.background(Color(0xFFCED0D5)), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize(.5f)) {
            drawCircle(Color.White.copy(alpha = .7f))
            drawCircle(Color(0xFFCED0D5), radius = size.minDimension * .18f)
        }
    }
}

@Composable
private fun PlayerIcon(resource: Int, description: String, size: Dp = 44.dp, iconSize: Dp = 24.dp,
                       color: Color = LocalContentColor.current, action: () -> Unit) {
    IconButton(action, Modifier.size(size)) {
        Icon(painterResource(resource), description, Modifier.size(iconSize), tint = color)
    }
}

@Composable
private fun OutputIcon(action: () -> Unit) {
    val color = LocalContentColor.current
    IconButton(action, Modifier.size(40.dp)) {
        Icon(painterResource(R.drawable.ic_media_router), "音频输出", Modifier.size(20.dp), tint = color.copy(alpha = .85f))
    }
}

@Composable
private fun TranslationIcon(enabled: Boolean, action: () -> Unit) {
    val color = LocalContentColor.current
    IconButton(action, Modifier.size(32.dp)) {
        val painter = painterResource(R.drawable.ic_translation)
        Box(Modifier.size(18.dp).graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .clip(RoundedCornerShape(3.dp)).drawWithContent {
                if (enabled) {
                    drawRect(color.copy(alpha = .65f))
                    drawContext.canvas.saveLayer(androidx.compose.ui.geometry.Rect(Offset.Zero, size),
                        Paint().apply { blendMode = BlendMode.DstOut })
                    with(painter) { draw(size) }
                    drawContext.canvas.restore()
                } else {
                    drawContent()
                }
            }) {
            if (!enabled) Icon(painter, "翻译", Modifier.fillMaxSize().border(.8.dp, color.copy(alpha = .2f), RoundedCornerShape(3.dp)).padding(2.dp),
                tint = color.copy(alpha = .65f))
        }
    }
}

private fun time(ms: Long): String = "%02d:%02d".format(ms / 60000, ms / 1000 % 60)

@Composable
private fun PlayerControls(store: PlayerStore, modifier: Modifier, onMode: () -> Unit, onTimer: () -> Unit,
                           onEffects: () -> Unit, onQueue: () -> Unit, onMore: () -> Unit, bottom: Dp) {
    val color = LocalContentColor.current
    Column(modifier) {
        var seek by remember { mutableStateOf<Float?>(null) }
        Canvas(Modifier.fillMaxWidth().height(24.dp).pointerInput(store.track?.id) {
            detectDragGestures(onDragEnd = { seek?.let { store.seek((it * (store.track?.duration ?: 0)).toLong()) }; seek = null },
                onDragCancel = { seek = null }, onDragStart = { seek = (it.x / size.width).coerceIn(0f, 1f) },
                onDrag = { change, _ -> change.consume(); seek = (change.position.x / size.width).coerceIn(0f, 1f) })
        }.pointerInput(store.track?.id) {
            detectTapGestures { store.seek((it.x / size.width * (store.track?.duration ?: 0)).toLong()) }
        }) {
            val progress = seek ?: (store.framePosition.longValue.toFloat() / (store.track?.duration ?: 1).coerceAtLeast(1)).coerceIn(0f, 1f)
            val y = size.height / 2
            drawLine(color.copy(alpha = .24f), Offset(0f, y), Offset(size.width, y), 2.dp.toPx(), StrokeCap.Round)
            drawLine(color, Offset(0f, y), Offset(size.width * progress, y), if (seek == null) 2.dp.toPx() else 4.dp.toPx(), StrokeCap.Round)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(time(seek?.let { (it * (store.track?.duration ?: 0)).toLong() } ?: store.coarsePosition), color = color.copy(alpha = .65f), fontSize = 12.sp, lineHeight = 16.sp)
            Text(time(store.track?.duration ?: 0), color = color.copy(alpha = .65f), fontSize = 12.sp, lineHeight = 16.sp)
        }
        Row(Modifier.fillMaxWidth().height(70.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            PlayerIcon(R.drawable.ic_round_skip_previous_24, "上一首", 80.dp, 40.dp, action = store::previous)
            PlayerIcon(if (store.playing) R.drawable.ic_round_pause_24 else R.drawable.ic_round_play_arrow_24,
                "播放或暂停", 80.dp, 54.dp, action = store::toggle)
            PlayerIcon(R.drawable.ic_round_skip_next_24, "下一首", 80.dp, 40.dp, action = store::next)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().height(56.dp).graphicsLayer { translationY = -4.dp.toPx() }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            PlayerIcon(if (store.shuffle) R.drawable.ic_player_random else if (store.repeat == 1) R.drawable.ic_player_repeat_one else R.drawable.ic_player_circle, "播放模式", action = onMode)
            PlayerIcon(R.drawable.ic_sleep_timer, "睡眠定时", action = onTimer)
            PlayerIcon(R.drawable.ic_audio_effect, "音效", action = onEffects)
            PlayerIcon(R.drawable.ic_play_queue, "播放队列", action = onQueue)
            PlayerIcon(R.drawable.ic_player_more, "更多", action = onMore)
        }
        Spacer(Modifier.height(bottom))
    }
}

@Composable
private fun LyricsFooter(store: PlayerStore, settings: ReplicaSettings, nav: Dp, modifier: Modifier, onOpenSettings: () -> Unit) {
    val color = LocalContentColor.current
    Row(modifier.padding(start = 23.dp, end = 24.dp, bottom = nav).graphicsLayer { translationY = -10.dp.toPx() },
        verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clickable(onClick = onOpenSettings), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).padding(7.dp).border(.8.dp, color.copy(alpha = .2f), RoundedCornerShape(3.dp)),
                contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_player_lyrics), "歌词设置", Modifier.requiredSize(27.dp), tint = color.copy(alpha = .85f))
            }
            Text(store.track?.lyrics?.source ?: "EMBEDDED", color = color.copy(alpha = .6f),
                fontSize = 12.sp, modifier = Modifier.padding(start = 1.dp))
        }
        TranslationIcon(settings.translation) {
            settings.translation = !settings.translation
            settings.save()
        }
    }
}

@Composable
private fun PanelTitle(title: String, add: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Medium)
        if (add != null) IconButton(add) { Icon(Icons.Default.Add, "打开音频") }
    }
}

@Composable
private fun SettingsPanel(settings: ReplicaSettings, onImport: () -> Unit, onOnline: () -> Unit,
                          onRestore: () -> Unit, hasTrack: Boolean) {
    PanelTitle("歌词设置")
    Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState()).padding(bottom = 30.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            dev.salt.buttons.SyncToneButton(onOnline, Modifier.weight(1f), enabled = hasTrack) { Text("联网下载歌词") }
            TextButton(onImport, enabled = hasTrack) { Text("打开 LRC") }
        }
        TextButton(onRestore, enabled = hasTrack, modifier = Modifier.padding(horizontal = 16.dp)) { Text("恢复原歌词") }
        SettingsSlider("字号", settings.fontSize, 16f..40f, 23, { "${it.roundToInt()} sp" }) {
            settings.fontSize = it; settings.save()
        }
        SettingsSlider("字重", settings.fontWeight.toFloat(), 100f..900f, 7, { "${it.roundToInt()}" }) {
            settings.fontWeight = (it / 100).roundToInt() * 100; settings.save()
        }
        Text("动画曲线", Modifier.padding(24.dp, 12.dp, 24.dp, 4.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            listOf("标准", "柔和", "线性").forEachIndexed { index, label ->
                SegmentedButton(selected = settings.lyricCurve == index,
                    onClick = { settings.lyricCurve = index; settings.save() },
                    shape = SegmentedButtonDefaults.itemShape(index, 3)) { Text(label) }
            }
        }
        SettingsSlider("动画速度", settings.lyricSpeed, .5f..2f, 14,
            { "%.1fx".format(java.util.Locale.US, it) }) {
            settings.lyricSpeed = it; settings.save()
        }
        SettingToggle("歌词模糊", settings.blur) { settings.blur = it; settings.save() }
        SettingToggle("逐字辉光", settings.glow) { settings.glow = it; settings.save() }
        SettingToggle("歌词错峰", settings.stagger) { settings.stagger = it; settings.save() }
        SettingToggle("显示翻译", settings.translation) { settings.translation = it; settings.save() }
        SettingToggle("歌词居中", settings.center) { settings.center = it; settings.save() }
        SettingToggle("背景流光", settings.flowing) { settings.flowing = it; settings.save() }
        SettingToggle("浅色流光", settings.lightFlowing) { settings.lightFlowing = it; settings.save() }
        SettingToggle("减弱流光效果", settings.reduced) { settings.reduced = it; settings.save() }
        Text("流光形态  ${settings.mesh + 1}", Modifier.padding(24.dp, 12.dp))
        Slider(settings.mesh.toFloat(), { settings.mesh = it.roundToInt() }, valueRange = 0f..4f, steps = 3,
            onValueChangeFinished = settings::save, modifier = Modifier.padding(horizontal = 24.dp))
    }
}

@Composable
private fun SettingsSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int,
                           label: (Float) -> String, onCommit: (Float) -> Unit) {
    var preview by remember(value) { mutableFloatStateOf(value) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        Text(label(preview), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Slider(preview, { preview = it }, valueRange = range, steps = steps,
        onValueChangeFinished = { onCommit(preview) }, modifier = Modifier.padding(horizontal = 24.dp))
}

@Composable
private fun SettingToggle(title: String, checked: Boolean, changed: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { changed(!checked) }.padding(horizontal = 24.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 16.sp)
        Switch(checked, changed)
    }
}
