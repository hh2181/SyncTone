package dev.salt.replica.lyrics

import androidx.compose.animation.core.CubicBezierEasing

/** Groups sustained notes and computes their glow independently of character movement. */
class LongNoteEnvelope(cells: List<LyricCell>) {
    private data class Group(val start: Long, val firstDuration: Long, val end: Long)
    private val groups = ArrayList<Group>()
    private val groupIndices = IntArray(cells.size) { -1 }
    private val amplitudes = FloatArray(cells.size)
    private val easing = CubicBezierEasing(.25f, 0f, .2f, 1f)

    init {
        val last = cells.indexOfLast { it.text.isNotBlank() }
        var start = -1
        fun flush(end: Int) {
            if (start < 0) return
            if (end == last || cells[end].end - cells[start].start >= 2000) {
                for (i in start..end) groupIndices[i] = groups.size
                groups += Group(cells[start].start, cells[start].end - cells[start].start, cells[end].end)
            }
            start = -1
        }
        cells.forEachIndexed { i, cell ->
            val hangul = cell.text.count { it in '\uAC00'..'\uD7A3' }.coerceAtLeast(1)
            val duration = (cell.end - cell.start) / hangul
            val long = duration >= 1200 && cell.text.isNotBlank()
            val connected = long && start >= 0 && cells[i - 1].end == cell.start
            if (start >= 0 && !connected) flush(i - 1)
            if (long) {
                amplitudes[i] = ((duration - 1200) / 2000f).coerceIn(0f, 1f) * .68f + .32f
                if (start < 0) start = i
            }
        }
        flush(cells.lastIndex)
    }

    fun alpha(cell: Int, now: Long): Float {
        val index = groupIndices[cell]
        if (index < 0) return 0f
        val group = groups[index]
        if (now <= group.start || now - group.end >= 800) return 0f
        val enter = (now - group.start).toFloat() / group.firstDuration.coerceAtLeast(1)
        val after = now - group.end
        val value = when {
            enter < .25f -> easing.transform((enter / .25f).coerceIn(0f, 1f))
            after <= 200 -> 1f
            else -> ((after - 200) / 600f).let { 1 - (3 - 2 * it) * it * it }
        }
        return value * amplitudes[cell]
    }
}
