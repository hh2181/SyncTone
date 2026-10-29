package dev.salt.desktop

import dev.salt.replica.lyrics.online.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Opt-in real network probe; prints counts only and never stores third-party lyrics. */
fun main() = runBlocking {
    val client = OkHttpClient.Builder().callTimeout(14, TimeUnit.SECONDS).build()
    try {
    val http = LyricHttp(client)
    val service = OnlineLyricsService(listOf(KugouLyrics(http), LrclibLyrics(http)))
    val song = LyricSong("network-probe", "ロックな君とはお別れだ", "ツユ", 208000)
    val result = service.search(song.query, song, LyricSource.Kugou)
    check(result.candidates.isNotEmpty()) { "Kugou search returned no candidates: ${result.warnings}" }
    val download = service.download(result.candidates.first(), song)
    check(download.wordTimed && download.lyrics.lines.isNotEmpty())
    val restored = OnlineLyricsCodec.fromLrc(OnlineLyricsCodec.toLrc(download.lyrics), song.durationMs)
    check(restored.lines.map { it.cells } == download.lyrics.lines.map { it.cells })
    println("Kugou live: ${result.candidates.size} candidates, ${download.lyrics.lines.size} lines, word timing and offline round-trip OK")
    val lrclib = service.search("Never Gonna Give You Up Rick Astley", song, LyricSource.Lrclib)
    check(lrclib.candidates.isNotEmpty()) { "LRCLIB search returned no timed candidates: ${lrclib.warnings}" }
    val row = service.download(lrclib.candidates.first(), song)
    check(row.lyrics.lines.isNotEmpty())
    println("LRCLIB live: ${lrclib.candidates.size} candidates, ${row.lyrics.lines.size} timed lines OK")
    } finally { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
}
