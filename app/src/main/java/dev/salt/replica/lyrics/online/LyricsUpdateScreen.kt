@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.salt.replica.lyrics.online

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.salt.buttons.SyncToneButton
import dev.salt.buttons.SyncToneButtonStyle
import dev.salt.buttons.SyncToneIconButton
import dev.salt.buttons.SyncToneTextButton
import dev.salt.replica.lyrics.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val UpdateShape = RoundedCornerShape(18.dp)
val LyricsPageCurve = CubicBezierEasing(.2f, .8f, .2f, 1f)
fun lyricsPageAnimation() = tween<Float>(420, easing = LyricsPageCurve)
@Composable private fun updateGreen() = if (MaterialTheme.colorScheme.background.luminance() > .5f) Color(0xFF25864A) else Color(0xFF85D8A2)
private fun durationText(ms: Long) = if (ms <= 0) "时长未知" else "${ms / 60000}:${(ms / 1000 % 60).toString().padStart(2, '0')}"
private fun <T> moved(list: List<T>, item: T, direction: Int): List<T> = list.toMutableList().apply {
    val from = indexOf(item); val to = from + direction
    if (from in indices && to in indices) { val value = this[from]; this[from] = this[to]; this[to] = value }
}

/** Pager keeps the outgoing and incoming cards mounted throughout the horizontal transition. */
@Composable
fun LyricsUpdateScreen(controller: OnlineLyricsController, modifier: Modifier = Modifier, onMenu: (() -> Unit)? = null,
                       pagerState: PagerState = rememberPagerState { 3 }, pagerModifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var picker by remember { mutableStateOf(false) }
    var mobilePreview by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    fun page(index: Int) { mobilePreview = false; scope.launch { pagerState.animateScrollToPage(index, animationSpec = lyricsPageAnimation()) } }
    BoxWithConstraints(modifier.fillMaxSize().background(colors.background)) {
        val wide = maxWidth >= 720.dp && maxHeight >= 600.dp
        Column(Modifier.fillMaxSize().padding(horizontal = if (wide) 24.dp else 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onMenu != null) SyncToneIconButton(onMenu) { Icon(Icons.Default.Menu, "打开侧栏", tint = colors.onBackground) }
                Column(Modifier.weight(1f)) {
                    Text("歌词更新", fontSize = 26.sp, fontWeight = FontWeight.Medium, color = colors.onBackground)
                    Text("为本地音乐找到合适的歌词", fontSize = 12.sp, color = colors.onSurfaceVariant)
                }
            }
            UpdateCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(if (wide) 48.dp else 40.dp).clip(UpdateShape).background(colors.primaryContainer), Alignment.Center) {
                        Icon(Icons.Default.MusicNote, null, tint = colors.primary)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(state.song?.title ?: "尚未选择音乐", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        Text(state.song?.let { "${it.artist} · ${durationText(it.durationMs)}" } ?: "导入音乐后可搜索歌词",
                            fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    SyncToneButton({ picker = true }, style = SyncToneButtonStyle.Secondary, enabled = state.songs.isNotEmpty()) {
                        Text("已选 ${state.selectedIds.size} 首", fontSize = 12.sp)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().clip(UpdateShape).background(colors.surfaceVariant).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("搜索与预览", "匹配设置", "批量更新").forEachIndexed { index, label ->
                    val selected = pagerState.targetPage == index
                    SyncToneButton({ page(index) }, Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                        .background(if (selected) colors.surface else Color.Transparent), style = SyncToneButtonStyle.Ghost,
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 10.dp),
                        contentColor = if (selected) colors.primary else colors.onSurfaceVariant) {
                        Text(label, fontSize = if (wide) 13.sp else 12.sp, maxLines = 1)
                    }
                }
            }
            state.message?.let { message ->
                Row(Modifier.fillMaxWidth().clip(UpdateShape).background(colors.primaryContainer).padding(start = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f).padding(vertical = 10.dp), fontSize = 12.sp, color = colors.onPrimaryContainer)
                    SyncToneIconButton(controller::dismissMessage) { Icon(Icons.Default.Close, "关闭提示", Modifier.size(16.dp)) }
                }
            }
            HorizontalPager(state = pagerState, modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds().then(pagerModifier),
                beyondViewportPageCount = 1, userScrollEnabled = !picker && !mobilePreview, key = { it }) { index ->
                when (index) {
                    0 -> SearchUpdatePage(state, controller, wide, pagerState.currentPage == 0,
                        { mobilePreview = true }, { page(1) })
                    1 -> UpdateSettingsPage(state.settings, controller::updateSettings, wide, { page(0) }, { page(2) })
                    2 -> BatchUpdatePage(state, controller, { picker = true }, { page(1) }, { song ->
                        controller.bind(song); controller.search(song.query.take(200), null); page(0)
                    })
                }
            }
        }
        if (picker) SongPicker(state, controller, { picker = false }, { song ->
            controller.bind(song); picker = false; page(0); controller.search(song.query.take(200), null)
        })
        if (!wide && mobilePreview) Dialog(onDismissRequest = { mobilePreview = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)) {
            BoxWithConstraints(Modifier.fillMaxSize().imePadding().padding(top = 24.dp), contentAlignment = Alignment.BottomCenter) {
                Surface(Modifier.fillMaxWidth().heightIn(max = maxHeight).fillMaxHeight(.88f),
                    shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp), color = colors.surface) {
                    Column(Modifier.padding(18.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("歌词预览", Modifier.weight(1f), fontSize = 18.sp)
                            SyncToneIconButton({ mobilePreview = false }) { Icon(Icons.Default.Close, "关闭预览") }
                        }
                        UpdatePreview(state, controller, Modifier.weight(1f), true, { page(1) })
                    }
                }
            }
        }
    }
}

@Composable private fun UpdateCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = UpdateShape, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable private fun SearchUpdatePage(state: OnlineLyricsState, controller: OnlineLyricsController, wide: Boolean,
                                       visible: Boolean, onPreview: () -> Unit, onSettings: () -> Unit) {
    var query by remember(state.song?.id) { mutableStateOf(state.song?.query?.take(200).orEmpty()) }
    var source by remember { mutableStateOf<LyricSource?>(null) }
    LaunchedEffect(state.settings.sources) { if (source !in state.settings.sources) source = null }
    fun search() { if (query.isNotBlank()) controller.search(query, source) }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(if (wide) .52f else 1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(query, { query = it.take(200) }, Modifier.fillMaxWidth(), shape = UpdateShape,
                enabled = state.song != null, singleLine = true, placeholder = { Text("歌名 / 歌手") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { SyncToneTextButton(::search, enabled = state.song != null && query.isNotBlank() && !state.busy) { Text("搜索") } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf<LyricSource?>(null) + LyricSource.entries.filter { it in state.settings.sources }).forEach { item ->
                    FilterChip(selected = source == item, onClick = { source = item }, label = { Text(item?.label ?: "全部", fontSize = 12.sp) })
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${state.results.size} 个结果 · 自由选择版本", Modifier.weight(1f), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SyncToneTextButton(onSettings) { Icon(Icons.Default.Tune, null, Modifier.size(16.dp)); Text("规则", fontSize = 12.sp) }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                state.warnings.forEach { warning -> item { Text(warning, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                if (state.results.isEmpty()) item {
                    UpdateCard {
                        Text(if (state.busy) "正在搜索所选来源…" else if (state.searched) "没有找到同步歌词，可缩短关键词或切换来源" else "选择音乐后搜索，预览满意再应用",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.padding(vertical = 24.dp))
                    }
                }
                items(state.results, key = { it.key }) { candidate ->
                    val selected = state.loadingCandidate == candidate.key || state.preview?.candidate?.key == candidate.key
                    Surface(Modifier.fillMaxWidth().clip(UpdateShape).clickable(enabled = !state.busy) {
                        controller.preview(candidate); if (!wide) onPreview()
                    }, shape = UpdateShape, color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(candidate.title, Modifier.weight(1f), fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${state.song?.let { LyricsMatching.score(it, candidate) } ?: 0}%", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                            }
                            Text("${candidate.artist} · ${durationText(candidate.durationMs)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(candidate.source.label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.weight(1f))
                                Text("预览", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                                Icon(Icons.Default.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
                item { Text("手动选择不受批量阈值限制。搜索会向所选来源发送关键词。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        if (wide) Surface(Modifier.weight(.48f).fillMaxHeight(), shape = UpdateShape, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(18.dp)) {
                Text("歌词预览", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                UpdatePreview(state, controller, Modifier.weight(1f), visible, onSettings)
            }
        }
    }
}

@Composable private fun UpdatePreview(state: OnlineLyricsState, controller: OnlineLyricsController, modifier: Modifier,
                                     visible: Boolean, onSettings: () -> Unit) {
    val raw = state.preview
    val document = remember(raw, state.settings) { raw?.let { runCatching { LyricsMatching.arrange(it, state.settings) }.getOrNull() } }
    var playing by remember(raw?.candidate?.key) { mutableStateOf(false) }
    var position by remember(raw?.candidate?.key) { mutableLongStateOf(raw?.lyrics?.lines?.firstOrNull()?.start ?: 0L) }
    val end = raw?.lyrics?.lines?.lastOrNull()?.end?.coerceAtLeast(1L) ?: 1L
    LaunchedEffect(playing, raw, visible) {
        if (playing && visible) while (position < end) { delay(33); position = (position + 33).coerceAtMost(end) }
        if (position >= end) playing = false
    }
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (raw == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(if (state.busy) "正在载入歌词…" else "从搜索结果中选择一个版本", color = colors.onSurfaceVariant)
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${raw.candidate.source.label} · ${if (raw.wordTimed) "逐字" else "逐行"} · ${raw.lyrics.lines.size} 行",
                    color = if (raw.wordTimed) updateGreen() else colors.onSurfaceVariant, fontSize = 12.sp)
                Text(durationText(raw.candidate.durationMs), color = colors.onSurfaceVariant, fontSize = 12.sp)
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                if (document == null) item { Text("此版本没有所选歌词内容，请调整内容设置或切换版本", color = colors.onSurfaceVariant) }
                items(document?.lyrics?.lines.orEmpty()) { line ->
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(buildAnnotatedString {
                            line.cells.forEach { cell ->
                                val fraction = if (cell.end > cell.start) ((position - cell.start).toFloat() / (cell.end - cell.start)).coerceIn(0f, 1f)
                                    else if (position >= cell.start) 1f else 0f
                                withStyle(SpanStyle(color = lerp(colors.onSurfaceVariant, colors.primary, fraction))) { append(cell.text) }
                            }
                        }, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                        line.subLines.forEach { sub -> Text(sub.text, fontSize = if (sub.kind == LyricContent.Romanization) 12.sp else 13.sp, color = colors.onSurfaceVariant) }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SyncToneIconButton({ if (position >= end) position = raw.lyrics.lines.firstOrNull()?.start ?: 0; playing = !playing }) {
                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "歌词动画预览（无音频）")
                }
                Text(durationText(position), fontSize = 11.sp, color = colors.onSurfaceVariant)
                UpdateSlider(position.toFloat().coerceIn(0f, end.toFloat()), { position = it.toLong() }, Modifier.weight(1f), 0f..end.toFloat(), "预览歌词进度")
            }
            Text("歌词动画预览 · 无音频", fontSize = 11.sp, color = colors.onSurfaceVariant)
        }
        SyncToneButton(controller::savePreview, Modifier.fillMaxWidth(), enabled = document != null && !state.busy && !state.saved) {
            Icon(if (state.saved) Icons.Default.Check else Icons.Default.Download, null, Modifier.size(18.dp))
            Text(if (state.saved) "已应用此版本" else "应用此歌词", Modifier.padding(start = 6.dp))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(state.settings.contentOrder.filter { it in state.settings.contents }.joinToString(" → ") { it.label },
                Modifier.weight(1f), fontSize = 11.sp, color = colors.onSurfaceVariant)
            SyncToneTextButton(onSettings) { Text("调整顺序", fontSize = 12.sp) }
        }
    }
}

@Composable private fun UpdateSettingsPage(settings: LyricsMatchSettings, changed: (LyricsMatchSettings) -> Unit,
                                         wide: Boolean, onPreview: () -> Unit, onBatch: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 18.dp)) {
        item {
            if (wide) Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                OrderSettings(settings, changed, onPreview, Modifier.weight(1f))
                MatchSettings(settings, changed, Modifier.weight(1f))
            } else OrderSettings(settings, changed, onPreview)
        }
        if (!wide) item { MatchSettings(settings, changed) }
        item {
            if (wide) Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                SourceSettings(settings, changed, Modifier.weight(1f))
                SaveSettings(settings, changed, onBatch, Modifier.weight(1f))
            } else SourceSettings(settings, changed)
        }
        if (!wide) item { SaveSettings(settings, changed, onBatch) }
    }
}
@Composable private fun OrderSettings(settings: LyricsMatchSettings, changed: (LyricsMatchSettings) -> Unit,
                                     onPreview: () -> Unit, modifier: Modifier = Modifier) {
    UpdateCard(modifier) {
        Text("歌词内容与顺序", fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text("勾选需要的内容，调整显示与保存顺序", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
        settings.contentOrder.forEachIndexed { index, kind ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                Checkbox(kind in settings.contents, { checked -> changed(settings.copy(contents = if (checked) settings.contents + kind else settings.contents - kind)) },
                    enabled = kind !in settings.contents || settings.contents.size > 1)
                Text(kind.label, Modifier.weight(1f))
                SyncToneIconButton({ changed(settings.copy(contentOrder = moved(settings.contentOrder, kind, -1))) }, enabled = index > 0) {
                    Icon(Icons.Default.KeyboardArrowUp, "上移${kind.label}")
                }
                SyncToneIconButton({ changed(settings.copy(contentOrder = moved(settings.contentOrder, kind, 1))) }, enabled = index < settings.contentOrder.lastIndex) {
                    Icon(Icons.Default.KeyboardArrowDown, "下移${kind.label}")
                }
            }
        }
        SyncToneTextButton(onPreview) { Text("查看预览") }
    }
}
@Composable private fun MatchSettings(settings: LyricsMatchSettings, changed: (LyricsMatchSettings) -> Unit, modifier: Modifier = Modifier) {
    UpdateCard(modifier) {
        Text("自动匹配规则", fontSize = 16.sp, fontWeight = FontWeight.Medium)
        MatchSlider("匹配相似度", settings.minimumScore.toFloat(), 60f..100f, { "至少 ${it.roundToInt()}%" }, { changed(settings.copy(minimumScore = it.roundToInt())) })
        MatchToggle("必须包含逐字时间", settings.wordOnly, { changed(settings.copy(wordOnly = it)) }, "逐行歌词留待确认，不自动应用")
        MatchToggle("必须包含翻译", settings.requireTranslation, { changed(settings.copy(requireTranslation = it)) })
        MatchToggle("必须包含罗马音", settings.requireRomanization, { changed(settings.copy(requireRomanization = it)) })
        MatchSlider("时长允许误差", settings.durationToleranceMs / 1000f, .5f..5f, { "±${(it * 2).roundToInt() / 2f} 秒" },
            { changed(settings.copy(durationToleranceMs = (it * 2).roundToInt() * 500L)) })
    }
}
@Composable private fun MatchSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, labelValue: (Float) -> String, changed: (Float) -> Unit) {
    var preview by remember(value) { mutableFloatStateOf(value) }
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 13.sp); Text(labelValue(preview), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
    }
    UpdateSlider(preview, { preview = it }, range = range, description = label, onCommit = { changed(preview) })
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun UpdateSlider(value: Float, changed: (Float) -> Unit, modifier: Modifier = Modifier,
                                    range: ClosedFloatingPointRange<Float>, description: String, onCommit: () -> Unit = {}) {
    val colors = MaterialTheme.colorScheme
    val fraction = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    Slider(value, changed, modifier.semantics { contentDescription = description }, valueRange = range, onValueChangeFinished = onCommit,
        thumb = { Box(Modifier.size(16.dp).background(colors.primary, CircleShape)) },
        track = {
            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(colors.primary.copy(alpha = .12f))) {
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(colors.primary))
            }
        })
}
@Composable private fun MatchToggle(title: String, value: Boolean, changed: (Boolean) -> Unit, detail: String? = null) {
    Row(Modifier.fillMaxWidth().clip(UpdateShape).toggleable(value, role = Role.Switch, onValueChange = changed).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp)
            if (detail != null) Text(detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(value, null)
    }
}
@Composable private fun SourceSettings(settings: LyricsMatchSettings, changed: (LyricsMatchSettings) -> Unit, modifier: Modifier = Modifier) {
    UpdateCard(modifier) {
        Text("来源与优先级", fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text("从上到下优先匹配，可关闭不用的来源", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 10.dp))
        settings.priority.forEachIndexed { index, source ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                Checkbox(source in settings.sources, { enabled -> changed(settings.copy(sources = if (enabled) settings.sources + source else settings.sources - source)) })
                Column(Modifier.weight(1f)) {
                    Text(source.label, fontSize = 14.sp)
                    Text(if (source == LyricSource.Kugou) "可提供逐字歌词" else "同步逐行歌词", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SyncToneIconButton({ changed(settings.copy(priority = moved(settings.priority, source, -1))) }, enabled = index > 0) { Icon(Icons.Default.KeyboardArrowUp, "提高${source.label}优先级") }
                SyncToneIconButton({ changed(settings.copy(priority = moved(settings.priority, source, 1))) }, enabled = index < settings.priority.lastIndex) { Icon(Icons.Default.KeyboardArrowDown, "降低${source.label}优先级") }
            }
        }
    }
}
@Composable private fun SaveSettings(settings: LyricsMatchSettings, changed: (LyricsMatchSettings) -> Unit,
                                    onBatch: () -> Unit, modifier: Modifier = Modifier) {
    UpdateCard(modifier) {
        Text("已有歌词处理", fontSize = 16.sp, fontWeight = FontWeight.Medium)
        listOf(false to "保留已有歌词", true to "替换符合条件的歌词").forEach { (replace, title) ->
            Row(Modifier.fillMaxWidth().selectable(settings.overwrite == replace, role = Role.RadioButton, onClick = { changed(settings.copy(overwrite = replace)) }),
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(settings.overwrite == replace, null); Text(title, fontSize = 13.sp)
            }
        }
        MatchToggle("缺少歌词时自动匹配", settings.automatic, { changed(settings.copy(automatic = it)) })
        Text("规则用于自动与批量匹配，手动始终可自由选择版本。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 10.dp))
        SyncToneButton(onBatch, Modifier.fillMaxWidth(), style = SyncToneButtonStyle.Secondary) { Text("按当前规则批量更新", fontSize = 13.sp) }
    }
}

@Composable private fun BatchUpdatePage(state: OnlineLyricsState, controller: OnlineLyricsController,
                                      onPicker: () -> Unit, onSettings: () -> Unit, onReview: (LyricSong) -> Unit) {
    val batch = state.batch
    val rules = batch.settings ?: state.settings
    val entries = if (batch.phase == LyricsBatchPhase.Idle) state.songs.filter { it.id in state.selectedIds }.map(::LyricsBatchEntry) else batch.entries
    val progress by animateFloatAsState(if (entries.isEmpty()) 0f else batch.processed.toFloat() / entries.size, tween(500, easing = LyricsPageCurve))
    val green = updateGreen()
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 18.dp)) {
        item {
            UpdateCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("本次匹配要求", Modifier.weight(1f), fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
                    SyncToneTextButton(onSettings) { Text("调整规则", fontSize = 12.sp) }
                }
                Text("${if (rules.wordOnly) "必须逐字" else "允许逐行"} · ≥${rules.minimumScore}% · ${rules.priority.filter { it in rules.sources }.joinToString(" → ") { it.label }.ifBlank { "未启用来源" }}",
                    color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                Text(rules.contentOrder.filter { it in rules.contents }.joinToString(" → ") { it.label } + " · " + if (rules.overwrite) "替换匹配项" else "保留已有歌词",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                if (batch.active) Text("执行期间修改设置，将用于下一次任务", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            UpdateCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(when (batch.phase) {
                            LyricsBatchPhase.Running -> "正在批量匹配"; LyricsBatchPhase.Paused -> "已暂停"
                            LyricsBatchPhase.Complete -> "更新完成"; LyricsBatchPhase.Stopped -> "已停止"; else -> "待更新音乐"
                        }, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        Text("已处理 ${batch.processed} / ${entries.size} 首", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (batch.phase == LyricsBatchPhase.Running) SyncToneButton(controller::pauseBatch, style = SyncToneButtonStyle.Secondary) { Text("暂停") }
                    else SyncToneButton(controller::startBatch, enabled = entries.isNotEmpty() && rules.sources.isNotEmpty()) {
                        Text(if (batch.phase == LyricsBatchPhase.Paused) "继续匹配" else if (batch.phase == LyricsBatchPhase.Idle) "开始匹配" else "重新匹配")
                    }
                }
                LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 10.dp), color = green)
                Text("已应用 ${batch.entries.count { it.status == LyricsBatchStatus.Applied }}  ·  已保留 ${batch.entries.count { it.status == LyricsBatchStatus.Kept }}  ·  待确认 ${batch.entries.count { it.status == LyricsBatchStatus.Review || it.status == LyricsBatchStatus.Failed }}",
                    fontSize = 12.sp, color = green)
                if (batch.phase == LyricsBatchPhase.Paused) SyncToneTextButton(controller::stopBatch) { Text("结束本次任务") }
            }
        }
        items(entries, key = { it.song.id }) { entry ->
            UpdateCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.song.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(entry.song.artist, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (entry.detail.isNotBlank()) Text(entry.detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(entry.status.label, fontSize = 12.sp, color = if (entry.status == LyricsBatchStatus.Applied) green else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (entry.status == LyricsBatchStatus.Review || entry.status == LyricsBatchStatus.Failed)
                            SyncToneTextButton({ onReview(entry.song) }) { Text("手动选择", fontSize = 12.sp) }
                    }
                }
            }
        }
        item { SyncToneTextButton(onPicker, enabled = !batch.active) { Text("更改选择") } }
    }
}

@Composable private fun SongPicker(state: OnlineLyricsState, controller: OnlineLyricsController,
                                  onDismiss: () -> Unit, onSearch: (LyricSong) -> Unit) {
    var filter by remember { mutableStateOf("") }
    val songs = remember(state.songs, filter) { state.songs.filter { filter.isBlank() || "${it.title} ${it.artist}".contains(filter, true) } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().imePadding().padding(16.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight).fillMaxHeight(.88f),
                shape = UpdateShape, color = MaterialTheme.colorScheme.background) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("选择音乐", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Medium)
                        SyncToneIconButton(onDismiss) { Icon(Icons.Default.Close, "关闭选择音乐") }
                    }
                    OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth(), shape = UpdateShape, singleLine = true, placeholder = { Text("筛选本地曲库") })
                    Text("${songs.size} 首 · 可多选批量更新，或直接搜索单曲", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(songs, key = { it.id }) { song ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(song.id in state.selectedIds, { selected -> controller.setSelection(if (selected) state.selectedIds + song.id else state.selectedIds - song.id) }, enabled = !state.batch.active)
                                Column(Modifier.weight(1f)) {
                                    Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${song.artist} · ${durationText(song.durationMs)}", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                SyncToneTextButton({ onSearch(song) }) { Text("搜索", fontSize = 12.sp) }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        val all = songs.isNotEmpty() && songs.all { it.id in state.selectedIds }
                        SyncToneTextButton({ controller.setSelection(if (all) state.selectedIds - songs.map { it.id }.toSet() else state.selectedIds + songs.map { it.id }) }, enabled = !state.batch.active && songs.isNotEmpty()) { Text(if (all) "取消全选" else "全选") }
                        SyncToneButton(onDismiss) { Text("完成 · ${state.selectedIds.size} 首") }
                    }
                }
            }
        }
    }
}
