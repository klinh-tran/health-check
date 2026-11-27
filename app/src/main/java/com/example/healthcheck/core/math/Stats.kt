package com.example.healthcheck.core.math

import kotlin.math.abs
import kotlin.math.sqrt

object Stats {
    fun median(values: List<Double>): Double {
        require(values.isNotEmpty()) { "median of empty list" }
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    fun median(values: DoubleArray): Double = median(values.toList())

    /** Median absolute deviation (unscaled). */
    fun mad(values: List<Double>): Double {
        val m = median(values)
        return median(values.map { abs(it - m) })
    }

    fun mean(values: DoubleArray): Double = if (values.isEmpty()) 0.0 else values.sum() / values.size

    fun std(values: DoubleArray): Double {
        if (values.size < 2) return 0.0
        val m = mean(values)
        var acc = 0.0
        for (v in values) acc += (v - m) * (v - m)
        return sqrt(acc / values.size)
    }

    /** p in 0..1, linear interpolation between order statistics. */
    fun percentile(values: DoubleArray, p: Double): Double {
        require(values.isNotEmpty())
        val s = values.sorted()
        val pos = p.coerceIn(0.0, 1.0) * (s.size - 1)
        val lo = pos.toInt()
        val hi = minOf(lo + 1, s.size - 1)
        return s[lo] + (s[hi] - s[lo]) * (pos - lo)
    }
}
