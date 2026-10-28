package dev.salt.replica.sync

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SyncLogicTest {
    private fun song(value: Int, name: String = "Song") = SyncSong(value.toString(16).padStart(64, '0'), name, "Artist", "Album", "$name.mp3", 100, false)
    private fun page(songs: List<SyncSong>, total: Int = songs.size, snapshot: String = "snapshot") = JSONObject()
        .put("v", SYNC_PROTOCOL).put("snapshot", snapshot).put("total", total).put("songs", JSONArray(songs.map { it.json() }))

    @Test fun noSongsToReceiveDoesNotMeanLibrariesAreIdentical() {
        val difference = compareSyncLibraries(listOf(song(1), song(2)), listOf(song(1)))
        assertTrue(difference.receive.isEmpty())
        assertFalse(difference.summary.identical)
        assertEquals(1, difference.summary.missingRemote)
        assertEquals(1, difference.send.size)
        assertTrue(syncEmptyLibraryText(difference.summary).contains("对方还缺 1 首"))
    }
    @Test fun sameCountsWithDifferentContentsAreDifferentInBothDirections() {
        val summary = compareSyncLibraries(listOf(song(1)), listOf(song(2))).summary
        assertFalse(summary.identical); assertEquals(0, summary.commonCount)
        assertEquals(1, summary.missingLocal); assertEquals(1, summary.missingRemote)
        assertEquals(summary, summary.reversed().reversed())
        assertEquals(summary, SyncComparison.parse(summary.json()))
    }
    @Test fun namesOrderAndDuplicateRegistrationsDoNotAffectContentEquality() {
        val local = listOf(song(1), song(2), song(1))
        val remote = listOf(song(2, "Renamed"), song(1, "Other filename"))
        val result = compareSyncLibraries(local, remote)
        assertTrue(result.summary.identical); assertEquals(2, result.summary.localCount)
        assertTrue(result.receive.isEmpty()); assertTrue(result.send.isEmpty())
        assertTrue(compareSyncLibraries(emptyList(), emptyList()).summary.identical)
    }
    @Test fun comparisonRejectsImpossibleCountsAndConflictingRoots() {
        val valid = compareSyncLibraries(listOf(song(1)), listOf(song(2))).summary.json()
        assertThrows(IllegalArgumentException::class.java) { SyncComparison.parse(JSONObject(valid.toString()).put("commonCount", 2)) }
        assertThrows(IllegalArgumentException::class.java) { SyncComparison.parse(JSONObject(valid.toString()).put("remoteRoot", valid.getString("localRoot"))) }
        assertThrows(IllegalArgumentException::class.java) { SyncComparison.parse(JSONObject(valid.toString()).put("commonCount", 1)) }
    }
    @Test fun manifestReadsMultiplePagesAndValidatesTheSameSnapshot() = runBlocking {
        val songs = (1..201).map(::song); var calls = 0
        val result = readSyncManifest { offset, token ->
            assertEquals(if (calls == 0) "" else "snapshot", token); calls++
            page(songs.drop(offset).take(200), songs.size)
        }
        assertEquals(songs, result); assertEquals(2, calls)
        assertTrue(readSyncManifest { _, _ -> page(emptyList()) }.isEmpty())
    }
    @Test fun manifestRejectsSnapshotChangesOversizedAndRepeatedPages() {
        val first = (1..200).map(::song)
        assertThrows(IllegalStateException::class.java) { runBlocking {
            readSyncManifest { offset, _ -> if (offset == 0) page(first, 201) else page(listOf(song(201)), 201, "changed") }
        } }
        assertThrows(IllegalStateException::class.java) { runBlocking {
            readSyncManifest { offset, _ -> if (offset == 0) page(first, 201) else page(listOf(song(1)), 201) }
        } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { readSyncManifest { _, _ -> page((1..201).map(::song)) } } }
        assertThrows(IllegalStateException::class.java) { runBlocking { readSyncManifest { _, _ -> page(emptyList(), 1) } } }
    }
    @Test fun manifestRejectsChangedTotalOverrunAndOldProtocol() {
        assertThrows(IllegalStateException::class.java) { runBlocking {
            readSyncManifest { offset, _ -> if (offset == 0) page((1..200).map(::song), 202) else page(listOf(song(201)), 201) }
        } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { readSyncManifest { _, _ -> page(listOf(song(1)), 0) } } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { readSyncManifest { _, _ -> page(emptyList()).put("v", SYNC_PROTOCOL - 1) } } }
    }
    @Test fun historyRejectsResetEpochAndNonAdvancingFullPages() {
        val empty = JSONObject().put("epoch", "epoch").put("next", 3).put("records", JSONArray())
        assertEquals(3L, parseSyncHistoryBatch(empty, "epoch", 3).next)
        assertThrows(IllegalStateException::class.java) { parseSyncHistoryBatch(empty, "new-epoch", 3) }
        assertThrows(IllegalStateException::class.java) { parseSyncHistoryBatch(empty.put("next", 2), "epoch", 3) }
        val invalid = JSONObject().put("epoch", "epoch").put("next", 3).put("records", JSONArray().put(JSONObject()))
        assertThrows(IllegalStateException::class.java) { parseSyncHistoryBatch(invalid, "epoch", 3) }
        assertThrows(IllegalArgumentException::class.java) { parseSyncHistoryBatch(invalid.put("next", 4).put("records", JSONArray((1..201).map { JSONObject() })), "epoch", 3) }
    }
    @Test fun manualAddressRejectsCredentialsPathsDomainsAndUnexpectedSchemes() {
        assertEquals(SyncAddress("192.168.1.2", 1234), parseSyncAddress(" https://192.168.1.2:1234/ "))
        for (invalid in listOf("http://192.168.1.2:1234", "192.168.1.2", "192.168.1.2:65536", "user@192.168.1.2:1234", "192.168.1.2:1234/file", "192.168.1.2:1234?token=x", "localhost:1234")) {
            assertThrows(invalid, IllegalArgumentException::class.java) { parseSyncAddress(invalid) }
        }
    }
    @Test fun bootstrapIdentityRequiresProtocolCertificateAndAnotherDevice() {
        val address = SyncAddress("192.168.1.2", 1234); val pin = "a".repeat(64)
        fun identity() = JSONObject().put("v", SYNC_PROTOCOL).put("id", "remote").put("name", "Remote").put("pin", pin)
        assertEquals("remote", parseSyncIdentity(identity(), pin, "local", address).id)
        assertThrows(IllegalArgumentException::class.java) { parseSyncIdentity(identity(), "b".repeat(64), "local", address) }
        assertThrows(IllegalArgumentException::class.java) { parseSyncIdentity(identity(), pin, "remote", address) }
        assertThrows(IllegalArgumentException::class.java) { parseSyncIdentity(identity().put("v", 1), pin, "local", address) }
    }
    @Test fun jsonReadingIsBoundedInBytesAndAcceptsExactLimit() {
        assertEquals(SYNC_MAX_JSON, ByteArray(SYNC_MAX_JSON) { 32 }.inputStream().readBoundedSyncJson().length)
        assertThrows(IllegalStateException::class.java) { ByteArray(SYNC_MAX_JSON + 1).inputStream().readBoundedSyncJson() }
    }
    @Test fun fingerprintReuseRequiresReliableUnchangedSourceMetadata() {
        val stamp = SyncSourceStamp(100, 1000, 1)
        assertTrue(stamp.reusableFor(stamp))
        assertFalse(stamp.reusableFor(stamp.copy(size = 101)))
        assertFalse(stamp.reusableFor(stamp.copy(modified = 2000)))
        assertFalse(stamp.reusableFor(stamp.copy(generation = 2)))
        assertFalse(SyncSourceStamp(100, 0).reusableFor(SyncSourceStamp(100, 0)))
        assertTrue(SyncSourceStamp(100, 0, 1).reusableFor(SyncSourceStamp(100, 0, 1)))
    }
    @Test fun filteredSelectionOnlyChangesVisibleValidSongs() {
        val all = listOf(song(1), song(2), song(3)); val selected = setOf(song(1).hash, song(3).hash)
        assertEquals(setOf(song(3).hash), toggleSyncSelection(selected, all, setOf(song(1).hash)))
        assertEquals(all.map { it.hash }.toSet(), toggleSyncSelection(selected, all, setOf(song(2).hash)))
        assertEquals(selected, toggleSyncSelection(selected, all, setOf("not-a-song")))
    }
}
