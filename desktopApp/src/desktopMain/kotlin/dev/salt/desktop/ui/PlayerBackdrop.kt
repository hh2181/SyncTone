package dev.salt.desktop.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.IntSize
import dev.salt.desktop.library.DesktopTrack
import dev.salt.desktop.lyrics.DesktopLyricsSettings
import dev.salt.desktop.visual.FlowingLightRenderer
import kotlinx.coroutines.*
import java.util.logging.Level
import java.util.logging.Logger

@Composable
internal fun PlayerBackdrop(track: DesktopTrack?, settings: DesktopLyricsSettings, visible: Boolean,
                            viewport: IntSize, modifier: Modifier) {
    val artwork by produceState<ByteArray?>(null, track?.id) {
        value = withContext(Dispatchers.IO) { track?.artwork }
    }
    val fallback = if (settings.light) Color(0xFFE6E7EB) else Color(0xFF282C37)
    BoxWithConstraints(modifier.background(fallback)) {
        val width = viewport.width
        val height = viewport.height
        Crossfade(artwork, animationSpec = tween(500)) { bytes ->
            var frame by remember(bytes) { mutableStateOf<ImageBitmap?>(null) }
            var elapsed by remember(bytes) { mutableLongStateOf(0) }
            LaunchedEffect(bytes, visible, settings.flowing, settings.light, settings.reduced, settings.mesh, width, height) {
                if (bytes == null || width <= 0 || height <= 0 || !visible) return@LaunchedEffect
                withContext(Dispatchers.Default) {
                    try {
                        FlowingLightRenderer(bytes).use { renderer ->
                            var previous = System.nanoTime()
                            do {
                                ensureActive()
                                val started = System.nanoTime()
                                if (settings.flowing) elapsed += ((started - previous) / 1_000_000).coerceAtLeast(0)
                                previous = started
                                val bitmap = renderer.render(width, height, elapsed, settings.mesh, settings.light, settings.reduced).use {
                                    it.toComposeImageBitmap()
                                }
                                withContext(Dispatchers.Main) { frame = bitmap }
                                if (settings.flowing) delay((42 - (System.nanoTime() - started) / 1_000_000).coerceAtLeast(1))
                            } while (settings.flowing)
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { Logger.getLogger("dev.salt.desktop.flow").log(Level.WARNING, "Flow renderer failed", e) }
                }
            }
            Canvas(Modifier.fillMaxSize()) {
                frame?.let { drawImage(it, dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Medium) }
            }
        }
    }
}
