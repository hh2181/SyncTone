package dev.salt.desktop

import dev.salt.desktop.playback.DesktopPlayerStore
import dev.salt.desktop.storage.DesktopPaths
import dev.salt.desktop.storage.DesktopSessionRepository
import dev.salt.replica.sync.fileDigest
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

fun main() {
    try {
        DesktopPaths.configureRuntime()
        runBlocking {
            val root = Path.of(System.getProperty("synctone.probe.root"))
            val repository = DesktopSessionRepository.forApplication()
            val store = DesktopPlayerStore(repository)
            try {
                withTimeout(20_000) { while (store.state.value.track == null || store.state.value.seekRevision == 0) delay(25) }
                check(store.state.value.error == null) { store.state.value.error.orEmpty() }
                check(store.state.value.tracks.size == 2)
                check(!store.state.value.playing)
                check(store.state.value.positionMs >= 31_500)
                check(store.state.value.lyricSettings.fontWeight == 473)
                check(store.state.value.lyricsOffsetMs == 300L)
                check(store.statistics.history.origin == Files.readString(root.resolve("origin.txt")))
                val managed = repository.directory.resolve("synced-music/song.mp3")
                check(store.library.idForPath(managed.toString()) == Files.newInputStream(managed).use(::fileDigest))
                val temporary = Files.createTempFile("portable-restart-", ".tmp")
                check(temporary.toAbsolutePath().normalize().startsWith(DesktopPaths.applicationDirectory().resolve("portable")))
                Files.delete(temporary)
            } finally { store.close() }
            Files.writeString(root.resolve("relocation-verification.json"),
                """{"passed":true,"movedFolder":true,"pausedRestore":true,"sameIdentity":true,"managedMusic":true,"portableTemp":true}""")
        }
        exitProcess(0)
    } catch (error: Throwable) { error.printStackTrace(); exitProcess(1) }
}
