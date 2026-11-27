package com.example.healthcheck.core.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class IbiAndHrvTest {

    private fun raw(vararg ms: Double): List<Pair<Double, Double>> {
        var t = 0.0
        return ms.map { t += it / 1000; it to t }
    }

    @Test fun rejectsPhysiologicallyImpossibleIntervals() {
        val r = IbiCleaner.clean(raw(1000.0, 1000.0, 250.0, 1000.0, 1700.0, 1000.0, 1000.0))
        // 250 is too short to be a beat and cannot merge into a normal one (250+1000 is 25% off), 1700 is too long
        assertFalse(r.ibis.first { it.ms == 250.0 }.valid)
        assertFalse(r.ibis.first { it.ms == 1700.0 }.valid)
        assertEquals(5, r.validCount)
    }

    @Test fun mergesDoubleDetections() {
        val r = IbiCleaner.clean(raw(1000.0, 1000.0, 1000.0, 400.0, 600.0, 1000.0, 1000.0))
        assertEquals(1, r.mergedDoubles)
        assertEquals(6, r.totalCount)
        assertTrue(r.ibis.all { it.valid })
        assertEquals(1000.0, r.ibis[3].ms, 1e-9)
    }

    @Test fun missedBeatIsRejectedNotInterpolated() {
        val r = IbiCleaner.clean(raw(1000.0, 1010.0, 990.0, 2000.0, 1000.0, 1005.0))
        assertEquals(1, r.missedBeats)
        assertFalse(r.ibis[3].valid)
        assertEquals(5, r.validCount)
    }

    @Test fun rejectsLocalOutliersOver25Percent() {
        val r = IbiCleaner.clean(raw(1000.0, 1000.0, 1000.0, 1300.0, 1000.0, 1150.0, 1000.0, 1000.0))
        assertFalse(r.ibis[3].valid)  // +30%
        assertTrue(r.ibis[5].valid)   // +15%
    }

    @Test fun emptyInput() {
        val r = IbiCleaner.clean(emptyList())
        assertEquals(0, r.totalCount)
        assertEquals(0.0, r.keptRatio, 0.0)
    }

    @Test fun rmssdOnKnownSequence() {
        // diffs 10, -20, 15 -> sqrt((100 + 400 + 225) / 3)
        assertEquals(sqrt(725.0 / 3), Hrv.rmssdOf(listOf(800.0, 810.0, 790.0, 805.0))!!, 1e-9)
        assertEquals(0.0, Hrv.rmssdOf(listOf(1000.0, 1000.0, 1000.0))!!, 1e-9)
        assertEquals(50.0, Hrv.rmssdOf(listOf(1000.0, 1050.0, 1000.0, 1050.0))!!, 1e-9)
        assertNull(Hrv.rmssdOf(listOf(1000.0)))
    }

    @Test fun successiveDifferencesSkipRejectedBeats() {
        val ibis = listOf(
            Ibi(1000.0, 1.0, true), Ibi(1020.0, 2.0, true), Ibi(2000.0, 4.0, false),
            Ibi(990.0, 5.0, true), Ibi(1000.0, 6.0, true),
        )
        assertEquals(listOf(20.0, 10.0), Hrv.successiveDifferences(ibis))
    }

    @Test fun meanHeartRate() {
        assertEquals(60.0, Hrv.meanHeartRate(listOf(1000.0, 1000.0))!!, 1e-9)
        assertEquals(75.0, Hrv.meanHeartRate(listOf(800.0, 800.0))!!, 1e-9)
        assertNull(Hrv.meanHeartRate(emptyList()))
    }

    private fun tachogram(ibiAt: (Double) -> Double, seconds: Double): IbiCleanResult {
        val list = ArrayList<Ibi>()
        var t = 0.0
        while (t < seconds) {
            val ms = ibiAt(t)
            t += ms / 1000
            list.add(Ibi(ms, t, true))
        }
        return IbiCleanResult(list, list.size, list.size, 0, 0)
    }

    @Test fun breathingRateFromRespiratorySinusArrhythmia() {
        // 15 breaths/min = 0.25 Hz modulation of +/-40 ms around 900 ms
        val r = tachogram({ t -> 900 + 40 * sin(2 * PI * 0.25 * t) }, 55.0)
        val br = BreathingEstimator.estimate(r)
        assertNotNull(br)
        assertEquals(15.0, br!!, 1.5)
    }

    @Test fun breathingRateIsNotInventedFromNoise() {
        val rnd = Random(4)
        val r = tachogram({ _ -> 900 + Synth.gaussian(rnd) * 15 }, 55.0)
        assertNull(BreathingEstimator.estimate(r))
    }

    @Test fun breathingRateNeedsLongCleanRecording() {
        val short = tachogram({ t -> 900 + 40 * sin(2 * PI * 0.25 * t) }, 20.0)
        assertNull(BreathingEstimator.estimate(short))
    }
}
