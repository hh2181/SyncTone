package dev.salt.replica.lyrics.online

import dev.salt.replica.lyrics.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class LyricsUpdateTest {
    private val song = LyricSong("one", "Sample", "Artist", 120000)
    private fun candidate(song: LyricSong, source: LyricSource = LyricSource.Kugou) =
        LyricCandidate(source, song.id, song.title, song.artist, song.durationMs)
    private fun words(candidate: LyricCandidate) = LyricDownload(candidate, Lyrics(listOf(
        LyricLine(1000, 3000, listOf(LyricCell(1000, 2000, "測試"), LyricCell(2000, 3000, "原文")), listOf(
            LyricSubLine(listOf(LyricCell(1000, 3000, "translation")), LyricContent.Translation),
            LyricSubLine(listOf(LyricCell(1000, 2000, "test "), LyricCell(2000, 3000, "roman")), LyricContent.Romanization)))
    ), "ONLINE_KUGOU"))
    private fun lines(candidate: LyricCandidate) = LyricDownload(candidate, LyricsParser.parse("[00:01]Only line timing", 120000))
    private fun provider(source: LyricSource = LyricSource.Kugou, search: suspend (LyricSong) -> Unit = {},
                         download: (LyricCandidate) -> LyricDownload = ::words) = object : LyricProvider {
        override val source = source
        override suspend fun search(query: String, song: LyricSong): List<LyricCandidate> { search(song); return listOf(candidate(song, source)) }
        override suspend fun download(candidate: LyricCandidate, durationMs: Long) = download(candidate)
    }
    private fun fixture(block: suspend CoroutineScope.(java.io.File) -> Unit) = runBlocking {
        val directory = Files.createTempDirectory("synctone-update-test").toFile()
        val worker = CoroutineScope(coroutineContext + SupervisorJob())
        try { worker.block(directory) } finally { worker.coroutineContext[Job]?.cancelAndJoin(); directory.deleteRecursively() }
    }
    private suspend fun OnlineLyricsController.finished() = withTimeout(5000) { state.first { it.batch.phase == LyricsBatchPhase.Complete } }

    @Test fun preferencesMigrateAndRoundTripWithoutLosingOrderOrSourceChoices() = fixture { root ->
        val file = root.resolve("preferences")
        file.writeText("true")
        assertTrue(LyricsMatchSettings.read(file).automatic)
        val settings = LyricsMatchSettings(minimumScore = 94, wordOnly = false, requireRomanization = true,
            sources = setOf(LyricSource.Lrclib), priority = listOf(LyricSource.Lrclib, LyricSource.Kugou),
            contentOrder = listOf(LyricContent.Translation, LyricContent.Romanization, LyricContent.Original),
            contents = setOf(LyricContent.Translation, LyricContent.Original))
        file.writeText(settings.encode())
        assertEquals(settings, LyricsMatchSettings.read(file))
        assertEquals(100, settings.copy(minimumScore = 200).normalized().minimumScore)
    }

    @Test fun contentKindsAndReorderingSurviveAndroidOfflineSerialization() {
        val raw = words(candidate(song))
        val settings = LyricsMatchSettings(contentOrder = listOf(LyricContent.Romanization, LyricContent.Original, LyricContent.Translation))
        val arranged = LyricsMatching.arrange(raw, settings)
        assertEquals(LyricContent.Romanization, arranged.lyrics.lines.single().kind)
        assertEquals(listOf(LyricContent.Original, LyricContent.Translation), arranged.lyrics.lines.single().subLines.map { it.kind })
        assertEquals(arranged.lyrics, OnlineLyricsCodec.fromLrc(OnlineLyricsCodec.toLrc(arranged.lyrics), song.durationMs))
        assertNull(LyricsMatching.contentFailure(raw, settings.copy(requireRomanization = true, requireTranslation = true)))
        assertNotNull(LyricsMatching.contentFailure(lines(candidate(song)), settings.copy(wordOnly = true)))
    }

    @Test fun similarityUsesRealMetadataAndStillRejectsDurationAndUnknownArtist() {
        val exact = candidate(song)
        assertEquals(100, LyricsMatching.score(song, exact))
        assertTrue(LyricsMatching.score(song, exact.copy(title = "Sample Live")) < 90)
        assertNotNull(LyricsMatching.metadataFailure(song, exact.copy(durationMs = 125000), LyricsMatchSettings(minimumScore = 60)))
        assertNotNull(LyricsMatching.metadataFailure(song.copy(artist = ""), exact, LyricsMatchSettings(minimumScore = 60)))
    }

    @Test fun batchAppliesRealWordTimingKeepsLocalAndAllowsManualReview() = fixture { root ->
        val songs = (1..3).map { song.copy(id = "$it") }
        val searched = mutableListOf<String>(); val saved = mutableListOf<String>()
        val controller = OnlineLyricsController(this, root.resolve("prefs"), { target, _, _ -> saved += target.id },
            OnlineLyricsService(listOf(provider(search = { searched += it.id }, download = { if (it.id == "2") lines(it) else words(it) }))),
            hasLyrics = { it.id == "3" })
        controller.updateLibrary(songs, "1"); controller.setSelection(songs.map { it.id }.toSet()); controller.startBatch()
        val done = controller.finished().batch
        assertEquals(listOf(LyricsBatchStatus.Applied, LyricsBatchStatus.Review, LyricsBatchStatus.Kept), done.entries.map { it.status })
        assertEquals(listOf("1"), saved); assertFalse("3" in searched)
        controller.bind(songs[1]); controller.search(songs[1].query, null)
        withTimeout(3000) { controller.state.first { it.searched && !it.busy } }
        controller.preview(controller.state.value.results.single())
        withTimeout(3000) { controller.state.first { it.preview != null && !it.busy } }
        controller.savePreview()
        withTimeout(3000) { controller.state.first { it.saved && !it.busy } }
        assertEquals(LyricsBatchStatus.Applied, controller.state.value.batch.entries[1].status)
        assertEquals(listOf("1", "2"), saved)
    }

    @Test fun batchHonorsEnabledSourcesAndPriority() = fixture { root ->
        val saved = mutableListOf<LyricSource>()
        val controller = OnlineLyricsController(this, root.resolve("prefs"), { _, download, _ -> saved += download.candidate.source },
            OnlineLyricsService(listOf(provider(), provider(LyricSource.Lrclib))))
        controller.updateLibrary(listOf(song), song.id)
        controller.updateSettings(LyricsMatchSettings(priority = listOf(LyricSource.Lrclib, LyricSource.Kugou)))
        controller.startBatch(); controller.finished()
        assertEquals(listOf(LyricSource.Lrclib), saved)
        controller.updateSettings(controller.state.value.settings.copy(sources = setOf(LyricSource.Kugou)))
        controller.startBatch(); controller.finished()
        assertEquals(listOf(LyricSource.Lrclib, LyricSource.Kugou), saved)
    }

    @Test fun pauseCancelsNetworkAndResumeKeepsFrozenRulesAndCompletedSongs() = fixture { root ->
        val started = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
        val saved = mutableListOf<String>(); var requests = 0
        val songs = listOf(song, song.copy(id = "two"))
        val slow = provider(search = {
            if (it.id == "two" && requests++ == 0) {
                started.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
        }, download = { if (it.id == "two") lines(it) else words(it) })
        val controller = OnlineLyricsController(this, root.resolve("prefs"), { target, _, _ -> saved += target.id }, OnlineLyricsService(listOf(slow)))
        controller.updateLibrary(songs, song.id); controller.setSelection(songs.map { it.id }.toSet()); controller.startBatch()
        withTimeout(3000) { started.await() }
        controller.pauseBatch(); withTimeout(3000) { cancelled.await() }
        assertEquals(LyricsBatchPhase.Paused, controller.state.value.batch.phase)
        controller.updateSettings(controller.state.value.settings.copy(wordOnly = false))
        controller.startBatch(); val done = controller.finished()
        assertEquals(listOf("one"), saved)
        assertEquals(LyricsBatchStatus.Review, done.batch.entries[1].status)
        assertTrue(done.batch.settings!!.wordOnly)
    }

    @Test fun aLocalImportThatWinsTheSaveRaceIsPreserved() = fixture { root ->
        val controller = OnlineLyricsController(this, root.resolve("prefs"), { _, _, _ -> throw LyricsAlreadyPresentException() },
            OnlineLyricsService(listOf(provider())))
        controller.updateLibrary(listOf(song), song.id); controller.startBatch()
        assertEquals(LyricsBatchStatus.Kept, controller.finished().batch.entries.single().status)
    }
}
