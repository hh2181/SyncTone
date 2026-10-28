package dev.salt.desktop.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import dev.salt.desktop.lyrics.DesktopLyricsSettings
import kotlin.math.roundToInt

@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun LyricsSettingsDialog(visible: Boolean, settings: DesktopLyricsSettings, offsetMs: Long, enabled: Boolean,
                                   onChange: (DesktopLyricsSettings) -> Unit, onOffset: (Long) -> Unit,
                                   onImport: () -> Unit, onRestore: () -> Unit, onDismiss: () -> Unit) {
    val presence = remember { MutableTransitionState(false) }
    presence.targetState = visible
    val scrim by animateFloatAsState(if (visible) .52f else 0f,
        tween(if (visible) 230 else 260, easing = LinearOutSlowInEasing))
    if (!presence.currentState && !presence.targetState && presence.isIdle) return
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BoxWithConstraints(Modifier.fillMaxSize().focusRequester(focus).focusProperties {
        exit = { FocusRequester.Cancel }
    }.onPreviewKeyEvent {
        if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) { onDismiss(); true } else false
    }.focusable().focusGroup().semantics { paneTitle = "歌词设置" }) {
        val contentHeight = minOf(500.dp, maxHeight - 192.dp).coerceAtLeast(100.dp)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrim))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss))
        val travel = with(LocalDensity.current) { maxHeight.roundToPx() }
        AnimatedVisibility(presence, Modifier.align(Alignment.Center).padding(16.dp),
            enter = slideInVertically(tween(440, 70, CubicBezierEasing(.2f, .9f, .25f, 1f))) { travel } + fadeIn(tween(180, 70)),
            exit = slideOutVertically(tween(260, easing = CubicBezierEasing(.4f, 0f, .2f, 1f))) { travel } + fadeOut(tween(180, 50))) {
            Surface(Modifier.widthIn(max = 528.dp).fillMaxWidth().heightIn(max = maxHeight - 32.dp)
                .clickable(remember { MutableInteractionSource() }, indication = null) {},
                shape = DesktopCardShape, color = MaterialTheme.colorScheme.background, shadowElevation = 16.dp) {
                Column(Modifier.padding(24.dp)) {
                    Text("歌词设置", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 20.dp))
                    Box(Modifier.weight(1f, fill = false)) {
                        LyricsSettingsContent(settings, offsetMs, enabled, onChange, onOffset, onImport, onRestore,
                            maximumHeight = contentHeight)
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                        DesktopButton(onDismiss) { Text("完成") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun LyricsSettingsContent(settings: DesktopLyricsSettings, offsetMs: Long, enabled: Boolean,
                                  onChange: (DesktopLyricsSettings) -> Unit, onOffset: (Long) -> Unit,
                                  onImport: () -> Unit, onRestore: () -> Unit, maximumHeight: Dp = 440.dp) {
    var tab by remember { mutableIntStateOf(0) }
            Column(Modifier.width(480.dp).height(maximumHeight)) {
                DesktopChoiceRow(listOf("文本", "动画", "背景"), tab, { tab = it }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                AnimatedContent(tab, transitionSpec = { desktopPageTransition(targetState > initialState) },
                    modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds()) { selectedTab ->
                ScrollableColumn(Modifier.fillMaxSize().padding(bottom = 4.dp)) {
                    when (selectedTab) {
                        0 -> {
                            DesktopSettingsGroup {
                            Text("MiSans", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 6.dp))
                            ValueSlider("字号", settings.fontSize, 20f..44f, "${settings.fontSize.roundToInt()} sp") {
                                onChange(settings.copy(fontSize = it))
                            }
                            ValueSlider("字重", settings.fontWeight.toFloat(), 150f..700f, "${settings.fontWeight}") {
                                onChange(settings.copy(fontWeight = it.roundToInt()))
                            }
                            }
                            Spacer(Modifier.height(12.dp))
                            DesktopSettingsGroup {
                                DesktopSettingToggle("显示翻译", settings.translation, { onChange(settings.copy(translation = it)) })
                                DesktopSettingToggle("歌词居中", settings.center, { onChange(settings.copy(center = it)) })
                            }
                            Spacer(Modifier.height(12.dp))
                            DesktopSettingsGroup {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("时间偏移", Modifier.weight(1f))
                                Tool("歌词提前 100 毫秒", Icons.Default.Remove, { onOffset(offsetMs + 100) }, enabled && offsetMs < 10_000)
                                Text("%+.1f s".format(java.util.Locale.US, -offsetMs / 1000f), Modifier.width(64.dp), fontSize = 13.sp)
                                Tool("歌词延后 100 毫秒", Icons.Default.Add, { onOffset(offsetMs - 100) }, enabled && offsetMs > -10_000)
                                Tool("重置时间偏移", Icons.Default.RestartAlt, { onOffset(0) }, enabled && offsetMs != 0L)
                            }
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                DesktopButton(onImport, enabled = enabled, style = DesktopButtonStyle.Secondary) { Icon(Icons.Default.FileOpen, null, Modifier.size(18.dp)); Text("打开 LRC", Modifier.padding(start = 8.dp)) }
                                DesktopButton(onRestore, enabled = enabled, style = DesktopButtonStyle.Secondary) { Icon(Icons.Default.Restore, null, Modifier.size(18.dp)); Text("恢复原歌词", Modifier.padding(start = 8.dp)) }
                            }
                        }
                        1 -> {
                            DesktopSettingsGroup {
                            Text("动画曲线", Modifier.padding(bottom = 10.dp))
                            DesktopChoiceRow(listOf("标准", "柔和", "线性"), settings.curve, { onChange(settings.copy(curve = it)) }, Modifier.fillMaxWidth())
                            ValueSlider("动画速度", settings.speed, .5f..2f, "%.1fx".format(java.util.Locale.US, settings.speed)) {
                                onChange(settings.copy(speed = it))
                            }
                            }
                            Spacer(Modifier.height(12.dp))
                            DesktopSettingsGroup {
                                DesktopSettingToggle("歌词模糊", settings.blur, { onChange(settings.copy(blur = it)) })
                                DesktopSettingToggle("逐字辉光", settings.glow, { onChange(settings.copy(glow = it)) })
                                DesktopSettingToggle("歌词错峰", settings.stagger, { onChange(settings.copy(stagger = it)) })
                            }
                        }
                        else -> {
                            DesktopSettingsGroup {
                                DesktopSettingToggle("背景流光", settings.flowing, { onChange(settings.copy(flowing = it)) })
                                DesktopSettingToggle("浅色流光", settings.light, { onChange(settings.copy(light = it)) })
                                DesktopSettingToggle("减弱流光效果", settings.reduced, { onChange(settings.copy(reduced = it)) })
                            }
                            Spacer(Modifier.height(12.dp))
                            DesktopSettingsGroup {
                                Text("流光形态", Modifier.padding(bottom = 10.dp))
                                DesktopChoiceRow(List(5) { "${it + 1}" }, settings.mesh, { onChange(settings.copy(mesh = it)) }, Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
                }
            }
}

@Composable
private fun ValueSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>, label: String, commit: (Float) -> Unit) {
    var preview by remember(value) { mutableFloatStateOf(value) }
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.width(64.dp), fontSize = 13.sp)
        DesktopSlider(preview, { preview = it }, Modifier.weight(1f).padding(horizontal = 8.dp),
            valueRange = range, onValueChangeFinished = { commit(preview) })
        Text(if (preview == value) label else if (title == "动画速度") "%.1fx".format(java.util.Locale.US, preview)
            else if (title == "字号") "${preview.roundToInt()} sp" else preview.roundToInt().toString(),
            Modifier.width(54.dp), fontSize = 12.sp, textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
