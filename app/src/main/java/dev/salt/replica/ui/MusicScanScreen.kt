package dev.salt.replica.ui

import dev.salt.buttons.SyncToneIconButton as IconButton

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.automirrored.filled.ManageSearch
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.salt.replica.library.MusicScanPhase
import dev.salt.replica.library.MusicScanner
import dev.salt.replica.ui.theme.hyperClickable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicScanScreen(onMenu: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val scanner = remember { MusicScanner.get(context) }
    val state by scanner.state.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    val scanning = state.phase == MusicScanPhase.SCANNING
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(scanner::addFolder)
    }
    val green = if (colors.background == Color(0xFFF5F6F8)) Color(0xFF249773) else Color(0xFF64CBA7)
    val accent by animateColorAsState(when (state.phase) {
        MusicScanPhase.NOT_SCANNED -> colors.onSurfaceVariant
        MusicScanPhase.SCANNING -> colors.primary
        MusicScanPhase.COMPLETE -> green
        MusicScanPhase.FAILED -> colors.error
    }, tween(350, easing = statisticsEasing(true)), label = "scan-accent")
    val surface by animateColorAsState(when (state.phase) {
        MusicScanPhase.NOT_SCANNED -> colors.surfaceVariant
        MusicScanPhase.SCANNING -> colors.primaryContainer
        MusicScanPhase.COMPLETE -> green.copy(alpha = .10f)
        MusicScanPhase.FAILED -> colors.error.copy(alpha = .10f)
    }, tween(350, easing = statisticsEasing(true)), label = "scan-surface")
    val lastScan = if (state.lastScanUtc > 0) DateTimeFormatter.ofPattern("M月d日 HH:mm")
        .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(state.lastScanUtc)) else "尚无记录"

    CompositionLocalProvider(LocalContentColor provides colors.onSurface) {
        LazyColumn(modifier.padding(horizontal = 16.dp), contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("header") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onMenu) { Icon(Icons.Default.Menu, "打开侧栏") }
                    Text("音乐库", fontSize = 13.sp, color = colors.onSurfaceVariant)
                }
                Text("扫描音乐", fontSize = 27.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
            }
            item("status") {
                Column(Modifier.fillMaxWidth().aspectRatio(2f).clip(RoundedCornerShape(26.dp))
                    .background(surface).hyperClickable(enabled = !scanning) {
                        if (state.folders.isEmpty()) picker.launch(null) else scanner.scan()
                    }.padding(horizontal = 18.dp, vertical = 14.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (scanning) CircularProgressIndicator(Modifier.size(20.dp), color = accent, strokeWidth = 2.dp)
                        else Icon(when (state.phase) {
                            MusicScanPhase.COMPLETE -> Icons.Default.CheckCircle
                            MusicScanPhase.FAILED -> Icons.Default.ErrorOutline
                            else -> Icons.AutoMirrored.Filled.ManageSearch
                        }, null, Modifier.size(22.dp), tint = accent)
                        Text("本地音乐", fontSize = 12.sp, color = accent, modifier = Modifier.padding(start = 8.dp))
                        Spacer(Modifier.weight(1f))
                        if (scanning) Text("${state.found} 首", fontSize = 12.sp, color = accent)
                    }
                    AnimatedContent(state.phase, transitionSpec = {
                        (fadeIn(tween(220)) + slideInVertically(tween(350, easing = statisticsEasing(true))) { it / 5 })
                            .togetherWith(fadeOut(tween(120)) + slideOutVertically(tween(220)) { -it / 5 })
                    }, label = "scan-status", modifier = Modifier.padding(top = 8.dp)) { phase ->
                        Text(when (phase) {
                            MusicScanPhase.NOT_SCANNED -> "未扫描"
                            MusicScanPhase.SCANNING -> "扫描中"
                            MusicScanPhase.COMPLETE -> "已扫描"
                            MusicScanPhase.FAILED -> "扫描未完成"
                        }, fontSize = 26.sp, fontWeight = FontWeight.Medium, color = colors.onSurface)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(if (scanning) state.current else if (state.phase == MusicScanPhase.NOT_SCANNED)
                        "${state.folders.size} 个目录" else "新增 ${state.added} 首 · 重复 ${state.duplicates} 首" +
                        if (state.unreadable > 0) " · 无法读取 ${state.unreadable} 首" else "",
                        fontSize = 11.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("上次扫描：$lastScan", fontSize = 11.sp, color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(top = 3.dp), maxLines = 1)
                }
            }
            state.error?.let { error -> item("error") {
                Text(error, color = colors.error, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp))
            } }
            item("folders-header") {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("扫描目录", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("${state.folders.size} 个", fontSize = 12.sp, color = colors.onSurfaceVariant)
                    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text("添加目录") } }, state = rememberTooltipState()) {
                        IconButton(onClick = { picker.launch(null) }, enabled = !scanning) {
                            Icon(Icons.Default.Add, "添加目录", tint = if (scanning) colors.onSurfaceVariant else colors.primary)
                        }
                    }
                }
            }
            if (state.folders.isEmpty()) item("empty") {
                Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.FolderOpen, null, Modifier.size(38.dp), tint = colors.onSurfaceVariant)
                    Text("暂无扫描目录", fontSize = 14.sp, color = colors.onSurfaceVariant)
                }
            }
            items(state.folders, key = { it.uri }) { folder ->
                Row(Modifier.fillMaxWidth().animateItem(tween(220), tween(350, easing = statisticsEasing(true)), tween(150))
                    .clip(RoundedCornerShape(22.dp)).background(colors.surface)
                    .hyperClickable(enabled = !scanning && folder.issue != null) { picker.launch(android.net.Uri.parse(folder.uri)) }
                    .padding(start = 16.dp, end = 6.dp, top = 14.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.FolderOpen, null, Modifier.size(27.dp), tint = if (folder.issue == null) colors.primary else colors.error)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(folder.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(folder.issue ?: folder.path, fontSize = 11.sp, color = if (folder.issue == null) colors.onSurfaceVariant else colors.error,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text("移除扫描目录") } }, state = rememberTooltipState()) {
                        IconButton(onClick = { scanner.removeFolder(folder) }, enabled = !scanning) {
                            Icon(Icons.Default.Close, "移除扫描目录 ${folder.name}", Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
