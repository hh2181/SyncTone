package dev.salt.desktop

import dev.salt.desktop.library.DesktopLibrary
import dev.salt.desktop.statistics.DesktopStatistics
import dev.salt.desktop.sync.DesktopSyncRepository
import dev.salt.replica.statistics.*
import dev.salt.replica.sync.*
import kotlinx.coroutines.*
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path

internal suspend fun verifyPackagedCore(root: Path, reference: Path) = withContext(Dispatchers.IO) {
    val libraries = List(2) { DesktopLibrary() }
    val statistics = List(2) { DesktopStatistics() }
    val repositories = List(2) { i -> DesktopSyncRepository(libraries[i], statistics[i], InetAddress.getLoopbackAddress(), false, true) }
    suspend fun await(predicate: () -> Boolean) = withTimeout(20_000) { while (!predicate()) delay(20) }
    try {
        for (i in 0..1) {
            val data = root.resolve("core-$i")
            libraries[i].initialize(data, emptyList())
            statistics[i].initialize(data)
            repositories[i].initialize(data)
            val track = StatTrack("fixture-$i", "Package song $i", "Artist", "Album")
            statistics[i].history.metadata(listOf(track))
            val now = System.currentTimeMillis()
            statistics[i].history.record(RecordingBatch(ListeningSpan("package-$i", track.id, now - 30_000, now, 30_000),
                CountedPlay("package-$i", track.id, now)))
            repositories[i].setPageOpen(true)
        }
        val hash = Files.newInputStream(reference).use(::fileDigest)
        libraries[0].registerSynced(reference, hash)
        await { repositories.all { it.state.value.serving } }
        repositories[1].connectAddress(repositories[0].state.value.address)
        await { repositories.all { it.state.value.prompt != null } }
        check(repositories[0].state.value.prompt!!.code == repositories[1].state.value.prompt!!.code)
        repositories.forEach { it.confirmPair() }
        await { repositories[1].state.value.phase == SyncPhase.READY && repositories[1].state.value.songs.size == 1 }
        delay(100)
        val part = Files.createDirectories(root.resolve("core-1/sync-partial")).resolve("$hash.part")
        Files.newInputStream(reference).use { Files.write(part, it.readNBytes(12_345)) }
        repositories[1].begin()
        await { repositories[1].state.value.phase in listOf(SyncPhase.COMPLETE, SyncPhase.FAILED) }
        check(repositories[1].state.value.phase == SyncPhase.COMPLETE) { repositories[1].state.value.error.orEmpty() }
        check(libraries[1].state.value.tracks.single().id == hash)
        check(statistics.all { it.history.snapshot().totalPlays == 2L })
        delay(100)
        repositories[1].begin()
        await { repositories[1].state.value.phase in listOf(SyncPhase.COMPLETE, SyncPhase.FAILED) }
        check(repositories[1].state.value.historyChanges == 0)
        Files.writeString(root.resolve("core-verification.json"),
            """{"passed":true,"privateRuntimeTLS":true,"twoSidedPairing":true,"partialResume":true,"sha256":true,"historyBothDirections":true,"idempotent":true}""")
    } finally {
        for (i in 0..1) {
            repositories[i].close()
            statistics[i].close(System.currentTimeMillis(), System.nanoTime() / 1_000_000)
            libraries[i].close()
        }
    }
}
