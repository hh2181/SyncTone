package dev.salt.desktop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.salt.desktop.library.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun MusicScanScreen(state: DesktopLibraryState, library: DesktopLibrary, chooseFolder: () -> Unit) {
    val progress = state.scan
    val busy = progress.phase == ScanPhase.Scanning
    val color = when (progress.phase) {
        ScanPhase.Scanning -> MaterialTheme.colorScheme.primary
        ScanPhase.Complete -> Color(0xFF34A853)
        ScanPhase.Failed -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().clip(DesktopCardShape)) {
        Row(Modifier.fillMaxWidth().background(color.copy(alpha = .08f)).padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                AnimatedContent(progress.phase, transitionSpec = {
                    (fadeIn(tween(350)) + slideInVertically(tween(350)) { it / 3 }) togetherWith
                        (fadeOut(tween(350)) + slideOutVertically(tween(350)) { -it / 3 })
                }) { phase ->
                    Text(when (phase) {
                        ScanPhase.NotScanned -> "尚未扫描"
                        ScanPhase.Scanning -> "正在扫描"
                        ScanPhase.Complete -> "扫描完成"
                        ScanPhase.Cancelled -> "扫描已中断"
                        ScanPhase.Failed -> "扫描未完成"
                    }, fontSize = 22.sp, fontWeight = FontWeight.Medium, color = color)
                }
                Text("${state.tracks.size} 首歌曲 · ${state.tracks.count { !it.available }} 首暂不可用",
                    Modifier.padding(top = 10.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (progress.lastSuccessUtc > 0) "上次完成  " + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(progress.lastSuccessUtc)) else "上次完成  —",
                    Modifier.padding(top = 6.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (busy) ScanAction("取消扫描", Icons.Default.Stop, library::cancelScan)
            else {
                ScanAction("重新扫描", Icons.Default.Refresh, { library.scan() }, state.folders.isNotEmpty() && state.ready && !state.importing)
                ScanAction("重新校验全部内容", Icons.AutoMirrored.Filled.FactCheck, { library.scan(force = true) }, state.folders.isNotEmpty() && state.ready && !state.importing)
                ScanAction("添加目录", Icons.Default.CreateNewFolder, chooseFolder, state.ready && !state.importing)
            }
        }
        if (busy) DesktopTaskProgress(Modifier.fillMaxWidth())
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Text("检查 ${progress.checked}", fontSize = 13.sp)
            Text("新增 ${progress.added}", fontSize = 13.sp)
            Text("重复 ${progress.duplicates}", fontSize = 13.sp)
            Text("不可读 ${progress.unreadable}", fontSize = 13.sp, color = if (progress.unreadable > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        }
        Text(progress.current.ifBlank { progress.error.orEmpty() }, Modifier.fillMaxWidth().heightIn(min = 26.dp),
            maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp,
            color = if (progress.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().height(58.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("扫描目录", Modifier.weight(1f), fontWeight = FontWeight.Medium)
            if (state.folders.isEmpty()) DesktopTextButton(chooseFolder, enabled = state.ready && !busy) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Text("添加目录", Modifier.padding(start = 8.dp))
            }
        }
        ScrollableList(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(state.folders, key = { it.path }) { folder ->
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Folder, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                        Text(folder.path, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                        folder.issue?.let { Text(it, Modifier.padding(top = 5.dp), fontSize = 12.sp, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.error) }
                    }
                    ScanAction("移除扫描目录", Icons.Default.RemoveCircleOutline, { library.removeFolder(folder.path) }, !busy)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ScanAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, action: () -> Unit, enabled: Boolean = true) {
    Tool(label, icon, action, enabled)
}
