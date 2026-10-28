package dev.salt.desktop.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.salt.desktop.library.DesktopTrack
import dev.salt.desktop.playback.DesktopPlayerStore
import dev.salt.replica.statistics.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal val StatisticsGreen = Color(0xFF249773)
private val dateLabel = DateTimeFormatter.ofPattern("M/d")
private val hourLabel = DateTimeFormatter.ofPattern("HH:mm")

internal fun listeningTime(ms: Long): String = when {
    ms >= HOUR_MS -> "${ms / HOUR_MS} 小时 ${ms / 60_000 % 60} 分"
    ms >= 60_000 -> "${ms / 60_000} 分钟"
    else -> "${ms / 1_000} 秒"
}

private data class Ranking(val key: String, val title: String, val subtitle: String, val plays: Long,
                           val ms: Long, val track: DesktopTrack?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StatisticsScreen(store: DesktopPlayerStore, search: String, coordinates: LayoutCoordinates?, activeAnchor: DetailAnchor?,
                              onEntity: (String, Boolean, DetailAnchor) -> Unit) {
    val state by store.statistics.state.collectAsState()
    val libraryTracks by remember(store) { store.state.map { it.tracks }.distinctUntilChanged() }
        .collectAsState(store.state.value.tracks)
    val scope = rememberCoroutineScope()
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        store.statistics.refresh()
        while (true) { delay(10_000); clock = System.currentTimeMillis(); store.statistics.refresh() }
    }
    val zone = ZoneId.systemDefault()
    val days = remember(clock, zone) { recentDays(clock, zone) }
    val hours = remember(clock) { recentHours(clock) }
    val snapshot = state.snapshot
    val daily = remember(snapshot, days) { days.map { day -> snapshot.between(day.start, day.end).sumOf { it.listenedMs }.toDouble() / HOUR_MS } }
    val hourly = remember(snapshot, hours) { hours.map { hour -> snapshot.between(hour.start, hour.end).sumOf { it.plays }.toDouble() } }
    val colors = MaterialTheme.colorScheme
    val green = if (colors.background.luminance() < .5f) Color(0xFF64CBA7) else StatisticsGreen
    var category by remember { mutableIntStateOf(0) }
    val tracks = remember(libraryTracks) { libraryTracks.sortedBy { it.available }.associateBy { songIdentity(it.title, it.artist) } }
    val rows = remember(snapshot, category, tracks, search) {
        val result = if (category == 0) snapshot.songs.map {
            Ranking(it.track.id, it.track.title, "${it.track.artist} · ${it.track.album}", it.plays, it.listenedMs, tracks[it.track.id])
        } else (if (category == 1) snapshot.artists() else snapshot.albums()).map { entity ->
            Ranking(entity.key, entity.title, entity.subtitle, entity.plays, entity.songs.sumOf { it.listenedMs },
                entity.songs.firstNotNullOfOrNull { tracks[it.track.id] })
        }
        result.filter { search.isBlank() || "${it.title} ${it.subtitle}".contains(search.trim(), true) }
    }
    ScrollableList(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (state.error) item {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (store.statistics.persistent) "统计更新失败，已保存的记录仍保留" else "统计当前暂存在内存，退出后无法保留",
                    Modifier.weight(1f), fontSize = 12.sp, color = colors.error)
                Tool("重新加载统计", Icons.Default.Refresh, { scope.launch { store.statistics.refresh() } })
            }
        }
        item {
            Row(Modifier.fillMaxWidth().height(112.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                StatisticNumber("累计播放次数", "%,d".format(snapshot.totalPlays), "次", Modifier.weight(1f), colors.primary)
                StatisticNumber("累计聆听时长", listeningTime(snapshot.songs.sumOf { it.listenedMs }), "", Modifier.weight(1.3f), green)
                StatisticNumber("已聆听歌曲", snapshot.songs.size.toString(), "首", Modifier.weight(1f), colors.onSurface)
            }
            Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 20.dp).height(232.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                ListeningChart("最近 7 天播放时长", days, daily, green, true, zone, Modifier.weight(1f))
                ListeningChart("最近 24 小时播放次数", hours, hourly, colors.primary, false, zone, Modifier.weight(1f))
            }
            HorizontalDivider(color = colors.outlineVariant.copy(alpha = .6f))
            Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("播放排行", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                DesktopChoiceRow(listOf("歌曲", "艺人", "专辑"), category, { category = it }, fillWidth = false)
            }
        }
        if (rows.isEmpty()) item {
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                Text(if (state.loading) "正在读取统计" else if (search.isNotBlank()) "没有匹配的记录" else "暂无播放记录",
                    fontSize = 13.sp, color = colors.onSurfaceVariant)
            }
        }
        itemsIndexed(rows, key = { _, row -> "$category:${row.key}" }) { index, row ->
            val anchor = remember { DetailAnchor() }
            Row(Modifier.fillMaxWidth().height(70.dp).onGloballyPositioned { child ->
                coordinates?.takeIf { it.isAttached && child.isAttached }?.let { anchor.card = it.localBoundingBoxOf(child, false) }
            }.detailSource(anchor, activeAnchor === anchor).clip(DesktopCardShape).desktopClickable(enabled = row.track != null) {
                if (category == 0) row.track?.takeIf { it.available }?.let(store::select)
                else onEntity(row.title, category == 1, anchor)
            }.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}", Modifier.width(34.dp), fontSize = 13.sp, color = colors.onSurfaceVariant)
                Artwork(row.track, Modifier.size(44.dp).clip(DesktopCardShape).onGloballyPositioned { child ->
                    coordinates?.takeIf { it.isAttached && child.isAttached }?.let { anchor.cover = it.localBoundingBoxOf(child, false) }
                }, onReady = { anchor.artwork = it })
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(row.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(row.subtitle, Modifier.padding(top = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontSize = 11.sp, color = colors.onSurfaceVariant)
                }
                Column(Modifier.width(138.dp), horizontalAlignment = Alignment.End) {
                    Text("${row.plays} 次", fontSize = 13.sp)
                    Text(listeningTime(row.ms), Modifier.padding(top = 4.dp), fontSize = 11.sp, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun StatisticNumber(label: String, value: String, unit: String, modifier: Modifier, accent: Color) {
    Column(modifier.fillMaxHeight().clip(DesktopCardShape).background(MaterialTheme.colorScheme.surface)
        .padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, fontSize = if (value.length > 10) 20.sp else 26.sp, fontWeight = FontWeight.SemiBold,
                color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
            if (unit.isNotEmpty()) Text(unit, Modifier.padding(start = 6.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ListeningChart(title: String, windows: List<StatWindow>, values: List<Double>, color: Color,
                           daily: Boolean, zone: ZoneId, modifier: Modifier) {
    var selected by remember { mutableIntStateOf(-1) }
    val colors = MaterialTheme.colorScheme
    val maximum = maxOf(if (daily) 1.0 else 4.0, kotlin.math.ceil(values.maxOrNull() ?: 0.0))
    Column(modifier.fillMaxHeight().clip(DesktopCardShape).background(colors.surface).padding(18.dp)) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        val value = if (selected in values.indices) values[selected] else values.sum()
        val period = if (selected in windows.indices) {
            val window = windows[selected]
            Instant.ofEpochMilli(window.start).atZone(zone).format(if (daily) dateLabel else hourLabel)
        } else "合计"
        Text("$period · ${if (daily) listeningTime((value * HOUR_MS).toLong()) else "${value.toLong()} 次"}",
            Modifier.padding(top = 5.dp, bottom = 16.dp), fontSize = 12.sp, color = colors.onSurfaceVariant)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.width(27.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                Text("${maximum.toInt()}${if (daily) "h" else ""}", fontSize = 10.sp, color = colors.onSurfaceVariant)
                Text("0", fontSize = 10.sp, color = colors.onSurfaceVariant)
            }
            Row(Modifier.weight(1f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(if (daily) 7.dp else 3.dp)) {
                values.forEachIndexed { index, number ->
                    val height by animateFloatAsState((number / maximum).toFloat(), tween(550))
                    val label = Instant.ofEpochMilli(windows[index].start).atZone(zone).format(if (daily) dateLabel else hourLabel)
                    Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(3.dp))
                        .background(if (selected == index) color.copy(alpha = .07f) else Color.Transparent)
                        .desktopClickable(shape = RoundedCornerShape(3.dp)) { selected = if (selected == index) -1 else index }
                        .semantics { contentDescription = "$label ${if (daily) listeningTime((number * HOUR_MS).toLong()) else "${number.toLong()} 次"}" },
                        contentAlignment = Alignment.BottomCenter) {
                        Box(Modifier.fillMaxWidth(if (daily) .68f else .8f).fillMaxHeight(height.coerceIn(.012f, 1f))
                            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)).background(color.copy(alpha = if (selected < 0 || selected == index) .9f else .35f)))
                    }
                }
            }
        }
        Row(Modifier.padding(start = 35.dp, top = 9.dp).fillMaxWidth()) {
            val ticks = if (daily) windows else windows.filterIndexed { index, _ -> index % 6 == 0 }
            ticks.forEach { window ->
                val label = if (daily) window.date?.format(dateLabel).orEmpty()
                else Instant.ofEpochMilli(window.start).atZone(zone).format(DateTimeFormatter.ofPattern("HH"))
                Text(label, Modifier.weight(1f), fontSize = 9.sp, color = colors.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}
