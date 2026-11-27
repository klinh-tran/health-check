package com.example.healthcheck.core.signal

import com.example.healthcheck.core.math.Stats
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object PeakDetector {

    /**
     * Finds pulse peaks in a band-passed signal.
     * - local maxima above an adaptive threshold (a fraction of the local RMS),
     * - a minimum distance between peaks (keeps the tallest when two are too close),
     * - sub-sample timing by parabolic interpolation.
     * Returns peak times in seconds.
     */
    fun detect(
        signal: UniformSignal,
        minDistanceS: Double,
        thresholdFactor: Double = 0.5,
        rmsWindowS: Double = 2.0,
    ): List<Double> {
        val x = signal.values
        val n = x.size
        if (n < 5) return emptyList()
        val rms = rollingRms(x, max(3, (rmsWindowS * signal.fs).toInt()))
        val candidates = ArrayList<Int>()
        for (i in 1 until n - 1) {
            if (x[i] > x[i - 1] && x[i] >= x[i + 1] && x[i] > thresholdFactor * rms[i] && x[i] > 0) {
                candidates.add(i)
            }
        }
        val minDist = max(1, (minDistanceS * signal.fs).toInt())
        val accepted = BooleanArray(n)
        val kept = ArrayList<Int>()
        for (i in candidates.sortedByDescending { x[it] }) {
            var ok = true
            for (k in max(0, i - minDist + 1)..min(n - 1, i + minDist - 1)) {
                if (accepted[k]) { ok = false; break }
            }
            if (ok) { accepted[i] = true; kept.add(i) }
        }
        kept.sort()
        return kept.map { i -> signal.timeOf(i + parabolicOffset(x[i - 1], x[i], x[i + 1])) }
    }

    fun parabolicOffset(y0: Double, y1: Double, y2: Double): Double {
        val den = y0 - 2 * y1 + y2
        return if (den >= 0.0) 0.0 else (0.5 * (y0 - y2) / den).coerceIn(-0.5, 0.5)
    }

    private fun rollingRms(x: DoubleArray, window: Int): DoubleArray {
        val n = x.size
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + x[i] * x[i]
        val half = window / 2
        return DoubleArray(n) { i ->
            val a = max(0, i - half)
            val b = min(n, i + half + 1)
            sqrt((prefix[b] - prefix[a]) / (b - a))
        }
    }
}

/** One inter-beat interval; [valid] after cleaning. */
data class Ibi(val ms: Double, val endTimeS: Double, val valid: Boolean)

data class IbiCleanResult(
    val ibis: List<Ibi>,
    val totalCount: Int,
    val validCount: Int,
    val mergedDoubles: Int,
    val missedBeats: Int,
) {
    val keptRatio: Double get() = if (totalCount == 0) 0.0 else validCount.toDouble() / totalCount
    val valid: List<Double> get() = ibis.filter { it.valid }.map { it.ms }
}

object IbiCleaner {
    const val MIN_IBI_MS = 300.0
    const val MAX_IBI_MS = 1500.0
    /** Was 0.22. Relaxed so normal breathing-related changes and small hand movements are kept; a missed beat (~2x) is still rejected. */
    const val MAX_LOCAL_DEVIATION = 0.25
    /** Two short intervals are merged only when their sum is this close to the typical interval (unchanged). */
    const val MERGE_TOLERANCE = 0.22
    private const val LOCAL_HALF_WINDOW = 5

    fun fromPeaks(peakTimesS: List<Double>): List<Pair<Double, Double>> =
        peakTimesS.zipWithNext { a, b -> (b - a) * 1000.0 to b }

    /**
     * Cleans raw IBIs (ms, end time s):
     * 1. merges "double detections" (two short intervals that add up to one normal interval),
     * 2. rejects physiologically impossible intervals (outside 300-1500 ms),
     * 3. rejects intervals deviating more than 25% from the local median (missed beats show
     *    up as ~2x intervals and are rejected here, never interpolated).
     * [referenceMedian] lets short segments of one recording share the recording-wide typical interval.
     */
    fun clean(raw: List<Pair<Double, Double>>, referenceMedian: Double? = null): IbiCleanResult {
        if (raw.isEmpty()) return IbiCleanResult(emptyList(), 0, 0, 0, 0)
        val plausible = raw.map { it.first }.filter { it in MIN_IBI_MS..MAX_IBI_MS }
        if (plausible.isEmpty() && referenceMedian == null) {
            return IbiCleanResult(raw.map { Ibi(it.first, it.second, false) }, raw.size, 0, 0, 0)
        }
        val globalMedian = referenceMedian ?: Stats.median(plausible)

        // 1. Merge double detections.
        val merged = ArrayList<Pair<Double, Double>>()
        var doubles = 0
        var i = 0
        while (i < raw.size) {
            val cur = raw[i]
            if (i + 1 < raw.size && cur.first < 0.75 * globalMedian) {
                val sum = cur.first + raw[i + 1].first
                if (abs(sum - globalMedian) <= MERGE_TOLERANCE * globalMedian) {
                    merged.add(sum to raw[i + 1].second)
                    doubles++
                    i += 2
                    continue
                }
            }
            merged.add(cur)
            i++
        }

        // 2 + 3. Validate against physiological range and local median.
        var missed = 0
        val values = merged.map { it.first }
        val result = merged.mapIndexed { idx, (ms, t) ->
            val neighbours = (max(0, idx - LOCAL_HALF_WINDOW)..min(values.size - 1, idx + LOCAL_HALF_WINDOW))
                .map { values[it] }
                .filter { it in MIN_IBI_MS..MAX_IBI_MS }
            val local = if (neighbours.size >= 3) Stats.median(neighbours) else globalMedian
            val inRange = ms in MIN_IBI_MS..MAX_IBI_MS
            val close = abs(ms - local) <= MAX_LOCAL_DEVIATION * local
            if (!close && ms > 1.6 * local) missed++
            Ibi(ms, t, inRange && close)
        }
        return IbiCleanResult(result, result.size, result.count { it.valid }, doubles, missed)
    }
}

object Hrv {
    /** Successive differences only between adjacent valid intervals (a rejected beat breaks the chain). */
    fun successiveDifferences(ibis: List<Ibi>): List<Double> =
        ibis.zipWithNext().filter { (a, b) -> a.valid && b.valid }.map { (a, b) -> b.ms - a.ms }

    fun rmssd(diffs: List<Double>): Double? {
        if (diffs.isEmpty()) return null
        return sqrt(diffs.sumOf { it * it } / diffs.size)
    }

    fun rmssdOf(ibisMs: List<Double>): Double? = rmssd(ibisMs.zipWithNext { a, b -> b - a })

    fun meanHeartRate(validIbisMs: List<Double>): Double? =
        if (validIbisMs.isEmpty()) null else 60000.0 / validIbisMs.average()
}
