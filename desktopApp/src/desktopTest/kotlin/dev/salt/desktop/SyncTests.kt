package dev.salt.desktop

import dev.salt.desktop.library.DesktopLibrary
import dev.salt.desktop.statistics.DesktopStatistics
import dev.salt.desktop.sync.*
import dev.salt.replica.statistics.*
import dev.salt.replica.sync.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.Request
import org.junit.Test
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class SyncTests {
    private val reference get() = Path.of(System.getProperty("synctone.reference.path"))
    private suspend fun waitFor(predicate: () -> Boolean) = withTimeout(25_000) { while (!predicate()) delay(20) }

    @Test fun changedNetworkAddressRefreshesWithoutRestartingTheHttpsServer() = runBlocking {
        val root = Files.createTempDirectory("synctone-network-change")
        val library = DesktopLibrary()
        val statistics = DesktopStatistics()
        val address = java.util.concurrent.atomic.AtomicReference<InetAddress?>(null)
        val sync = DesktopSyncRepository(library, statistics, discoveryEnabled = false,
            allowLoopback = true, addressProvider = { address.get() ?: error("尚未连接局域网") })
        try {
            library.initialize(root, emptyList()); statistics.initialize(root); sync.initialize(root)
            sync.setPageOpen(true)
            waitFor { sync.state.value.phase == SyncPhase.FAILED }
            address.set(InetAddress.getByName("127.0.0.1"))
            waitFor { sync.state.value.serving && sync.state.value.phase == SyncPhase.READY }
            val port = sync.state.value.address.substringAfterLast(':')
            address.set(null)
            waitFor { !sync.state.value.serving && sync.state.value.address.isEmpty() }
            address.set(InetAddress.getByName("127.0.0.2"))
            waitFor { sync.state.value.address == "127.0.0.2:$port" }
            assertTrue(sync.state.value.serving)
            val client = SyncIdentity("network-probe", SyncPreferences(root.resolve("probe"))).client(null)
            try {
                client.newCall(Request.Builder().url("https://127.0.0.2:$port/identity").build()).execute().use {
                    assertEquals(200, it.code)
                }
            } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        } finally {
            sync.close(); statistics.close(System.currentTimeMillis(), System.nanoTime() / 1_000_000)
            library.close(); root.toFile().deleteRecursively()
        }
    }

    private class Device(val directory: Path) {
        val library = DesktopLibrary()
        val statistics = DesktopStatistics()
        val sync = DesktopSyncRepository(library, statistics, InetAddress.getLoopbackAddress(), false, true)
        suspend fun initialize() { library.initialize(directory, emptyList()); statistics.initialize(directory); sync.initialize(directory) }
        suspend fun close() { sync.close(); statistics.close(System.currentTimeMillis(), System.nanoTime() / 1_000_000); library.close() }
    }

    private suspend fun pair(sender: Device, receiver: Device) {
        sender.sync.setPageOpen(true); receiver.sync.setPageOpen(true)
        waitFor { sender.sync.state.value.serving && receiver.sync.state.value.serving }
        receiver.sync.connectAddress(sender.sync.state.value.address)
        waitFor { sender.sync.state.value.prompt != null && receiver.sync.state.value.prompt != null }
        assertEquals(sender.sync.state.value.prompt!!.code, receiver.sync.state.value.prompt!!.code)
        receiver.sync.confirmPair()
        delay(400)
        assertEquals(SyncPhase.WAITING, receiver.sync.state.value.phase)
        sender.sync.confirmPair()
        waitFor { receiver.sync.state.value.phase == SyncPhase.READY && receiver.sync.state.value.peer != null }
        delay(100)
    }

    private fun record(device: Device, title: String) {
        val track = StatTrack("local:$title", title, "Artist", "Album")
        device.statistics.history.metadata(listOf(track))
        val now = System.currentTimeMillis()
        val session = java.util.UUID.randomUUID().toString()
        device.statistics.history.record(RecordingBatch(ListeningSpan(session, track.id, now - 30_000, now, 30_000),
            CountedPlay(session, track.id, now)))
    }

    private suspend fun addDistinctSong(device: Device, suffix: Int) {
        val path = device.directory.resolve("song-$suffix.mp3")
        Files.copy(reference, path)
        Files.write(path, byteArrayOf(suffix.toByte()), java.nio.file.StandardOpenOption.APPEND)
        device.library.registerSynced(path, Files.newInputStream(path).use(::fileDigest))
    }

    @Test fun comparisonDetectsEqualCountsWithDifferentContentsAndMirrorsBothDevices() = runBlocking {
        val root = Files.createTempDirectory("synctone-comparison")
        val sender = Device(root.resolve("sender")); val receiver = Device(root.resolve("receiver"))
        try {
            sender.initialize(); receiver.initialize()
            addDistinctSong(sender, 1); addDistinctSong(receiver, 2)
            pair(sender, receiver)
            val summary = assertNotNull(receiver.sync.state.value.comparison)
            assertEquals(1, summary.localCount); assertEquals(1, summary.remoteCount)
            assertFalse(summary.identical); assertEquals(1, summary.missingLocal); assertEquals(1, summary.missingRemote)
            assertEquals(summary.reversed(), sender.sync.state.value.comparison)
            receiver.sync.begin()
            waitFor { receiver.sync.state.value.phase in listOf(SyncPhase.COMPLETE, SyncPhase.FAILED) }
            assertEquals(SyncPhase.COMPLETE, receiver.sync.state.value.phase, receiver.sync.state.value.error)
            val after = assertNotNull(receiver.sync.state.value.comparison)
            assertEquals(0, after.missingLocal); assertEquals(1, after.missingRemote); assertFalse(after.identical)
            assertEquals(after.reversed(), sender.sync.state.value.comparison)
            waitFor { !sender.sync.state.value.remoteSending }
            sender.sync.refreshComparison()
            waitFor { sender.sync.state.value.phase == SyncPhase.READY && sender.sync.state.value.songs.size == 1 }
            sender.sync.begin()
            waitFor { sender.sync.state.value.phase in listOf(SyncPhase.COMPLETE, SyncPhase.FAILED) }
            assertEquals(SyncPhase.COMPLETE, sender.sync.state.value.phase, sender.sync.state.value.error)
            assertTrue(assertNotNull(sender.sync.state.value.comparison).identical)
            assertTrue(assertNotNull(receiver.sync.state.value.comparison).identical)
        } finally { receiver.close(); sender.close(); root.toFile().deleteRecursively() }
    }

    @Test fun emptyReceiveListStillReportsWhatTheOtherDeviceIsMissing() = runBlocking {
        val root = Files.createTempDirectory("synctone-one-way-subset")
        val sender = Device(root.resolve("sender")); val receiver = Device(root.resolve("receiver"))
        try {
            sender.initialize(); receiver.initialize(); addDistinctSong(receiver, 3)
            pair(sender, receiver)
            assertTrue(receiver.sync.state.value.songs.isEmpty())
            val summary = assertNotNull(receiver.sync.state.value.comparison)
            assertEquals(0, summary.missingLocal); assertEquals(1, summary.missingRemote)
            assertFalse(summary.identical)
            assertEquals(summary.reversed(), sender.sync.state.value.comparison)
            sender.sync.refreshComparison()
            waitFor { sender.sync.state.value.phase == SyncPhase.READY && sender.sync.state.value.songs.size == 1 }
            assertNull(sender.sync.state.value.prompt)
        } finally { receiver.close(); sender.close(); root.toFile().deleteRecursively() }
    }

    @Test fun forgettingTrustAllowsPairingAgainWithoutRemovingMusic(): Unit = runBlocking {
        val root = Files.createTempDirectory("synctone-forget")
        val sender = Device(root.resolve("sender")); val receiver = Device(root.resolve("receiver"))
        try {
            sender.initialize(); receiver.initialize(); addDistinctSong(receiver, 4)
            pair(sender, receiver)
            receiver.sync.forgetPeer()
            waitFor { receiver.sync.state.value.peer == null }
            assertNull(receiver.sync.state.value.comparison)
            assertEquals(1, receiver.library.state.value.tracks.size)
            pair(sender, receiver)
            assertNotNull(receiver.sync.state.value.comparison)
        } finally { receiver.close(); sender.close(); root.toFile().deleteRecursively() }
    }

    @Test fun recompareRefreshesAChangedSourceInsteadOfReusingItsOldContentId() = runBlocking {
        val root = Files.createTempDirectory("synctone-source-change")
        val sender = Device(root.resolve("sender")); val receiver = Device(root.resolve("receiver"))
        try {
            sender.initialize(); receiver.initialize(); addDistinctSong(sender, 8)
            val path = sender.library.state.value.tracks.single().path
            val oldHash = sender.library.state.value.tracks.single().id
            pair(sender, receiver)
            assertEquals(oldHash, receiver.sync.state.value.songs.single().hash)
            Files.write(path, byteArrayOf(9), java.nio.file.StandardOpenOption.APPEND)
            receiver.sync.refreshComparison()
            waitFor { receiver.sync.state.value.phase == SyncPhase.READY && receiver.sync.state.value.songs.singleOrNull()?.hash != oldHash }
            assertEquals(Files.newInputStream(path).use(::fileDigest), receiver.sync.state.value.songs.single().hash)
        } finally { receiver.close(); sender.close(); root.toFile().deleteRecursively() }
    }

    @Test fun pausedTransferRetainsItsPlanAndCanResumeImmediately() = runBlocking {
        val root = Files.createTempDirectory("synctone-sync-pause")
        val sender = Device(root.resolve("sender")); val receiver = Device(root.resolve("receiver"))
        try {
            sender.initialize(); receiver.initialize()
            val hash = Files.newInputStream(reference).use(::fileDigest)
            sender.library.registerSynced(reference, hash)
            pair(sender, receiver)
            val pause = launch(Dispatchers.Unconfined) {
                receiver.sync.state.first { it.currentHash != null }
                receiver.sync.pause()
            }
            receiver.sync.begin()
            withTimeout(10_000) { pause.join() }
            assertEquals(SyncPhase.PAUSED, receiver.sync.state.value.phase)
            assertTrue(Files.readString(receiver.directory.resolve("sync-state.json")).contains("plan"))
            receiver.sync.begin()
            waitFor { receiver.sync.state.value.phase in listOf(SyncPhase.COMPLETE, SyncPhase.FAILED) }
            assertEquals(SyncPhase.COMPLETE, receiver.sync.state.value.phase, receiver.sync.state.value.error)
            assertEquals(hash, receiver.library.state.value.tracks.single().id)
        } finally { receiver.close(); sender.close(); root.toFile().deleteRecursively() }
    }

    @Test fun tlsPairingResumesPartialMusicAndExchangesHistoryWithoutDuplicates() = runBlocking {
        val root = Files.createTempDirectory("synctone-sync-loopback")
        val sender = Device(root.resolve("sender")); val receiver = Device(root.resolve("receiver"))
        try {
            sender.initialize(); receiver.initialize()
            val hash = Files.newInputStream(reference).use(::fileDigest)
            sender.library.registerSynced(reference, hash)
            record(sender, "Sender song"); record(receiver, "Receiver song")
            pair(sender, receiver)
            val song = receiver.sync.state.value.songs.single()
            assertEquals(hash, song.hash)
            val part = Files.createDirectories(receiver.directory.resolve("sync-partial")).resolve("$hash.part")
            Files.newInputStream(reference).use { input -> Files.write(part, input.readNBytes(123456)) }
            receiver.sync.begin()
            waitFor { receiver.sync.state.value.phase in listOf(SyncPhase.COMPLETE, SyncPhase.FAILED) }
            assertEquals(SyncPhase.COMPLETE, receiver.sync.state.value.phase, receiver.sync.state.value.error)
            assertFalse(Files.exists(part))
            assertEquals(hash, receiver.library.state.value.tracks.single().id)
            assertEquals(2, receiver.statistics.history.snapshot().totalPlays)
            assertEquals(2, sender.statistics.history.snapshot().totalPlays)
            assertEquals(60_000, receiver.statistics.history.snapshot().songs.sumOf { it.listenedMs })
            delay(100)
            receiver.sync.begin()
            waitFor { receiver.sync.state.value.phase in listOf(SyncPhase.COMPLETE, SyncPhase.FAILED) }
            assertEquals(0, receiver.sync.state.value.historyChanges)
            assertEquals(2, receiver.statistics.history.snapshot().totalPlays)
            assertEquals(1, receiver.library.state.value.tracks.size)
        } finally { receiver.close(); sender.close(); root.toFile().deleteRecursively() }
    }

    @Test fun invalidPartialIsRejectedAndTrustedDeviceCanReconnectAfterRestart() = runBlocking {
        val root = Files.createTempDirectory("synctone-sync-restart")
        val sender = Device(root.resolve("sender")); var receiver = Device(root.resolve("receiver"))
        try {
            sender.initialize(); receiver.initialize()
            val hash = Files.newInputStream(reference).use(::fileDigest)
            sender.library.registerSynced(reference, hash)
            pair(sender, receiver)
            val part = Files.createDirectories(receiver.directory.resolve("sync-partial")).resolve("$hash.part")
            Files.write(part, ByteArray(123456))
            receiver.sync.begin()
            waitFor { receiver.sync.state.value.phase == SyncPhase.FAILED }
            assertTrue(receiver.sync.state.value.error!!.contains("校验"))
            assertTrue(receiver.library.state.value.tracks.isEmpty())
            assertFalse(Files.exists(part))
            receiver.close()
            receiver = Device(root.resolve("receiver")); receiver.initialize()
            assertEquals(SyncPhase.PAUSED, receiver.sync.state.value.phase)
            receiver.sync.setPageOpen(true)
            receiver.sync.begin()
            waitFor { receiver.sync.state.value.phase in listOf(SyncPhase.COMPLETE, SyncPhase.FAILED) }
            assertEquals(SyncPhase.COMPLETE, receiver.sync.state.value.phase, receiver.sync.state.value.error)
            assertNull(receiver.sync.state.value.prompt)
            assertEquals(hash, receiver.library.state.value.tracks.single().id)
        } finally { receiver.close(); sender.close(); root.toFile().deleteRecursively() }
    }

    @Test fun unauthenticatedManifestAndChangedCertificateAreRejected(): Unit = runBlocking {
        val root = Files.createTempDirectory("synctone-sync-auth")
        val sender = Device(root.resolve("sender"))
        try {
            sender.initialize(); sender.sync.setPageOpen(true)
            waitFor { sender.sync.state.value.serving }
            val identity = SyncIdentity("probe", SyncPreferences(root.resolve("probe")))
            val client = identity.client(null)
            try {
                client.newCall(Request.Builder().url("https://${sender.sync.state.value.address}/manifest").build()).execute().use {
                    assertEquals(401, it.code)
                }
            } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
            val changed = identity.client("0".repeat(64))
            try {
                assertFailsWith<java.io.IOException> {
                    changed.newCall(Request.Builder().url("https://${sender.sync.state.value.address}/identity").build()).execute().close()
                }
            } finally { changed.connectionPool.evictAll(); changed.dispatcher.executorService.shutdown() }
        } finally { sender.close(); root.toFile().deleteRecursively() }
    }
}
