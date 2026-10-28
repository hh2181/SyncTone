package dev.salt.replica.lyrics

import java.io.File
import java.io.InputStream

data class LyricCell(val start: Long, val end: Long, val text: String)
data class LyricSubLine(val cells: List<LyricCell>) {
    val text: String = cells.joinToString("") { it.text }
    val wordTimed: Boolean = cells.count { it.text.isNotBlank() && it.end > it.start } > 1 &&
        cells.zipWithNext().all { (a, b) -> a.start <= b.start }
}
data class LyricLine(val start: Long, val end: Long, val cells: List<LyricCell>,
                     val subLines: List<LyricSubLine> = emptyList()) {
    val text: String = cells.joinToString("") { it.text }
    val translation: String = subLines.joinToString("\n") { it.text }
}
data class Lyrics(val lines: List<LyricLine>, val source: String = "EMBEDDED") {
    fun indexAt(position: Long): Int {
        var low = 0
        var high = lines.lastIndex
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].start <= position) { found = mid; low = mid + 1 } else high = mid - 1
        }
        return found
    }
    companion object { val Empty = Lyrics(emptyList()) }
}

object LyricsParser {
    private val stamp = Regex("[\\[<](\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?[\\]>]")
    private fun time(m: MatchResult): Long = m.groupValues[1].toLong() * 60000 +
        m.groupValues[2].toLong() * 1000 + m.groupValues[3].padEnd(3, '0').take(3).toLong()

    fun parse(text: String, duration: Long = 0, source: String = "EMBEDDED"): Lyrics {
        val offset = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE).find(text)
            ?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        data class Row(val start: Long, val cells: List<LyricCell>, val text: String)
        val rows = text.trim('\uFEFF', '\u0000').lineSequence().flatMap { raw ->
            val matches = stamp.findAll(raw).toList()
            if (matches.isEmpty()) return@flatMap emptySequence()
            val repeatedPlain = matches.size > 1 && matches.all { it.value.startsWith('[') } &&
                matches.zipWithNext().all { (a, b) -> raw.substring(a.range.last + 1, b.range.first).isEmpty() }
            if (repeatedPlain) {
                val words = raw.substring(matches.last().range.last + 1)
                return@flatMap matches.asSequence().map { m ->
                    val start = (time(m) + offset).coerceAtLeast(0)
                    Row(start, listOf(LyricCell(start, -1, words)), words)
                }
            }
            val cells = matches.mapIndexedNotNull { i, m ->
                val end = matches.getOrNull(i + 1)?.range?.first ?: raw.length
                val word = raw.substring(m.range.last + 1, end)
                if (word.isEmpty()) null else LyricCell((time(m) + offset).coerceAtLeast(0),
                    matches.getOrNull(i + 1)?.let { time(it) + offset } ?: -1, word)
            }
            val start = (time(matches.first()) + offset).coerceAtLeast(0)
            val content = cells.ifEmpty { listOf(LyricCell(start, -1, "")) }
            sequenceOf(Row(start, content, content.joinToString("") { it.text }))
        }.toList()
        val groups = rows.groupBy { it.start }.toSortedMap().entries.toList()
        return Lyrics(groups.mapIndexed { i, entry ->
            val variants = entry.value
            val main = variants.first()
            val nextStart = groups.getOrNull(i + 1)?.key ?: maxOf(duration, main.cells.last().start + 4000)
            fun finish(row: Row, trim: Boolean = false): List<LyricCell> {
                val first = if (trim) row.text.length - row.text.trimStart().length else 0
                val last = if (trim) row.text.trimEnd().length else row.text.length
                var offset = 0
                return row.cells.mapNotNull { cell ->
                    val left = (first - offset).coerceIn(0, cell.text.length)
                    val right = (last - offset).coerceIn(left, cell.text.length)
                    offset += cell.text.length
                    if (trim && left == right) null else cell.copy(
                        end = (if (cell.end < 0) nextStart else cell.end).coerceAtLeast(cell.start),
                        text = cell.text.substring(left, right))
                }
            }
            val cells = finish(main)
            val sub = variants.drop(1).map { LyricSubLine(finish(it, trim = true)) }
            LyricLine(entry.key, cells.last().end, cells, sub)
        }, source)
    }

    fun fromMp3(file: File, duration: Long): Lyrics = file.inputStream().use { fromMp3(it, duration) }

    fun fromMp3(input: InputStream, duration: Long): Lyrics {
        val header = input.readUpTo(10)
        if (header.size != 10 || String(header, 0, 3, Charsets.US_ASCII) != "ID3") return Lyrics.Empty
        val version = header[3].toInt()
        val size = syncSafe(header, 6)
        if (size !in 1..16_777_216) return Lyrics.Empty
        val body = input.readUpTo(size)
        var p = 0
        var best = Lyrics.Empty
        while (p + 10 <= body.size) {
            val id = String(body, p, 4, Charsets.US_ASCII)
            if (id.firstOrNull() == '\u0000') break
            val length = if (version == 4) syncSafe(body, p + 4) else bigEndian(body, p + 4)
            p += 10
            if (length <= 0 || length > body.size - p) break
            if (id == "USLT" && length > 5) {
                val payload = body.copyOfRange(p, p + length)
                val encoding = payload[0].toInt()
                val start = terminator(payload, 4, encoding) + (if (encoding == 1 || encoding == 2) 2 else 1)
                if (start < payload.size) {
                    val parsed = parse(decode(payload.copyOfRange(start, payload.size), encoding), duration)
                    if (parsed.lines.size > best.lines.size) best = parsed
                }
            }
            p += length
        }
        return best
    }

    fun decode(bytes: ByteArray, encoding: Int = 3): String = when {
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> String(bytes, Charsets.UTF_16LE).trimStart('\uFEFF')
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, Charsets.UTF_16BE).trimStart('\uFEFF')
        encoding == 1 -> String(bytes, Charsets.UTF_16LE)
        encoding == 2 -> String(bytes, Charsets.UTF_16BE)
        encoding == 0 -> String(bytes, Charsets.ISO_8859_1)
        else -> String(bytes, Charsets.UTF_8).trimStart('\uFEFF')
    }.trimEnd('\u0000')

    private fun terminator(data: ByteArray, start: Int, encoding: Int): Int {
        val step = if (encoding == 1 || encoding == 2) 2 else 1
        var i = start
        while (i + step <= data.size) {
            if (data[i] == 0.toByte() && (step == 1 || data[i + 1] == 0.toByte())) return i
            i += step
        }
        return data.size
    }
    private fun InputStream.readUpTo(size: Int): ByteArray {
        val buffer = ByteArray(size)
        var count = 0
        while (count < size) {
            val read = read(buffer, count, size - count)
            if (read < 0) break
            if (read == 0) {
                val byte = read()
                if (byte < 0) break
                buffer[count++] = byte.toByte()
            } else count += read
        }
        return if (count == size) buffer else buffer.copyOf(count)
    }
    private fun syncSafe(data: ByteArray, offset: Int): Int = (0..3).fold(0) { n, i -> (n shl 7) or (data[offset + i].toInt() and 127) }
    private fun bigEndian(data: ByteArray, offset: Int): Int = (0..3).fold(0) { n, i -> (n shl 8) or (data[offset + i].toInt() and 255) }
}
