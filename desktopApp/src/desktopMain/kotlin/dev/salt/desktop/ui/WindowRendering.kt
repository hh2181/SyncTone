package dev.salt.desktop.ui

import androidx.compose.runtime.*
import java.awt.Frame
import java.awt.event.WindowStateListener

/** Iconified windows keep playing audio, but must not produce invisible animation frames. */
@Composable
internal fun rememberWindowRendering(owner: Frame): Boolean {
    var active by remember(owner) { mutableStateOf(owner.extendedState and Frame.ICONIFIED == 0) }
    DisposableEffect(owner) {
        val listener = WindowStateListener { active = it.newState and Frame.ICONIFIED == 0 }
        owner.addWindowStateListener(listener)
        onDispose { owner.removeWindowStateListener(listener) }
    }
    return active
}
