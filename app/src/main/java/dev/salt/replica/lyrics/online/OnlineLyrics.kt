package dev.salt.replica.lyrics.online

import dev.salt.replica.lyrics.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.Normalizer
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

data class LyricSong(val id: String, val title: String, val artist: String, val durationMs: Long) {
    val query: String get() = "$title $artist".trim()
}

enum class LyricSource(val label: String) { Kugou("酷狗"), Lrclib("LRCLIB") }

data class LyricCandidate(val source: LyricSource, val id: String, val title: String,
    val artist: String, val durationMs: Long, val accessKey: String = "", val syncedText: String = "") {
    val key: String get() = "${source.name}:$id"
}
data class LyricSearchResult(val candidates: List<LyricCandidate>, val warnings: List<String>)
data class LyricDownload(val candidate: LyricCandidate, val lyrics: Lyrics) {
    val wordTimed: Boolean get() = lyrics.lines.any { it.cells.count { c -> c.text.isNotBlank() && c.end > c.start } > 1 }
}

interface LyricProvider {
    val source: LyricSource
    suspend fun search(query: String, song: LyricSong): List<LyricCandidate>
    suspend fun download(candidate: LyricCandidate, durationMs: Long): LyricDownload
}

/** One bounded HTTP pool, no disk cache, cookies or music/audio downloads. */
class LyricHttp(private val client: OkHttpClient = sharedClient) {
    suspend fun get(url: HttpUrl): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url)
            .header("User-Agent", "SyncTone/0.7 (https://github.com/hh2181/SyncTone)")
            .header("Accept", "application/json").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val text = response.use {
                        if (!it.isSuccessful) throw IOException("服务返回 HTTP ${it.code}")
                        val body = it.body ?: throw IOException("服务返回空内容")
                        if (body.contentLength() > MAX_RESPONSE) throw IOException("歌词响应过大")
                        body.byteStream().use { input ->
                            val out = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (out.size() + count > MAX_RESPONSE) throw IOException("歌词响应过大")
                                out.write(buffer, 0, count)
                            }
                            out.toString("UTF-8")
                        }
                    }
                    if (continuation.isActive) continuation.resume(text)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }
    companion object {
        const val MAX_RESPONSE = 2 * 1024 * 1024
        private val sharedClient = OkHttpClient.Builder().connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS).callTimeout(14, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).build()
    }
}

private fun url(base: String, vararg values: Pair<String, String>): HttpUrl = base.toHttpUrl().newBuilder().apply {
    values.forEach { (key, value) -> addQueryParameter(key, value) }
}.build()

class KugouLyrics(private val http: LyricHttp = LyricHttp()) : LyricProvider {
    override val source = LyricSource.Kugou
    override suspend fun search(query: String, song: LyricSong): List<LyricCandidate> {
        val data = JSONObject(http.get(url("https://lyrics.kugou.com/search", "ver" to "1",
            "man" to "yes", "client" to "pc", "keyword" to query)))
        check(data.optInt("status") == 200) { "歌词源暂不可用" }
        val rows = data.optJSONArray("candidates") ?: return emptyList()
        return (0 until minOf(rows.length(), 30)).mapNotNull { i ->
            val row = rows.optJSONObject(i) ?: return@mapNotNull null
            val id = row.optString("id")
            val key = row.optString("accesskey")
            if (id.isBlank() || key.isBlank()) null else LyricCandidate(source, id,
                row.optString("song"), row.optString("singer"), row.optLong("duration"), key)
        }
    }
    override suspend fun download(candidate: LyricCandidate, durationMs: Long): LyricDownload {
        val data = JSONObject(http.get(url("https://lyrics.kugou.com/download", "ver" to "1",
            "client" to "pc", "id" to candidate.id, "accesskey" to candidate.accessKey,
            "fmt" to "krc", "charset" to "utf8")))
        val content = data.optString("content")
        require(content.isNotBlank()) { "该版本暂无可下载歌词" }
        val bytes = java.util.Base64.getDecoder().decode(content)
        val lyrics = if (data.optInt("contenttype") == 2)
            LyricsParser.parse(bytes.toString(Charsets.UTF_8), durationMs, "ONLINE_KUGOU")
        else OnlineLyricsCodec.parseKrc(OnlineLyricsCodec.decryptKrc(bytes))
        require(lyrics.lines.any { it.text.isNotBlank() }) { "该版本没有有效的同步歌词" }
        return LyricDownload(candidate, lyrics)
    }
}

class LrclibLyrics(private val http: LyricHttp = LyricHttp()) : LyricProvider {
    override val source = LyricSource.Lrclib
    override suspend fun search(query: String, song: LyricSong): List<LyricCandidate> {
        val rows = JSONArray(http.get(url("https://lrclib.net/api/search", "q" to query)))
        return (0 until minOf(rows.length(), 30)).mapNotNull { i ->
            val row = rows.optJSONObject(i) ?: return@mapNotNull null
            // Unsynchronised text cannot follow the playback timeline.
            if (row.optString("syncedLyrics").let { it.isBlank() || it == "null" }) null
            else LyricCandidate(source, row.getLong("id").toString(), row.optString("trackName"),
                row.optString("artistName"), (row.optDouble("duration", 0.0) * 1000).toLong(),
                syncedText = row.optString("syncedLyrics"))
        }
    }
    override suspend fun download(candidate: LyricCandidate, durationMs: Long): LyricDownload {
        require(candidate.id.all(Char::isDigit))
        // Search already supplies the selected record's complete LRC. Keep it only in the
        // bounded search result, avoiding another request (and an unnecessary failure point).
        val text = candidate.syncedText.ifBlank {
            JSONObject(http.get("https://lrclib.net/api/get/${candidate.id}".toHttpUrl())).optString("syncedLyrics")
        }
        val lyrics = LyricsParser.parse(text, durationMs, "ONLINE_LRCLIB")
        require(lyrics.lines.any { it.text.isNotBlank() }) { "该版本暂无同步歌词" }
        return LyricDownload(candidate, lyrics)
    }
}

class OnlineLyricsService(private val providers: List<LyricProvider> = listOf(KugouLyrics(), LrclibLyrics())) {
    suspend fun search(query: String, song: LyricSong, source: LyricSource? = null,
                       sources: Set<LyricSource> = LyricSource.entries.toSet()): LyricSearchResult = supervisorScope {
        require(query.trim().length in 1..200) { "请输入 1–200 字的歌名或歌手" }
        val replies = providers.filter { it.source in sources && (source == null || it.source == source) }.map { provider -> async(Dispatchers.IO) {
            try { provider.search(query.trim(), song) to null }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { emptyList<LyricCandidate>() to "${provider.source.label} 暂时无法连接，可重试或切换来源" }
        } }.awaitAll()
        LyricSearchResult(replies.flatMap { it.first }.distinctBy { it.key }.sortedByDescending { rank(song, it) },
            replies.mapNotNull { it.second })
    }
    suspend fun download(candidate: LyricCandidate, song: LyricSong): LyricDownload = withContext(Dispatchers.IO) {
        providers.first { it.source == candidate.source }.download(candidate, song.durationMs)
    }
    companion object {
        private fun normalized(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
            .lowercase(java.util.Locale.ROOT).filter(Char::isLetterOrDigit)
        fun confident(song: LyricSong, candidate: LyricCandidate): Boolean =
            normalized(song.title).isNotEmpty() && normalized(song.artist).isNotEmpty() &&
                normalized(song.title) == normalized(candidate.title) &&
                normalized(song.artist) == normalized(candidate.artist) &&
                song.durationMs > 0 && candidate.durationMs > 0 && abs(song.durationMs - candidate.durationMs) <= 2500
        fun rank(song: LyricSong, candidate: LyricCandidate): Int = LyricsMatching.score(song, candidate)
    }
}
