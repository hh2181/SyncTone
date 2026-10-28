@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package dev.salt.desktop

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import dev.salt.desktop.playback.*
import dev.salt.desktop.storage.*
import dev.salt.desktop.ui.DesktopApp
import dev.salt.replica.ui.PlayerSheetState
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat
import java.awt.Frame
import java.nio.file.Files
import java.nio.file.Path

/** Also runs against the previous packaged main JAR to compare unchanged seek-bar pixels. */
fun main(args: Array<String>): Unit = runBlocking {
    val output = Files.createDirectories(Path.of(args[1]))
    val data = Files.createDirectories(output.resolve("data"))
    val id = DesktopLibraryRepository(data).use { it.register(Path.of(args[0])).id }
    val repository = DesktopSessionRepository(data)
    repository.write(DesktopSession(current = id, positionMs = 54_000, queueIds = listOf(id),
        lyrics = dev.salt.desktop.lyrics.DesktopLyricsSettings(flowing = false, blur = false, glow = false)))
    withContext(Dispatchers.Main) {
        val store = DesktopPlayerStore(repository, engineFactory = { object : AudioEngine {
            private var position = 54_000L
            override fun open(path: Path, play: Boolean) {}
            override fun setPaused(paused: Boolean) {}
            override fun seek(positionMs: Long) { position = positionMs }
            override fun setVolume(volume: Int) {}
            override fun snapshot() = AudioSnapshot(AudioStatus.Paused, position, 208_308, 72)
            override fun stop() {}
            override fun close() {}
        } })
        val clock = BroadcastFrameClock()
        val scope = CoroutineScope(coroutineContext + SupervisorJob() + clock)
        val sheet = PlayerSheetState(scope)
        val owner = Frame()
        var frameTime = System.nanoTime()
        var scene: ImageComposeScene? = null
        try {
            withTimeout(15_000) { while (!store.state.value.ready) delay(10) }
            val active = ImageComposeScene(1280, 820, Density(1f), coroutineContext = coroutineContext) { DesktopApp(store, sheet, owner) }
            scene = active
            suspend fun frames(count: Int = 30) {
                repeat(count) { frameTime += 16_666_667; clock.sendFrame(frameTime); active.render(frameTime).close(); delay(8) }
            }
            fun snapshot(name: String) {
                active.render(frameTime).use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { Files.write(output.resolve(name), it.bytes) } }
            }
            for (dark in listOf(false, true)) {
                sheet.begin(); sheet.drag(sheet.travel); frames()
                if (dark) {
                    active.sendPointerEvent(PointerEventType.Press, Offset(1120f, 40f))
                    active.sendPointerEvent(PointerEventType.Release, Offset(1120f, 40f))
                    active.sendPointerEvent(PointerEventType.Move, Offset(10f, 10f)); frames()
                }
                snapshot("seek-mini-${if (dark) "dark" else "light"}.png")
                sheet.begin(); sheet.drag(-sheet.travel); frames()
                snapshot("seek-full-${if (dark) "dark" else "light"}.png")
            }
        } finally { scene?.close(); scope.cancel(); owner.dispose(); store.close() }
    }
}
