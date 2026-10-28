@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package dev.salt.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import dev.salt.desktop.playback.DesktopPlayerStore
import dev.salt.desktop.library.ScanPhase
import dev.salt.desktop.ui.DesktopApp
import dev.salt.replica.ui.PlayerSheetState
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat
import java.awt.Frame
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

fun main(args: Array<String>): Unit = runBlocking {
    withContext(Dispatchers.Main) {
        val output = Path.of(args[1])
        Files.createDirectories(output)
        val store = DesktopPlayerStore()
        val frameClock = BroadcastFrameClock()
        val animationScope = CoroutineScope(coroutineContext + SupervisorJob() + frameClock)
        val sheet = PlayerSheetState(animationScope)
        val owner = Frame()
        try {
            withTimeout(20_000) { while (!store.state.value.ready) delay(25) }
            store.importFiles(listOf(Path.of(args[0])))
            withTimeout(20_000) { while (store.state.value.tracks.isEmpty()) delay(25) }
            val music = Files.createDirectories(output.resolve("scan-fixture"))
            Files.copy(Path.of(args[0]), music.resolve("song.mp3"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            store.library.addFolder(music)
            withTimeout(20_000) { while (store.library.state.value.scan.phase != ScanPhase.Complete) delay(25) }
            val statTrack = store.state.value.tracks.first()
            store.statistics.metadata(listOf(statTrack))
            val now = System.currentTimeMillis()
            store.statistics.history.record(dev.salt.replica.statistics.RecordingBatch(
                dev.salt.replica.statistics.ListeningSpan("ui-smoke", statTrack.id, now - 35_000, now, 35_000),
                dev.salt.replica.statistics.CountedPlay("ui-smoke", statTrack.id, now)))
            store.statistics.refresh()
            var frameTime = System.nanoTime()
            suspend fun frames(scene: ImageComposeScene, count: Int = 50) {
                repeat(count) {
                    frameTime += 16_666_667
                    frameClock.sendFrame(frameTime)
                    scene.render(frameTime).close()
                    delay(16)
                }
            }
            fun snapshot(scene: ImageComposeScene, name: String): BufferedImage {
                scene.render(frameTime).use { image ->
                    image.encodeToData(EncodedImageFormat.PNG)?.use { Files.write(output.resolve(name), it.bytes) }
                }
                val image = ImageIO.read(output.resolve(name).toFile())
                val colors = mutableSetOf<Int>()
                for (x in 0 until image.width step 4) for (y in 0 until image.height step 4) colors += image.getRGB(x, y)
                check(colors.size > 100) { "Blank or unrendered scene: $name" }
                return image
            }
            suspend fun capture(scene: ImageComposeScene, name: String): BufferedImage {
                frames(scene)
                return snapshot(scene, name)
            }
            fun click(scene: ImageComposeScene, x: Float, y: Float) {
                scene.sendPointerEvent(PointerEventType.Press, Offset(x, y))
                scene.sendPointerEvent(PointerEventType.Release, Offset(x, y))
            }
            fun escape(scene: ImageComposeScene) {
                scene.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyDown))
                scene.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyUp))
            }
            fun differences(a: BufferedImage, b: BufferedImage, left: Int, top: Int, right: Int, bottom: Int): Int {
                var changed = 0
                for (x in left until right) for (y in top until bottom) if (a.getRGB(x, y) != b.getRGB(x, y)) changed++
                return changed
            }
            fun brightness(image: BufferedImage, x: Int, y: Int): Int {
                val color = image.getRGB(x, y)
                return (color shr 16 and 255) + (color shr 8 and 255) + (color and 255)
            }
            val scene = ImageComposeScene(1280, 820, Density(1f), coroutineContext = coroutineContext) {
                DesktopApp(store, sheet, owner)
            }
            try {
                val libraryImage = capture(scene, "native-library.png")
                click(scene, 330f, 240f)
                frames(scene, 5)
                val opening = snapshot(scene, "card-opening.png")
                check(differences(libraryImage, opening, 206, 0, 1280, 726) > 1000) { "Card did not start transforming" }
                check(differences(libraryImage, opening, 0, 0, 206, 726) == 0) { "Card escaped into sidebar" }
                check(differences(libraryImage, opening, 0, 726, 1280, 820) == 0) { "Card affected mini-player" }
                val opened = capture(scene, "card-expanded.png")
                check(differences(opening, opened, 206, 0, 1280, 726) > 1000) { "Card skipped intermediate geometry" }
                click(scene, 256f, 32f)
                frames(scene, 5)
                snapshot(scene, "card-closing.png")
                val returned = capture(scene, "card-returned.png")
                check(differences(libraryImage, returned, 206, 80, 1280, 726) < 20) { "Card did not return to source grid" }
                click(scene, 330f, 240f)
                frames(scene, 3)
                escape(scene)
                scene.sendPointerEvent(PointerEventType.Move, Offset(1000f, 500f))
                val interrupted = capture(scene, "card-interrupted.png")
                check(differences(libraryImage, interrupted, 206, 80, 1280, 726) < 20) { "Escape did not reverse interrupted opening" }
                click(scene, 95f, 324f)
                capture(scene, "native-artists.png")
                click(scene, 330f, 240f)
                capture(scene, "artist-expanded.png")
                click(scene, 95f, 324f)
                capture(scene, "artist-returned.png")
                scene.sendPointerEvent(PointerEventType.Press, Offset(95f, 222f))
                scene.sendPointerEvent(PointerEventType.Release, Offset(95f, 222f))
                frames(scene, 5)
                snapshot(scene, "page-forward.png")
                capture(scene, "native-scanner.png")
                click(scene, 95f, 374f)
                val statistics = capture(scene, "native-statistics.png")
                check(differences(libraryImage, statistics, 236, 150, 1250, 710) > 1000) { "Statistics page did not render" }
                click(scene, 1120f, 40f)
                capture(scene, "native-statistics-dark.png")
                click(scene, 95f, 222f)
                frames(scene, 5)
                snapshot(scene, "page-backward.png")
                capture(scene, "native-scanner-dark.png")
                click(scene, 1120f, 40f)
                click(scene, 95f, 425f)
                capture(scene, "native-sync.png")
                scene.sendPointerEvent(PointerEventType.Press, Offset(95f, 120f))
                scene.sendPointerEvent(PointerEventType.Release, Offset(95f, 120f))
                capture(scene, "native-library.png")
                // Drive the actual Compose pointer handler through a half drag and reversal.
                scene.sendPointerEvent(PointerEventType.Press, Offset(45f, 770f))
                scene.sendPointerEvent(PointerEventType.Move, Offset(45f, 700f))
                scene.render(frameTime).close()
                scene.sendPointerEvent(PointerEventType.Move, Offset(45f, 350f))
                scene.render(frameTime).close()
                val dragged = sheet.progress
                check(dragged > .3f) { "Sheet did not follow drag: $dragged" }
                scene.sendPointerEvent(PointerEventType.Move, Offset(45f, 480f))
                scene.render(frameTime).close()
                check(sheet.progress < dragged) { "Sheet did not reverse with the pointer." }
                scene.sendPointerEvent(PointerEventType.Release, Offset(45f, 480f))
                store.seek(2600)
                withTimeout(10_000) { while (store.state.value.positionMs != 2600L) delay(20) }
                store.updateLyricsSettings(store.state.value.lyricSettings.copy(flowing = false))
                sheet.to(true)
                val player = capture(scene, "native-player.png")
                click(scene, 650f, 635f)
                frames(scene, 3)
                snapshot(scene, "settings-dimming.png")
                frames(scene, 5)
                val rising = snapshot(scene, "settings-rising.png")
                val modal = capture(scene, "settings-open.png")
                check(differences(rising, modal, 380, 150, 900, 700) > 1000) { "Settings skipped rise animation" }
                check(brightness(modal, 20, 20) < brightness(player, 20, 20) * .8f) { "Settings scrim missed top of window" }
                check(brightness(modal, 20, 800) < brightness(player, 20, 800) * .8f) { "Settings scrim missed bottom controls" }
                click(scene, 640f, 180f)
                frames(scene, 5)
                snapshot(scene, "settings-tab-slide.png")
                capture(scene, "settings-animation.png")
                click(scene, 800f, 180f)
                capture(scene, "settings-background.png")
                click(scene, 480f, 180f)
                capture(scene, "settings-text-returned.png")
                escape(scene)
                frames(scene, 5)
                snapshot(scene, "settings-lowering.png")
                val dismissed = capture(scene, "settings-dismissed.png")
                check(brightness(dismissed, 200, 200) > brightness(modal, 200, 200) * 1.3f) { "Settings did not dismiss with Escape" }
                store.updateLyricsSettings(store.state.value.lyricSettings.copy(light = true))
                capture(scene, "native-player-light.png")
                store.updateLyricsSettings(store.state.value.lyricSettings.copy(light = false))
            } finally { scene.close() }
            val small = ImageComposeScene(960, 640, Density(1f), coroutineContext = coroutineContext) {
                DesktopApp(store, sheet, owner)
            }
            try {
                capture(small, "native-player-small.png")
                click(small, 500f, 455f)
                capture(small, "settings-small.png")
                click(small, 20f, 20f)
                capture(small, "settings-small-dismissed.png")
                sheet.to(false)
                capture(small, "native-library-small.png")
                click(small, 330f, 220f)
                capture(small, "card-expanded-small.png")
                escape(small)
                capture(small, "card-returned-small.png")
                small.sendPointerEvent(PointerEventType.Press, Offset(95f, 222f))
                small.sendPointerEvent(PointerEventType.Release, Offset(95f, 222f))
                capture(small, "native-scanner-small.png")
                click(small, 95f, 374f)
                capture(small, "native-statistics-small.png")
                click(small, 800f, 40f)
                capture(small, "native-statistics-small-dark.png")
                click(small, 95f, 222f)
                capture(small, "native-scanner-small-dark.png")
                click(small, 800f, 40f)
                click(small, 95f, 425f)
                capture(small, "native-sync-small.png")
            } finally { small.close() }
            Files.writeString(output.resolve("verification.json"),
                """{"passed":true,"renderedBy":"Compose ImageComposeScene","sizes":[[1280,820],[960,640]],"statistics":true,"sync":true,"dragAndReverse":true,"scanner":true,"cardIntermediateGeometry":true,"cardBounds":true,"cardReturnAndInterrupt":true,"settingsRiseAndWindowScrim":true,"escapeDismissal":true}""")
        } finally {
            animationScope.cancel()
            owner.dispose()
            store.close()
        }
    }
}
