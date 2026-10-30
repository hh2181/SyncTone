package dev.salt.replica.equalizer

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import dev.salt.buttons.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.*

@Composable
fun EqualizerScreen(repository: EqualizerRepository, modifier: Modifier = Modifier,
    onMenu: (() -> Unit)? = null, desktop: Boolean = false) {
    val state by repository.state.collectAsState()
    val runtime by repository.runtime.collectAsState()
    val rate = runtime.model?.sampleRate ?: 48_000
    val model by produceState<EqModel?>(null, state.config, rate) {
        value = withContext(Dispatchers.Default) { EqModel.design(state.config, rate) }
    }
    val colors = MaterialTheme.colorScheme
    val green = if (colors.background.luminance() > .5f) Color(0xFF25864A) else Color(0xFF85D8A2)
    var selected by rememberSaveable { mutableIntStateOf(5) }
    var saving by remember { mutableStateOf(false) }
    val currentName = state.presets.firstOrNull { it.id == state.selected }?.name
        ?: if (state.config.gains.all { it == 0 }) "平直" else "未保存的调节"
    val applied = runtime.connected && runtime.model?.config == state.config
    Surface(modifier.fillMaxSize(), color = colors.background, contentColor = colors.onSurface) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = desktop && maxWidth >= 760.dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onMenu != null) SyncToneIconButton(onMenu) { Icon(Icons.Default.Menu, "打开侧边栏") }
                Text("均衡器", Modifier.weight(1f), fontSize = 27.sp, fontWeight = FontWeight.SemiBold)
                Text(if (state.config.enabled) "已开启" else "已旁路", fontSize = 12.sp, color = colors.onSurfaceVariant)
                Switch(state.config.enabled, repository::setEnabled, Modifier.padding(start = 8.dp).semantics { contentDescription = "开启均衡器" })
            }
            Text("让熟悉的音乐，更合你的听感", Modifier.padding(start = if (onMenu != null) 48.dp else 0.dp),
                fontSize = 12.sp, lineHeight = 18.sp, color = colors.onSurfaceVariant)
            }
            @Composable fun Main(modifier: Modifier) {
                Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    EqCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("频率响应", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                            Text(if (!state.config.enabled) "原始声音" else currentName, color = if (state.selected != null) green else colors.primary,
                                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("输出响应 · 含前级", fontSize = 11.sp, color = colors.onSurfaceVariant)
                            Text("前级 ${eqGain(model?.preampDb ?: 0.0)} dB", fontSize = 11.sp, color = colors.onSurfaceVariant)
                        }
                        model?.let { EqCurve(it, selected, wide) { index -> selected = index } }
                            ?: Spacer(Modifier.height(if (wide) 260.dp else 206.dp))
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("低频", fontSize = 11.sp, color = colors.onSurfaceVariant)
                            Text(if (runtime.connected) "${rate / 1000.0} kHz · ${if (applied) "已应用" else "等待应用"}"
                                else "播放后按实际采样率应用", fontSize = 11.sp, color = colors.onSurfaceVariant)
                            Text("高频", fontSize = 11.sp, color = colors.onSurfaceVariant)
                        }
                    }
                    EqCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("十段微调", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                            Text("中心频率 · Hz", fontSize = 12.sp, color = colors.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(10.dp))
                        val columns = if (wide) 10 else 5
                        for (row in 0 until 10 / columns) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (wide) 2.dp else 4.dp)) {
                                for (column in 0 until columns) {
                                    val index = row * columns + column
                                    EqVerticalBand(index, state.config.gains[index], selected == index,
                                        state.config.enabled && index < (model?.activeBands ?: 10), desktop,
                                        { selected = index }, { gain -> selected = index; repository.setGain(index, gain) }, Modifier.weight(1f))
                                }
                            }
                            if (row == 0 && columns == 5) Spacer(Modifier.height(8.dp))
                        }
                        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = colors.onSurface.copy(alpha = .08f))
                        EqFineControl(selected, state.config.gains[selected],
                            state.config.enabled && selected < (model?.activeBands ?: 10)) { repository.setGain(selected, it) }
                        if ((model?.activeBands ?: 10) < 10) Text("部分频段超出当前音频采样率范围，已暂停使用。",
                            Modifier.padding(top = 8.dp), fontSize = 11.sp, color = colors.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("自动预留音量空间", fontSize = 12.sp)
                                Text("前级 ${eqGain(model?.preampDb ?: 0.0)} dB", fontSize = 11.sp, color = colors.onSurfaceVariant)
                            }
                            Switch(state.config.autoHeadroom, repository::setHeadroom,
                                Modifier.semantics { contentDescription = "自动预留音量空间，减少提升频段时削波" })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SyncToneButton({ saving = true }, Modifier.weight(1f)) {
                            Icon(Icons.Default.Save, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("保存方案")
                        }
                        SyncToneButton(repository::reset, style = SyncToneButtonStyle.Secondary) {
                            Icon(Icons.Default.RestartAlt, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("重置")
                        }
                    }
                }
            }
            @Composable fun Saved(modifier: Modifier) {
                EqCard(modifier) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("已保存方案", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                        Text("${state.presets.size} 个", fontSize = 12.sp, color = colors.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(14.dp))
                    if (state.presets.isEmpty()) Text("调好后保存，随时切换喜欢的听感。", fontSize = 12.sp, color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp))
                    state.presets.chunked(if (wide) 1 else 2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { preset ->
                                EqPresetCard(preset, preset.id == state.selected, green, rate, { repository.apply(preset.id) },
                                    { repository.delete(preset.id) }, Modifier.weight(1f))
                            }
                            if (!wide && row.size == 1) Spacer(Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
            if (wide) Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                Main(Modifier.weight(1f)); Saved(Modifier.width(210.dp))
            } else { Main(Modifier.fillMaxWidth()); Saved(Modifier.fillMaxWidth()) }
            (state.error ?: runtime.error)?.let { Text(it, color = colors.error, fontSize = 12.sp) }
            Spacer(Modifier.height(4.dp))
        }
    }
    }
    if (saving) {
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { saving = false }, shape = EqShape, containerColor = colors.surface,
            title = { Text("保存方案") }, text = {
                OutlinedTextField(name, { name = it.take(40) }, label = { Text("方案名称") }, singleLine = true, shape = RoundedCornerShape(12.dp))
            }, confirmButton = { SyncToneTextButton({ repository.save(name); saving = false }, enabled = name.isNotBlank()) { Text("保存") } },
            dismissButton = { SyncToneTextButton({ saving = false }) { Text("取消") } })
    }
}

@Composable private fun EqCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(EqShape).background(MaterialTheme.colorScheme.surface).padding(14.dp), content = content)
}

@Composable private fun EqFineControl(index: Int, gain: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    var value by remember(index) { mutableStateOf(eqGain(gain / 10.0)) }
    LaunchedEffect(gain, focused, index) { if (!focused) value = eqGain(gain / 10.0) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${eqFrequency(EqFrequencies[index])} Hz", fontSize = 15.sp)
            Text("0.1 dB / 步", fontSize = 11.sp, color = colors.onSurfaceVariant)
        }
        SyncToneIconButton({ onChange((gain - 1).coerceAtLeast(-120)) }, enabled = enabled,
            modifier = Modifier.size(44.dp).background(colors.surfaceVariant, RoundedCornerShape(12.dp))) { Icon(Icons.Default.Remove, "降低所选频段 0.1 分贝") }
        BasicTextField(value, { text ->
            if (text.length <= 7) { value = text; text.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { onChange((it * 10).roundToInt().coerceIn(-120, 120)) } }
        }, enabled = enabled, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            textStyle = TextStyle(fontSize = 16.sp, textAlign = TextAlign.Center, color = colors.onSurface),
            modifier = Modifier.padding(horizontal = 5.dp).width(73.dp).height(44.dp)
                .background(colors.surfaceVariant, RoundedCornerShape(12.dp))
                .onFocusChanged { focused = it.isFocused; if (!it.isFocused) value = eqGain(gain / 10.0) }
                .semantics { contentDescription = "所选频段目标增益，分贝" }, decorationBox = { inner ->
                Row(Modifier.fillMaxSize().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f), Alignment.Center) { inner() }; Text("dB", fontSize = 10.sp, color = colors.onSurfaceVariant)
                }
            })
        SyncToneIconButton({ onChange((gain + 1).coerceAtMost(120)) }, enabled = enabled,
            modifier = Modifier.size(44.dp).background(colors.surfaceVariant, RoundedCornerShape(12.dp))) { Icon(Icons.Default.Add, "提高所选频段 0.1 分贝") }
    }
}

@Composable private fun EqPresetCard(preset: EqPreset, active: Boolean, green: Color, rate: Int,
    onApply: () -> Unit, onDelete: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val model by produceState<EqModel?>(null, preset.config, rate) {
        value = withContext(Dispatchers.Default) { EqModel.design(preset.config.copy(enabled = true), rate) }
    }
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(if (active) green.copy(alpha = .08f) else colors.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onApply).padding(12.dp).semantics { contentDescription = "应用方案：${preset.name}" }) {
            Text(preset.name, Modifier.fillMaxWidth().padding(end = 27.dp), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (active) "正在使用" else "自定义方案", Modifier.padding(top = 4.dp), fontSize = 11.sp,
                color = if (active) green else colors.onSurfaceVariant)
            val current = model
            Canvas(Modifier.fillMaxWidth().height(34.dp).padding(top = 8.dp)) {
                if (current != null) {
                    val values = (0..60).map { current.responseDb(20 * (current.maxFrequency / 20).pow(it / 60.0)).toFloat() }
                    val bound = max(6f, values.maxOf { abs(it) })
                    val path = Path().apply { values.forEachIndexed { i, v ->
                        val x = size.width * i / 60; val y = size.height / 2 - v / bound * size.height * .42f
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                    } }
                    drawPath(path, if (active) green else colors.onSurfaceVariant, style = Stroke(1.4.dp.toPx()))
                }
            }
        }
        SyncToneIconButton(onDelete, Modifier.align(Alignment.TopEnd).size(40.dp)) {
            Icon(Icons.Default.DeleteOutline, "删除方案：${preset.name}", Modifier.size(16.dp), tint = colors.onSurfaceVariant)
        }
    }
}
