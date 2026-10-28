@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.salt.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.salt.desktop.lyrics.*
import dev.salt.desktop.ui.MiSans
import dev.salt.desktop.ui.LyricsSettingsContent
import dev.salt.replica.lyrics.Lyrics
import dev.salt.replica.lyrics.LyricsParser
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

fun main(args: Array<String>): Unit = runBlocking {
    withContext(Dispatchers.Main) {
        val output = Path.of(args[0])
        Files.createDirectories(output)
        val position = mutableLongStateOf(1300)
        val settings = mutableStateOf(DesktopLyricsSettings(fontWeight = 380, blur = false, stagger = false))
        val viewport = LyricsViewportState()
        val revision = mutableIntStateOf(0)
        val seeks = mutableListOf<Long>()
        val lyrics = mutableStateOf(LyricsParser.parse("""
            [00:01]<00:01>你<00:02>好<00:03>世<00:04>界<00:06>
            [00:01]<00:01>ni <00:02>hao <00:03>shi <00:04>jie <00:06>
            [00:01]你好世界
            [00:08]ロックな君とはお別れだ
            [00:12]A long wrapped line with MiSans, 中文 and 日本語 that must wrap inside the lyric viewport without clipping.
            [00:18]émotion 𝄞 combining marks and supplementary characters
            [00:22]next line
            [00:26]last line
        """.trimIndent(), 30_000))
        val scene = ImageComposeScene(520, 560, Density(1f), coroutineContext = coroutineContext) {
            MaterialTheme(typography = MiSans.typography) {
                DesktopLyricsPanel(lyrics.value, position, settings.value, "fixture", revision.intValue,
                    onSeek = { seeks += it; position.longValue = it; revision.intValue++ }, onSettings = {},
                    Modifier.fillMaxSize().background(Color(0xFF25262C)), viewportState = viewport)
            }
        }
        suspend fun frames(count: Int = 55) {
            repeat(count) { scene.render(System.nanoTime()).close(); delay(16) }
        }
        suspend fun capture(name: String) {
            frames()
            scene.render(System.nanoTime()).use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use {
                Files.write(output.resolve(name), it.bytes)
            } }
        }
        fun brightness(name: String): Long {
            val image = ImageIO.read(output.resolve(name).toFile())
            var total = 0L
            for (y in 130 until 330) for (x in 0 until image.width) {
                val color = image.getRGB(x, y)
                total += (color shr 16 and 255) + (color shr 8 and 255) + (color and 255)
            }
            return total
        }
        try {
            capture("karaoke-early.png")
            position.longValue = 4500
            capture("karaoke-late.png")
            check(brightness("karaoke-late.png") > brightness("karaoke-early.png")) { "Karaoke pixels did not brighten with timed progress" }
            settings.value = settings.value.copy(fontWeight = 150)
            capture("misans-150.png")
            settings.value = settings.value.copy(fontWeight = 700)
            capture("misans-700.png")
            check(brightness("misans-700.png") > brightness("misans-150.png")) { "Variable MiSans weight did not change pixels" }
            scene.sendPointerEvent(PointerEventType.Scroll, Offset(220f, 250f), scrollDelta = Offset(0f, 3f))
            frames(15)
            check(viewport.browsing) { "Mouse wheel did not suspend lyric following" }
            check(seeks.isEmpty()) { "Browsing lyrics unexpectedly sought playback" }
            capture("lyrics-browsing.png")
            viewport.follow()
            frames(60)
            scene.sendPointerEvent(PointerEventType.Press, Offset(80f, 246f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(80f, 246f))
            frames(20)
            check(seeks.lastOrNull() == 1000L) { "Clicking current lyric did not seek its timestamp: $seeks" }
            scene.sendPointerEvent(PointerEventType.Scroll, Offset(220f, 250f), scrollDelta = Offset(0f, 2f))
            frames(15)
            check(viewport.browsing)
            frames(200)
            check(!viewport.browsing) { "Following did not resume after browsing timeout" }
            position.longValue = 12_500
            revision.intValue++
            settings.value = settings.value.copy(fontWeight = 380, fontSize = 44f, center = true, translation = false)
            capture("lyrics-wrapped-centered.png")
            settings.value = settings.value.copy(blur = true, stagger = true, fontSize = 30f)
            position.longValue = 18_500
            capture("lyrics-unicode.png")
            lyrics.value = Lyrics.Empty
            capture("lyrics-empty.png")
            val controls = ImageComposeScene(400, 480, Density(1f), coroutineContext = coroutineContext) {
                MaterialTheme(typography = MiSans.typography) {
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(Color(0xFFF8F8FA)).padding(16.dp)) {
                        LyricsSettingsContent(settings.value, 100, true, { settings.value = it }, {}, {}, {})
                    }
                }
            }
            try {
                suspend fun captureControls(name: String) {
                    repeat(35) { controls.render(System.nanoTime()).close(); delay(16) }
                    controls.render(System.nanoTime()).use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use {
                        Files.write(output.resolve(name), it.bytes)
                    } }
                }
                captureControls("settings-text.png")
                controls.sendPointerEvent(PointerEventType.Press, Offset(200f, 38f))
                controls.sendPointerEvent(PointerEventType.Release, Offset(200f, 38f))
                captureControls("settings-animation.png")
                controls.sendPointerEvent(PointerEventType.Press, Offset(320f, 38f))
                controls.sendPointerEvent(PointerEventType.Release, Offset(320f, 38f))
                captureControls("settings-background.png")
            } finally { controls.close() }
            Files.writeString(output.resolve("verification.json"),
                """{"passed":true,"perCharacterPixels":true,"variableMiSansPixels":true,"wheelDoesNotSeek":true,"clickSeeks":true,"browseRecovery":true,"wrappedAndUnicode":true,"emptyLyrics":true}""")
        } finally { scene.close() }
    }
}
