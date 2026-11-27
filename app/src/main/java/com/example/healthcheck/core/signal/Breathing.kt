package com.example.healthcheck.core.signal

import com.example.healthcheck.core.math.Stats
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Breathing rate from respiratory sinus arrhythmia (the heart speeds up slightly on each in-breath).
 * Only returns a value when one clear spectral peak dominates the 6-30 breaths/min band; otherwise null.
 */
object BreathingEstimator {
    const val MIN_KEPT_RATIO = 0.9
    const val MIN_SPAN_S = 40.0
    const val MIN_PEAK_SHARE = 0.5
    private const val MIN_HZ = 0.1
    private const val MAX_HZ = 0.5
    private const val STEP_HZ = 0.005
    private const val LOBE_HZ = 0.02
    private const val TACHO_FS = 4.0

    fun estimate(clean: IbiCleanResult): Double? {
        if (clean.keptRatio < MIN_KEPT_RATIO) return null
        val valid = clean.ibis.filter { it.valid }
        if (valid.size < 30) return null
        val times = DoubleArray(valid.size) { valid[it].endTimeS }
        val span = times.last() - times.first()
        if (span < MIN_SPAN_S) return null
        val tacho = Dsp.resample(times, DoubleArray(valid.size) { valid[it].ms }, TACHO_FS)
        val x = tacho.values
        // Remove mean and linear trend.
        val n = x.size
        val tm = (n - 1) / 2.0
        val xm = Stats.mean(x)
        var num = 0.0
        var den = 0.0
        for (i in 0 until n) { num += (i - tm) * (x[i] - xm); den += (i - tm) * (i - tm) }
        val slope = if (den > 0) num / den else 0.0
        val y = DoubleArray(n) { x[it] - xm - slope * (it - tm) }

        val freqs = generateSequence(MIN_HZ) { it + STEP_HZ }.takeWhile { it <= MAX_HZ + 1e-9 }.toList()
        val power = freqs.map { f ->
            var re = 0.0
            var im = 0.0
            for (i in 0 until n) {
                val ph = 2 * PI * f * i / TACHO_FS
                re += y[i] * cos(ph)
                im -= y[i] * sin(ph)
            }
            re * re + im * im
        }
        val total = power.sum()
        if (total <= 1e-9) return null
        val peakIdx = power.indices.maxBy { power[it] }
        val fp = freqs[peakIdx]
        val lobe = freqs.indices.filter { abs(freqs[it] - fp) <= LOBE_HZ }.sumOf { power[it] }
        if (lobe / total < MIN_PEAK_SHARE) return null
        if (peakIdx == 0 || peakIdx == freqs.size - 1) return null // edge of band: not a real peak
        return fp * 60.0
    }
}
