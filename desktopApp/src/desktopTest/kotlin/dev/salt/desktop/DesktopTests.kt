package dev.salt.desktop

import dev.salt.desktop.library.DesktopTrackReader
import dev.salt.desktop.library.ScanPhase
import dev.salt.desktop.playback.*
import dev.salt.desktop.storage.*
import dev.salt.replica.lyrics.LyricsParser
import dev.salt.replica.sync.fileDigest
import kotlinx.coroutines.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class DesktopTests {
    private val reference get() = Path.of(System.getProperty("synctone.reference.path"))

    @Test fun cachedLibraryAndSelectionAreVisibleBeforeTheAudioEngineFinishesStarting() = runBlocking {
        val directory = Files.createTempDirectory("synctone-cached-startup")
        val id = DesktopLibraryRepository(directory).use { db -> db.register(reference).id }
        val repository = DesktopSessionRepository(directory)
        repository.write(DesktopSession(current = id, positionMs = 25_000, queueIds = listOf(id)))
        val entered = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val store = DesktopPlayerStore(repository) {
            entered.complete(Unit)
            check(release.await(20, java.util.concurrent.TimeUnit.SECONDS))
            FakeEngine()
        }
        try {
            withTimeout(10_000) { entered.await() }
            assertFalse(store.state.value.ready)
            assertTrue(store.library.state.value.ready)
            assertEquals(id, store.library.state.value.tracks.single().id)
            assertEquals(id, store.state.value.track?.id)
            assertEquals(25_000, store.state.value.positionMs)
            release.countDown()
            withTimeout(10_000) { while (!store.state.value.ready) delay(10) }
            assertFalse(store.state.value.playing)
            assertEquals(listOf(id), store.state.value.queueIds)
        } finally { release.countDown(); store.close() }
    }

    @Test fun playerRecordsActualTimeAndSyncRegistrationPreservesTheActiveQueue() = runBlocking {
        val directory = Files.createTempDirectory("synctone-player-statistics")
        val clock = java.util.concurrent.atomic.AtomicLong(0)
        val store = DesktopPlayerStore(DesktopSessionRepository(directory), clock::get) { FakeEngine() }
        suspend fun await(predicate: () -> Boolean) = withTimeout(10_000) { while (!predicate()) delay(20) }
        try {
            await { store.state.value.ready }
            store.importFiles(listOf(reference))
            await { store.state.value.track != null }
            store.select(store.state.value.track!!)
            await { store.state.value.playing }
            repeat(15) { clock.addAndGet(2_000_000_000); delay(150) }
            await { store.statistics.state.value.snapshot.totalPlays == 1L }
            store.toggle()
            await { store.state.value.status == AudioStatus.Paused }
            store.seek(90_000)
            await { store.state.value.positionMs == 90_000L }
            clock.addAndGet(60_000_000_000)
            val before = store.state.value
            val incoming = directory.resolve("received.mp3")
            Files.copy(reference, incoming)
            Files.write(incoming, byteArrayOf(0, 1, 2, 3), java.nio.file.StandardOpenOption.APPEND)
            val hash = Files.newInputStream(incoming).use(::fileDigest)
            store.library.registerSynced(incoming, hash)
            await { store.state.value.tracks.size == 2 }
            assertEquals(before.queueIds, store.state.value.queueIds)
            assertEquals(before.currentId, store.state.value.currentId)
            assertEquals(90_000, store.state.value.positionMs)
            assertEquals(30_000, store.statistics.state.value.snapshot.songs.sumOf { it.listenedMs })
            assertFalse(store.state.value.playing)
        } finally { store.close() }
        val restored = DesktopPlayerStore(DesktopSessionRepository(directory)) { FakeEngine() }
        try {
            await { restored.state.value.ready }
            assertEquals(1, restored.statistics.state.value.snapshot.totalPlays)
            assertEquals(30_000, restored.statistics.state.value.snapshot.songs.sumOf { it.listenedMs })
        } finally { restored.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun metadataAndOriginalParserReadRealReference() {
        val track = DesktopTrackReader.read(reference)
        assertTrue(track.durationMs > 100_000)
        assertTrue(track.artwork!!.isNotEmpty())
        assertTrue(track.lyrics.lines.size > 30)
        val sung = track.lyrics.lines.first { it.start == 968L }
        assertTrue(sung.subLines.first().wordTimed)
        assertEquals("要与Rock的你说再见了", sung.subLines[1].text)
    }

    @Test fun sidecarLyricsOverrideEmbeddedAndInvalidSidecarFallsBack() {
        val directory = Files.createTempDirectory("synctone-metadata")
        val file = directory.resolve("music.mp3")
        Files.copy(reference, file)
        val lrc = directory.resolve("music.lrc")
        Files.writeString(lrc, "[00:01.0]external lyrics")
        val external = DesktopTrackReader.read(file)
        assertEquals("LRC", external.lyrics.source)
        assertEquals("external lyrics", external.lyrics.lines.single().text)
        Files.writeString(lrc, "not timed lyrics")
        assertTrue(DesktopTrackReader.read(file).lyrics.lines.size > 30)
    }

    @Test fun desktopSessionRoundTripsUnicodeAndDoesNotLeaveTemporaryFiles() {
        val directory = Files.createTempDirectory("synctone-session")
        val repository = DesktopSessionRepository(directory)
        assertEquals(DesktopSession(), repository.read())
        val session = DesktopSession(listOf("C:\\音乐\\a.mp3", "D:\\b.flac"), "D:\\b.flac", 40_123, 37, PlayMode.Shuffle)
        repository.write(session)
        assertEquals(session, repository.read())
        repository.write(session.copy(volume = 0))
        assertEquals(0, repository.read().volume)
        Files.list(directory).use { assertEquals(1L, it.count()) }
    }

    @Test fun firstLaunchCreatesMissingDataDirectory() {
        val directory = Files.createTempDirectory("synctone-first-launch").resolve("Local").resolve("SyncTone")
        val repository = DesktopSessionRepository(directory)
        assertEquals(DesktopSession(), repository.read())
        val session = DesktopSession(volume = 41)
        repository.write(session)
        assertEquals(session, repository.read())
    }

    @Test fun packagedDefaultDirectoryLivesBesidePortableLauncher() {
        val root = Files.createTempDirectory("synctone-portable-default")
        val application = root.resolve("SyncTone")
        val resources = application.resolve("app").resolve("resources")
        Files.createDirectories(resources)
        Files.writeString(application.resolve("SyncTone.exe"), "test launcher")
        val oldData = System.getProperty("synctone.data.dir")
        val oldResources = System.getProperty("compose.application.resources.dir")
        try {
            System.clearProperty("synctone.data.dir")
            System.setProperty("compose.application.resources.dir", resources.toString())
            assertEquals(application.resolve("data").toAbsolutePath().normalize(),
                DesktopSessionRepository.defaultDirectory().toAbsolutePath().normalize())
        } finally {
            if (oldData == null) System.clearProperty("synctone.data.dir") else System.setProperty("synctone.data.dir", oldData)
            if (oldResources == null) System.clearProperty("compose.application.resources.dir")
            else System.setProperty("compose.application.resources.dir", oldResources)
            root.toFile().deleteRecursively()
        }
    }

    @Test fun unusableDefaultLocationFallsBackAndRestartsFromThePortableSession() {
        val root = Files.createTempDirectory("synctone-portable")
        val preferred = root.resolve("blocked")
        Files.writeString(preferred, "preserve this file")
        val fallback = root.resolve("data")
        val repository = DesktopSessionRepository(preferred, fallback)
        val saved = DesktopSession(listOf("music.mp3"), "music.mp3", 12345, 63)
        repository.write(saved)
        assertEquals(fallback, repository.directory)
        assertEquals("preserve this file", Files.readString(preferred))
        assertEquals(saved, DesktopSessionRepository(preferred, fallback).read())
        Files.delete(preferred)
        DesktopSessionRepository(preferred).write(saved.copy(volume = 10))
        assertEquals(saved, DesktopSessionRepository(preferred, fallback).read())
    }

    @Test fun failedSavesDoNotRepeatDialogsAndRecoverWithoutInterruptingPlayback() = runBlocking {
        val root = Files.createTempDirectory("synctone-save-failure")
        val directory = root.resolve("SyncTone")
        Files.writeString(directory, "existing file must be preserved")
        val repository = DesktopSessionRepository(directory)
        val backend = FakeEngine()
        var now = 0L
        val store = DesktopPlayerStore(repository, { now }, { backend })
        suspend fun waitFor(predicate: () -> Boolean) = withTimeout(10_000) { while (!predicate()) delay(25) }
        try {
            waitFor { store.state.value.ready }
            store.importFiles(listOf(reference))
            waitFor { store.state.value.track != null && !store.state.value.importing && store.state.value.error != null }
            assertTrue(store.state.value.error!!.contains("无法保存播放记录"))
            assertTrue(store.state.value.error!!.contains("同名文件"))
            store.dismissError()
            store.toggle()
            waitFor { store.state.value.playing }
            repeat(2) {
                now += 61_000_000_000L
                val mode = store.state.value.mode
                store.cycleMode()
                waitFor { store.state.value.mode != mode }
                assertNull(store.state.value.error)
                assertTrue(store.state.value.playing)
            }
            assertEquals("existing file must be preserved", Files.readString(directory))
            // Remove only the test fixture to make the same location writable again.
            Files.delete(directory)
            now += 61_000_000_000L
            store.cycleMode()
            waitFor { Files.exists(directory.resolve("session.json")) }
            assertEquals(1, repository.read().paths.size)
            assertNull(store.state.value.error)
        } finally { store.close() }
        assertTrue(backend.closed)
        val restored = DesktopPlayerStore(repository) { FakeEngine() }
        try {
            waitFor { restored.state.value.ready }
            assertEquals(1, restored.state.value.tracks.size)
        } finally { restored.close() }
    }

    @Test fun scanningDoesNotChangeActiveQueuePositionOrPauseControls() = runBlocking {
        val root = Files.createTempDirectory("synctone-scan-playing")
        val folder = Files.createDirectory(root.resolve("music"))
        repeat(30) { index ->
            val file = folder.resolve("$index.mp3")
            Files.copy(reference, file)
            Files.write(file, byteArrayOf(index.toByte()), java.nio.file.StandardOpenOption.APPEND)
        }
        val store = DesktopPlayerStore(DesktopSessionRepository(root.resolve("data"))) { FakeEngine() }
        suspend fun waitFor(predicate: () -> Boolean) = withTimeout(10_000) { while (!predicate()) delay(10) }
        try {
            waitFor { store.state.value.ready }
            store.importFiles(listOf(reference))
            waitFor { store.state.value.track != null }
            store.toggle()
            waitFor { store.state.value.playing }
            store.seek(42_000)
            waitFor { store.state.value.positionMs == 42_000L }
            val queue = store.state.value.queueIds
            val current = store.state.value.currentId
            store.library.addFolder(folder)
            waitFor { store.library.state.value.scan.phase == ScanPhase.Scanning }
            store.toggle()
            waitFor { store.state.value.status == AudioStatus.Paused }
            store.seek(54_000)
            waitFor { store.state.value.positionMs == 54_000L }
            waitFor { store.library.state.value.scan.phase == ScanPhase.Complete }
            waitFor { store.state.value.tracks.size == 31 }
            assertEquals(queue, store.state.value.queueIds)
            assertEquals(current, store.state.value.currentId)
            assertEquals(54_000, store.state.value.positionMs)
            assertFalse(store.state.value.playing)
            store.next()
            waitFor { store.state.value.playing }
            assertEquals(current, store.state.value.currentId)
        } finally { store.close() }
    }

    @Test fun versionOneMigrationRestoresSelectionAndRetriesOfflineLegacyFiles() = runBlocking {
        val root = Files.createTempDirectory("synctone-v1")
        val data = Files.createDirectory(root.resolve("data"))
        val offline = root.resolve("offline.mp3")
        val session = kotlinx.serialization.json.buildJsonObject {
            put("version", kotlinx.serialization.json.JsonPrimitive(1))
            put("paths", kotlinx.serialization.json.JsonArray(listOf(reference, offline).map { kotlinx.serialization.json.JsonPrimitive(it.toString()) }))
            put("current", kotlinx.serialization.json.JsonPrimitive(reference.toString()))
            put("positionMs", kotlinx.serialization.json.JsonPrimitive(25_000))
            put("volume", kotlinx.serialization.json.JsonPrimitive(44))
        }
        Files.writeString(data.resolve("session.json"), session.toString())
        val repository = DesktopSessionRepository(data)
        val store = DesktopPlayerStore(repository) { FakeEngine() }
        suspend fun waitFor(predicate: () -> Boolean) = withTimeout(10_000) { while (!predicate()) delay(10) }
        var current = ""
        try {
            waitFor { store.state.value.track != null }
            current = store.state.value.currentId!!
            assertEquals(64, current.length)
            assertFalse(store.state.value.playing)
            assertEquals(25_000, store.state.value.positionMs)
            assertEquals(44, store.state.value.volume)
            assertEquals(listOf(current), store.state.value.queueIds)
        } finally { store.close() }
        assertTrue(offline.toString() in repository.read().paths)
        Files.copy(reference, offline)
        Files.write(offline, byteArrayOf(9), java.nio.file.StandardOpenOption.APPEND)
        val restored = DesktopPlayerStore(repository) { FakeEngine() }
        try {
            waitFor { restored.state.value.track != null }
            assertEquals(2, restored.state.value.tracks.size)
            assertEquals(current, restored.state.value.currentId)
            assertEquals(listOf(current), restored.state.value.queueIds)
        } finally { restored.close() }
    }

    @Test fun malformedSessionReportsFailureRatherThanInventingState() {
        val directory = Files.createTempDirectory("synctone-invalid")
        Files.writeString(directory.resolve("session.json"), "broken")
        assertFails { DesktopSessionRepository(directory).read() }
        val backup = DesktopSessionRepository(directory).preserveUnreadable()
        assertEquals("broken", Files.readString(backup))
    }

    @Test fun unavailableMusicRemainsInSavedLibrary() = runBlocking {
        val directory = Files.createTempDirectory("synctone-unavailable")
        val repository = DesktopSessionRepository(directory)
        val missing = directory.resolve("removed.mp3").toString()
        repository.write(DesktopSession(listOf(missing)))
        val store = DesktopPlayerStore(repository) { FakeEngine() }
        try {
            withTimeout(10_000) { while (!store.state.value.ready) delay(25) }
            assertTrue(store.state.value.tracks.isEmpty())
        } finally { store.close() }
        assertEquals(listOf(missing), repository.read().paths)
    }

    @Test fun parserRetainsOffsetAndSecondaryTimingsOnDesktop() {
        val lyrics = LyricsParser.parse("[offset:-100]\n[00:01.0]main<00:02.0> words\n[00:01.0]translation\n[00:03.0]next")
        assertEquals(900L, lyrics.lines.first().start)
        assertEquals("translation", lyrics.lines.first().translation)
        assertEquals(1900L, lyrics.lines.first().cells[1].start)
    }

    @Test fun queueHandlesImportDeduplicationEndAndRestartsPaused() = runBlocking {
        val directory = Files.createTempDirectory("synctone-controller")
        val second = directory.resolve("second.mp3")
        Files.copy(reference, second)
        Files.write(second, byteArrayOf(1, 2, 3), java.nio.file.StandardOpenOption.APPEND)
        val backend = FakeEngine()
        val repository = DesktopSessionRepository(directory.resolve("data"))
        val store = DesktopPlayerStore(repository) { backend }
        suspend fun waitFor(predicate: () -> Boolean) = withTimeout(10_000) {
            while (!predicate()) delay(25)
        }
        try {
            waitFor { store.state.value.ready }
            store.importFiles(listOf(reference, second, reference))
            waitFor { store.state.value.tracks.size == 2 && !store.state.value.importing && store.state.value.track != null }
            val secondId = store.state.value.tracks[1].id
            assertFalse(store.state.value.playing)
            store.toggle()
            waitFor { store.state.value.playing }
            backend.finish()
            waitFor { store.state.value.currentId == secondId && store.state.value.playing }
            store.volume(31)
            store.cycleMode()
            waitFor { store.state.value.volume == 31 && store.state.value.mode == PlayMode.RepeatOne }
            val opens = backend.opens
            backend.finish()
            waitFor { backend.opens > opens && store.state.value.playing }
            assertEquals(secondId, store.state.value.currentId)
        } finally { store.close() }
        assertTrue(backend.closed)
        val restored = DesktopPlayerStore(repository) { FakeEngine() }
        try {
            waitFor { restored.state.value.ready && restored.state.value.currentId != null }
            assertFalse(restored.state.value.playing)
            assertEquals(31, restored.state.value.volume)
            assertEquals(2, restored.state.value.tracks.size)
        } finally { restored.close() }
    }

    @Test fun lyricSettingsOffsetsAndOverridesPersistAndRestoreIndependently() = runBlocking {
        val directory = Files.createTempDirectory("synctone-lyrics-control")
        val repository = DesktopSessionRepository(directory)
        val store = DesktopPlayerStore(repository) { FakeEngine() }
        val settings = dev.salt.desktop.lyrics.DesktopLyricsSettings(fontWeight = 473, mesh = 3, speed = 1.4f)
        suspend fun await(predicate: () -> Boolean) = withTimeout(10_000) { while (!predicate()) delay(20) }
        try {
            await { store.state.value.ready }
            store.importFiles(listOf(reference))
            await { store.state.value.track != null }
            store.updateLyricsSettings(settings)
            store.offsetLyrics(500)
            val lrc = directory.resolve("override.lrc")
            Files.writeString(lrc, "[00:01]override")
            store.importLyrics(lrc)
            await { store.state.value.lyricSettings == settings && store.state.value.lyricsOffsetMs == 500L &&
                store.state.value.track!!.lyrics.lines.singleOrNull()?.text == "override" }
        } finally { store.close() }
        val restored = DesktopPlayerStore(repository) { FakeEngine() }
        try {
            // Cached rows intentionally precede native initialization and playback settings.
            await { restored.state.value.ready && restored.state.value.track != null }
            assertEquals(settings, restored.state.value.lyricSettings)
            assertEquals(500, restored.state.value.lyricsOffsetMs)
            assertEquals("override", restored.state.value.track!!.lyrics.lines.single().text)
            restored.restoreLyrics()
            await { restored.state.value.track!!.lyrics.lines.size == 60 }
            assertEquals(500, restored.state.value.lyricsOffsetMs)
        } finally { restored.close() }
    }
}

private class FakeEngine : AudioEngine {
    @Volatile private var value = AudioSnapshot()
    @Volatile var opens = 0
    @Volatile var closed = false
    override fun open(path: Path, play: Boolean) {
        opens++
        value = value.copy(status = if (play) AudioStatus.Playing else AudioStatus.Idle, positionMs = 0, durationMs = 100_000)
    }
    override fun setPaused(paused: Boolean) { value = value.copy(status = if (paused) AudioStatus.Paused else AudioStatus.Playing) }
    override fun seek(positionMs: Long) { value = value.copy(positionMs = positionMs) }
    override fun setVolume(volume: Int) { value = value.copy(volume = volume) }
    override fun snapshot() = value
    override fun stop() { value = value.copy(status = AudioStatus.Stopped) }
    override fun close() { closed = true }
    fun finish() { value = value.copy(status = AudioStatus.Ended) }
}
