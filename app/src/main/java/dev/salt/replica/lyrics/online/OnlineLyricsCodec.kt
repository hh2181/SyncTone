package dev.salt.replica.lyrics.online

import dev.salt.replica.lyrics.*
import org.json.JSONObject
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.InflaterInputStream

/** KRC is a zlib stream XORed with a fixed 16-byte format key after the krc1 header. */
object OnlineLyricsCodec {
    private val key = byteArrayOf(0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47,
        0x51, 0x36, 0x31, 0x2d, 0xce.toByte(), 0xd2.toByte(), 0x6e, 0x69)
    fun decryptKrc(bytes: ByteArray): String {
        require(bytes.size > 4 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "krc1") { "歌词格式无效" }
        val payload = ByteArray(bytes.size - 4) { i -> (bytes[i + 4].toInt() xor key[i % key.size].toInt()).toByte() }
        return InflaterInputStream(payload.inputStream()).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                require(output.size() + read <= LyricHttp.MAX_RESPONSE) { "解压后的歌词过大" }
                output.write(buffer, 0, read)
            }
            output.toString("UTF-8")
        }
    }

    fun parseKrc(text: String): Lyrics {
        val rowPattern = Regex("^\\[(\\d+),(\\d+)](.*)$")
        val wordPattern = Regex("<(\\d+),(\\d+),\\d+>([^<]*)")
        val offset = Regex("\\[offset:([+-]?\\d+)]").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val lines = text.lineSequence().mapNotNull { raw ->
            val match = rowPattern.matchEntire(raw.trimEnd('\r')) ?: return@mapNotNull null
            val start = match.groupValues[1].toLong() + offset
            val end = start + match.groupValues[2].toLong()
            val cells = wordPattern.findAll(match.groupValues[3]).map { word ->
                val begin = start + word.groupValues[1].toLong()
                LyricCell(begin.coerceAtLeast(0), (begin + word.groupValues[2].toLong()).coerceAtLeast(0), word.groupValues[3])
            }.toList()
            if (cells.isEmpty()) null else LyricLine(start.coerceAtLeast(0), end.coerceAtLeast(0), cells)
        }.take(10000).toList()
        val secondary = mutableMapOf<Int, MutableList<LyricSubLine>>()
        // Optional language metadata contains translation (type 1) or romanisation (type 0).
        Regex("\\[language:([^]]+)]").find(text)?.groupValues?.get(1)?.let { encoded ->
            runCatching {
                val content = JSONObject(Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8)).getJSONArray("content")
                for (i in 0 until content.length()) {
                    val language = content.getJSONObject(i)
                    val type = language.optInt("type", -1)
                    if (type !in 0..1) continue
                    val rows = language.getJSONArray("lyricContent")
                    for (j in 0 until minOf(rows.length(), lines.size)) {
                        val words = rows.getJSONArray(j)
                        val line = lines[j]
                        val cells = if (type == 0 && words.length() == line.cells.size) line.cells.mapIndexed { n, cell ->
                            cell.copy(text = words.optString(n) + if (n < words.length() - 1) " " else "")
                        } else listOf(LyricCell(line.start, line.end, (0 until words.length()).joinToString(" ") { words.optString(it) }))
                        if (cells.any { it.text.isNotBlank() }) secondary.getOrPut(j) { mutableListOf() }.add(
                            LyricSubLine(cells, if (type == 0) LyricContent.Romanization else LyricContent.Translation))
                    }
                }
            } // Broken optional metadata must not discard the timed original.
        }
        return Lyrics(lines.mapIndexed { i, line -> line.copy(subLines = secondary[i].orEmpty()) }.sortedBy { it.start }, "ONLINE_KUGOU")
    }

    /** Enhanced LRC preserves real word boundaries, gaps, translations and the provider marker. */
    fun toLrc(lyrics: Lyrics): String = buildString {
        appendLine("[source:${lyrics.source.replace(Regex("[^A-Za-z0-9_]"), "")}]")
        val kinds = JSONArray(lyrics.lines.map { line -> JSONArray(listOf(line.kind.name) + line.subLines.map { it.kind.name }) })
        appendLine("[synctone-kinds:${Base64.getEncoder().encodeToString(kinds.toString().toByteArray(Charsets.UTF_8))}]")
        fun timestamp(value: Long): String {
            val ms = value.coerceAtLeast(0)
            return "%02d:%02d.%03d".format(java.util.Locale.ROOT, ms / 60000, ms / 1000 % 60, ms % 1000)
        }
        fun row(line: LyricLine, cells: List<LyricCell>) {
            append("[${timestamp(line.start)}]")
            cells.forEach { cell -> append("<${timestamp(cell.start)}>${cell.text.replace('\n', ' ').replace('\r', ' ')}<${timestamp(cell.end)}>") }
            appendLine()
        }
        lyrics.lines.forEach { line -> row(line, line.cells); line.subLines.forEach { row(line, it.cells) } }
    }
    fun fromLrc(text: String, duration: Long): Lyrics {
        val lyrics = LyricsParser.parse(text, duration,
            Regex("\\[source:(ONLINE_[A-Z]+)]").find(text)?.groupValues?.get(1) ?: "LRC")
        val metadata = Regex("\\[synctone-kinds:([A-Za-z0-9+/=]+)]").find(text)?.groupValues?.get(1) ?: return lyrics
        return runCatching {
            val kinds = JSONArray(Base64.getDecoder().decode(metadata).toString(Charsets.UTF_8))
            lyrics.copy(lines = lyrics.lines.mapIndexed { index, line ->
                val row = kinds.optJSONArray(index) ?: return@mapIndexed line
                line.copy(kind = LyricContent.valueOf(row.getString(0)), subLines = line.subLines.mapIndexed { i, sub ->
                    sub.copy(kind = LyricContent.valueOf(row.getString(i + 1)))
                })
            })
        }.getOrDefault(lyrics)
    }
}
