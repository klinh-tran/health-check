package com.example.healthcheck.core.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/** Uncovered camera looking at a room: red, green and blue similar. */
private val ROOM: (FrameSample) -> FrameSample = { it.copy(r = 120.0, g = 115.0, b = 110.0, coverage = 0.0) }

private fun List<FrameSample>.during(fromS: Double, toS: Double, f: (FrameSample) -> FrameSample) =
    map { val t = it.timestampNs / 1e9; if (t >= fromS && t < toS) f(it) else it }

private fun valid(o: PpgOutcome): PpgMeasurement = when (o) {
    is PpgOutcome.Valid -> o.measurement
    is PpgOutcome.Invalid -> { fail("expected valid but was ${o.reason} ${o.diagnostics}"); error("") }
}

private fun invalid(o: PpgOutcome): RejectReason = when (o) {
    is PpgOutcome.Invalid -> o.reason
    is PpgOutcome.Valid -> { fail("expected invalid but got ${o.measurement}"); error("") }
}

private fun MeasurementSession.feed(frames: List<FrameSample>) = apply { frames.forEach(::onFrame) }

private fun lastS(s: MeasurementSession) = s.samples.last().timestampNs / 1e9
private fun firstS(s: MeasurementSession) = s.samples.first().timestampNs / 1e9

/** Simulated noisy, interrupted and weak fingertip signals, through the analyser and the session. */
class AnalyzerRobustnessTest {

    @Test fun analysingTwiceInARowGivesTheSameResultAndNothingLeaks() {
        val a = Synth.frames(Synth.beatsAtBpm(70.0, 60.0), noise = 0.3, seed = 4)
        val b = Synth.frames(Synth.beatsAtBpm(95.0, 60.0), noise = 0.8, seed = 9)
        val first = PpgAnalyzer.analyze(a)
        val other = PpgAnalyzer.analyze(b)
        val again = PpgAnalyzer.analyze(a)
        assertEquals(first, again)
        assertEquals(70.0, valid(first).heartRateBpm, 2.0)
        assertEquals(95.0, valid(other).heartRateBpm, 2.0)
        // A rejected run in between doesn't change the next one either.
        invalid(PpgAnalyzer.analyze(a.map(ROOM)))
        assertEquals(first, PpgAnalyzer.analyze(a))
    }

    @Test fun shortInterruptionsAreSkippedNotFatal() {
        val frames = Synth.frames(Synth.beatsAtBpm(70.0, 60.0))
            .during(15.0, 19.0, ROOM)
            .during(38.0, 41.0, ROOM)
        val m = valid(PpgAnalyzer.analyze(frames))
        assertEquals(70.0, m.heartRateBpm, 2.0)
        assertTrue("usable=${m.usableSeconds}", m.usableSeconds in 45.0..56.0)
    }

    @Test fun motionBurstsAreCutOutAndTheRestIsUsed() {
        val frames = Synth.frames(Synth.beatsAtBpm(66.0, 60.0))
            .during(12.0, 13.5) { it.copy(r = it.r - 30.0) }
            .during(30.0, 31.0) { it.copy(r = it.r + 30.0) }
            .during(47.0, 48.5) { it.copy(r = it.r - 30.0) }
        val r = PpgAnalyzer.analyze(frames)
        val m = valid(r)
        assertEquals(66.0, m.heartRateBpm, 2.0)
        assertTrue(r.diagnostics.lostMotionS > 0)
        assertTrue(r.diagnostics.segments >= 3)
    }

    @Test fun ordinaryHandMovementDoesNotInvalidateTheRun() {
        val rnd = Random(21)
        // Slow wobble, small shifts (3-4%) every few seconds, and frame noise.
        var offset = 0.0
        val frames = Synth.frames(Synth.beatsAtBpm(78.0, 60.0), noise = 0.5, driftAmp = 5.0, jitterS = 0.008, seed = 6)
            .map { s ->
                if (rnd.nextDouble() < 0.01) offset = (rnd.nextDouble() * 2 - 1) * 7.0
                s.copy(r = s.r + offset)
            }
        val m = valid(PpgAnalyzer.analyze(frames))
        assertEquals(78.0, m.heartRateBpm, 3.0)
        assertNotNull("heart rate and HRV are still reported", m.rmssdMs)
    }

    @Test fun noisySignalStillGivesHeartRate() {
        val frames = Synth.frames(Synth.beatsAtBpm(84.0, 60.0), noise = 1.2, jitterS = 0.01, seed = 13)
        val m = valid(PpgAnalyzer.analyze(frames))
        assertEquals(84.0, m.heartRateBpm, 3.0)
    }

    @Test fun weakButRealPulseIsAccepted() {
        val frames = Synth.frames(Synth.beatsAtBpm(62.0, 60.0), amp = 0.4, noise = 0.1, seed = 2)
        val m = valid(PpgAnalyzer.analyze(frames))
        assertEquals(62.0, m.heartRateBpm, 2.0)
    }

    @Test fun partialCreditGivesALowerConfidenceResult() {
        // Only the first ~36 s were usable.
        val frames = Synth.frames(Synth.beatsAtBpm(72.0, 60.0)).during(36.0, 60.0, ROOM)
        val m = valid(PpgAnalyzer.analyze(frames))
        assertEquals(72.0, m.heartRateBpm, 2.0)
        assertEquals(SignalQuality.LOW, m.quality)
        assertTrue(m.usableSeconds in 30.0..40.0)
        assertTrue("HRV is flagged when confidence is low", m.rmssdMs == null || m.hrvLowConfidence)
    }

    @Test fun tooLittleUsableSignalIsRejectedWithTheMainCause() {
        val frames = Synth.frames(Synth.beatsAtBpm(72.0, 60.0)).during(20.0, 60.0, ROOM)
        val r = PpgAnalyzer.analyze(frames)
        assertEquals(RejectReason.NO_FINGER, invalid(r))
        assertTrue(r.diagnostics.usableS < PpgConfig.MIN_USABLE_S)
    }

    @Test fun partlyCoveredSideLitFingertipIsAccepted() {
        // Flash below the lens: more green leaks through (g/r = 0.7 was rejected before), half the frame covered.
        val frames = Synth.frames(Synth.beatsAtBpm(68.0, 60.0), dc = 150.0, green = 105.0, blue = 60.0)
            .map { it.copy(coverage = 0.5, clipped = 0.3) }
        assertEquals(FrameCheck.OK, PpgAnalyzer.check(frames[100]))
        assertEquals(68.0, valid(PpgAnalyzer.analyze(frames)).heartRateBpm, 2.0)
    }

    @Test fun pureNoiseIsNeverAccepted() {
        for (level in listOf(0.5, 2.0, 5.0)) for (seed in 1..8) {
            val rnd = Random(seed)
            val frames = (0 until 1800).map { FrameSample(it * 33_333_333L, 200.0 + Synth.gaussian(rnd) * level, 40.0, 25.0) }
            val o = PpgAnalyzer.analyze(frames)
            assertTrue("noise $level seed $seed accepted: $o", o is PpgOutcome.Invalid)
        }
    }

    @Test fun frameChecksTellTheProblemsApart() {
        fun f(r: Double, g: Double, b: Double, coverage: Double = 1.0, clipped: Double = 0.0) =
            PpgAnalyzer.check(FrameSample(0, r, g, b, coverage, clipped))
        assertEquals(FrameCheck.OK, f(180.0, 40.0, 25.0))
        assertEquals(FrameCheck.OK, f(25.0, 12.0, 8.0)) // dim but a fingertip (MIN_RED was 40)
        assertEquals(FrameCheck.NO_FINGER, f(120.0, 115.0, 110.0)) // room
        assertEquals(FrameCheck.NO_FINGER, f(255.0, 250.0, 248.0, clipped = 1.0)) // bright white, not "too bright"
        assertEquals(FrameCheck.NO_FINGER, f(180.0, 40.0, 25.0, coverage = 0.1)) // only a sliver covered
        assertEquals(FrameCheck.TOO_BRIGHT, f(252.0, 80.0, 40.0, clipped = 0.95))
        assertEquals(FrameCheck.TOO_DARK, f(8.0, 2.0, 1.0))
    }
}

class MeasurementSessionTest {

    private fun good(seconds: Double, bpm: Double = 70.0) =
        Synth.frames(Synth.beatsAtBpm(bpm, seconds), durationS = seconds)

    @Test fun timerStartsOnlyOnceTheFingerIsDetected() {
        val frames = good(70.0).during(0.0, 4.0, ROOM)
        val s = MeasurementSession()
        s.feed(frames.filter { it.timestampNs < 3_000_000_000L })
        assertEquals(SessionPhase.WAITING_FOR_FINGER, s.phase)
        assertEquals(0.0, s.measuredS, 0.0)
        assertEquals(MeasureHint.COVER, s.hint(s.live()))
        s.feed(frames)
        assertEquals(EndReason.COMPLETE, s.endReason)
        assertTrue("collection starts with the finger", firstS(s) >= 4.0)
        // 4 s waiting + 1 s confirmation + 60 s timer.
        assertEquals(65.0, lastS(s), 0.3)
        assertTrue(s.measuredS >= PpgConfig.MEASUREMENT_S)
        assertEquals(70.0, valid(PpgAnalyzer.analyze(s.samples)).heartRateBpm, 2.0)
    }

    @Test fun givesUpAfterTheGracePeriodWithoutAFinger() {
        val s = MeasurementSession().feed(good(15.0).map(ROOM))
        assertEquals(SessionPhase.FINISHED, s.phase)
        assertEquals(EndReason.NO_FINGER_TIMEOUT, s.endReason)
        assertEquals(PpgConfig.GRACE_S, s.sinceFirstFrameS, 0.1)
    }

    @Test fun aShortDropPausesTheTimerAndResumes() {
        val s = MeasurementSession()
        val frames = good(75.0).during(20.0, 23.0, ROOM)
        s.feed(frames.filter { it.timestampNs < 22_000_000_000L })
        assertEquals(SessionPhase.PAUSED, s.phase)
        val pausedAt = s.measuredS
        s.feed(frames.filter { it.timestampNs < 22_900_000_000L })
        assertEquals("the timer doesn't move while paused", pausedAt, s.measuredS, 1e-9)
        s.feed(frames)
        assertEquals(EndReason.COMPLETE, s.endReason)
        assertEquals(1, s.pauses)
        // 1 s confirmation + 60 s counted + the 3 s drop + 0.5 s to resume.
        assertEquals(64.5, lastS(s), 0.4)
        assertEquals(70.0, valid(PpgAnalyzer.analyze(s.samples)).heartRateBpm, 2.0)
    }

    @Test fun aLongDropEndsTheRunButKeepsWhatWasCollected() {
        val s = MeasurementSession().feed(good(70.0).during(41.0, 50.0, ROOM))
        assertEquals(EndReason.SIGNAL_LOST, s.endReason)
        val m = valid(PpgAnalyzer.analyze(s.samples))
        assertEquals(70.0, m.heartRateBpm, 2.0)
        assertEquals(SignalQuality.LOW, m.quality)
    }

    @Test fun repeatedPausesCannotRunForever() {
        // 2 s good, 4 s bad, over and over: never 5 s of pause, never 60 s of signal.
        val frames = good(200.0).map { s -> if ((s.timestampNs / 1e9) % 6.0 >= 2.0) ROOM(s) else s }
        val s = MeasurementSession().feed(frames)
        assertEquals(EndReason.TIME_LIMIT, s.endReason)
        assertTrue(invalid(PpgAnalyzer.analyze(s.samples)) in RejectReason.entries)
    }

    @Test fun tooBrightStepsExposureDownThenLocksThenUnlocksOnDrift() {
        val bright: (FrameSample) -> FrameSample = { it.copy(r = 255.0, clipped = 1.0) }
        val frames = good(12.0).during(0.0, 3.0, bright).during(8.0, 10.0, bright)
        val s = MeasurementSession()
        s.setExposureRange(-4, 4)
        s.feed(frames.filter { it.timestampNs < 3_000_000_000L })
        assertEquals(MeasureHint.TOO_BRIGHT, s.hint(s.live()))
        assertTrue("exposure ${s.exposureSteps}", s.exposureSteps in -4..-2)
        assertFalse(s.exposureLocked)
        s.feed(frames.filter { it.timestampNs < 7_000_000_000L })
        assertTrue("locked once the image is good for 2 s", s.exposureLocked)
        val steps = s.exposureSteps
        s.feed(frames.filter { it.timestampNs < 9_800_000_000L })
        assertFalse("unlocked when the image drifts out of range", s.exposureLocked)
        assertTrue(s.exposureSteps <= steps)
    }

    @Test fun exposureStaysPutWhenTheCameraHasNoCompensation() {
        val s = MeasurementSession().feed(good(3.0).map { it.copy(r = 255.0, clipped = 1.0) })
        assertEquals(0, s.exposureSteps)
    }

    @Test fun hintsDescribeWhatToDo() {
        val dark = MeasurementSession().feed(good(3.0).map { it.copy(r = 10.0, g = 3.0, b = 2.0) })
        assertEquals(MeasureHint.TOO_DARK, dark.hint(dark.live()))

        val fine = MeasurementSession().feed(good(9.0))
        assertEquals(MeasureHint.GOOD, fine.hint(fine.live()))

        val pressed = MeasurementSession().feed(Synth.frames(Synth.beatsAtBpm(70.0, 9.0), durationS = 9.0, amp = 0.02))
        assertEquals(MeasureHint.PRESS_LIGHTER, pressed.hint(pressed.live()))

        // The finger shifts by ~20% every 2 s.
        val moving = MeasurementSession().feed(
            good(9.0).map { s -> s.copy(r = s.r + if ((s.timestampNs / 2_000_000_000L) % 2 == 0L) 0.0 else 40.0) },
        )
        assertEquals(MeasureHint.HOLD_STILL, moving.hint(moving.live()))
    }

    @Test fun twoSessionsInARowAreIndependent() {
        val frames = good(70.0, bpm = 64.0).during(25.0, 27.0, ROOM)
        val a = MeasurementSession().feed(frames)
        val b = MeasurementSession().feed(frames)
        assertEquals(a.samples, b.samples)
        assertEquals(a.measuredS, b.measuredS, 0.0)
        assertEquals(PpgAnalyzer.analyze(a.samples), PpgAnalyzer.analyze(b.samples))
        // A finished session ignores anything sent later.
        val size = a.samples.size
        a.feed(good(5.0))
        assertEquals(size, a.samples.size)
    }

    @Test fun lateAndDuplicateFramesAreIgnored() {
        val frames = good(70.0)
        val s = MeasurementSession()
        frames.forEachIndexed { i, f ->
            s.onFrame(f)
            if (i % 50 == 0) s.onFrame(f) // duplicate
            if (i > 10 && i % 70 == 0) s.onFrame(frames[i - 10]) // late
        }
        assertEquals(EndReason.COMPLETE, s.endReason)
        assertEquals(s.samples.distinct().size, s.samples.size)
    }
}
