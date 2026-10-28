@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.salt.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import dev.salt.buttons.SyncToneButton
import dev.salt.buttons.SyncToneButtonDensity
import dev.salt.buttons.SyncToneButtonStyle

internal typealias DesktopButtonStyle = SyncToneButtonStyle

/** Soft pointer feedback with Foundation's keyboard activation and semantic actions. */
@Composable
internal fun Modifier.desktopClickable(
    enabled: Boolean = true,
    shape: Shape = DesktopCardShape,
    scaleOnPress: Boolean = false,
    role: Role = Role.Button,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    val focused by source.collectIsFocusedAsState()
    val keyboard = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val scale by animateFloatAsState(if (enabled && pressed && scaleOnPress) .98f else 1f, tween(130))
    val ink = LocalContentColor.current
    val accent = MaterialTheme.colorScheme.primary
    return graphicsLayer { scaleX = scale; scaleY = scale }.clip(shape)
        .hoverable(source, enabled)
        .clickable(source, indication = null, enabled = enabled, role = role, onClick = onClick)
        .drawWithContent {
            drawContent()
            val alpha = if (!enabled) 0f else if (pressed) .09f else if (hovered) .045f else 0f
            if (alpha > 0) drawRect(ink.copy(alpha = alpha))
        }.then(if (enabled && focused && keyboard) Modifier.border(1.5.dp, accent, shape) else Modifier)
}

@Composable
internal fun DesktopButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: DesktopButtonStyle = DesktopButtonStyle.Primary,
    contentColor: Color = Color.Unspecified,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 9.dp),
    content: @Composable RowScope.() -> Unit,
) {
    SyncToneButton(onClick, modifier, enabled, style, SyncToneButtonDensity.Desktop,
        contentColor, contentPadding, content)
}

@Composable
internal fun DesktopTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color = MaterialTheme.colorScheme.primary,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    content: @Composable RowScope.() -> Unit,
) = DesktopButton(onClick, modifier, enabled, DesktopButtonStyle.Ghost, contentColor, contentPadding, content)

/** Retain Material's pointer/semantic behavior; this pinned desktop version needs explicit slider keys. */
@Composable
internal fun DesktopSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    compact: Boolean = false,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val accent = if (enabled) colors.primary else colors.onSurface.copy(alpha = .25f)
    val track = if (compact) LocalContentColor.current.copy(alpha = .17f) else colors.surfaceVariant
    val height = if (compact) 6.dp else 16.dp
    val diameter = if (compact) 12.dp else 22.dp
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val keyboard = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    var keyAdjusting by remember { mutableStateOf(false) }
    val currentValue by rememberUpdatedState(value)
    val currentChange by rememberUpdatedState(onValueChange)
    val currentFinish by rememberUpdatedState(onValueChangeFinished)
    Slider(value.coerceIn(valueRange), onValueChange, modifier = modifier.height(if (compact) 32.dp else 38.dp)
        .onPreviewKeyEvent { event ->
            val step = (valueRange.endInclusive - valueRange.start) / 100f
            val target = when (event.key) {
                Key.DirectionRight, Key.DirectionUp -> currentValue + step
                Key.DirectionLeft, Key.DirectionDown -> currentValue - step
                Key.PageUp -> currentValue + step * 10
                Key.PageDown -> currentValue - step * 10
                Key.MoveHome -> valueRange.start
                Key.MoveEnd -> valueRange.endInclusive
                else -> null
            }
            if (!enabled || target == null) false else {
                if (event.type == KeyEventType.KeyDown) {
                    keyAdjusting = true
                    currentChange(target.coerceIn(valueRange))
                } else if (event.type == KeyEventType.KeyUp && keyAdjusting) {
                    keyAdjusting = false
                    currentFinish?.invoke()
                }
                true
            }
        }.onFocusChanged {
            if (!it.isFocused && keyAdjusting) { keyAdjusting = false; currentFinish?.invoke() }
        }
        .then(if (focused && keyboard) Modifier.border(1.5.dp, accent, RoundedCornerShape(8.dp)) else Modifier),
        enabled = enabled, valueRange = valueRange, onValueChangeFinished = onValueChangeFinished,
        interactionSource = source,
        thumb = {
            Box(Modifier.size(diameter).clip(CircleShape).background(accent).padding(if (compact) 2.dp else 3.dp)
                .clip(CircleShape).background(Color.White))
        },
        track = { slider ->
            val fraction = ((slider.value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(height).clip(CircleShape).background(track)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).clip(CircleShape).background(accent))
            }
        })
}

@Composable
internal fun DesktopVolumeSlider(value: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) =
    DesktopSlider(value.toFloat(), { onChange(it.toInt()) }, modifier, valueRange = 0f..100f, compact = true)

@Composable
internal fun DesktopSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    val p by animateFloatAsState(if (checked) 1f else 0f, tween(160, easing = FastOutSlowInEasing))
    val fill by animateColorAsState(if (checked) colors.primary else colors.outlineVariant, tween(160))
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val keyboard = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    Box(modifier.size(44.dp, 26.dp).clip(CircleShape).background(if (enabled) fill else fill.copy(alpha = .4f))
        .then(if (onCheckedChange != null) Modifier.toggleable(checked, source, indication = null, enabled = enabled,
            role = Role.Switch, onValueChange = onCheckedChange) else Modifier)
        .then(if (enabled && focused && keyboard) Modifier.border(1.5.dp, colors.primary, CircleShape) else Modifier)) {
        Box(Modifier.offset(x = 2.dp + 18.dp * p, y = 2.dp).size(22.dp).clip(CircleShape)
            .background(Color.White.copy(alpha = if (enabled) 1f else .7f)))
    }
}

@Composable
internal fun DesktopSettingToggle(title: String, checked: Boolean, change: (Boolean) -> Unit, enabled: Boolean = true) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val keyboard = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
        .background(if (hovered && enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = .035f) else Color.Transparent)
        .hoverable(source, enabled).toggleable(checked, source, indication = null, enabled = enabled, role = Role.Switch, onValueChange = change)
        .then(if (focused && keyboard) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier)
        .padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 13.sp)
        DesktopSwitch(checked, null, enabled = enabled)
    }
}

@Composable
internal fun DesktopCheckbox(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val keyboard = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val fill by animateColorAsState(if (checked) colors.primary else Color.Transparent, tween(140))
    Box(modifier.size(40.dp).then(if (onCheckedChange != null) Modifier.toggleable(checked, source, null, enabled,
        Role.Checkbox, onCheckedChange) else Modifier), contentAlignment = Alignment.Center) {
        Box(Modifier.size(21.dp).clip(RoundedCornerShape(6.dp)).background(if (enabled) fill else fill.copy(alpha = .35f))
            .then(if (!checked || (focused && keyboard)) Modifier.border(if (focused && keyboard) 2.dp else 1.5.dp,
                if (focused && keyboard) colors.primary else colors.outline.copy(alpha = if (enabled) 1f else .35f), RoundedCornerShape(6.dp)) else Modifier),
            contentAlignment = Alignment.Center) {
            if (checked) Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = colors.onPrimary.copy(alpha = if (enabled) 1f else .5f))
        }
    }
}

@Composable
internal fun DesktopChoiceRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit,
                             modifier: Modifier = Modifier, fillWidth: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    val requesters = remember(options) { options.map { FocusRequester() } }
    Row(modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEachIndexed { index, label ->
            val source = remember { MutableInteractionSource() }
            val hovered by source.collectIsHoveredAsState()
            val pressed by source.collectIsPressedAsState()
            val focused by source.collectIsFocusedAsState()
            val keyboard = LocalInputModeManager.current.inputMode == InputMode.Keyboard
            val scale by animateFloatAsState(if (pressed) .98f else 1f, tween(130))
            val fill by animateColorAsState(if (selected == index) colors.primary else colors.surfaceVariant, tween(150))
            Box((if (fillWidth) Modifier.weight(1f) else Modifier).heightIn(min = 38.dp).focusRequester(requesters[index])
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) false else {
                        val next = when (event.key) {
                            Key.DirectionLeft, Key.DirectionUp -> Math.floorMod(index - 1, options.size)
                            Key.DirectionRight, Key.DirectionDown -> (index + 1) % options.size
                            Key.MoveHome -> 0
                            Key.MoveEnd -> options.lastIndex
                            else -> null
                        }
                        if (next == null) false else { onSelect(next); requesters[next].requestFocus(); true }
                    }
                }.graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape).background(fill)
                .hoverable(source).selectable(selected == index, source, indication = null, role = Role.Tab) { onSelect(index) }
                .then(if (focused && keyboard) Modifier.border(1.5.dp, colors.primary, CircleShape) else Modifier)
                .drawWithContent { drawContent(); if (hovered || pressed) drawRect(colors.onSurface.copy(alpha = if (pressed) .08f else .04f)) }
                .padding(horizontal = 14.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
                Text(label, fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
                    color = if (selected == index) colors.onPrimary else colors.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun DesktopSettingsGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(DesktopCardShape).background(MaterialTheme.colorScheme.surface).padding(16.dp), content = content)
}

@Composable
internal fun DesktopTextField(value: String, change: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, Modifier.padding(bottom = 8.dp), fontSize = 12.sp, color = colors.onSurfaceVariant)
        BasicTextField(value, change, Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
            .clip(DesktopCardShape).background(colors.surfaceVariant)
            .then(if (focused) Modifier.border(1.5.dp, colors.primary, DesktopCardShape) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp).semantics { contentDescription = label }, singleLine = true,
            textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, color = colors.onSurface), cursorBrush = SolidColor(colors.primary))
    }
}

@Composable
internal fun DesktopAlertDialog(onDismissRequest: () -> Unit, confirmButton: @Composable () -> Unit,
                               modifier: Modifier = Modifier, dismissButton: (@Composable () -> Unit)? = null,
                               title: @Composable () -> Unit, text: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    AlertDialog(onDismissRequest, confirmButton, modifier = modifier, dismissButton = dismissButton,
        title = { ProvideTextStyle(MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp, fontWeight = FontWeight.Medium), title) },
        text = text, shape = DesktopCardShape, containerColor = colors.background,
        titleContentColor = colors.onSurface, textContentColor = colors.onSurface, tonalElevation = 0.dp)
}

@Composable
internal fun DesktopTaskProgress(modifier: Modifier = Modifier, progress: Float? = null) {
    val colors = MaterialTheme.colorScheme
    val phase = if (progress == null) rememberInfiniteTransition().animateFloat(-.35f, 1f,
        infiniteRepeatable(tween(1200, easing = LinearEasing))) else null
    val value by animateFloatAsState(progress?.coerceIn(0f, 1f) ?: 0f, tween(160))
    Canvas(modifier.fillMaxWidth().height(4.dp).clip(CircleShape)
        .then(if (progress == null) Modifier.progressSemantics() else Modifier.progressSemantics(progress.coerceIn(0f, 1f)))) {
        drawRect(colors.surfaceVariant)
        val start = phase?.value ?: 0f
        drawRect(colors.primary, topLeft = androidx.compose.ui.geometry.Offset(start * size.width, 0f),
            size = androidx.compose.ui.geometry.Size((if (progress == null) .35f else value) * size.width, size.height))
    }
}
