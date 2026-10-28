package dev.salt.desktop.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// The home artwork is the reference for content cards, rows, fields and dialogs.
internal val DesktopCardRadius = 18.dp
internal val DesktopCardShape = RoundedCornerShape(DesktopCardRadius)
internal val DesktopShapes = Shapes(
    extraSmall = DesktopCardShape,
    small = DesktopCardShape,
    medium = DesktopCardShape,
    large = DesktopCardShape,
    extraLarge = DesktopCardShape,
)
