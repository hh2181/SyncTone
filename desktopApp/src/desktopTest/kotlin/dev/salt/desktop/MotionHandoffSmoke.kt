@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package dev.salt.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import dev.salt.desktop.ui.*
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat
import java.awt.Frame
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** Checks the last overlay frame against the source, at normal and Windows 150% scaling. */
fun main(args: Array<String>): Unit = runBlocking {
    val output = Files.createDirectories(Path.of(args[0]))
    withContext(Dispatchers.Main) {
        val owner = Frame()
        val clock = BroadcastFrameClock()
        val scope = CoroutineScope(coroutineContext + SupervisorJob() + clock)
        var frameTime = System.nanoTime()
        val results = mutableListOf<String>()
        try {
            for (density in listOf(1f, 1.5f)) for (row in listOf(false, true)) for (dark in listOf(false, true)) {
                val anchor = DetailAnchor()
                val detail = LibraryDetailState(scope)
                val name = "${if (row) "ranking" else "card"}-$density-${if (dark) "dark" else "light"}"
                val scene = ImageComposeScene((900 * density).toInt(), (640 * density).toInt(), Density(density),
                    coroutineContext = coroutineContext) {
                    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme(), typography = MiSans.typography, shapes = DesktopShapes) {
                        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            val source = Modifier.offset(30.dp, 140.dp).width(if (row) 760.dp else 220.dp)
                                .onGloballyPositioned { anchor.card = it.boundsInRoot() }
                                .detailSource(anchor, detail.request != null)
                            if (row) Row(source.height(70.dp).clip(DesktopCardShape).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("12", Modifier.width(34.dp), fontSize = 13.sp)
                                ArtworkImage(null, Modifier.size(44.dp).onGloballyPositioned { anchor.cover = it.boundsInRoot() }
                                    .clip(DesktopCardShape))
                                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                                    Text("很长的艺人与专辑名称 / Artist", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text("横向排行条目的副标题", Modifier.padding(top = 4.dp), fontSize = 11.sp)
                                }
                                Column {
                                    Text("38 次", fontSize = 13.sp)
                                    Text("2 小时 35 分", Modifier.padding(top = 4.dp), fontSize = 11.sp)
                                }
                            } else Column(source.clip(DesktopCardShape).padding(10.dp)) {
                                ArtworkImage(null, Modifier.fillMaxWidth().aspectRatio(1f)
                                    .onGloballyPositioned { anchor.cover = it.boundsInRoot() }.clip(DesktopCardShape))
                                Text("很长的专辑名称 / Album title", Modifier.padding(top = 12.dp), maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                Text("艺人名称 · Artist", Modifier.padding(top = 4.dp), fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            LibraryDetailOverlay(detail, emptyList(), null, {}, owner, dragEnabled = false)
                        }
                        }
                    }
                }
                suspend fun frames(count: Int) {
                    repeat(count) {
                        frameTime += 16_666_667
                        clock.sendFrame(frameTime)
                        scene.render(frameTime).close()
                        delay(1)
                    }
                }
                fun snapshot(suffix: String): java.awt.image.BufferedImage {
                    val path = output.resolve("$name-$suffix.png")
                    scene.render(frameTime).use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { Files.write(path, it.bytes) } }
                    return ImageIO.read(path.toFile())
                }
                try {
                    frames(8)
                    detail.open("很长的专辑名称 / Album title", row, anchor)
                    frames(6)
                    snapshot("opening")
                    // snapTo cancels the running animation but retains its request for the exact handoff frame.
                    detail.progress.snapTo(0f)
                    frames(3)
                    val before = snapshot("last-overlay")
                    detail.clear()
                    frames(3)
                    val after = snapshot("source")
                    var changed = 0
                    // Exclude cover resampling: this regression specifically checks all source text.
                    val left = ((if (row) 130 else 40) * density).toInt()
                    val top = ((if (row) 150 else 362) * density).toInt()
                    val right = ((if (row) 780 else 240) * density).toInt()
                    val bottom = ((if (row) 200 else 414) * density).toInt()
                    for (x in left until right) for (y in top until bottom) {
                        if (before.getRGB(x, y) != after.getRGB(x, y)) changed++
                    }
                    check(changed == 0) { "$name: $changed text pixels changed at overlay removal" }
                    results += "\"$name\":$changed"
                } finally { detail.clear(); scene.close() }
            }
            Files.writeString(output.resolve("verification.json"), "{\"passed\":true,\"changedTextPixels\":{${results.joinToString(",")}}}")
        } finally { scope.cancel(); owner.dispose() }
    }
}
