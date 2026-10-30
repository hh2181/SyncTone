package dev.salt.replica.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import dev.salt.buttons.SyncToneIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.salt.buttons.SyncToneButton

val HyperBlue = Color(0xFF3482FF)
val HyperRed = Color(0xFFF05A63)
val HyperGreen = Color(0xFF34A853)
val HyperPurple = Color(0xFF8B5CF6)
private val Ink = Color(0xFF1A1A1C)

private val LightColors: ColorScheme = lightColorScheme(
    primary = HyperBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8F0FF),
    onPrimaryContainer = Color(0xFF1D4ED8),
    secondary = Color(0xFF5B6472),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0F2F5),
    onSecondaryContainer = Color(0xFF2A3038),
    tertiary = HyperPurple,
    background = Color(0xFFF5F6F8),
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF1F3F6),
    onSurfaceVariant = Color(0xFF79808A),
    outline = Color(0xFFE3E6EB),
    outlineVariant = Color(0xFFEDEFF2),
    error = Color(0xFFE5484D),
    onError = Color.White,
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF7FB0FF),
    onPrimary = Color(0xFF08203F),
    primaryContainer = Color(0xFF1B2A44),
    onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = Color(0xFFA7B0BE),
    secondaryContainer = Color(0xFF242A33),
    onSecondaryContainer = Color(0xFFE7EBF2),
    tertiary = Color(0xFFC4B5FD),
    background = Color(0xFF0E1013),
    onBackground = Color(0xFFF2F3F5),
    surface = Color(0xFF171A1F),
    onSurface = Color(0xFFF2F3F5),
    surfaceVariant = Color(0xFF22262D),
    onSurfaceVariant = Color(0xFF9AA2AD),
    outline = Color(0xFF2C313A),
    outlineVariant = Color(0xFF232830),
    error = Color(0xFFFF8A8F),
    onError = Color(0xFF3B0A0C),
)

private val HyperTypography = Typography(
    displaySmall = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp),
)

val HyperShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun HyperTheme(dark: Boolean, content: @Composable () -> Unit) {
    val indicationColor = if (dark) Color.White.copy(alpha = .10f) else Color.Black.copy(alpha = .06f)
    CompositionLocalProvider(
        LocalIndication provides ripple(color = indicationColor)
    ) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = HyperTypography,
            shapes = HyperShapes,
            content = content
        )
    }
}

/** Press feedback in the HyperOS manner: the surface dips slightly instead of showing a hard ripple. */
@Composable
fun Modifier.hyperClickable(enabled: Boolean = true, pressedScale: Float = .975f, onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = .7f, stiffness = 620f),
        label = "hyper-press"
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }.clickable(interactionSource = interaction, indication = LocalIndication.current, enabled = enabled, onClick = onClick)
}

@Composable
fun HyperCard(
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(22.dp),
    color: Color = MaterialTheme.colorScheme.surface,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val border = BorderStroke(.7.dp, MaterialTheme.colorScheme.outlineVariant)
    val base = modifier
        .clip(shape)
        .background(color)
        .border(border, shape)
    Column(
        modifier = if (onClick != null) base.hyperClickable(onClick = onClick) else base,
        content = content
    )
}

@Composable
fun HyperSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = colors.onSurface, fontSize = 14.sp),
        cursorBrush = SolidColor(colors.primary),
        modifier = modifier,
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surfaceVariant)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Search, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Box(Modifier.weight(1f).padding(start = 9.dp)) {
                    if (value.isEmpty()) {
                        Text(placeholder, color = colors.onSurfaceVariant, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    inner()
                }
                if (value.isNotEmpty()) {
                    IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, "清空", tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    )
}

@Composable
fun HyperPillButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    SyncToneButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 18.dp),
        modifier = modifier.height(42.dp)
    ) {
        if (icon != null) {
            Icon(icon, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
