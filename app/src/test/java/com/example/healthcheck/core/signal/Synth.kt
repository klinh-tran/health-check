package com.example.healthcheck.core.signal

import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** Synthetic fingertip-camera PPG frames for tests. */
object Synth {

    fun beatsAtBpm(bpm: Double, durationS: Double, start: Double = 0.3): List<Double> {
        val ibi = 60.0 / bpm
        return generateSequence(start) { it + ibi }.takeWhile { it < durationS + 1 }.toList()
    }

    /** Beat times from an explicit IBI sequence (ms), repeated until [durationS]. */
    fun beatsFromIbis(ibisMs: List<Double>, durationS: Double, start: Double = 0.3): List<Double> {
        val out = ArrayList<Double>()
        var t = start
        var i = 0
        while (t < durationS + 1) {
            out.add(t)
            t += ibisMs[i % ibisMs.size] / 1000.0
            i++
        }
        return out
    }

    private fun pulse(dt: Double): Double =
        exp(-0.5 * (dt / 0.07) * (dt / 0.07)) + 0.4 * exp(-0.5 * ((dt - 0.3) / 0.08) * ((dt - 0.3) / 0.08))

    fun frames(
        beatTimes: List<Double>,
        durationS: Double = 60.0,
        fps: Double = 30.0,
        jitterS: Double = 0.004,
        dc: Double = 200.0,
        amp: Double = 2.0,
        noise: Double = 0.0,
        driftAmp: Double = 0.0,
        green: Double = 40.0,
        blue: Double = 25.0,
        seed: Int = 1,
    ): List<FrameSample> {
        val rnd = Random(seed)
        val out = ArrayList<FrameSample>()
        var t = 0.0
        var beatIdx = 0
        while (t < durationS) {
            while (beatIdx < beatTimes.size && beatTimes[beatIdx] < t - 1.0) beatIdx++
            var p = 0.0
            var k = beatIdx
            while (k < beatTimes.size && beatTimes[k] < t + 1.0) {
                p += pulse(t - beatTimes[k]); k++
            }
            val n = if (noise > 0) gaussian(rnd) * noise else 0.0
            val drift = driftAmp * sin(2 * Math.PI * 0.1 * t)
            val r = dc - amp * p + drift + n
            out.add(FrameSample((t * 1e9).toLong(), r, green, blue))
            t += 1.0 / fps + (rnd.nextDouble() * 2 - 1) * jitterS
        }
        return out
    }

    fun gaussian(rnd: Random): Double {
        // Box-Muller
        val u1 = rnd.nextDouble().coerceAtLeast(1e-12)
        val u2 = rnd.nextDouble()
        return kotlin.math.sqrt(-2 * kotlin.math.ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)
    }
}
