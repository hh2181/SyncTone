@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.salt.desktop

import dev.salt.desktop.library.ScanPhase
import dev.salt.desktop.playback.*
import dev.salt.desktop.storage.DesktopSessionRepository
import dev.salt.desktop.storage.DesktopPaths
import dev.salt.replica.sync.fileDigest
import dev.salt.desktop.lyrics.DesktopLyricsSettings
import dev.salt.desktop.lyrics.DesktopLyricsPanel
import dev.salt.desktop.visual.FlowingLightRenderer
import dev.salt.desktop.ui.MiSans
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import org.jetbrains.skia.EncodedImageFormat
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

fun main() {
    try { runLibraryPackageSmoke(); exitProcess(0) }
    catch (error: Throwable) { error.printStackTrace(); exitProcess(1) }
}

private fun runLibraryPackageSmoke(): Unit = runBlocking {
    DesktopPaths.configureRuntime()
    val root = Path.of(System.getProperty("synctone.probe.root"))
    val music = root.resolve("music")
    val repository = DesktopSessionRepository.forApplication()
    check(repository.directory == DesktopPaths.applicationDirectory().resolve("data"))
    val temporary = Files.createTempFile("portable-test-", ".tmp")
    check(temporary.toAbsolutePath().normalize().startsWith(DesktopPaths.applicationDirectory().resolve("portable")))
    Files.delete(temporary)
    suspend fun await(description: String, predicate: () -> Boolean) {
        try { withTimeout(20_000) { while (!predicate()) delay(20) } }
        catch (e: TimeoutCancellationException) { error("Timeout: $description") }
    }
    val store = DesktopPlayerStore(repository)
    try {
        await("startup") { store.state.value.ready }
        check(store.state.value.error == null) { store.state.value.error.orEmpty() }
        store.volume(0)
        await("mute") { store.state.value.volume == 0 }
        store.library.addFolder(music)
        await("scan") { store.library.state.value.scan.phase == ScanPhase.Complete }
        await("tracks") { store.state.value.tracks.size == 2 }
        check(store.library.state.value.scan.duplicates == 1)
        check(store.library.state.value.scan.unreadable == 1)
        val track = store.state.value.tracks.first { it.durationMs > 100_000 }
        check(track.lyrics.lines.size == 60)
        check(track.artwork!!.size > 100_000)
        store.select(track)
        await("real playback") { store.state.value.playing && store.state.value.positionMs > 400 }
        store.toggle()
        await("pause") { store.state.value.status == AudioStatus.Paused }
        store.seek(32_000)
        await("seek") { store.state.value.positionMs >= 31_500 }
        val visualSettings = DesktopLyricsSettings(fontWeight = 473, speed = 1.4f, mesh = 3)
        store.updateLyricsSettings(visualSettings)
        store.offsetLyrics(300)
        await("visual settings") { store.state.value.lyricSettings == visualSettings && store.state.value.lyricsOffsetMs == 300L }
        FlowingLightRenderer(track.artwork!!).use { renderer ->
            renderer.render(1280, 820, 8000, 3, false, false).use { image ->
                image.encodeToData(EncodedImageFormat.PNG)!!.use { Files.write(root.resolve("packaged-flow.png"), it.bytes) }
            }
        }
        withContext(Dispatchers.Main) {
            val scene = ImageComposeScene(600, 440, Density(1f), coroutineContext = coroutineContext) {
                MaterialTheme(typography = MiSans.typography) {
                    DesktopLyricsPanel(track.lyrics, mutableLongStateOf(2600), visualSettings,
                        track.id, 0, {}, {}, Modifier.fillMaxSize().background(Color(0xFF272832)))
                }
            }
            try {
                repeat(25) { scene.render(System.nanoTime()).close(); delay(16) }
                scene.render(System.nanoTime()).use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use {
                    Files.write(root.resolve("packaged-misans-lyrics.png"), it.bytes)
                } }
            } finally { scene.close() }
        }
        val lrc = root.resolve("selected.lrc")
        Files.writeString(lrc, "[00:01]persisted package lyrics")
        store.importLyrics(lrc)
        await("lyrics override") { store.state.value.track?.lyrics?.lines?.singleOrNull()?.text == "persisted package lyrics" }
        store.library.scan()
        await("cached scan") { store.library.state.value.scan.phase == ScanPhase.Complete }
        check(store.library.state.value.scan.cached == 3)
        check(store.state.value.error == null) { store.state.value.error.orEmpty() }
        check(store.library.state.value.error == null) { store.library.state.value.error.orEmpty() }
    } finally { store.close() }
    val restored = DesktopPlayerStore(repository)
    try {
        // Cached selection is published before the audio worker restores per-track state.
        await("restore") { restored.state.value.track != null && restored.state.value.seekRevision > 0 }
        check(restored.state.value.tracks.size == 2)
        check(!restored.state.value.playing)
        check(restored.state.value.positionMs >= 31_500)
        check(restored.state.value.queueIds.size == 2)
        check(restored.state.value.lyricSettings.fontWeight == 473)
        check(restored.state.value.lyricsOffsetMs == 300L)
        check(restored.state.value.track!!.lyrics.lines.single().text == "persisted package lyrics")
        check(restored.library.state.value.scan.phase == ScanPhase.Complete)
        check(Files.isRegularFile(repository.directory.resolve("library.db")))
        val managed = Files.createDirectories(repository.directory.resolve("synced-music")).resolve("song.mp3")
        Files.copy(music.resolve("song.mp3"), managed)
        restored.library.registerSynced(managed, Files.newInputStream(managed).use(::fileDigest))
        Files.writeString(root.resolve("origin.txt"), restored.statistics.history.origin)
        verifyPackagedCore(root, music.resolve("song.mp3"))
        Files.writeString(root.resolve("verification.json"),
            """{"passed":true,"nativeLauncher":true,"privateRuntime":true,"portableDefault":true,"portableTemp":true,"tracks":2,"contentDeduplication":true,"cachedScan":true,"realPlayback":true,"lyricsPersisted":true,"pausedRestore":true,"miSansRendered":true,"meshRendered":true,"visualSettingsPersisted":true,"statisticsAndSync":true}""")
    } finally { restored.close() }
}
