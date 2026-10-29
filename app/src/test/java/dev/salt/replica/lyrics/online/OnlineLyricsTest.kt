package dev.salt.replica.lyrics.online

import dev.salt.replica.lyrics.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.Base64
import java.util.zip.DeflaterOutputStream
import java.io.ByteArrayOutputStream

class OnlineLyricsTest {
    private val song = LyricSong("local-a", "Example", "Singer", 120000)
    private val candidate = LyricCandidate(LyricSource.Kugou, "12", "Example", "Singer", 120000, "test")

    @Test fun krcWordsGapsTranslationsAndOfflineRoundTrip() {
        val metadata = """{"content":[{"type":1,"lyricContent":[["测试译文"]]}]}"""
        val text = "[offset:100]\n[language:${Base64.getEncoder().encodeToString(metadata.toByteArray())}]\n" +
            "[1000,2000]<0,400,0>One <1000,1000,0>two\n"
        val compressed = ByteArrayOutputStream().apply {
            DeflaterOutputStream(this).use { it.write(text.toByteArray()) }
        }.toByteArray()
        val key = intArrayOf(64,71,97,119,94,50,116,71,81,54,49,45,206,210,110,105)
        val bytes = "krc1".toByteArray() + ByteArray(compressed.size) { (compressed[it].toInt() xor key[it % 16]).toByte() }
        val lyrics = OnlineLyricsCodec.parseKrc(OnlineLyricsCodec.decryptKrc(bytes))
        assertEquals(1100L, lyrics.lines.single().start)
        assertEquals(2100L, lyrics.lines.single().cells[1].start)
        assertEquals("测试译文", lyrics.lines.single().translation)
        assertEquals(lyrics, OnlineLyricsCodec.fromLrc(OnlineLyricsCodec.toLrc(lyrics), 120000))
    }

    @Test fun invalidOrOversizedKrcIsRejected() {
        assertTrue(runCatching { OnlineLyricsCodec.decryptKrc("not krc".toByteArray()) }.isFailure)
        val compressed = ByteArrayOutputStream().apply {
            DeflaterOutputStream(this).use { it.write(ByteArray(LyricHttp.MAX_RESPONSE + 1)) }
        }.toByteArray()
        val key = intArrayOf(64,71,97,119,94,50,116,71,81,54,49,45,206,210,110,105)
        val bytes = "krc1".toByteArray() + ByteArray(compressed.size) { (compressed[it].toInt() xor key[it % 16]).toByte() }
        assertTrue(runCatching { OnlineLyricsCodec.decryptKrc(bytes) }.isFailure)
    }

    @Test fun automaticMatchRejectsWrongVersionOrUnknownMetadata() {
        assertTrue(OnlineLyricsService.confident(song, candidate))
        assertFalse(OnlineLyricsService.confident(song, candidate.copy(durationMs = 125000)))
        assertFalse(OnlineLyricsService.confident(song, candidate.copy(title = "Example (Live)")))
        assertFalse(OnlineLyricsService.confident(song.copy(artist = ""), candidate))
        assertFalse(OnlineLyricsService.confident(song.copy(durationMs = 0), candidate))
    }

    @Test fun oneSourceFailureStillReturnsOtherSource() = runBlocking {
        val failed = object : LyricProvider {
            override val source = LyricSource.Lrclib
            override suspend fun search(query: String, song: LyricSong): List<LyricCandidate> = error("offline")
            override suspend fun download(candidate: LyricCandidate, durationMs: Long): LyricDownload = error("offline")
        }
        val result = OnlineLyricsService(listOf(provider(), failed)).search(song.query, song)
        assertEquals(listOf(candidate), result.candidates)
        assertEquals(1, result.warnings.size)
    }

    @Test fun lrclibUsesSelectedSearchContentWithoutAnotherNetworkRequest() = runBlocking {
        val client = okhttp3.OkHttpClient.Builder().addInterceptor { error("Unexpected network request") }.build()
        try {
            val row = candidate.copy(source = LyricSource.Lrclib, syncedText = "[00:01]Synthetic line")
            val download = LrclibLyrics(LyricHttp(client)).download(row, song.durationMs)
            assertEquals("Synthetic line", download.lyrics.lines.single().text)
            assertEquals("ONLINE_LRCLIB", download.lyrics.source)
        } finally { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
    }

    @Test fun trackSwitchCancelsOldRequestAndSaveUsesBoundTrack() = runBlocking {
        val directory = Files.createTempDirectory("synctone-online-test").toFile()
        val worker = CoroutineScope(coroutineContext + SupervisorJob())
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val slow = object : LyricProvider by provider() {
            override suspend fun search(query: String, song: LyricSong): List<LyricCandidate> {
                if (query != "slow") return listOf(candidate)
                started.complete(Unit)
                try { awaitCancellation() } finally { stopped.complete(Unit) }
            }
        }
        var savedId: String? = null
        try {
            val controller = OnlineLyricsController(worker, directory.resolve("preference"),
                { target, _, _ -> savedId = target.id }, OnlineLyricsService(listOf(slow)))
            controller.bind(song)
            controller.search("slow", null)
            withTimeout(3000) { started.await() }
            val next = song.copy(id = "local-b")
            controller.bind(next)
            withTimeout(3000) { stopped.await() }
            assertTrue(controller.state.value.results.isEmpty())
            assertFalse(controller.state.value.busy)
            controller.search("fast", null)
            withTimeout(3000) { controller.state.first { it.searched && !it.busy } }
            controller.preview(candidate)
            withTimeout(3000) { controller.state.first { it.preview != null && !it.busy } }
            controller.savePreview()
            withTimeout(3000) { controller.state.first { it.saved && !it.busy } }
            assertEquals("local-b", savedId)
        } finally { worker.cancel(); directory.deleteRecursively() }
    }

    private fun provider(): LyricProvider = object : LyricProvider {
        override val source = LyricSource.Kugou
        override suspend fun search(query: String, song: LyricSong) = listOf(candidate)
        override suspend fun download(candidate: LyricCandidate, durationMs: Long) =
            LyricDownload(candidate, LyricsParser.parse("[00:01]<00:01>Test<00:02> words<00:03>", durationMs))
    }
}
