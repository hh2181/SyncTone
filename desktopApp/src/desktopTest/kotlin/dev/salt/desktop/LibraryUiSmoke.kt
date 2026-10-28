@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package dev.salt.desktop

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import dev.salt.desktop.playback.DesktopPlayerStore
import dev.salt.desktop.storage.DesktopLibraryRepository
import dev.salt.desktop.storage.DesktopSessionRepository
import dev.salt.desktop.storage.DesktopSession
import dev.salt.desktop.ui.DesktopApp
import dev.salt.replica.ui.PlayerSheetState
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat
import java.awt.Color
import java.awt.Frame
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import javax.imageio.ImageIO

/** Uses a separate database; no imported library or source audio is changed. */
fun main(args: Array<String>): Unit = runBlocking {
    val output = Path.of(args[1])
    val data = Files.createDirectories(output.resolve("data"))
    DesktopLibraryRepository(data).close()
    val source = Path.of(args[0])
    val music = Files.createDirectories(output.resolve("music"))
    val paths = List(180) { index ->
        music.resolve("fixture-$index.mp3").also {
            if (!Files.exists(it)) Files.createLink(it, source)
        }.toAbsolutePath()
    }
    val artwork = BufferedImage(1600, 1600, BufferedImage.TYPE_INT_RGB)
    val encoded = ByteArrayOutputStream().use { bytes ->
        val graphics = artwork.createGraphics()
        try {
            repeat(80) { row ->
                graphics.color = Color.getHSBColor(row / 80f, .4f, .85f)
                graphics.fillRect(0, row * 20, 1600, 20)
            }
        } finally { graphics.dispose() }
        ImageIO.write(artwork, "jpg", bytes)
        bytes.toByteArray()
    }
    artwork.flush()
    DriverManager.getConnection("jdbc:sqlite:${data.resolve("library.db")}").use { connection ->
        connection.autoCommit = false
        connection.prepareStatement("""INSERT OR REPLACE INTO tracks
            (id,title,artist,album,duration_ms,artwork,lyrics,original_path,added_utc) VALUES(?,?,?,?,?,?,?,?,?)""").use { statement ->
            repeat(180) { index ->
                statement.setString(1, "ui-fixture-$index")
                statement.setString(2, "测试歌曲 ${index + 1} / Library performance")
                statement.setString(3, "测试艺人 ${index / 3}")
                statement.setString(4, "测试专辑 ${index / 2}")
                statement.setLong(5, 180_000)
                statement.setBytes(6, encoded)
                statement.setString(7, "{\"source\":\"LRC\",\"lines\":[]}")
                statement.setString(8, paths[index].toString())
                statement.setLong(9, index.toLong())
                statement.addBatch()
            }
            statement.executeBatch()
        }
        connection.prepareStatement("""INSERT OR REPLACE INTO files
            (path,track_id,size,modified,file_key,sidecar_stamp,available) VALUES(?,?,?,?,?,'',1)""").use { statement ->
            paths.forEachIndexed { index, path ->
                val attributes = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java)
                statement.setString(1, path.toString().lowercase())
                statement.setString(2, "ui-fixture-$index")
                statement.setLong(3, attributes.size())
                statement.setString(4, attributes.lastModifiedTime().toString())
                statement.setString(5, attributes.fileKey()?.toString().orEmpty())
                statement.addBatch()
            }
            statement.executeBatch()
        }
        connection.commit()
    }
    DesktopSessionRepository(data).write(DesktopSession(current = "ui-fixture-0", queueIds = listOf("ui-fixture-0")))
    withContext(Dispatchers.Main) {
        val store = DesktopPlayerStore(DesktopSessionRepository(data))
        val frameClock = BroadcastFrameClock()
        val animationScope = CoroutineScope(coroutineContext + SupervisorJob() + frameClock)
        val sheet = PlayerSheetState(animationScope)
        val owner = Frame()
        var frameTime = System.nanoTime()
        val frameTimes = mutableListOf<Double>()
        val retainedHeap = mutableListOf<Long>()
        suspend fun frames(scene: ImageComposeScene, count: Int = 35, measure: Boolean = false) {
            repeat(count) {
                frameTime += 16_666_667
                frameClock.sendFrame(frameTime)
                val started = System.nanoTime()
                scene.render(frameTime).close()
                if (measure) frameTimes += (System.nanoTime() - started) / 1_000_000.0
                delay(8)
            }
        }
        fun snapshot(scene: ImageComposeScene, name: String) {
            scene.render(frameTime).use { image ->
                image.encodeToData(EncodedImageFormat.PNG)?.use { Files.write(output.resolve(name), it.bytes) }
            }
        }
        fun click(scene: ImageComposeScene, x: Float, y: Float) {
            scene.sendPointerEvent(PointerEventType.Press, Offset(x, y))
            scene.sendPointerEvent(PointerEventType.Release, Offset(x, y))
        }
        try {
            withTimeout(20_000) { while (!store.state.value.ready) delay(25) }
            check(store.state.value.tracks.size == 180)
            check(store.state.value.tracks.all { it.available })
            for ((width, height) in listOf(1280 to 820, 960 to 640)) {
                val scene = ImageComposeScene(width, height, Density(1f), coroutineContext = coroutineContext) {
                    DesktopApp(store, sheet, owner)
                }
                try {
                    frames(scene)
                    scene.sendPointerEvent(PointerEventType.Move, Offset(330f, 270f))
                    frames(scene)
                    snapshot(scene, "albums-hover-$width.png")
                    repeat(3) {
                        for ((page, y) in listOf("songs" to 171f, "albums" to 273f, "artists" to 324f)) {
                            click(scene, 90f, y)
                            frames(scene)
                            repeat(12) {
                                scene.sendPointerEvent(PointerEventType.Scroll, Offset(650f, 400f), scrollDelta = Offset(0f, 3f))
                                frames(scene, 2, measure = true)
                            }
                            snapshot(scene, "$page-scrolled-$width.png")
                        }
                        System.gc()
                        delay(100)
                        retainedHeap += java.lang.management.ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
                    }
                    click(scene, 90f, 171f)
                    frames(scene)
                    scene.sendPointerEvent(PointerEventType.Move, Offset(160f, height - 45f))
                    frames(scene)
                    snapshot(scene, "miniplayer-hover-$width.png")
                    check(store.state.value.error == null) { store.state.value.error.orEmpty() }
                } finally { scene.close() }
            }
            frameTimes.sort()
            Files.writeString(output.resolve("verification.json"),
                """{"passed":true,"tracks":180,"albums":90,"artists":60,"pageCyclesPerViewport":3,"sizes":[[1280,820],[960,640]],"measuredFrames":${frameTimes.size},"renderMedianMs":${frameTimes[frameTimes.size / 2]},"renderP95Ms":${frameTimes[(frameTimes.size * .95).toInt()]},"retainedHeapBytes":${retainedHeap}}""")
        } finally {
            animationScope.cancel()
            owner.dispose()
            store.close()
        }
    }
}
