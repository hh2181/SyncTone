package dev.salt.replica.ui

import dev.salt.buttons.SyncToneIconButton as IconButton
import dev.salt.buttons.SyncToneTextButton as TextButton

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Paint
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.salt.replica.R
import dev.salt.replica.playback.PlayerStore
import dev.salt.replica.statistics.*
import dev.salt.replica.ui.theme.HyperSearchField
import dev.salt.replica.ui.theme.hyperClickable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

private enum class StatPage { DAYS, HOURS, ARTISTS, ALBUMS, ARTIST, ALBUM }
private data class StatDestination(val page: StatPage, val key: String = "")
private data class StatLaunch(
    val destination: StatDestination,
    val sourceKey: String,
    val rect: Rect,
    val artRect: Rect?,
    val artwork: Bitmap?,
    val artRadius: Float,
    val source: @Composable (Boolean) -> Unit
)
private class StatTransition(val request: StatLaunch) {
    val progress = Animatable(0f)
    var job: Job? = null
    var targetArt by mutableStateOf<Rect?>(null)
    var pageCoordinates: LayoutCoordinates? = null
}

private val StatGreen = Color(0xFF249773)
private val DayLabel = DateTimeFormatter.ofPattern("M/d")
private val HourLabel = DateTimeFormatter.ofPattern("HH:mm")
private val FullDate = DateTimeFormatter.ofPattern("yyyy/M/d")
private fun countText(value: Long): String = NumberFormat.getIntegerInstance().format(value)
private fun timeText(ms: Long): String = when {
    ms >= HOUR_MS -> "${ms / HOUR_MS}小时 ${ms / 60_000 % 60}分"
    ms >= 60_000 -> "${ms / 60_000}分钟"
    ms > 0 -> "${ms / 1_000}秒"
    else -> "0分钟"
}
private fun periodText(start: Long, end: Long, zone: ZoneId): String {
    val a = Instant.ofEpochMilli(start).atZone(zone)
    val b = Instant.ofEpochMilli(end).atZone(zone)
    return "${a.format(DayLabel)} ${a.format(HourLabel)} — ${b.format(DayLabel)} ${b.format(HourLabel)}"
}

@Composable
fun StatisticsScreen(store: PlayerStore, onMenu: () -> Unit, playerExpanded: Boolean,
                     onDetailActive: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val repository = store.statistics
    val state by repository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val stack = remember { mutableStateListOf<StatTransition>() }
    val context = LocalContext.current
    val density = LocalDensity.current
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var zone by remember { mutableStateOf(ZoneId.systemDefault()) }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    val now = remember(state.snapshot, clock, zone) { System.currentTimeMillis() }
    val days = remember(now, zone) { recentDays(now, zone) }
    val hours = remember(now) { recentHours(now) }
    val snapshot = state.snapshot
    val dayValues = remember(snapshot, days) { days.map { snapshot.between(it.start, it.end).sumOf { song -> song.listenedMs }.toDouble() / HOUR_MS } }
    val hourValues = remember(snapshot, hours) { hours.map { snapshot.between(it.start, it.end).sumOf { song -> song.plays }.toDouble() } }
    val artists = remember(snapshot) { snapshot.artists() }
    val albums = remember(snapshot) { snapshot.albums() }
    val art = remember(store.tracks) { store.tracks.associate { songIdentity(it.title, it.artist) to it.artwork } }
    val green = if (MaterialTheme.colorScheme.background.luminance() < .5f) Color(0xFF64CBA7) else StatGreen
    val animationsEnabled = remember(context) { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f }

    fun close() {
        val transition = stack.lastOrNull() ?: return
        val velocity = transition.progress.velocity
        transition.job?.cancel()
        transition.job = scope.launch {
            val start = transition.progress.value
            val duration = if (animationsEnabled) (STATISTICS_CLOSE_DURATION_MS * start.coerceAtLeast(.45f)).roundToInt() else 0
            transition.progress.animateTo(0f, tween(duration, easing = statisticsReverseEasing(start, 0f, velocity, duration)))
            stack.remove(transition)
        }
    }
    fun open(request: StatLaunch) {
        if (stack.lastOrNull()?.progress?.isRunning == true || request.rect.width <= 0) return
        val transition = StatTransition(request)
        stack += transition
        transition.job = scope.launch {
            // Let the new lazy content lay out before starting the visible transform.
            withFrameNanos { }
            withFrameNanos { }
            transition.progress.animateTo(1f, tween(if (animationsEnabled) STATISTICS_OPEN_DURATION_MS else 0, easing = CardOpenEasing))
        }
    }
    LaunchedEffect(stack.size) { onDetailActive(stack.isNotEmpty()) }
    DisposableEffect(Unit) { onDispose { onDetailActive(false) } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { zone = ZoneId.systemDefault(); clock = System.currentTimeMillis(); repository.refresh() }
    LaunchedEffect(Unit) { while (true) { delay(10_000); clock = System.currentTimeMillis() } }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { zone = ZoneId.systemDefault(); clock = System.currentTimeMillis(); repository.refresh() }
        }
        val filter = IntentFilter().apply { addAction(Intent.ACTION_TIMEZONE_CHANGED); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_DATE_CHANGED) }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    BackHandler(stack.isNotEmpty() && !playerExpanded) { close() }
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
      BoxWithConstraints(modifier) {
        val viewport = Rect(0f, 0f, with(density) { maxWidth.toPx() }, with(density) { maxHeight.toPx() })
        Box(Modifier.fillMaxSize().onGloballyPositioned { rootOrigin = it.boundsInRoot().topLeft }) {
            StatisticsOverview(
                snapshot, state.loading, state.error, days, dayValues, hours, hourValues, artists, albums, art, zone, green,
                onMenu, repository::refresh, ::open, { it.translate(-rootOrigin) },
                stack.map { it.request.sourceKey }.toSet(),
                Modifier.fillMaxSize()
            )
            stack.forEachIndexed { index, transition ->
                key(transition) {
                    StatisticsOverlay(transition, viewport, snapshot, days, dayValues, hours, hourValues,
                        artists, albums, art, zone, green, rootOrigin, index == stack.lastIndex,
                        stack.map { it.request.sourceKey }.toSet(), ::open, ::close)
                }
            }
        }
      }
    }
}

@Composable
private fun StatisticsOverview(
    snapshot: StatisticsSnapshot, loading: Boolean, error: Boolean,
    days: List<StatWindow>, dayValues: List<Double>, hours: List<StatWindow>, hourValues: List<Double>,
    artists: List<EntityStatistic>, albums: List<EntityStatistic>, art: Map<String, Bitmap?>, zone: ZoneId, green: Color,
    onMenu: () -> Unit, onRetry: () -> Unit, onOpen: (StatLaunch) -> Unit, localRect: (Rect) -> Rect,
    hidden: Set<String>, modifier: Modifier
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val rows = remember(snapshot, query) { snapshot.songs.filter { song ->
        query.isBlank() || listOf(song.track.title, song.track.artist, song.track.album).any { it.contains(query.trim(), true) }
    } }
    val shown = if (expanded) rows else snapshot.songs.take(5)
    LazyColumn(modifier.padding(horizontal = 16.dp), contentPadding = PaddingValues(top = 4.dp, bottom = 18.dp)) {
        item("header") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onMenu, Modifier.size(40.dp)) { Icon(Icons.Default.Menu, "打开侧边栏", Modifier.size(22.dp)) }
                Spacer(Modifier.weight(1f))
                Text("SyncTone", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("播放统计", fontSize = 27.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, bottom = 18.dp))
            if (error) Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("暂时无法读取统计", color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f), fontSize = 13.sp)
                IconButton(onRetry) { Icon(Icons.Default.Refresh, "重新加载统计") }
            }
        }
        item("total") {
            StatCard(Modifier.fillMaxWidth().aspectRatio(2f), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .68f)) {
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    StatLabel("累计播放次数", R.drawable.ic_round_play_arrow_24, arrow = false)
                    Row(verticalAlignment = Alignment.Bottom) {
                        if (loading) Skeleton(Modifier.width(124.dp).height(44.dp))
                        else Text(countText(snapshot.totalPlays), fontSize = if (snapshot.totalPlays < 1_000_000) 42.sp else 32.sp, fontWeight = FontWeight.SemiBold, lineHeight = 46.sp)
                        Text("次", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(start = 7.dp, bottom = 5.dp))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        MutedText("全部记录")
                        MutedText("${snapshot.songs.size} 首歌曲")
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        item("days") {
            StatisticWidget("home:days", StatDestination(StatPage.DAYS), "最近 7 天播放时长", R.drawable.ic_nav_stats,
                "共 ${timeText((dayValues.sum() * HOUR_MS).toLong())} · 含今天", days, dayValues, green, true, loading,
                zone, hidden, localRect, onOpen)
            Spacer(Modifier.height(12.dp))
        }
        item("hours") {
            StatisticWidget("home:hours", StatDestination(StatPage.HOURS), "过去 24 小时播放分布", R.drawable.ic_sleep_timer,
                periodText(hours.first().start, hours.last().end, zone), hours, hourValues, MaterialTheme.colorScheme.primary, false, loading,
                zone, hidden, localRect, onOpen)
            Spacer(Modifier.height(12.dp))
        }
        item("entities") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                EntityWidget(true, artists.firstOrNull(), art, loading, hidden, localRect, onOpen, Modifier.weight(1f).aspectRatio(1f))
                EntityWidget(false, albums.firstOrNull(), art, loading, hidden, localRect, onOpen, Modifier.weight(1f).aspectRatio(1f))
            }
            Spacer(Modifier.height(12.dp))
        }
        item("rank-header") {
            Column(Modifier.fillMaxWidth().clip(musicListCorners(first = true, last = false)).background(MaterialTheme.colorScheme.surface).padding(horizontal = 15.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("歌曲播放排行", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton({ expanded = !expanded; if (!expanded) query = "" }, enabled = !loading, contentPadding = PaddingValues(start = 8.dp)) {
                        Text(if (expanded) "收起" else "全部 ${snapshot.songs.size}", fontSize = 12.sp)
                        Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, Modifier.size(18.dp))
                    }
                }
                AnimatedVisibility(expanded, enter = fadeIn(tween(220)) + expandVertically(tween(320)), exit = fadeOut(tween(160)) + shrinkVertically(tween(260))) {
                    HyperSearchField(query, { query = it }, "搜索歌曲、歌手或专辑", Modifier.fillMaxWidth().padding(bottom = 9.dp))
                }
            }
        }
        if (loading) items(4) { SkeletonSong() }
        else if (shown.isEmpty()) item("empty") { StatEmpty(if (query.isBlank()) "暂无播放记录" else "没有匹配的歌曲", Modifier.background(MaterialTheme.colorScheme.surface)) }
        else itemsIndexed(shown, key = { _, row -> row.track.id }) { index, row ->
            StatSongRow(row, snapshot.songs.indexOfFirst { it.track.id == row.track.id } + 1, art[row.track.id], showTime = false)
            if (index != shown.lastIndex) StatDivider()
        }
        item("rank-bottom") { Spacer(Modifier.fillMaxWidth().height(12.dp).clip(musicListCorners(first = false, last = true)).background(MaterialTheme.colorScheme.surface)) }
    }
}

@Composable
private fun StatisticWidget(key: String, destination: StatDestination, title: String, icon: Int, subtitle: String,
                            windows: List<StatWindow>, values: List<Double>, color: Color, daily: Boolean, loading: Boolean,
                            zone: ZoneId, hidden: Set<String>, localRect: (Rect) -> Rect, onOpen: (StatLaunch) -> Unit) {
    var rect by remember { mutableStateOf(Rect.Zero) }
    val body: @Composable (Boolean) -> Unit = { _ -> GraphWidget(title, icon, subtitle, windows, values, color, daily, loading, zone) }
    Box(Modifier.fillMaxWidth().aspectRatio(2f).onGloballyPositioned { rect = localRect(it.boundsInRoot()) }
        .graphicsLayer { alpha = if (key in hidden) 0f else 1f }
        .hyperClickable(enabled = !loading, pressedScale = 1f) { onOpen(StatLaunch(destination, key, rect, null, null, 0f, body)) }) { body(false) }
}

@Composable
private fun GraphWidget(title: String, icon: Int, subtitle: String, windows: List<StatWindow>, values: List<Double>,
                        color: Color, daily: Boolean, loading: Boolean, zone: ZoneId, onSelect: ((Int) -> Unit)? = null, selected: Int = -1) {
    StatCard(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(15.dp)) {
            StatLabel(title, icon, arrow = onSelect == null)
            if (loading) Skeleton(Modifier.padding(top = 5.dp).width(140.dp).height(13.dp))
            else Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            StatisticsChart(windows, values, color, daily, loading, zone, Modifier.fillMaxWidth().weight(1f).padding(top = 7.dp), onSelect, selected)
        }
    }
}

@Composable
private fun EntityWidget(artist: Boolean, entity: EntityStatistic?, art: Map<String, Bitmap?>, loading: Boolean,
                         hidden: Set<String>, localRect: (Rect) -> Rect, onOpen: (StatLaunch) -> Unit, modifier: Modifier) {
    val key = if (artist) "home:artists" else "home:albums"
    var rect by remember { mutableStateOf(Rect.Zero) }
    var cover by remember { mutableStateOf<Rect?>(null) }
    val bitmap = entity?.songs?.firstNotNullOfOrNull { art[it.track.id] }
    val density = LocalDensity.current
    val body: @Composable (Boolean) -> Unit = { hideArt -> EntityWidgetContent(artist, entity, bitmap, loading, hideArt) }
    Box(modifier.onGloballyPositioned { rect = localRect(it.boundsInRoot()) }.graphicsLayer { alpha = if (key in hidden) 0f else 1f }
        .hyperClickable(enabled = !loading, pressedScale = 1f) {
            onOpen(StatLaunch(StatDestination(if (artist) StatPage.ARTISTS else StatPage.ALBUMS), key, rect, cover.takeIf { entity != null }, bitmap,
                with(density) { (if (artist) 25.dp else 11.dp).toPx() }, body))
        }) {
        EntityWidgetContent(artist, entity, bitmap, loading, false) { cover = localRect(it) }
    }
}

@Composable
private fun EntityWidgetContent(artist: Boolean, entity: EntityStatistic?, bitmap: Bitmap?, loading: Boolean,
                                hideArt: Boolean, onCover: ((Rect) -> Unit)? = null) {
    StatCard(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(13.dp)) {
            StatLabel(if (artist) "最常播放歌手" else "最常播放专辑", if (artist) R.drawable.ic_nav_artist else R.drawable.ic_nav_album, small = true)
            Spacer(Modifier.height(9.dp))
            Artwork(bitmap, Modifier.size(46.dp).clip(if (artist) CircleShape else RoundedCornerShape(11.dp))
                .graphicsLayer { alpha = if (hideArt) 0f else 1f }.onGloballyPositioned { onCover?.invoke(it.boundsInRoot()) })
            Spacer(Modifier.height(5.dp))
            if (loading) Skeleton(Modifier.fillMaxWidth(.8f).height(16.dp))
            else Text(entity?.title ?: "暂无记录", fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            if (loading) Skeleton(Modifier.width(50.dp).height(14.dp)) else Text("${countText(entity?.plays ?: 0)} 次", fontSize = 12.sp)
        }
    }
}

@Composable
private fun StatCard(modifier: Modifier, color: Color = MaterialTheme.colorScheme.surface, content: @Composable () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(26.dp)).background(color)) { content() }
}

@Composable
private fun StatLabel(text: String, icon: Int, arrow: Boolean = true, small: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (small) 4.dp else 6.dp)) {
        Icon(painterResource(icon), null, Modifier.size(if (small) 15.dp else 18.dp), tint = MaterialTheme.colorScheme.primary)
        Text(text, fontSize = if (small) 11.sp else 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (arrow) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(if (small) 13.dp else 16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MutedText(text: String) { Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }

@Composable
private fun StatisticsChart(windows: List<StatWindow>, values: List<Double>, color: Color, daily: Boolean, loading: Boolean,
                            zone: ZoneId, modifier: Modifier, onSelect: ((Int) -> Unit)? = null, selected: Int = -1) {
    val density = LocalDensity.current
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val line = MaterialTheme.colorScheme.outlineVariant
    val skeletonColor = MaterialTheme.colorScheme.surfaceVariant
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL) } }
    val max = if (daily) (ceil((values.maxOrNull() ?: 0.0).coerceAtLeast(1.0) * 2) / 2) else (ceil((values.maxOrNull() ?: 0.0).coerceAtLeast(4.0) / 4) * 4)
    val description = if (daily) windows.zip(values).joinToString { (window, value) -> "${window.date}: ${timeText((value * HOUR_MS).toLong())}" }
        else "过去24小时，每柱1小时，共${values.sum().toLong()}次"
    Canvas(modifier.semantics { contentDescription = description }.then(if (onSelect != null) Modifier.pointerInput(windows) {
        detectTapGestures { offset ->
            val left = with(density) { 27.dp.toPx() }
            val index = ((offset.x - left) / (size.width - left) * values.size).toInt().coerceIn(0, values.lastIndex)
            onSelect(index)
        }
    } else Modifier)) {
        val left = 27.dp.toPx(); val bottom = 19.dp.toPx(); val plotHeight = (size.height - bottom).coerceAtLeast(1f)
        val width = (size.width - left).coerceAtLeast(1f); val slot = width / values.size
        paint.textSize = 10.sp.toPx(); paint.color = textColor
        listOf(0f, .5f, 1f).forEach { fraction ->
            val y = plotHeight * (1 - fraction)
            drawLine(line, Offset(left, y), Offset(size.width, y), strokeWidth = 1.dp.toPx() * .6f)
            val number = max * fraction
            val label = if (number % 1.0 == 0.0) number.toLong().toString() else String.format(java.util.Locale.ROOT, "%.1f", number)
            drawContext.canvas.nativeCanvas.drawText(label + if (fraction == 1f) { if (daily) "h" else "次" } else "", 0f, y.coerceAtLeast(paint.textSize), paint)
        }
        values.forEachIndexed { index, value ->
            val barWidth = min(if (daily) 25.dp.toPx() else 7.dp.toPx(), slot * if (daily) .65f else .7f)
            val height = (if (loading) .27f else (value / max).toFloat()) * plotHeight
            if (height > 0) drawRoundRect(if (loading) skeletonColor else color.copy(alpha = if (selected >= 0 && index != selected) .32f else if (daily && index != values.lastIndex) .75f else 1f),
                Offset(left + slot * (index + .5f) - barWidth / 2, plotHeight - height), Size(barWidth, height), CornerRadius(if (daily) 4.dp.toPx() else 2.dp.toPx()))
        }
        if (daily) windows.forEachIndexed { index, window ->
            val label = "${window.date?.dayOfMonth}日"
            val x = left + slot * (index + .5f) - paint.measureText(label) / 2
            drawContext.canvas.nativeCanvas.drawText(label, x, size.height - 2.dp.toPx(), paint)
        } else listOf(0, 6, 12, 18, 24).forEach { index ->
            val utc = if (index == 24) windows.last().end else windows[index].start
            val label = Instant.ofEpochMilli(utc).atZone(zone).format(HourLabel)
            val x = (left + width * index / 24 - paint.measureText(label) / 2).coerceIn(left, (size.width - paint.measureText(label)).coerceAtLeast(left))
            drawContext.canvas.nativeCanvas.drawText(label, x, size.height - 2.dp.toPx(), paint)
        }
    }
}

@Composable
private fun StatSongRow(song: SongStatistic, index: Int, bitmap: Bitmap?, showTime: Boolean) {
    MusicListRow(song.track.title, song.track.artist, index, artwork = { Artwork(bitmap, it) }) {
        MusicListMetric("${countText(song.plays)} 次", if (showTime) timeText(song.listenedMs) else null)
    }
}

@Composable
private fun StatDivider() { MusicListDivider() }

private fun statisticsRankShape(first: Boolean, last: Boolean) = musicListCorners(first, last)

@Composable
private fun StatEmpty(label: String = "暂无播放记录", modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().heightIn(min = 150.dp).padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(painterResource(R.drawable.ic_nav_stats), null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = .65f))
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun Skeleton(modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "statistics-loading")
    val alpha by transition.animateFloat(.4f, .8f, infiniteRepeatable(tween(850), RepeatMode.Reverse), label = "skeleton-opacity")
    Box(modifier.clip(RoundedCornerShape(7.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)))
}

@Composable
private fun SkeletonSong() {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Skeleton(Modifier.size(39.dp)); Skeleton(Modifier.weight(1f).height(17.dp)); Skeleton(Modifier.width(40.dp).height(17.dp))
    }
}

private class StatisticsClip(private val rect: Rect, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline = Outline.Rounded(RoundRect(rect, CornerRadius(radius)))
}

@Composable
private fun StatisticsOverlay(
    transition: StatTransition, viewport: Rect, snapshot: StatisticsSnapshot,
    days: List<StatWindow>, dayValues: List<Double>, hours: List<StatWindow>, hourValues: List<Double>,
    artists: List<EntityStatistic>, albums: List<EntityStatistic>, art: Map<String, Bitmap?>,
    zone: ZoneId, green: Color, rootOrigin: Offset, active: Boolean, hidden: Set<String>,
    onOpen: (StatLaunch) -> Unit, onClose: () -> Unit
) {
    val density = LocalDensity.current
    val request = transition.request
    val radius = with(density) { 26.dp.toPx() }
    val expandedRadius = with(density) { CARD_EXPANDED_RADIUS_DP.dp.toPx() }
    val arc = with(density) { 26.dp.toPx() }
    val surface = MaterialTheme.colorScheme.surface
    val background = MaterialTheme.colorScheme.background
    val geometry = { statisticsGeometry(request.rect, viewport, transition.progress.value, radius, expandedRadius) }
    val cover = request.artRect
    val hasSharedArt = cover != null
    val phase: (Float, Float) -> Modifier = { start, end -> Modifier.graphicsLayer {
        val alphaProgress = smoothRange(transition.progress.value, start, end)
        alpha = alphaProgress; translationY = (1 - alphaProgress) * 14.dp.toPx()
    } }
    // The artwork anchor stays in the final page's layout coordinates. Only its
    // opacity changes, so measuring it never feeds animated transforms back into its path.
    val anchorPhase: (Float, Float) -> Modifier = { start, end -> Modifier.graphicsLayer {
        alpha = smoothRange(transition.progress.value, start, end)
    } }
    val targetArtModifier = if (hasSharedArt) Modifier.graphicsLayer {
        alpha = if (transition.progress.value == 1f && !transition.progress.isRunning) 1f else 0f
    }.onGloballyPositioned { coordinates ->
        val page = transition.pageCoordinates
        if (page != null && page.isAttached && coordinates.isAttached) {
            val target = page.localBoundingBoxOf(coordinates, clipBounds = false)
            val old = transition.targetArt
            if (old == null || (!transition.progress.isRunning && old != target)) transition.targetArt = target
        }
    } else Modifier
    val targetLabelModifier = if (hasSharedArt) Modifier.graphicsLayer { alpha = smoothRange(transition.progress.value, .68f, .94f) } else Modifier
    Box(Modifier.fillMaxSize().graphicsLayer {
        val g = geometry(); shape = StatisticsClip(g.rect, g.radius); clip = true
    }.pointerInput(active) { detectTapGestures { } }) {
        Canvas(Modifier.fillMaxSize()) {
            val g = geometry()
            drawRoundRect(lerp(surface, background, smoothRange(transition.progress.value, .25f, .90f)), g.rect.topLeft, g.rect.size, CornerRadius(g.radius))
        }
        Box(Modifier.fillMaxSize().onPlaced { transition.pageCoordinates = it }) {
            StatisticsDetailPage(request.destination, snapshot, days, dayValues, hours, hourValues, artists, albums, art, zone, green,
                active, hidden, { it.translate(-rootOrigin) }, phase, anchorPhase, targetArtModifier, targetLabelModifier, onOpen, onClose)
        }
        Box(Modifier.size(with(density) { request.rect.width.toDp() }, with(density) { request.rect.height.toDp() }).graphicsLayer {
            val p = transition.progress.value
            val rect = geometry().rect
            translationX = rect.left; translationY = rect.top
            alpha = 1 - smoothRange(p, .10f, .40f)
        }) { request.source(hasSharedArt) }
        if (cover != null) {
            Box(Modifier.size(with(density) { cover.width.toDp() }, with(density) { cover.height.toDp() }).graphicsLayer {
                val p = transition.progress.value
                val q = smoothRange(p, .10f, 1f)
                val destination = transition.targetArt ?: cover
                val w = cover.width + (destination.width - cover.width) * q
                val h = cover.height + (destination.height - cover.height) * q
                val center = statisticsPath(cover.center, destination.center, q, arc)
                transformOrigin = TransformOrigin(0f, 0f)
                translationX = center.x - w / 2; translationY = center.y - h / 2
                scaleX = w / cover.width; scaleY = h / cover.height
                val endRadius = with(density) { (if (request.destination.page in listOf(StatPage.ARTIST, StatPage.ALBUM)) 17.dp else 11.dp).toPx() }
                val artRadius = request.artRadius + (endRadius - request.artRadius) * q
                shape = RoundedCornerShape(with(density) { (artRadius / scaleX.coerceAtLeast(.01f)).toDp() }); clip = true
                // Exactly one image owns the transition. At the endpoint both layouts
                // share the same bounds/radius; no late cross-fade between moving images.
                alpha = if (transition.targetArt == null || (p == 1f && !transition.progress.isRunning)) 0f else 1f
            }) { Artwork(request.artwork, Modifier.fillMaxSize()) }
        }
    }
}

@Composable
private fun StatisticsDetailPage(
    destination: StatDestination, snapshot: StatisticsSnapshot,
    days: List<StatWindow>, dayValues: List<Double>, hours: List<StatWindow>, hourValues: List<Double>,
    artists: List<EntityStatistic>, albums: List<EntityStatistic>, art: Map<String, Bitmap?>,
    zone: ZoneId, green: Color, active: Boolean, hidden: Set<String>, localRect: (Rect) -> Rect,
    phase: (Float, Float) -> Modifier, anchorPhase: (Float, Float) -> Modifier,
    targetArtModifier: Modifier, targetLabelModifier: Modifier,
    onOpen: (StatLaunch) -> Unit, onClose: () -> Unit
) {
    val entities = if (destination.page in listOf(StatPage.ARTISTS, StatPage.ARTIST)) artists else albums
    val entity = entities.firstOrNull { it.key == destination.key }
    val isTime = destination.page == StatPage.DAYS || destination.page == StatPage.HOURS
    val isTotal = destination.page == StatPage.ARTISTS || destination.page == StatPage.ALBUMS
    val daily = destination.page == StatPage.DAYS
    var selected by rememberSaveable(destination) { mutableIntStateOf(-1) }
    val windows = if (daily) days else hours
    val range = if (selected in windows.indices) windows[selected] else StatWindow(windows.first().start, windows.last().end)
    val rows = remember(snapshot, range, destination, entity) { if (isTime) snapshot.between(range.start, range.end) else entity?.songs.orEmpty() }
    val title = when (destination.page) {
        StatPage.DAYS -> "最近 7 天"
        StatPage.HOURS -> "过去 24 小时"
        StatPage.ARTISTS -> "歌手播放排行"
        StatPage.ALBUMS -> "专辑播放排行"
        else -> entity?.title ?: "暂无记录"
    }
    val period = when {
        isTime && daily -> "${days.first().date?.format(FullDate)} — ${days.last().date?.format(FullDate)}"
        isTime -> periodText(hours.first().start, hours.last().end, zone)
        isTotal -> "累计全部 · 按播放次数排序"
        else -> if (destination.page == StatPage.ARTIST) "歌手 · 累计全部" else "专辑 · 累计全部"
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(top = 4.dp, bottom = 20.dp)) {
        item("header") {
            Column(phase(.18f, .60f)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClose, enabled = active, modifier = Modifier.size(40.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回统计", Modifier.size(21.dp)) }
                    Spacer(Modifier.weight(1f)); MutedText("播放统计")
                }
                Text(title, fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
                Text(period, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(bottom = if (isTime) 4.dp else 18.dp))
                if (isTime) Text("GMT${Instant.ofEpochMilli(range.end - 1).atZone(zone).offset.id.replace("Z", "+00:00")}", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 16.dp))
            }
        }
        if (isTotal) {
            if (entities.isEmpty()) item { StatEmpty(modifier = phase(.39f, .84f).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surface)) }
            itemsIndexed(entities, key = { _, item -> item.key }) { index, item ->
                val bitmap = item.songs.firstNotNullOfOrNull { art[it.track.id] }
                val rowPhase = if (index == 0) anchorPhase(.38f, .77f)
                    else phase(.38f + min(index, 5) * .025f, .77f + min(index, 5) * .025f)
                Column(rowPhase
                    .clip(statisticsRankShape(index == 0, index == entities.lastIndex))) {
                    EntityRankRow(destination, item, index, bitmap, hidden, localRect, onOpen,
                        if (index == 0) targetArtModifier else Modifier,
                        if (index == 0) targetLabelModifier else Modifier, Modifier, active)
                    if (index != entities.lastIndex) StatDivider()
                }
            }
        } else {
            if (isTime) {
                item("graph") {
                    Box(Modifier.fillMaxWidth().aspectRatio(2f).then(phase(.30f, .74f))) {
                        GraphWidget(if (daily) "每日播放时长" else "每小时播放次数", if (daily) R.drawable.ic_nav_stats else R.drawable.ic_sleep_timer,
                            if (daily) "实际播放时长 · 小时" else "播放次数 · 次", windows, if (daily) dayValues else hourValues,
                            if (daily) green else MaterialTheme.colorScheme.primary, daily, false, zone, { selected = it }, selected)
                    }
                }
                item("selection") {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 10.dp).then(phase(.36f, .78f)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        FilterChip(selected < 0, { selected = -1 }, label = { Text("全部", fontSize = 12.sp) }, border = null)
                        windows.forEachIndexed { index, window ->
                            FilterChip(selected == index, { selected = index }, label = { Text(if (daily) "${window.date?.dayOfMonth}日" else Instant.ofEpochMilli(window.start).atZone(zone).format(HourLabel), fontSize = 12.sp) }, border = null)
                        }
                    }
                    Text(if (selected < 0 && daily) "${days.first().date?.format(DayLabel)} — ${days.last().date?.format(DayLabel)}" else periodText(range.start, range.end, zone),
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp).then(phase(.36f, .78f)))
                }
                item("summary") {
                    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp).then(phase(.40f, .82f)), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column { Text("${countText(rows.sumOf { it.plays })} 次", fontSize = 23.sp, fontWeight = FontWeight.Medium); MutedText("播放次数") }
                        Column(Modifier.weight(1f)) { Text(timeText(rows.sumOf { it.listenedMs }), fontSize = 22.sp, fontWeight = FontWeight.Medium, maxLines = 2); MutedText("实际播放时长") }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            } else item("entity-summary") {
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp).then(anchorPhase(.28f, .72f)), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Artwork(entity?.songs?.firstNotNullOfOrNull { art[it.track.id] }, Modifier.size(65.dp).clip(RoundedCornerShape(17.dp)).then(targetArtModifier))
                    Column(targetLabelModifier) { Text("${countText(entity?.plays ?: 0)} 次", fontSize = 24.sp, fontWeight = FontWeight.Medium); MutedText("${entity?.songs?.size ?: 0} 首歌曲") }
                }
            }
            item("song-heading") {
                Row(Modifier.fillMaxWidth().padding(top = 17.dp, bottom = 12.dp).then(phase(.44f, .86f)), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isTime) "歌曲排行" else "曲目播放排行", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); MutedText("按播放次数")
                }
            }
            if (rows.isEmpty()) item("empty") { StatEmpty(modifier = phase(.44f, .86f).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surface)) }
            itemsIndexed(rows, key = { _, row -> row.track.id }) { index, row ->
                Column(phase(.42f + min(index, 5) * .025f, .81f + min(index, 5) * .025f)
                    .clip(statisticsRankShape(index == 0, index == rows.lastIndex))) {
                    StatSongRow(row, index + 1, art[row.track.id], isTime)
                    if (index != rows.lastIndex) StatDivider()
                }
            }
        }
    }
}

@Composable
private fun EntityRankRow(parent: StatDestination, entity: EntityStatistic, index: Int, bitmap: Bitmap?, hidden: Set<String>,
                          localRect: (Rect) -> Rect, onOpen: (StatLaunch) -> Unit, artModifier: Modifier,
                          labelModifier: Modifier, modifier: Modifier, active: Boolean) {
    var rect by remember { mutableStateOf(Rect.Zero) }
    var cover by remember { mutableStateOf<Rect?>(null) }
    val density = LocalDensity.current
    val sourceKey = "${parent.page}:${entity.key}"
    val artist = parent.page == StatPage.ARTISTS
    val body: @Composable (Boolean) -> Unit = { hideArt -> EntityRankContent(entity, index, bitmap, hideArt) }
    Box(modifier.fillMaxWidth().onGloballyPositioned { rect = localRect(it.boundsInRoot()) }
        .graphicsLayer { alpha = if (sourceKey in hidden) 0f else 1f }
        .clickable(enabled = active) {
            onOpen(StatLaunch(StatDestination(if (artist) StatPage.ARTIST else StatPage.ALBUM, entity.key), sourceKey, rect, cover, bitmap,
                with(density) { 11.dp.toPx() }, body))
        }) {
        EntityRankContent(entity, index, bitmap, false, artModifier, labelModifier) { measured ->
            cover = localRect(measured)
        }
    }
}

@Composable
private fun EntityRankContent(entity: EntityStatistic, index: Int, bitmap: Bitmap?, hideArt: Boolean,
                              artModifier: Modifier = Modifier, labelModifier: Modifier = Modifier,
                              onCover: ((Rect) -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(12.dp).heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("${index + 1}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(16.dp))
        Artwork(bitmap, Modifier.size(46.dp).clip(RoundedCornerShape(11.dp)).then(artModifier).graphicsLayer { if (hideArt) alpha = 0f }
            .onGloballyPositioned { coordinates ->
                val top = coordinates.localToRoot(Offset.Zero)
                val bottom = coordinates.localToRoot(Offset(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
                onCover?.invoke(Rect(top, bottom))
            })
        Column(Modifier.weight(1f).then(labelModifier)) {
            Text(entity.title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(entity.subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
        Text("${countText(entity.plays)} 次", fontSize = 13.sp)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
