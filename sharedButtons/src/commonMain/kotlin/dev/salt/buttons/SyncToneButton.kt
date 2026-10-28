package dev.salt.buttons

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class SyncToneButtonStyle { Primary, Secondary, Ghost, Destructive }
enum class SyncToneButtonDensity { Mobile, Desktop }

/** Only buttons share a design system; their surrounding platform themes remain independent. */
@Composable
fun SyncToneButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: SyncToneButtonStyle = SyncToneButtonStyle.Primary,
    density: SyncToneButtonDensity = SyncToneButtonDensity.Mobile,
    contentColor: Color = Color.Unspecified,
    contentPadding: PaddingValues = if (density == SyncToneButtonDensity.Desktop)
        PaddingValues(horizontal = 16.dp, vertical = 9.dp) else PaddingValues(horizontal = 18.dp, vertical = 10.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val fill = when (style) {
        SyncToneButtonStyle.Primary -> colors.primary
        SyncToneButtonStyle.Secondary -> colors.surfaceVariant
        SyncToneButtonStyle.Ghost -> Color.Transparent
        SyncToneButtonStyle.Destructive -> colors.errorContainer
    }
    val ink = if (contentColor != Color.Unspecified) contentColor else when (style) {
        SyncToneButtonStyle.Primary -> colors.onPrimary
        SyncToneButtonStyle.Destructive -> colors.error
        else -> colors.onSurface
    }
    val background by animateColorAsState(
        if (enabled) fill else colors.onSurface.copy(alpha = .06f), tween(150), label = "button-fill")
    val actualInk = if (enabled) ink else colors.onSurface.copy(alpha = .38f)
    val minimumWidth = when (style) {
        SyncToneButtonStyle.Primary -> 88.dp
        SyncToneButtonStyle.Secondary, SyncToneButtonStyle.Destructive -> 72.dp
        SyncToneButtonStyle.Ghost -> Dp.Unspecified
    }
    val desktop = density == SyncToneButtonDensity.Desktop
    CompositionLocalProvider(
        LocalContentColor provides actualInk,
        LocalTextStyle provides MaterialTheme.typography.labelLarge.copy(
            fontSize = if (desktop) 13.sp else 14.sp, fontWeight = FontWeight.Medium),
    ) {
        Row(
            (if (desktop) modifier else modifier.minimumInteractiveComponentSize())
                .defaultMinSize(minWidth = minimumWidth, minHeight = if (desktop) 38.dp else 42.dp)
                .buttonFeedback(enabled, actualInk, onClick).background(background).padding(contentPadding),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
fun SyncToneTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color = MaterialTheme.colorScheme.primary,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    content: @Composable RowScope.() -> Unit,
) = SyncToneButton(onClick, modifier, enabled, SyncToneButtonStyle.Ghost,
    contentColor = contentColor, contentPadding = contentPadding, content = content)

@Composable
fun SyncToneIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    density: SyncToneButtonDensity = SyncToneButtonDensity.Mobile,
    content: @Composable () -> Unit,
) {
    val ink = LocalContentColor.current
    CompositionLocalProvider(LocalContentColor provides if (enabled) ink else ink.copy(alpha = .38f)) {
        val target = if (density == SyncToneButtonDensity.Mobile) modifier.minimumInteractiveComponentSize() else modifier
        Box(target.size(40.dp).buttonFeedback(enabled, ink, onClick),
            contentAlignment = Alignment.Center, content = { content() })
    }
}

/** Foundation supplies disabled semantics and keyboard activation on both platforms. */
@Composable
private fun Modifier.buttonFeedback(enabled: Boolean, ink: Color, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    val focused by source.collectIsFocusedAsState()
    val keyboard = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val accent = MaterialTheme.colorScheme.primary
    val scale by animateFloatAsState(if (enabled && pressed) .98f else 1f, tween(130), label = "button-press")
    return graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape)
        .hoverable(source, enabled)
        .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .drawWithContent {
            drawContent()
            val alpha = if (!enabled) 0f else if (pressed) .09f else if (hovered) .045f else 0f
            if (alpha > 0f) drawRect(ink.copy(alpha = alpha))
        }.then(if (enabled && focused && keyboard) Modifier.border(1.5.dp, accent, CircleShape) else Modifier)
}
