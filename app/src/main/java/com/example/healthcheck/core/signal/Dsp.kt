package com.example.healthcheck.core.signal

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A uniformly sampled signal. [startTimeS] is the time of sample 0. */
class UniformSignal(val values: DoubleArray, val fs: Double, val startTimeS: Double) {
    val size get() = values.size
    fun timeOf(index: Double) = startTimeS + index / fs
}

object Dsp {

    /**
     * Linear interpolation of irregular samples (times in seconds, strictly increasing) onto a uniform grid.
     * This uses the frames' real timestamps rather than assuming a frame rate.
     */
    fun resample(times: DoubleArray, values: DoubleArray, fs: Double): UniformSignal {
        require(times.size == values.size)
        if (times.size < 2) return UniformSignal(DoubleArray(0), fs, times.firstOrNull() ?: 0.0)
        val t0 = times.first()
        val n = ((times.last() - t0) * fs).toInt() + 1
        val out = DoubleArray(n)
        var j = 0
        for (i in 0 until n) {
            val t = t0 + i / fs
            while (j < times.size - 2 && times[j + 1] < t) j++
            val ta = times[j]
            val tb = times[j + 1]
            val frac = if (tb > ta) ((t - ta) / (tb - ta)).coerceIn(0.0, 1.0) else 0.0
            out[i] = values[j] + (values[j + 1] - values[j]) * frac
        }
        return UniformSignal(out, fs, t0)
    }

    /** Subtract a centred moving average (window in samples): removes slow baseline drift. */
    fun detrend(x: DoubleArray, window: Int): DoubleArray {
        val n = x.size
        if (n == 0) return x
        val half = max(1, window / 2)
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + x[i]
        return DoubleArray(n) { i ->
            val a = max(0, i - half)
            val b = min(n, i + half + 1)
            x[i] - (prefix[b] - prefix[a]) / (b - a)
        }
    }

    /** Second-order section, RBJ cookbook coefficients (Q = 1/sqrt(2) gives Butterworth). */
    class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        fun filter(x: DoubleArray): DoubleArray {
            val y = DoubleArray(x.size)
            var z1 = 0.0
            var z2 = 0.0
            for (i in x.indices) {
                val v = x[i]
                val out = b0 * v + z1
                z1 = b1 * v - a1 * out + z2
                z2 = b2 * v - a2 * out
                y[i] = out
            }
            return y
        }

        companion object {
            private val Q = 1.0 / sqrt(2.0)
            fun lowPass(cutoffHz: Double, fs: Double): Biquad {
                val w0 = 2 * PI * cutoffHz / fs
                val alpha = sin(w0) / (2 * Q)
                val c = cos(w0)
                val a0 = 1 + alpha
                return Biquad((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
            }

            fun highPass(cutoffHz: Double, fs: Double): Biquad {
                val w0 = 2 * PI * cutoffHz / fs
                val alpha = sin(w0) / (2 * Q)
                val c = cos(w0)
                val a0 = 1 + alpha
                return Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
            }
        }
    }

    /** Zero-phase filtering (forward + backward) with odd reflection padding to limit edge transients. */
    fun filtfilt(x: DoubleArray, sections: List<Biquad>, padLen: Int): DoubleArray {
        if (x.size < 4) return x.copyOf()
        val pad = min(padLen, x.size - 1)
        val n = x.size
        val ext = DoubleArray(n + 2 * pad)
        for (i in 0 until pad) {
            ext[i] = 2 * x[0] - x[pad - i]
            ext[n + pad + i] = 2 * x[n - 1] - x[n - 2 - i]
        }
        System.arraycopy(x, 0, ext, pad, n)
        var y = ext
        for (s in sections) y = s.filter(y)
        y.reverse()
        for (s in sections) y = s.filter(y)
        y.reverse()
        return y.copyOfRange(pad, pad + n)
    }

    /**
     * Running a 2nd-order Butterworth forwards and backwards squares its magnitude response, which
     * would move the -3 dB points inwards. These factors place the per-pass cutoffs so the combined
     * zero-phase response is -3 dB exactly at the requested lowHz / highHz.
     * (x^4 / (1 + x^4) = 1/sqrt(2)  ->  x = 1.2465;  1 / (1 + x^4) = 1/sqrt(2)  ->  x = 0.8022)
     */
    private const val FILTFILT_HP_FACTOR = 1.2465
    private const val FILTFILT_LP_FACTOR = 0.8022

    /** Detrend, then 2nd-order Butterworth high-pass + low-pass, both run forward and backward. */
    fun bandPass(x: DoubleArray, fs: Double, lowHz: Double, highHz: Double, detrendWindowS: Double): DoubleArray {
        val d = detrend(x, (detrendWindowS * fs).toInt())
        val lpCut = minOf(highHz / FILTFILT_LP_FACTOR, 0.45 * fs)
        return filtfilt(
            d,
            listOf(Biquad.highPass(lowHz / FILTFILT_HP_FACTOR, fs), Biquad.lowPass(lpCut, fs)),
            padLen = (3 * fs).toInt(),
        )
    }

    /** Normalised autocorrelation at [lag] (Pearson-like, in -1..1). */
    fun autocorrelation(x: DoubleArray, lag: Int): Double {
        if (lag <= 0 || lag >= x.size) return 0.0
        var sxy = 0.0
        var sxx = 0.0
        var syy = 0.0
        for (i in 0 until x.size - lag) {
            val a = x[i]
            val b = x[i + lag]
            sxy += a * b
            sxx += a * a
            syy += b * b
        }
        val den = sqrt(sxx * syy)
        return if (den <= 1e-12) 0.0 else sxy / den
    }

    data class Periodicity(val periodSamples: Double, val strength: Double)

    /**
     * Dominant period by autocorrelation in [minPeriodS, maxPeriodS]. Prefers the shortest strong
     * lag so it does not lock onto a multiple of the true period. Returns null for flat input.
     */
    fun dominantPeriod(x: DoubleArray, fs: Double, minPeriodS: Double, maxPeriodS: Double): Periodicity? {
        val minLag = max(1, (minPeriodS * fs).toInt())
        val maxLag = min(x.size / 2, (maxPeriodS * fs).toInt() + 1)
        if (maxLag <= minLag + 2) return null
        val ac = DoubleArray(maxLag + 2) { if (it in minLag - 1..maxLag + 1) autocorrelation(x, it) else 0.0 }
        // Local maxima only
        val peaks = (minLag..maxLag).filter { ac[it] > ac[it - 1] && ac[it] >= ac[it + 1] }
        if (peaks.isEmpty()) return null
        val best = peaks.maxBy { ac[it] }
        if (ac[best] <= 0.0) return null
        val chosen = peaks.firstOrNull { ac[it] >= 0.85 * ac[best] } ?: best
        // Parabolic refinement of the lag
        val y0 = ac[chosen - 1]
        val y1 = ac[chosen]
        val y2 = ac[chosen + 1]
        val den = y0 - 2 * y1 + y2
        val offset = if (den < 0) (0.5 * (y0 - y2) / den).coerceIn(-0.5, 0.5) else 0.0
        return Periodicity(chosen + offset, y1)
    }
}
