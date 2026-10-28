@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.salt.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import dev.salt.desktop.lyrics.*
import dev.salt.desktop.ui.MiSans
import dev.salt.replica.lyrics.LyricCell
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path

/** Fixed viewports isolate glyph pixels from asynchronous list-following/scroll animations. */
fun main(args: Array<String>): Unit = runBlocking {
    withContext(Dispatchers.Main) {
        val output = Files.createDirectories(Path.of(args[0]))
        val cells = listOf("你", "好", "世", "界 ", "A long wrapped line ", "中文 日本語 ", "émotion 𝄞")
            .mapIndexed { i, text -> LyricCell(i * 1000L, (i + 1) * 1000L, text) }
        for (density in listOf(1f, 1.5f)) for (glow in listOf(false, true)) for (weight in listOf(150, 380, 700)) {
            val settings = DesktopLyricsSettings(fontSize = 44f, fontWeight = weight, glow = glow, center = true)
            val clock = BroadcastFrameClock()
            val position = mutableLongStateOf(3500)
            val scene = ImageComposeScene(520, 560, Density(density), coroutineContext = coroutineContext + clock) {
                MaterialTheme(typography = MiSans.typography) {
                    Box(Modifier.fillMaxSize().background(Color(0xFF25262C)).padding(20.dp)) {
                        KaraokeText(cells, position, active = true, played = false,
                            style = TextStyle(fontFamily = MiSans.family(weight), fontSize = 44.sp,
                                lineHeight = 61.6.sp, textAlign = TextAlign.Center, color = Color.White),
                            settings = settings, prepareGlyphs = true)
                    }
                }
            }
            try {
                var time = 1_000_000_000L
                repeat(35) { time += 16_666_667; clock.sendFrame(time); scene.render(time).close(); delay(10) }
                scene.render(time).use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use {
                    Files.write(output.resolve("glyphs-$density-$glow-$weight.png"), it.bytes)
                } }
            } finally { scene.close() }
        }
    }
}
