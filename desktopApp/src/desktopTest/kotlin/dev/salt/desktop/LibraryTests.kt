package dev.salt.desktop

import dev.salt.desktop.library.*
import dev.salt.desktop.storage.*
import dev.salt.replica.lyrics.LyricsParser
import kotlinx.coroutines.*
import org.junit.Test
import java.nio.file.*
import kotlin.test.*

class LibraryTests {
    private val reference get() = Path.of(System.getProperty("synctone.reference.path"))
    private suspend fun waitFor(predicate: () -> Boolean) = withTimeout(20_000) { while (!predicate()) delay(20) }

    @Test fun cachedLibraryPrecedesAvailabilityChecksAndOfflineTracksArePreserved() = runBlocking {
        val root = Files.createTempDirectory("synctone-cached-availability")
        val song = root.resolve("song.mp3")
        Files.copy(reference, song, StandardCopyOption.COPY_ATTRIBUTES)
        DesktopLibraryRepository(root).use { it.register(song) }
        Files.delete(song)
        val library = DesktopLibrary()
        try {
            library.initialize(root, emptyList())
            val cached = library.state.value.tracks.single()
            assertTrue(cached.available)
            library.refreshAvailability()
            assertEquals(cached.id, library.state.value.tracks.single().id)
            assertFalse(library.state.value.tracks.single().available)
            Files.copy(reference, song, StandardCopyOption.COPY_ATTRIBUTES)
            library.refreshAvailability()
            assertTrue(library.state.value.tracks.single().available)
            val restored = library.state.value.tracks.single()
            library.refreshAvailability()
            assertSame(restored, library.state.value.tracks.single())
        } finally { library.close() }
    }

    @Test fun slowAvailabilityProbesDoNotBlockCachedArtworkAndLyrics() = runBlocking {
        val root = Files.createTempDirectory("synctone-probe-assets")
        DesktopLibraryRepository(root).use { it.register(reference) }
        DesktopLibraryRepository(root).use { db ->
            val track = db.tracks().single()
            val entered = CompletableDeferred<Unit>()
            val release = java.util.concurrent.CountDownLatch(1)
            val probe = async(Dispatchers.IO) {
                db.refreshAvailability {
                    entered.complete(Unit)
                    check(release.await(20, java.util.concurrent.TimeUnit.SECONDS))
                }
            }
            try {
                withTimeout(10_000) { entered.await() }
                withTimeout(5_000) {
                    withContext(Dispatchers.IO) {
                        assertTrue(track.artwork!!.isNotEmpty())
                        assertTrue(track.lyrics.lines.isNotEmpty())
                    }
                }
            } finally { release.countDown(); probe.await() }
        }
    }

    @Test fun databasePersistsAssetsIdentityOverridesAndFileLocations() {
        val root = Files.createTempDirectory("synctone-library-db")
        val first = root.resolve("a.mp3")
        val duplicate = root.resolve("renamed.mp3")
        Files.copy(reference, first); Files.copy(reference, duplicate)
        val data = root.resolve("data")
        var id = ""
        DesktopLibraryRepository(data).use { db ->
            val one = db.register(first)
            id = one.id
            assertTrue(one.added)
            assertEquals(64, id.length)
            assertFalse(db.register(duplicate).added)
            assertTrue(db.register(first).cached)
            assertFalse(db.register(first, force = true).cached)
            assertEquals(1, db.tracks().size)
            val track = db.tracks().single()
            assertEquals(60, track.lyrics.lines.size)
            assertTrue(track.artwork!!.size > 100_000)
            db.setLyrics(id, LyricsParser.parse("[00:01.0]override", source = "LRC"))
            Files.delete(first)
            db.refreshAvailability()
            assertTrue(db.tracks().single().available)
            assertEquals(duplicate.toString().lowercase(), db.tracks().single().path.toString())
        }
        DesktopLibraryRepository(data).use { db ->
            assertEquals(id, db.tracks().single().id)
            assertEquals("override", db.tracks().single().lyrics.lines.single().text)
            Files.delete(duplicate)
            db.refreshAvailability()
            assertFalse(db.tracks().single().available)
            val moved = root.resolve("moved.mp3")
            Files.copy(reference, moved)
            assertEquals(id, db.register(moved).id)
            assertEquals(1, db.tracks().size)
            assertTrue(db.tracks().single().available)
            assertEquals("override", db.tracks().single().lyrics.lines.single().text)
        }
    }

    @Test fun changedFileCreatesDistinctVersionAndKeepsOldMetadata() {
        val root = Files.createTempDirectory("synctone-version")
        val file = root.resolve("music.mp3")
        Files.copy(reference, file)
        DesktopLibraryRepository(root.resolve("db")).use { db ->
            val old = db.register(file).id
            Files.write(file, byteArrayOf(1), StandardOpenOption.APPEND)
            val new = db.register(file).id
            assertNotEquals(old, new)
            assertEquals(2, db.tracks().size)
            assertFalse(db.tracks().first { it.id == old }.available)
            assertTrue(db.tracks().first { it.id == new }.available)
        }
    }

    @Test fun sidecarChangesInvalidateCacheWithoutChangingAudioIdentity() {
        val root = Files.createTempDirectory("synctone-sidecar-cache")
        val file = root.resolve("music.mp3")
        Files.copy(reference, file)
        DesktopLibraryRepository(root.resolve("db")).use { db ->
            val id = db.register(file).id
            Files.writeString(root.resolve("music.lrc"), "[00:01]sidecar")
            val refresh = db.register(file)
            assertEquals(id, refresh.id)
            assertFalse(refresh.cached)
            assertEquals("sidecar", db.tracks().single().lyrics.lines.single().text)
        }
    }

    @Test fun recursiveScanningDeduplicatesSkipsBadFilesAndPersistsFolders() = runBlocking {
        val root = Files.createTempDirectory("synctone-scan")
        val music = Files.createDirectories(root.resolve("音乐"))
        val nested = Files.createDirectories(music.resolve("nested"))
        Files.copy(reference, music.resolve("song.mp3"))
        Files.copy(reference, nested.resolve("duplicate.MP3"))
        Files.writeString(nested.resolve("broken.flac"), "not audio")
        Files.writeString(nested.resolve("ignore.lrc"), "[00:01]lyrics")
        Files.writeString(nested.resolve("ignore.m3u"), "song.mp3")
        val data = root.resolve("data")
        val library = DesktopLibrary()
        var last = 0L
        try {
            library.initialize(data, emptyList())
            library.addFolder(music)
            waitFor { library.state.value.scan.phase == ScanPhase.Complete }
            val first = library.state.value
            assertEquals(1, first.tracks.size)
            assertEquals(3, first.scan.found)
            assertEquals(1, first.scan.added)
            assertEquals(1, first.scan.duplicates)
            assertEquals(1, first.scan.unreadable)
            last = first.scan.lastSuccessUtc
            library.addFolder(nested)
            waitFor { library.state.value.folders.size == 2 && library.state.value.scan.phase != ScanPhase.Scanning }
            assertEquals(3, library.state.value.scan.found)
            assertEquals(0, library.state.value.scan.added)
            assertEquals(2, library.state.value.scan.cached)
        } finally { library.close() }
        val restored = DesktopLibrary()
        try {
            restored.initialize(data, emptyList())
            assertEquals(2, restored.state.value.folders.size)
            assertEquals(ScanPhase.Complete, restored.state.value.scan.phase)
            assertTrue(restored.state.value.scan.lastSuccessUtc >= last)
            assertEquals(1, restored.state.value.tracks.size)
            restored.removeFolder(restored.state.value.folders.first().path)
            waitFor { restored.state.value.folders.size == 1 }
            assertEquals(1, restored.state.value.tracks.size)
        } finally { restored.close() }
    }

    @Test fun interruptedScanAndUnavailableFolderRemainVisible() = runBlocking {
        val root = Files.createTempDirectory("synctone-interrupted")
        val folder = Files.createDirectory(root.resolve("music"))
        val data = root.resolve("data")
        DesktopLibraryRepository(data).use { db ->
            db.addFolder(folder)
            db.saveScan(ScanProgress(ScanPhase.Scanning, added = 2, lastSuccessUtc = 123))
        }
        Files.delete(folder)
        val library = DesktopLibrary()
        try {
            library.initialize(data, emptyList())
            assertEquals(ScanPhase.Cancelled, library.state.value.scan.phase)
            assertEquals(123L, library.state.value.scan.lastSuccessUtc)
            library.scan()
            waitFor { library.state.value.scan.phase == ScanPhase.Failed }
            assertNotNull(library.state.value.folders.single().issue)
            Files.createDirectory(folder)
            library.scan()
            waitFor { library.state.value.scan.phase == ScanPhase.Complete }
            assertNull(library.state.value.folders.single().issue)
        } finally { library.close() }
    }

    @Test fun scanningCancellationCommitsPartialWorkAndCanRestart() = runBlocking {
        val root = Files.createTempDirectory("synctone-cancel")
        val folder = Files.createDirectory(root.resolve("music"))
        repeat(8) { index ->
            val file = folder.resolve("$index.mp3")
            Files.copy(reference, file)
            Files.write(file, byteArrayOf(index.toByte()), StandardOpenOption.APPEND)
        }
        val library = DesktopLibrary()
        try {
            library.initialize(root.resolve("db"), emptyList())
            library.addFolder(folder)
            waitFor { library.state.value.scan.phase == ScanPhase.Scanning }
            library.cancelScan()
            waitFor { library.state.value.scan.phase == ScanPhase.Cancelled }
            library.scan()
            waitFor { library.state.value.scan.phase == ScanPhase.Complete }
            assertEquals(8, library.state.value.tracks.size)
        } finally { library.close() }
    }

    @Test fun immediateCancellationAlwaysLeavesAScannableState() = runBlocking {
        val root = Files.createTempDirectory("synctone-cancel-immediate")
        val folder = Files.createDirectory(root.resolve("music"))
        val data = root.resolve("data")
        DesktopLibraryRepository(data).use { it.addFolder(folder) }
        val library = DesktopLibrary()
        try {
            library.initialize(data, emptyList())
            repeat(5) {
                library.scan()
                library.cancelScan()
                waitFor { library.state.value.scan.phase != ScanPhase.Scanning }
                assertTrue(library.state.value.scan.phase in listOf(ScanPhase.Cancelled, ScanPhase.Complete))
                delay(20)
            }
            library.scan()
            waitFor { library.state.value.scan.phase == ScanPhase.Complete }
        } finally { library.close() }
    }

    @Test fun newerDatabaseIsPreservedAndPlaybackLibraryCanStillInitialize() = runBlocking {
        val root = Files.createTempDirectory("synctone-future-db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:${root.resolve("library.db")}").use { db ->
            db.createStatement().use { it.execute("PRAGMA user_version=999") }
        }
        val library = DesktopLibrary()
        try {
            library.initialize(root, listOf(reference.toString()))
            assertTrue(library.state.value.ready)
            assertEquals(1, library.state.value.tracks.size)
            assertNotNull(library.state.value.error)
            assertEquals("", library.state.value.directory)
        } finally { library.close() }
        java.sql.DriverManager.getConnection("jdbc:sqlite:${root.resolve("library.db")}").use { db ->
            db.createStatement().use { sql -> sql.executeQuery("PRAGMA user_version").use { row -> row.next(); assertEquals(999, row.getInt(1)) } }
        }
    }

    @Test fun databaseUpgradePreservesAssetsAndOriginalPathWhenContentIsReplaced() {
        val root = Files.createTempDirectory("synctone-db-upgrade")
        val file = root.resolve("music.mp3")
        Files.copy(reference, file)
        val id = DesktopLibraryRepository(root).use { it.register(file).id }
        java.sql.DriverManager.getConnection("jdbc:sqlite:${root.resolve("library.db")}").use { db ->
            db.createStatement().use {
                it.executeUpdate("ALTER TABLE tracks DROP COLUMN original_path")
                it.executeUpdate("PRAGMA user_version=1")
            }
        }
        DesktopLibraryRepository(root).use { db ->
            assertEquals(id, db.tracks().single().id)
            assertEquals(60, db.tracks().single().lyrics.lines.size)
            Files.write(file, byteArrayOf(7), StandardOpenOption.APPEND)
            db.register(file)
            val old = db.tracks().first { it.id == id }
            assertFalse(old.available)
            assertEquals(file.toString().lowercase(), old.path.toString())
        }
    }
}
