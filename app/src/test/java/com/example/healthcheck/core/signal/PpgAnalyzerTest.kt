package com.example.healthcheck.core.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class PpgAnalyzerTest {

    private fun valid(outcome: PpgOutcome): PpgMeasurement = when (outcome) {
        is PpgOutcome.Valid -> outcome.measurement
        is PpgOutcome.Invalid -> { fail("expected valid but was ${outcome.reason}"); error("") }
    }

    private fun invalid(outcome: PpgOutcome): RejectReason = when (outcome) {
        is PpgOutcome.Invalid -> outcome.reason
        is PpgOutcome.Valid -> { fail("expected invalid but got ${outcome.measurement}"); error("") }
    }

    @Test fun bandPassKeepsPulseBandAndRemovesDcAndHighFrequency() {
        val fs = 30.0
        val n = 1800
        fun ampAfter(freq: Double): Double {
            val x = DoubleArray(n) { 100.0 + sin(2 * PI * freq * it / fs) }
            val y = Dsp.bandPass(x, fs, 0.7, 3.5, 3.0)
            return y.copyOfRange(300, 1500).maxOf { abs(it) }
        }
        assertTrue("1.2 Hz should pass", ampAfter(1.2) > 0.8)
        assertTrue("0.1 Hz should be removed", ampAfter(0.1) < 0.1)
        assertTrue("10 Hz should be removed", ampAfter(10.0) < 0.05)
        val dc = Dsp.bandPass(DoubleArray(n) { 100.0 }, fs, 0.7, 3.5, 3.0)
        assertTrue(dc.copyOfRange(100, 1700).all { abs(it) < 1e-6 })
    }

    @Test fun peakDetectorFindsEveryBeatOfASine() {
        val fs = 30.0
        val x = DoubleArray(1800) { sin(2 * PI * 1.0 * it / fs) }
        val peaks = PeakDetector.detect(UniformSignal(x, fs, 0.0), minDistanceS = 0.6)
        assertEquals(60, peaks.size)
        // Parabolic refinement puts peaks at 0.25 s + k
        peaks.forEachIndexed { k, t -> assertEquals(0.25 + k, t, 0.01) }
    }

    @Test fun clean60BpmIsAccurate() {
        val m = valid(PpgAnalyzer.analyze(Synth.frames(Synth.beatsAtBpm(60.0, 60.0))))
        assertEquals(60.0, m.heartRateBpm, 2.0)
        assertEquals(SignalQuality.GOOD, m.quality)
        assertTrue(m.validBeats >= 40)
    }

    @Test fun clean90BpmIsAccurate() {
        val m = valid(PpgAnalyzer.analyze(Synth.frames(Synth.beatsAtBpm(90.0, 60.0))))
        assertEquals(90.0, m.heartRateBpm, 2.0)
    }

    @Test fun noisyDriftingSignalStillAccurate() {
        val frames = Synth.frames(Synth.beatsAtBpm(72.0, 60.0), noise = 0.6, driftAmp = 4.0, jitterS = 0.008, seed = 3)
        val m = valid(PpgAnalyzer.analyze(frames))
        assertEquals(72.0, m.heartRateBpm, 2.0)
    }

    @Test fun missingBeatsAreRejectedButReadingSurvives() {
        val beats = Synth.beatsAtBpm(60.0, 60.0).filterIndexed { i, _ -> i !in setOf(20, 33, 47) }
        val m = valid(PpgAnalyzer.analyze(Synth.frames(beats)))
        assertEquals(60.0, m.heartRateBpm, 2.0)
        assertTrue("missed-beat gaps must be dropped", m.validBeats < m.totalBeats)
        // Gaps must not inflate HRV: steady 1000 ms beats -> RMSSD close to 0
        assertTrue("rmssd=${m.rmssdMs}", m.rmssdMs!! < 15.0)
    }

    @Test fun knownHrvIsRecovered() {
        // Alternating 950/1050 ms -> successive differences of 100 ms -> RMSSD = 100 ms
        val beats = Synth.beatsFromIbis(listOf(950.0, 1050.0), 60.0)
        val m = valid(PpgAnalyzer.analyze(Synth.frames(beats, noise = 0.1)))
        assertEquals(60.0, m.heartRateBpm, 2.0)
        assertEquals(100.0, m.rmssdMs!!, 12.0)
    }

    @Test fun flatSignalIsRejected() {
        val rnd = Random(5)
        val frames = (0 until 1800).map { FrameSample(it * 33_333_333L, 200.0 + rnd.nextDouble() * 0.05, 40.0, 25.0) }
        val reason = invalid(PpgAnalyzer.analyze(frames))
        assertTrue(reason in setOf(RejectReason.WEAK_SIGNAL, RejectReason.TOO_FEW_BEATS, RejectReason.TOO_MANY_REJECTED))
    }

    @Test fun perfectlyFlatSignalIsRejected() {
        val frames = (0 until 1800).map { FrameSample(it * 33_333_333L, 200.0, 40.0, 25.0) }
        assertEquals(RejectReason.WEAK_SIGNAL, invalid(PpgAnalyzer.analyze(frames)))
    }

    @Test fun noFingerIsRejected() {
        // Uncovered camera sees a room: red, green and blue similar.
        val frames = Synth.frames(Synth.beatsAtBpm(60.0, 60.0), dc = 120.0, green = 115.0, blue = 110.0)
        assertEquals(RejectReason.NO_FINGER, invalid(PpgAnalyzer.analyze(frames)))
    }

    @Test fun darkFrameIsRejectedAsTooDark() {
        val frames = Synth.frames(Synth.beatsAtBpm(60.0, 60.0), dc = 12.0, amp = 0.1, green = 3.0, blue = 2.0)
        assertEquals(RejectReason.TOO_DARK, invalid(PpgAnalyzer.analyze(frames)))
    }

    @Test fun saturatedImageIsRejected() {
        val frames = Synth.frames(Synth.beatsAtBpm(60.0, 60.0), dc = 255.0, amp = 0.0)
        assertEquals(RejectReason.OVEREXPOSED, invalid(PpgAnalyzer.analyze(frames)))
    }

    @Test fun motionIsRejected() {
        val base = Synth.frames(Synth.beatsAtBpm(70.0, 60.0))
        // Finger repeatedly shifting: big baseline jumps every 2 seconds.
        val moved = base.map { s ->
            val t = s.timestampNs / 1e9
            val offset = if ((t / 2).toInt() % 2 == 0) 0.0 else 25.0
            s.copy(r = s.r - offset)
        }
        assertEquals(RejectReason.MOTION, invalid(PpgAnalyzer.analyze(moved)))
    }

    @Test fun tooShortIsRejected() {
        val frames = Synth.frames(Synth.beatsAtBpm(60.0, 20.0), durationS = 20.0)
        assertEquals(RejectReason.TOO_SHORT, invalid(PpgAnalyzer.analyze(frames)))
    }

    @Test fun lowFrameRateIsRejected() {
        val frames = Synth.frames(Synth.beatsAtBpm(60.0, 60.0), fps = 10.0)
        assertEquals(RejectReason.LOW_FRAME_RATE, invalid(PpgAnalyzer.analyze(frames)))
    }

    @Test fun pureNoiseIsRejected() {
        val rnd = Random(11)
        val frames = (0 until 1800).map { FrameSample(it * 33_333_333L, 200.0 + Synth.gaussian(rnd) * 2.0, 40.0, 25.0) }
        val reason = invalid(PpgAnalyzer.analyze(frames))
        assertTrue("$reason", reason in setOf(RejectReason.WEAK_SIGNAL, RejectReason.TOO_FEW_BEATS, RejectReason.TOO_MANY_REJECTED))
    }

    @Test fun unorderedAndDuplicateTimestampsAreHandled() {
        val frames = Synth.frames(Synth.beatsAtBpm(66.0, 60.0))
        val messy = (frames + frames.take(50)).shuffled(Random(2))
        val m = valid(PpgAnalyzer.analyze(messy))
        assertEquals(66.0, m.heartRateBpm, 2.0)
    }

    @Test fun emptyInputIsRejectedNotCrashing() {
        assertEquals(RejectReason.TOO_SHORT, invalid(PpgAnalyzer.analyze(emptyList())))
        val live = PpgAnalyzer.live(emptyList())
        assertEquals(0, live.waveform.size)
    }

    @Test fun liveStatusReportsPulseAndCoverage() {
        val frames = Synth.frames(Synth.beatsAtBpm(75.0, 10.0), durationS = 10.0)
        val live = PpgAnalyzer.live(frames)
        assertEquals(SignalQuality.GOOD, live.quality)
        assertNotNull(live.heartRateBpm)
        assertEquals(75.0, live.heartRateBpm!!, 4.0)
        assertTrue(live.waveform.isNotEmpty())
        assertNotNull(live.lastBeatNs)

        val uncovered = Synth.frames(Synth.beatsAtBpm(75.0, 10.0), durationS = 10.0, dc = 120.0, green = 118.0, blue = 117.0)
        assertEquals(SignalQuality.POOR, PpgAnalyzer.live(uncovered).quality)
    }
}
