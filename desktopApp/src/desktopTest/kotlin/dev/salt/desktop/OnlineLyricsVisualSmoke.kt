@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.salt.desktop

import androidx.compose.foundation.pager.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.salt.desktop.ui.MiSans
import dev.salt.desktop.ui.desktopColorScheme
import dev.salt.replica.lyrics.*
import dev.salt.replica.lyrics.online.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path

/** Uses the production Compose page and synthetic lyrics, never a real music library. */
fun main(args: Array<String>) = runBlocking {
    val output = Files.createDirectories(Path.of(args[0]))
    val temporary = Files.createTempDirectory("synctone-update-visual")
    try {
        withContext(Dispatchers.Main) {
            val song = LyricSong("example", "夜空中的光", "SyncTone", 208000)
            val provider = object : LyricProvider {
                override val source = LyricSource.Kugou
                override suspend fun search(query: String, song: LyricSong) = (0..2).map { index ->
                    LyricCandidate(source, index.toString(), song.title + if (index == 1) " (Live)" else "",
                        song.artist, song.durationMs + index * 15000)
                }
                override suspend fun download(candidate: LyricCandidate, durationMs: Long) = LyricDownload(candidate,
                    Lyrics((0..11).map { i ->
                        val start = i * 5000L + 1000
                        LyricLine(start, start + 3000, listOf(LyricCell(start, start + 1000, "星光"),
                            LyricCell(start + 1000, start + 2000, "轻轻"), LyricCell(start + 2000, start + 3000, "落下")),
                            listOf(LyricSubLine(listOf(LyricCell(start, start + 3000, "Starlight falls softly")), LyricContent.Translation),
                                LyricSubLine(listOf(LyricCell(start, start + 3000, "hoshi no hikari")), LyricContent.Romanization)))
                    }, "ONLINE_KUGOU"))
            }
            val controller = OnlineLyricsController(this, temporary.resolve("preferences").toFile(), { _, _, _ -> },
                OnlineLyricsService(listOf(provider)))
            controller.updateLibrary(listOf(song, song.copy(id = "two", title = "雨を待つ街")), song.id)
            controller.search(song.query, null)
            withTimeout(3000) { controller.state.first { !it.busy && it.searched } }
            controller.preview(controller.state.value.results.first())
            withTimeout(3000) { controller.state.first { !it.busy && it.preview != null } }
            var pager: PagerState? = null
            var animationScope: CoroutineScope? = null
            var time = System.nanoTime()
            val scene = ImageComposeScene(1100, 820, Density(1f), coroutineContext = coroutineContext) {
                val page = rememberPagerState { 3 }
                val uiScope = rememberCoroutineScope()
                SideEffect { pager = page; animationScope = uiScope }
                MaterialTheme(colorScheme = desktopColorScheme(false), typography = MiSans.typography) {
                    LyricsUpdateScreen(controller, pagerState = page)
                }
            }
            suspend fun frames(count: Int) { repeat(count) { time += 16_666_667; scene.render(time).close(); delay(5) } }
            fun snapshot(name: String) { scene.render(time).use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use {
                Files.write(output.resolve(name), it.bytes)
            } } }
            try {
                frames(8); snapshot("windows-search.png")
                val motion = requireNotNull(animationScope).launch { requireNotNull(pager).animateScrollToPage(1, animationSpec = lyricsPageAnimation()) }
                frames(8)
                val layout = requireNotNull(pager).layoutInfo
                val old = layout.visiblePagesInfo.first { it.index == 0 }.offset
                val next = layout.visiblePagesInfo.first { it.index == 1 }.offset
                check(old < 0 && old > -layout.pageSize && next > 0 && next < layout.pageSize) {
                    "Pages must both remain laid out and translate horizontally: old=$old next=$next size=${layout.pageSize}"
                }
                snapshot("windows-swipe-mid.png")
                frames(35); motion.join()
                check(pager!!.settledPage == 1)
                println("Horizontal motion: outgoing=$old incoming=$next; both card pages present during transition")
            } finally { scene.close() }
            val phone = ImageComposeScene(390, 844, Density(1f), coroutineContext = coroutineContext) {
                val page = rememberPagerState(initialPage = 1) { 3 }
                MaterialTheme(colorScheme = desktopColorScheme(true), typography = MiSans.typography) {
                    LyricsUpdateScreen(controller, onMenu = {}, pagerState = page)
                }
            }
            try {
                repeat(6) { time += 16_666_667; phone.render(time).close(); delay(5) }
                phone.render(time).use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use {
                    Files.write(output.resolve("android-settings.png"), it.bytes)
                } }
            } finally { phone.close() }
            println("Production lyrics update UI rendered at 1100x820 and 390x844")
        }
    } finally { temporary.toFile().deleteRecursively() }
}
