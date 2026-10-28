package dev.salt.desktop.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** All control roles are explicit, so stock accessibility hosts never leak Material's purple palette. */
internal fun desktopColorScheme(dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val blue = if (dark) Color(0xFF7FB0FF) else Color(0xFF3482FF)
    val ink = if (dark) base.onSurface else Color(0xFF1A1A1C)
    val surface = if (dark) Color(0xFF191C21) else Color.White
    val muted = if (dark) Color(0xFF292E36) else Color(0xFFEDF0F4)
    val secondary = if (dark) Color(0xFFA4ADB9) else Color(0xFF707985)
    return base.copy(
        primary = blue, onPrimary = if (dark) Color(0xFF092347) else Color.White,
        primaryContainer = if (dark) Color(0xFF203552) else Color(0xFFE7F0FF),
        onPrimaryContainer = if (dark) Color(0xFFD7E7FF) else Color(0xFF20529B),
        inversePrimary = if (dark) Color(0xFF3482FF) else Color(0xFF9BC1FF),
        secondary = secondary, onSecondary = surface, secondaryContainer = muted, onSecondaryContainer = ink,
        tertiary = if (dark) Color(0xFF64CBA7) else Color(0xFF249773), onTertiary = surface,
        tertiaryContainer = if (dark) Color(0xFF203D34) else Color(0xFFE7F4ED), onTertiaryContainer = ink,
        background = if (dark) Color(0xFF101215) else Color(0xFFF5F6F8), onBackground = ink,
        surface = surface, onSurface = ink, surfaceVariant = muted, onSurfaceVariant = secondary,
        surfaceTint = blue, inverseSurface = if (dark) Color(0xFFE8ECF2) else Color(0xFF252B33),
        inverseOnSurface = if (dark) Color(0xFF252B33) else Color(0xFFF4F6F9),
        outline = if (dark) Color(0xFF48515D) else Color(0xFFB9C1CC),
        outlineVariant = if (dark) Color(0xFF303640) else Color(0xFFE1E5EB),
        error = if (dark) Color(0xFFFF8A8F) else Color(0xFFE5484D),
        onError = if (dark) Color(0xFF3B0A0C) else Color.White,
        errorContainer = if (dark) Color(0xFF47252A) else Color(0xFFFFEDEE), onErrorContainer = ink,
        scrim = Color.Black,
    )
}
