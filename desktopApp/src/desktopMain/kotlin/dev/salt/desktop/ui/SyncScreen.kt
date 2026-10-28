package dev.salt.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.salt.desktop.sync.DesktopSyncRepository
import dev.salt.replica.sync.*

private fun fileSize(bytes: Long) = if (bytes >= 1_073_741_824) "%.2f GB".format(bytes / 1_073_741_824.0)
    else "%.1f MB".format(bytes / 1_048_576.0)

@Composable
internal fun SyncScreen(repository: DesktopSyncRepository, search: String) {
    val state by repository.state.collectAsState()
    val colors = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    var dialog by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    val receiving = state.phase == SyncPhase.TRANSFERRING
    val busy = receiving || state.remoteSending
    val pairing = state.phase in listOf(SyncPhase.COMPARING, SyncPhase.WAITING) || state.prompt != null
    val connected = state.peer != null && !pairing
    val selected = state.songs.filter { it.hash in state.selected }
    val songs = state.songs.filter { search.isBlank() || "${it.title} ${it.artist} ${it.album}".contains(search.trim(), true) }
    val phase = when {
        state.remoteSending -> "正在发送"
        else -> when (state.phase) {
            SyncPhase.SCANNING -> "正在发现设备"
            SyncPhase.COMPARING -> "正在对比音乐库"
            SyncPhase.WAITING -> "等待配对"
            SyncPhase.TRANSFERRING -> "正在同步"
            SyncPhase.PAUSED -> "同步已暂停"
            SyncPhase.FAILED -> "同步未完成"
            SyncPhase.COMPLETE -> "同步完成"
            else -> if (state.serving) "已就绪" else "等待局域网连接"
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Wifi, null, Modifier.size(24.dp), tint = if (state.serving) StatisticsGreen else colors.onSurfaceVariant)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(state.localName.ifBlank { "Windows" }, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                    Tool("修改本机名称", Icons.Default.Edit, { input = state.localName; dialog = "rename" }, size = 32.dp, iconSize = 16.dp)
                }
                SelectionContainer { Text(state.address.ifBlank { "未连接" }, fontSize = 12.sp, color = colors.onSurfaceVariant) }
            }
            Tool("复制本机地址", Icons.Default.ContentCopy, { clipboard.setText(AnnotatedString(state.address)) }, state.address.isNotBlank())
            Text(phase, Modifier.padding(start = 12.dp), fontSize = 12.sp,
                color = if (state.phase == SyncPhase.COMPLETE) StatisticsGreen else colors.primary)
        }
        state.error?.let {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(it, Modifier.weight(1f), fontSize = 12.sp, color = colors.error)
                Tool("重新连接", Icons.Default.Refresh, { repository.start() }, !busy)
            }
        }
        HorizontalDivider(color = colors.outlineVariant.copy(alpha = .6f))
        Row(Modifier.weight(1f).padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            ScrollableColumn(Modifier.width(224.dp).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("附近设备", Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Tool("扫描设备", Icons.Default.Radar, repository::scan, !busy && !pairing, size = 34.dp, iconSize = 20.dp)
                    Tool("使用地址连接", Icons.Default.Link, { input = ""; dialog = "address" }, !busy && !pairing, size = 34.dp, iconSize = 20.dp)
                }
                Spacer(Modifier.height(10.dp))
                if (state.peers.isEmpty()) {
                    Text(if (state.phase == SyncPhase.SCANNING) "正在扫描…" else "暂无附近设备",
                        Modifier.padding(vertical = 16.dp), fontSize = 12.sp, color = colors.onSurfaceVariant)
                }
                state.peers.forEach { peer ->
                    val active = peer.id == state.peer?.id
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(DesktopCardShape)
                        .background(if (active) colors.primary.copy(alpha = .09f) else colors.surface)
                        .desktopClickable(enabled = !busy && !pairing) { repository.select(peer) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Devices, null, Modifier.size(23.dp), tint = if (active) colors.primary else colors.onSurfaceVariant)
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(peer.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${peer.host}:${peer.port}", Modifier.padding(top = 4.dp), fontSize = 10.sp,
                                color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (active) Icon(Icons.Default.CheckCircle, "已选中", Modifier.size(16.dp), tint = colors.primary)
                    }
                }
                state.peer?.let { peer ->
                    HorizontalDivider(Modifier.padding(vertical = 16.dp), color = colors.outlineVariant.copy(alpha = .6f))
                    Text("当前设备", fontSize = 11.sp, color = colors.onSurfaceVariant)
                    Text(peer.name, Modifier.padding(top = 8.dp), fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    DesktopTextButton({ dialog = "forget" }, enabled = !busy && !pairing, contentColor = colors.error, contentPadding = PaddingValues(0.dp)) {
                        Icon(Icons.Default.LinkOff, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("移除配对", fontSize = 12.sp)
                    }
                    Text(state.comparison?.statusText() ?: "尚未完成曲库比对", Modifier.padding(top = 10.dp),
                        fontSize = 12.sp, color = colors.onSurfaceVariant)
                    Text("MP3 / FLAC · 按文件内容比对", Modifier.padding(top = 4.dp), fontSize = 10.sp, color = colors.onSurfaceVariant)
                    DesktopTextButton(repository::refreshComparison, enabled = !busy && !pairing, contentPadding = PaddingValues(0.dp)) {
                        Icon(Icons.Default.Refresh, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("重新对比曲库", fontSize = 12.sp)
                    }
                }
            }
            VerticalDivider(color = colors.outlineVariant.copy(alpha = .6f))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 38.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("待接收歌曲", Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    if (songs.isNotEmpty()) {
                        Text(if (search.isBlank()) "全选" else "筛选结果", fontSize = 12.sp, color = colors.onSurfaceVariant)
                        DesktopCheckbox(songs.all { it.hash in state.selected }, { repository.selectAll(songs.map { it.hash }.toSet()) }, enabled = !busy && !pairing)
                    }
                }
                Text("已选 ${selected.size} 首 · ${fileSize(selected.sumOf { it.size })} · 跳过 ${state.skipped} 首相同文件",
                    Modifier.padding(top = 4.dp, bottom = 14.dp), fontSize = 11.sp, color = colors.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DesktopButton({ if (receiving) repository.pause() else repository.begin() }, enabled = connected && !state.remoteSending) {
                        Icon(if (receiving) Icons.Default.Pause else if (state.phase in listOf(SyncPhase.PAUSED, SyncPhase.FAILED)) Icons.Default.PlayArrow else Icons.Default.Download,
                            null, Modifier.size(18.dp))
                        Spacer(Modifier.width(7.dp))
                        Text(if (receiving) "暂停同步" else if(state.songs.isEmpty() && (state.comparison?.missingLocal ?: 0) > 0) "载入待接收歌曲" else if (state.phase in listOf(SyncPhase.PAUSED, SyncPhase.FAILED)) "继续同步"
                            else if (selected.isEmpty()) "同步播放历史" else "开始同步", fontSize = 13.sp)
                    }
                    if (state.phase in listOf(SyncPhase.TRANSFERRING, SyncPhase.PAUSED, SyncPhase.FAILED) && state.peer != null)
                        Tool("取消同步", Icons.Default.Close, repository::cancelTransfer)
                    if (pairing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                if (state.totalBytes > 0) {
                    DesktopTaskProgress(progress = (state.bytes.toFloat() / state.totalBytes).coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth())
                    Text("${fileSize(state.bytes)} / ${fileSize(state.totalBytes)} · ${state.completed.size}/${selected.size} 首",
                        Modifier.padding(top = 6.dp, bottom = 10.dp), fontSize = 11.sp, color = colors.onSurfaceVariant)
                }
                if (songs.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(if (connected) Icons.Default.CheckCircle else Icons.Default.LibraryMusic, null,
                                Modifier.size(36.dp), tint = if (connected) StatisticsGreen else colors.onSurfaceVariant.copy(alpha = .5f))
                            Text(if (search.isNotBlank()) "没有匹配的歌曲" else if (pairing) "正在连接设备"
                                else if (connected) syncEmptyLibraryText(state.comparison) else "尚未选择设备", Modifier.padding(top = 12.dp), fontSize = 13.sp,
                                color = colors.onSurfaceVariant)
                        }
                    }
                } else ScrollableList(Modifier.weight(1f).fillMaxWidth()) {
                    items(songs, key = { it.hash }) { song ->
                        Row(Modifier.fillMaxWidth().height(65.dp).clip(DesktopCardShape)
                            .desktopClickable(enabled = !busy && !pairing) { repository.toggle(song.hash) }, verticalAlignment = Alignment.CenterVertically) {
                            DesktopCheckbox(song.hash in state.selected, { repository.toggle(song.hash) }, enabled = !busy && !pairing)
                            Column(Modifier.weight(1f).padding(end = 10.dp)) {
                                Text(song.title, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${song.artist} · ${if (song.flac) "FLAC" else "MP3"} · ${fileSize(song.size)}", Modifier.padding(top = 4.dp),
                                    fontSize = 11.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (song.hash in state.completed) Icon(Icons.Default.CheckCircle, "已接收", Modifier.size(18.dp), tint = StatisticsGreen)
                            else if (song.hash == state.currentHash && receiving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                    }
                }
                HorizontalDivider(color = colors.outlineVariant.copy(alpha = .6f))
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.History, null, Modifier.size(20.dp), tint = colors.primary)
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text("播放历史", fontSize = 12.sp)
                        Text(if (state.phase == SyncPhase.COMPLETE) "双向合并完成 · 更新 ${state.historyChanges} 条"
                            else state.message.ifBlank { "等待同步" }, Modifier.padding(top = 4.dp), fontSize = 11.sp,
                            color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(if (state.phase == SyncPhase.COMPLETE) Icons.Default.CheckCircle else Icons.Default.SyncAlt, null,
                        Modifier.size(18.dp), tint = StatisticsGreen)
                }
            }
        }
    }
    state.prompt?.let { prompt ->
        DesktopAlertDialog(onDismissRequest = repository::cancelPair,
            title = { Text("${if (prompt.incoming) "来自" else "连接"} ${prompt.peerName}", fontSize = 20.sp) },
            text = { Column {
                Text("配对码", fontSize = 12.sp, color = colors.onSurfaceVariant)
                Text(prompt.code.chunked(3).joinToString(" "), Modifier.padding(vertical = 20.dp), fontSize = 34.sp, fontWeight = FontWeight.Medium)
                Text(if (prompt.localConfirmed) "等待对方确认" else "确认两台设备显示相同配对码", fontSize = 13.sp)
            } }, confirmButton = { DesktopButton(repository::confirmPair, enabled = !prompt.localConfirmed) { Text(if (prompt.localConfirmed) "已确认" else "确认配对") } },
            dismissButton = { DesktopButton(repository::cancelPair, style = DesktopButtonStyle.Secondary) { Text("取消") } })
    }
    if (dialog != null) DesktopAlertDialog(onDismissRequest = { dialog = null },
        title = { Text(when (dialog) { "rename" -> "本机名称"; "forget" -> "移除配对"; else -> "使用地址连接" }) },
        text = {
            if (dialog == "forget") Text("移除与 ${state.peer?.name.orEmpty()} 的配对？")
            else DesktopTextField(input, { input = it }, if (dialog == "rename") "设备名称" else "IP:端口", Modifier.width(360.dp))
        }, confirmButton = { DesktopButton({
            when (dialog) { "rename" -> repository.rename(input); "forget" -> repository.forgetPeer(); else -> repository.connectAddress(input) }
            dialog = null
        }, enabled = dialog == "forget" || input.isNotBlank()) { Text(when (dialog) { "rename" -> "保存"; "forget" -> "移除"; else -> "连接" }) } },
        dismissButton = { DesktopButton({ dialog = null }, style = DesktopButtonStyle.Secondary) { Text("取消") } })
}
