package dev.salt.replica.equalizer

import kotlin.math.*

val EqFrequencies = listOf(31.25, 62.5, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
const val EqModelId = "joint-cascade-v1"

data class EqConfig(
    val enabled: Boolean = false,
    val gains: List<Int> = List(10) { 0 },
    val autoHeadroom: Boolean = true,
) {
    fun sanitized() = copy(gains = List(10) { gains.getOrElse(it) { 0 }.coerceIn(-120, 120) })
}

data class EqSection(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
    fun responseDb(frequency: Double, sampleRate: Int): Double {
        val w = 2 * PI * frequency / sampleRate
        val cos = cos(w); val sin = sin(w)
        val nr = (b0 + b2) * cos + b1; val ni = (b0 - b2) * sin
        val dr = (1 + a2) * cos + a1; val di = (1 - a2) * sin
        return 10 * log10(((nr * nr + ni * ni) / (dr * dr + di * di)).coerceAtLeast(1e-24))
    }
}

class EqModel private constructor(
    val config: EqConfig,
    val sampleRate: Int,
    val sections: List<EqSection>,
    val activeBands: Int,
    val preampDb: Double,
) {
    val preamp = 10.0.pow(preampDb / 20)
    val maxFrequency = min(20_000.0, sampleRate * .49)
    fun responseDb(frequency: Double): Double = if (!config.enabled) 0.0 else
        preampDb + sections.sumOf { it.responseDb(frequency.coerceIn(1.0, sampleRate * .499), sampleRate) }

    companion object {
        /** Jointly calibrate actual biquads at centers and logarithmic midpoints.
         * Independent implementation of the interaction-matrix design principle:
         * Valimaki & Liski, 2017, DOI 10.1109/LSP.2016.2645280.
         * UI, Android PCM and Windows PCM all use these coefficients. */
        fun design(input: EqConfig, sampleRate: Int = 48_000): EqModel {
            require(sampleRate in 1_000..768_000)
            val config = input.sanitized()
            val count = EqFrequencies.count { it < sampleRate * .49 }
            if (!config.enabled) return EqModel(config, sampleRate, emptyList(), count, 0.0)
            val frequencies = EqFrequencies.take(count)
            val points = frequencies.flatMapIndexed { i, f ->
                if (i < count - 1) listOf(f, sqrt(f * frequencies[i + 1])) else listOf(f)
            }
            val target = config.gains.take(count).flatMapIndexed { i, g ->
                if (i < count - 1) listOf(g / 10.0, (g + config.gains[i + 1]) / 20.0) else listOf(g / 10.0)
            }
            var internal = DoubleArray(count) { 17.0 }
            repeat(3) {
                val prototype = internal.map { if (abs(it) < .001) .001 else it }
                val filters = prototype.mapIndexed { i, g -> section(frequencies[i], g, sampleRate) }
                val matrix = points.map { f -> DoubleArray(count) { i -> filters[i].responseDb(f, sampleRate) / prototype[i] } }
                internal = solve(matrix, target)
            }
            val sections = frequencies.mapIndexed { i, f -> section(f, internal[i], sampleRate) }
            val maxF = min(20_000.0, sampleRate * .49)
            var peak = 0.0
            if (config.autoHeadroom) {
                for (i in 0..1024) {
                    val f = 20 * (maxF / 20).pow(i / 1024.0)
                    peak = max(peak, sections.sumOf { it.responseDb(f, sampleRate) })
                }
                frequencies.forEach { f -> peak = max(peak, sections.sumOf { it.responseDb(f, sampleRate) }) }
            }
            val headroom = if (config.autoHeadroom && peak > .05) -peak - 1.0 else 0.0
            return EqModel(config, sampleRate, sections, count, headroom)
        }

        private fun section(frequency: Double, gain: Double, sampleRate: Int): EqSection {
            val w = 2 * PI * frequency / sampleRate; val neighbor = w / 2
            val width = abs((cos(neighbor) - cos(w)) / sin(neighbor))
            val t = ln(10.0) * gain / 10
            val ratio = if (abs(t) < 1e-9) .3 / .7 else expm1(.3 * t) / (exp(t) - exp(.3 * t))
            val beta = width * sqrt(ratio); val den = 1 + beta
            val gainLinear = 10.0.pow(gain / 20)
            return EqSection((1 + gainLinear * beta) / den, -2 * cos(w) / den,
                (1 - gainLinear * beta) / den, -2 * cos(w) / den, (1 - beta) / den)
        }

        private fun solve(matrix: List<DoubleArray>, target: List<Double>): DoubleArray {
            val count = matrix.first().size
            val a = Array(count) { j -> DoubleArray(count + 1) { k ->
                matrix.indices.sumOf { r -> matrix[r][j] * if (k == count) target[r] else matrix[r][k] }
            } }
            for (k in 0 until count) {
                val pivot = (k until count).maxBy { abs(a[it][k]) }
                val swap = a[k]; a[k] = a[pivot]; a[pivot] = swap
                check(abs(a[k][k]) > 1e-12) { "Invalid EQ interaction matrix" }
                for (j in k + 1 until count) {
                    val ratio = a[j][k] / a[k][k]
                    for (t in k..count) a[j][t] -= ratio * a[k][t]
                }
            }
            val result = DoubleArray(count)
            for (j in count - 1 downTo 0) {
                var sum = a[j][count]
                for (k in j + 1 until count) sum -= a[j][k] * result[k]
                result[j] = sum / a[j][j]
            }
            return result
        }
    }
}
