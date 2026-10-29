package dev.salt.replica.lyrics.online

import dev.salt.replica.lyrics.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.roundToInt

data class LyricsMatchSettings(
    val automatic: Boolean = false,
    val minimumScore: Int = 90,
    val wordOnly: Boolean = true,
    val requireTranslation: Boolean = false,
    val requireRomanization: Boolean = false,
    val durationToleranceMs: Long = 2500,
    val overwrite: Boolean = false,
    val sources: Set<LyricSource> = LyricSource.entries.toSet(),
    val priority: List<LyricSource> = LyricSource.entries.toList(),
    val contentOrder: List<LyricContent> = listOf(LyricContent.Original, LyricContent.Romanization, LyricContent.Translation),
    val contents: Set<LyricContent> = LyricContent.entries.toSet(),
) {
    fun normalized() = copy(minimumScore = minimumScore.coerceIn(60, 100), durationToleranceMs = durationToleranceMs.coerceIn(500, 5000),
        priority = (priority + LyricSource.entries).distinct(), contentOrder = (contentOrder + LyricContent.entries).distinct(),
        sources = sources.toSet(), contents = contents.ifEmpty { setOf(LyricContent.Original) }.toSet())

    fun encode(): String = JSONObject().apply {
        put("version", 1); put("automatic", automatic); put("minimumScore", minimumScore); put("wordOnly", wordOnly)
        put("requireTranslation", requireTranslation); put("requireRomanization", requireRomanization)
        put("durationToleranceMs", durationToleranceMs); put("overwrite", overwrite)
        put("sources", JSONArray(sources.map { it.name })); put("priority", JSONArray(priority.map { it.name }))
        put("contentOrder", JSONArray(contentOrder.map { it.name })); put("contents", JSONArray(contents.map { it.name }))
    }.toString()

    companion object {
        fun read(file: File): LyricsMatchSettings = runCatching {
            if (!file.isFile || file.length() > 65536) return@runCatching LyricsMatchSettings()
            val text = file.readText().trim()
            if (text == "true" || text == "false") return@runCatching LyricsMatchSettings(automatic = text == "true")
            val json = JSONObject(text)
            fun strings(key: String, fallback: List<String>): List<String> = json.optJSONArray(key)?.let { rows ->
                (0 until rows.length()).map { rows.optString(it) }
            } ?: fallback
            fun source(key: String) = strings(key, LyricSource.entries.map { it.name }).mapNotNull {
                runCatching { LyricSource.valueOf(it) }.getOrNull()
            }.distinct()
            fun content(key: String, fallback: List<LyricContent>) = strings(key, fallback.map { it.name }).mapNotNull {
                runCatching { LyricContent.valueOf(it) }.getOrNull()
            }.distinct()
            LyricsMatchSettings(json.optBoolean("automatic"), json.optInt("minimumScore", 90), json.optBoolean("wordOnly", true),
                json.optBoolean("requireTranslation"), json.optBoolean("requireRomanization"), json.optLong("durationToleranceMs", 2500),
                json.optBoolean("overwrite"), source("sources").toSet(), source("priority"),
                content("contentOrder", LyricsMatchSettings().contentOrder), content("contents", LyricContent.entries).toSet()).normalized()
        }.getOrDefault(LyricsMatchSettings())
    }
}

/** All comparisons are local; provider relevance numbers are never presented as our confidence. */
object LyricsMatching {
    private fun normalize(text: String) = Normalizer.normalize(text.take(200), Normalizer.Form.NFKC)
        .lowercase(java.util.Locale.ROOT).filter(Char::isLetterOrDigit)
    private fun similarity(a: String, b: String): Double {
        val left = normalize(a); val right = normalize(b)
        if (left.isEmpty() || right.isEmpty()) return 0.0
        var previous = IntArray(right.length + 1) { it }
        for (i in left.indices) {
            val current = IntArray(right.length + 1); current[0] = i + 1
            for (j in right.indices) current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1,
                previous[j] + if (left[i] == right[j]) 0 else 1)
            previous = current
        }
        return 1.0 - previous.last().toDouble() / maxOf(left.length, right.length)
    }
    fun score(song: LyricSong, candidate: LyricCandidate): Int {
        val duration = if (song.durationMs > 0 && candidate.durationMs > 0)
            (1.0 - abs(song.durationMs - candidate.durationMs).toDouble() / 10000).coerceIn(0.0, 1.0) else 0.0
        return (60 * similarity(song.title, candidate.title) + 30 * similarity(song.artist, candidate.artist) + 10 * duration).roundToInt()
    }
    fun metadataFailure(song: LyricSong, candidate: LyricCandidate, settings: LyricsMatchSettings): String? = when {
        song.artist.isBlank() || song.title.isBlank() || song.durationMs <= 0 -> "歌曲信息不完整，请手动选择"
        candidate.durationMs <= 0 -> "候选时长未知，请手动选择"
        score(song, candidate) < settings.minimumScore -> "相似度低于 ${settings.minimumScore}%"
        abs(song.durationMs - candidate.durationMs) > settings.durationToleranceMs -> "时长差超出允许范围"
        else -> null
    }
    fun contentFailure(download: LyricDownload, settings: LyricsMatchSettings): String? = when {
        settings.wordOnly && !download.wordTimed -> "只有逐行歌词，不满足逐字要求"
        settings.requireTranslation && !has(download.lyrics, LyricContent.Translation) -> "缺少翻译"
        settings.requireRomanization && !has(download.lyrics, LyricContent.Romanization) -> "缺少罗马音"
        settings.contents.none { has(download.lyrics, it) } -> "缺少所选歌词内容"
        else -> null
    }
    fun has(lyrics: Lyrics, kind: LyricContent): Boolean = lyrics.lines.any { line ->
        (line.kind == kind && line.text.isNotBlank()) || line.subLines.any { it.kind == kind && it.text.isNotBlank() }
    }
    fun arrange(download: LyricDownload, settings: LyricsMatchSettings): LyricDownload {
        val lines = download.lyrics.lines.mapNotNull { line ->
            val available = listOf(LyricSubLine(line.cells, line.kind)) + line.subLines
            val ordered = settings.contentOrder.filter { it in settings.contents }.flatMap { kind ->
                available.filter { it.kind == kind && it.text.isNotBlank() }
            }
            val first = ordered.firstOrNull() ?: return@mapNotNull null
            line.copy(cells = first.cells, kind = first.kind, subLines = ordered.drop(1))
        }
        require(lines.isNotEmpty()) { "此版本没有所选歌词内容" }
        return download.copy(lyrics = download.lyrics.copy(lines = lines))
    }
}

class LyricsAlreadyPresentException : IllegalStateException("已有本地歌词")
