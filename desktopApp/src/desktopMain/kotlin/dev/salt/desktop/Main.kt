package dev.salt.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.salt.desktop.playback.DesktopPlayerStore
import dev.salt.desktop.ui.DesktopApp
import dev.salt.replica.ui.PlayerSheetState
import kotlinx.coroutines.*
import java.awt.Dimension
import dev.salt.desktop.window.DesktopWindowChrome
import java.nio.file.Path
import dev.salt.desktop.storage.DesktopPaths
import javax.swing.JOptionPane

fun main(args: Array<String>) {
    try { DesktopPaths.configureRuntime() }
    catch (e: Exception) {
        JOptionPane.showMessageDialog(null, "便携版目录不可写，请将整个 SyncTone 文件夹移到可写的位置。\n${e.message}",
            "SyncTone", JOptionPane.ERROR_MESSAGE)
        return
    }
    launchDesktop(args)
}

private fun launchDesktop(args: Array<String>) = application {
    val scope = rememberCoroutineScope()
    val store = remember { DesktopPlayerStore() }
    val sheet = remember { PlayerSheetState(scope) }
    var closing by remember { mutableStateOf(false) }
    val windowState = rememberWindowState(width = 1280.dp, height = 820.dp)
    fun close() {
        if (closing) return
        closing = true
        scope.launch { try { store.close() } finally { exitApplication() } }
    }
    Window(onCloseRequest = ::close, state = windowState, title = "SyncTone",
        undecorated = true) {
        val chrome = remember(window) { DesktopWindowChrome(window) }
        DisposableEffect(chrome) { onDispose { chrome.close() } }
        SideEffect { window.minimumSize = Dimension(960, 640) }
        DesktopApp(store, sheet, window, roundedWindow = !chrome.nativeCorners)
        LaunchedEffect(Unit) {
            val input = args.firstOrNull { it.startsWith("--file=") }?.substringAfter('=')
            if (input != null) {
                withTimeout(20_000) { while (!store.state.value.ready) delay(50) }
                store.importFiles(listOf(Path.of(input)))
            }
        }
    }
}
