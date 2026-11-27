package com.example.healthcheck.core.signal

import com.example.healthcheck.core.math.Stats
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One camera frame reduced to a few numbers.
 * [r], [g], [b]: mean colour of the pixels that look like a lit fingertip (the whole frame when too few do).
 * [coverage]: fraction of the frame that looks like a lit fingertip (red-dominant, not clipped).
 * [clipped]: fraction of the frame whose red channel is saturated.
 * Nothing here assumes a lens or finger position: the whole frame is measured.
 */
data class FrameSample(
    val timestampNs: Long,
    val r: Double,
    val g: Double,
    val b: Double,
    val coverage: Double = 1.0,
    val clipped: Double = 0.0,
)

/** What one frame shows. Only [OK] frames are used for the pulse. */
enum class FrameCheck { OK, NO_FINGER, TOO_DARK, TOO_BRIGHT }

enum class SignalQuality(val label: String) {
    GOOD("Good"), FAIR("Fair"), LOW("Lower confidence"), POOR("Poor"),
}

enum class RejectReason(val message: String) {
    TOO_SHORT("The measurement was too short to analyse. Keep your fingertip in place until the timer finishes."),
    LOW_FRAME_RATE("The camera delivered too few frames per second to follow your pulse. Close other camera apps and try again."),
    NO_FINGER("No fingertip was detected for most of the measurement. Cover the camera lens and the flash with one fingertip."),
    TOO_DARK("The camera image was too dark to see your pulse. Press more lightly and make sure your fingertip also covers the flash."),
    OVEREXPOSED("The camera image was too bright to see your pulse. Cover the flash fully with your fingertip."),
    MOTION("There was too much movement. Rest your hand on a table and keep it still."),
    WEAK_SIGNAL("The pulse signal was too weak. Press lightly (not hard), keep your finger warm and still, and try again."),
    TOO_FEW_BEATS("Not enough clear heartbeats were found. Keep still and try again."),
    TOO_MANY_REJECTED("Too many beats were unclear or irregular. Keep still, breathe normally and try again."),
}

data class PpgMeasurement(
    val heartRateBpm: Double,
    /** Null when too few clean beat pairs were found; never made up. */
    val rmssdMs: Double?,
    /** HRV was computed but the signal was shaky: show it, but say it is less certain. */
    val hrvLowConfidence: Boolean,
    /** Null when it cannot be estimated with reasonable confidence; never made up. */
    val breathingRatePerMin: Double?,
    val quality: SignalQuality,
    val validBeats: Int,
    val totalBeats: Int,
    val keptRatio: Double,
    val usableSeconds: Double,
)

/** Numbers behind an analysis, for the optional on-screen signal details. */
data class AnalysisDiagnostics(
    val totalS: Double = 0.0,
    val usableS: Double = 0.0,
    val segments: Int = 0,
    val lostNoFingerS: Double = 0.0,
    val lostDarkS: Double = 0.0,
    val lostBrightS: Double = 0.0,
    val lostMotionS: Double = 0.0,
    val lostWeakS: Double = 0.0,
    val fps: Double = 0.0,
    val meanRed: Double = 0.0,
    val relAmplitude: Double = 0.0,
    val periodicity: Double = 0.0,
    val keptRatio: Double = 0.0,
    val validBeats: Int = 0,
)

sealed interface PpgOutcome {
    val diagnostics: AnalysisDiagnostics

    data class Valid(val measurement: PpgMeasurement, override val diagnostics: AnalysisDiagnostics) : PpgOutcome
    data class Invalid(val reason: RejectReason, override val diagnostics: AnalysisDiagnostics) : PpgOutcome
}

/** Live view of the last few seconds. */
class LiveStatus(
    /** What most frames in the last second show. */
    val check: FrameCheck,
    val quality: SignalQuality,
    /** 0..100, drives the quality bar. */
    val score: Int,
    val heartRateBpm: Double?,
    /** Recent band-passed waveform, normalised to -1..1, oldest first. */
    val waveform: FloatArray,
    /** Sensor timestamp of the most recent detected beat. */
    val lastBeatNs: Long?,
    val meanRed: Double,
    /** Pulse amplitude relative to the brightness (std of the filtered signal / mean red). */
    val relAmplitude: Double,
    val motion: Double,
    val coverage: Double,
    val clipped: Double,
    val fps: Double,
    /** Seconds of uninterrupted usable frames up to now. */
    val stableS: Double,
) {
    companion object {
        val EMPTY = LiveStatus(FrameCheck.NO_FINGER, SignalQuality.POOR, 0, null, FloatArray(0), null, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    }
}

/**
 * Every threshold in one place. Comments give the previous value where it was relaxed so ordinary
 * placement (any lens, flash below or beside it, a slightly moving hand) is not rejected.
 */
object PpgConfig {
    const val FS = 30.0
    /** Seconds of good signal the timer counts to. */
    const val MEASUREMENT_S = 60.0
    const val LOW_HZ = 0.7
    const val HIGH_HZ = 3.5
    const val DETREND_WINDOW_S = 3.0
    const val MIN_PERIOD_S = 60.0 / 210.0
    const val MAX_PERIOD_S = 60.0 / 42.0
    const val MIN_DISTANCE_FRACTION = 0.6

    // ---- Start-up, pauses ----
    /** Fingertip must be seen this long before the timer starts. */
    const val FINGER_CONFIRM_S = 1.0
    /** Wait this long for a fingertip before giving up. */
    const val GRACE_S = 10.0
    /** Bad frames for this long pause the timer (shorter blips are ignored). */
    const val PAUSE_AFTER_S = 0.5
    /** Good frames for this long resume it. */
    const val RESUME_AFTER_S = 0.5
    /** A pause longer than this ends the run (what was collected is still analysed). */
    const val MAX_PAUSE_S = 5.0
    /** Hard cap from the timer start, so repeated pauses can't run forever. */
    const val MAX_TOTAL_S = 120.0

    // ---- Finger detection (per frame) ----
    /** Was 40. Darker skin, a flash further from the lens and auto-exposure on a dim frame can sit well below 40. */
    const val MIN_RED = 20.0
    /** Was 0.6. A fingertip lit through the side (flash below the lens) lets more green/blue through. */
    const val MAX_GREEN_TO_RED = 0.8
    const val MAX_BLUE_TO_RED = 0.8
    /** New: at least this much of the frame must look like a lit fingertip (partial cover is fine). */
    const val MIN_COVERAGE = 0.3
    const val SATURATED_RED = 250.0
    /** New: a frame this clipped carries no pulse (it was "mean red >= 250" before). */
    const val TOO_BRIGHT_CLIPPED = 0.85

    // ---- Segments ----
    /** Trimmed from the start of every good stretch (finger and exposure settling). Was a fixed 5 s warm-up. */
    const val SEGMENT_SETTLE_S = 1.0
    /** Stretches of good signal shorter than this are not used. */
    const val MIN_SEGMENT_S = 5.0
    /** A gap between frames longer than this splits a segment. */
    const val MAX_FRAME_GAP_S = 0.5

    // ---- Acceptance ----
    /** Was "40 s, and 95% of the minute covered". Now: at least 30 s of usable signal, from any part of the run. */
    const val MIN_USABLE_S = 30.0
    const val GOOD_USABLE_S = 50.0
    const val FAIR_USABLE_S = 40.0
    /** Was 15. Some phones drop to ~12-15 fps with the torch on and the signal is still fine. */
    const val MIN_FPS = 12.0
    /** Was 30-40 beats. Now scaled with usable time: half of the beats expected, at least 15. */
    const val MIN_VALID_BEATS_FLOOR = 15
    const val REQUIRED_BEAT_FRACTION = 0.5
    /** Was 0.70. Below this the beats are too unclear even for heart rate. */
    const val MIN_KEPT_RATIO = 0.5
    /** Was 20 and required. Now HRV is shown from 10 pairs, flagged "low confidence" below [HRV_CONFIDENT_PAIRS]. */
    const val MIN_RMSSD_PAIRS = 10
    const val HRV_CONFIDENT_PAIRS = 25
    const val HRV_CONFIDENT_KEPT_RATIO = 0.8

    // ---- Motion (per 1-second window): motion windows are cut out instead of failing the run ----
    /** Was 0.05. A 5% baseline jump happens when a finger rolls slightly; 8% is a real shift. */
    const val MOTION_JUMP_FRACTION = 0.08
    /** Was 3.0. */
    const val MOTION_AMPLITUDE_RATIO = 4.0
    /** New: smaller sudden shifts (above this many noise sigmas and 1% of the level) are levelled out instead. */
    const val STEP_SIGMA = 6.0
    const val STEP_MIN_FRACTION = 0.01

    // ---- Pulse strength ----
    const val PERIODICITY_GOOD = 0.6
    const val PERIODICITY_FAIR = 0.4
    /** Was 0.35 for the whole minute. Now per segment. */
    const val PERIODICITY_MIN = 0.3
    /** Was 0.0002. */
    const val MIN_RELATIVE_AMPLITUDE = 0.0001
    const val GOOD_KEPT_RATIO = 0.85
    const val FAIR_KEPT_RATIO = 0.7

    // ---- Live hints ----
    /** Below this relative amplitude with a covered lens, the finger is probably pressing too hard. */
    const val PRESS_LIGHTER_REL_AMPLITUDE = 0.0004
    const val LIVE_MOTION_HOLD_STILL = 0.3
}

object PpgAnalyzer {

    /** Classifies one frame. Colour is judged first, so a bright white room is "no finger", not "too bright". */
    fun check(s: FrameSample): FrameCheck {
        val reddish = s.g <= PpgConfig.MAX_GREEN_TO_RED * s.r && s.b <= PpgConfig.MAX_BLUE_TO_RED * s.r
        return when {
            s.r < PpgConfig.MIN_RED -> FrameCheck.TOO_DARK
            !reddish -> FrameCheck.NO_FINGER
            s.clipped >= PpgConfig.TOO_BRIGHT_CLIPPED || s.r >= PpgConfig.SATURATED_RED -> FrameCheck.TOO_BRIGHT
            s.coverage < PpgConfig.MIN_COVERAGE -> FrameCheck.NO_FINGER
            else -> FrameCheck.OK
        }
    }

    fun isCovered(s: FrameSample) = check(s) == FrameCheck.OK

    /** Sorted by time, duplicates and out-of-order frames dropped. */
    fun prepare(samples: List<FrameSample>): List<FrameSample> {
        val sorted = samples.sortedBy { it.timestampNs }
        val out = ArrayList<FrameSample>(sorted.size)
        for (s in sorted) if (out.isEmpty() || s.timestampNs > out.last().timestampNs) out.add(s)
        return out
    }

    private fun times(s: List<FrameSample>, t0: Long) = DoubleArray(s.size) { (s[it].timestampNs - t0) / 1e9 }

    /** A stretch of clean signal, filtered on its own. */
    private class Segment(val signal: UniformSignal, val meanRed: Double, val relAmp: Double, val period: Dsp.Periodicity) {
        val durationS get() = signal.size / signal.fs
    }

    private class Losses {
        var noFinger = 0.0
        var dark = 0.0
        var bright = 0.0
        var motion = 0.0
        var weak = 0.0

        /** The main reason the run had too little usable signal. */
        fun dominant(): RejectReason {
            val all = listOf(
                RejectReason.NO_FINGER to noFinger, RejectReason.TOO_DARK to dark, RejectReason.OVEREXPOSED to bright,
                RejectReason.MOTION to motion, RejectReason.WEAK_SIGNAL to weak,
            )
            val best = all.maxBy { it.second }
            return if (best.second <= 0.0) RejectReason.WEAK_SIGNAL else best.first
        }
    }

    /**
     * Analyses a finished run using only its good parts:
     * 1. frames are classified (finger / dark / bright); only stretches of good frames are kept,
     * 2. each stretch is cut where the hand moved (baseline jumps, amplitude bursts),
     * 3. each remaining segment of at least 5 s is filtered on its own and must show a pulse,
     * 4. beats are found per segment; intervals never span a gap,
     * 5. the result is accepted with at least 30 s of usable signal; confidence reflects how much and how clean.
     */
    fun analyze(rawSamples: List<FrameSample>): PpgOutcome {
        val samples = prepare(rawSamples)
        if (samples.size < 10) return PpgOutcome.Invalid(RejectReason.TOO_SHORT, AnalysisDiagnostics())
        val t = times(samples, samples.first().timestampNs)
        val totalS = t.last()
        val checks = samples.map(::check)
        val losses = Losses()

        var okTime = 0.0
        var okFrames = 0
        for (i in samples.indices) {
            val d = if (i + 1 < samples.size) min(t[i + 1] - t[i], PpgConfig.MAX_FRAME_GAP_S) else 0.0
            when (checks[i]) {
                FrameCheck.OK -> { okTime += d; okFrames++ }
                FrameCheck.NO_FINGER -> losses.noFinger += d
                FrameCheck.TOO_DARK -> losses.dark += d
                FrameCheck.TOO_BRIGHT -> losses.bright += d
            }
        }
        val fps = if (okTime > 0) okFrames / okTime else 0.0

        // 1. Runs of consecutive good frames.
        val segments = ArrayList<Segment>()
        var i = 0
        while (i < samples.size) {
            if (checks[i] != FrameCheck.OK) { i++; continue }
            var j = i
            while (j + 1 < samples.size && checks[j + 1] == FrameCheck.OK && t[j + 1] - t[j] <= PpgConfig.MAX_FRAME_GAP_S) j++
            val settled = (i..j).firstOrNull { t[it] >= t[i] + PpgConfig.SEGMENT_SETTLE_S }
            if (settled != null && t[j] - t[settled] >= PpgConfig.MIN_SEGMENT_S) {
                segments += splitAndFilter(samples.subList(settled, j + 1), losses)
            } else {
                // Too brief to use: counts as intermittent coverage.
                losses.noFinger += t[j] - t[i]
            }
            i = j + 1
        }

        val usableS = segments.sumOf { it.durationS }
        fun diag(kept: Double = 0.0, valid: Int = 0) = AnalysisDiagnostics(
            totalS = totalS, usableS = usableS, segments = segments.size,
            lostNoFingerS = losses.noFinger, lostDarkS = losses.dark, lostBrightS = losses.bright,
            lostMotionS = losses.motion, lostWeakS = losses.weak, fps = fps,
            meanRed = weighted(segments) { it.meanRed }, relAmplitude = weighted(segments) { it.relAmp },
            periodicity = weighted(segments) { it.period.strength }, keptRatio = kept, validBeats = valid,
        )

        if (totalS < PpgConfig.MIN_USABLE_S) return PpgOutcome.Invalid(RejectReason.TOO_SHORT, diag())
        if (okFrames > 0 && fps < PpgConfig.MIN_FPS) return PpgOutcome.Invalid(RejectReason.LOW_FRAME_RATE, diag())
        if (usableS < PpgConfig.MIN_USABLE_S) return PpgOutcome.Invalid(losses.dominant(), diag())

        // 4. Beats per segment, cleaned against the recording-wide typical interval.
        val rawPerSegment = segments.map { seg ->
            val peaks = PeakDetector.detect(seg.signal, PpgConfig.MIN_DISTANCE_FRACTION * seg.period.periodSamples / PpgConfig.FS)
            IbiCleaner.fromPeaks(peaks)
        }
        val plausible = rawPerSegment.flatten().map { it.first }.filter { it in IbiCleaner.MIN_IBI_MS..IbiCleaner.MAX_IBI_MS }
        if (plausible.isEmpty()) return PpgOutcome.Invalid(RejectReason.TOO_FEW_BEATS, diag())
        val reference = Stats.median(plausible)
        val cleaned = rawPerSegment.map { IbiCleaner.clean(it, reference) }
        val all = IbiCleanResult(
            ibis = cleaned.flatMap { it.ibis },
            totalCount = cleaned.sumOf { it.totalCount },
            validCount = cleaned.sumOf { it.validCount },
            mergedDoubles = cleaned.sumOf { it.mergedDoubles },
            missedBeats = cleaned.sumOf { it.missedBeats },
        )
        val d = diag(all.keptRatio, all.validCount)
        val hr = Hrv.meanHeartRate(all.valid) ?: return PpgOutcome.Invalid(RejectReason.TOO_FEW_BEATS, d)

        val required = max(
            PpgConfig.MIN_VALID_BEATS_FLOOR,
            floor(PpgConfig.REQUIRED_BEAT_FRACTION * usableS * hr / 60.0).toInt(),
        )
        if (all.validCount < required) return PpgOutcome.Invalid(RejectReason.TOO_FEW_BEATS, d)
        if (all.keptRatio < PpgConfig.MIN_KEPT_RATIO) return PpgOutcome.Invalid(RejectReason.TOO_MANY_REJECTED, d)

        val strength = d.periodicity
        val quality = when {
            usableS >= PpgConfig.GOOD_USABLE_S && all.keptRatio >= PpgConfig.GOOD_KEPT_RATIO &&
                strength >= PpgConfig.PERIODICITY_GOOD -> SignalQuality.GOOD
            usableS >= PpgConfig.FAIR_USABLE_S && all.keptRatio >= PpgConfig.FAIR_KEPT_RATIO &&
                strength >= PpgConfig.PERIODICITY_FAIR -> SignalQuality.FAIR
            else -> SignalQuality.LOW
        }

        // Successive differences only inside a segment (a gap breaks the chain).
        val diffs = cleaned.flatMap { Hrv.successiveDifferences(it.ibis) }
        val rmssd = if (diffs.size >= PpgConfig.MIN_RMSSD_PAIRS) Hrv.rmssd(diffs) else null
        val hrvLow = rmssd != null && (quality == SignalQuality.LOW || diffs.size < PpgConfig.HRV_CONFIDENT_PAIRS ||
            all.keptRatio < PpgConfig.HRV_CONFIDENT_KEPT_RATIO)
        // Breathing needs one long uninterrupted stretch.
        val longest = segments.indices.maxBy { segments[it].durationS }
        val breathing = if (segments[longest].durationS >= BreathingEstimator.MIN_SPAN_S) BreathingEstimator.estimate(cleaned[longest]) else null

        return PpgOutcome.Valid(
            PpgMeasurement(
                heartRateBpm = hr,
                rmssdMs = rmssd,
                hrvLowConfidence = hrvLow,
                breathingRatePerMin = breathing,
                quality = quality,
                validBeats = all.validCount,
                totalBeats = all.totalCount,
                keptRatio = all.keptRatio,
                usableSeconds = usableS,
            ),
            d,
        )
    }

    private inline fun weighted(segments: List<Segment>, f: (Segment) -> Double): Double {
        val total = segments.sumOf { it.durationS }
        return if (total <= 0) 0.0 else segments.sumOf { f(it) * it.durationS } / total
    }

    /** 2 + 3: cut a stretch of good frames where the hand moved, then filter and check each piece. */
    private fun splitAndFilter(run: List<FrameSample>, losses: Losses): List<Segment> {
        val t0 = run.first().timestampNs
        val raw = Dsp.resample(times(run, t0), DoubleArray(run.size) { run[it].r }, PpgConfig.FS)
        val values = levelSteps(raw.values)
        val w = PpgConfig.FS.toInt()
        val windows = values.size / w
        if (windows < 2) return emptyList()
        val filtered = bandPassInverted(values)
        val means = DoubleArray(windows) { k -> Stats.mean(values.copyOfRange(k * w, (k + 1) * w)) }
        val stds = DoubleArray(windows) { k -> Stats.std(filtered.copyOfRange(k * w, (k + 1) * w)) }
        val overall = Stats.mean(means)
        val typical = Stats.median(stds.toList())
        val flagged = BooleanArray(windows)
        for (k in 0 until windows) {
            val jump = k > 0 && overall > 0 && abs(means[k] - means[k - 1]) / overall > PpgConfig.MOTION_JUMP_FRACTION
            if (jump) { flagged[k] = true; flagged[k - 1] = true }
            if (typical > 0 && stds[k] > PpgConfig.MOTION_AMPLITUDE_RATIO * typical) flagged[k] = true
        }
        losses.motion += flagged.count { it }.toDouble()

        val out = ArrayList<Segment>()
        val minWindows = PpgConfig.MIN_SEGMENT_S.toInt()
        var a = 0
        while (a < windows) {
            if (flagged[a]) { a++; continue }
            var b = a
            while (b + 1 < windows && !flagged[b + 1]) b++
            val count = b - a + 1
            // The last window also takes the leftover samples after it.
            val end = if (b == windows - 1) values.size else (b + 1) * w
            if (count < minWindows) {
                losses.motion += (end - a * w) / PpgConfig.FS
            } else {
                val piece = values.copyOfRange(a * w, end)
                val f = bandPassInverted(piece)
                val meanRed = Stats.mean(piece)
                val relAmp = if (meanRed > 0) Stats.std(f) / meanRed else 0.0
                val period = Dsp.dominantPeriod(f, PpgConfig.FS, PpgConfig.MIN_PERIOD_S, PpgConfig.MAX_PERIOD_S)
                if (relAmp < PpgConfig.MIN_RELATIVE_AMPLITUDE || period == null || period.strength < PpgConfig.PERIODICITY_MIN) {
                    losses.weak += piece.size / PpgConfig.FS
                } else {
                    out += Segment(UniformSignal(f, PpgConfig.FS, raw.timeOf((a * w).toDouble())), meanRed, relAmp, period)
                }
            }
            a = b + 1
        }
        return out
    }

    /**
     * Removes small sudden baseline shifts (a finger settling or rolling slightly): a frame-to-frame jump
     * much bigger than the pulse's own steps, but below the motion threshold, is subtracted from everything
     * after it. Bigger jumps are left in so the motion check cuts them out. A pulse upstroke changes the
     * level by well under 1% per frame, so it is never touched.
     */
    fun levelSteps(x: DoubleArray): DoubleArray {
        if (x.size < 3) return x.copyOf()
        val d = DoubleArray(x.size - 1) { x[it + 1] - x[it] }
        val med = Stats.median(d)
        val sigma = 1.4826 * Stats.median(DoubleArray(d.size) { abs(d[it] - med) })
        val level = Stats.mean(x)
        val threshold = max(PpgConfig.STEP_SIGMA * sigma, PpgConfig.STEP_MIN_FRACTION * level)
        val limit = PpgConfig.MOTION_JUMP_FRACTION * level
        val out = DoubleArray(x.size)
        out[0] = x[0]
        var shift = 0.0
        for (i in 1 until x.size) {
            val step = d[i - 1]
            if (abs(step) > threshold && abs(step) < limit) shift += step - med
            out[i] = x[i] - shift
        }
        return out
    }

    /** Blood volume increases absorb more light, so the signal is inverted: peaks = systolic pulses. */
    private fun bandPassInverted(values: DoubleArray): DoubleArray {
        val inv = DoubleArray(values.size) { -values[it] }
        return Dsp.bandPass(inv, PpgConfig.FS, PpgConfig.LOW_HZ, PpgConfig.HIGH_HZ, PpgConfig.DETREND_WINDOW_S)
    }

    /** Fraction of 1-second windows showing motion (baseline jump or amplitude burst). */
    fun motionFraction(raw: DoubleArray, filtered: DoubleArray, fs: Double): Double {
        val w = fs.toInt()
        val windows = min(raw.size, filtered.size) / w
        if (windows < 2) return 0.0
        val means = DoubleArray(windows) { k -> Stats.mean(raw.copyOfRange(k * w, (k + 1) * w)) }
        val stds = DoubleArray(windows) { k -> Stats.std(filtered.copyOfRange(k * w, (k + 1) * w)) }
        val overallMean = Stats.mean(means)
        val typicalStd = Stats.median(stds.toList())
        if (overallMean <= 0) return 1.0
        var flagged = 0
        for (k in 0 until windows) {
            val jump = if (k > 0) abs(means[k] - means[k - 1]) / overallMean else 0.0
            val bigAmp = typicalStd > 0 && stds[k] > PpgConfig.MOTION_AMPLITUDE_RATIO * typicalStd
            if (jump > PpgConfig.MOTION_JUMP_FRACTION || bigAmp) flagged++
        }
        return flagged.toDouble() / windows
    }

    /** 0..100 from pulse regularity, amplitude and steadiness. */
    fun score(periodicity: Double, relAmplitude: Double, motion: Double): Int {
        val reg = ((periodicity - 0.15) / 0.5).coerceIn(0.0, 1.0)
        val amp = ((relAmplitude - PpgConfig.MIN_RELATIVE_AMPLITUDE) / 0.0009).coerceIn(0.0, 1.0)
        val still = (1.0 - 2.0 * motion).coerceIn(0.0, 1.0)
        return (100 * reg * (0.4 + 0.6 * amp) * still).roundToInt()
    }

    /** Lightweight analysis of the most recent seconds, for the live UI. Uses only the latest stretch of good frames. */
    fun live(rawSamples: List<FrameSample>, waveformSeconds: Double = 5.0, windowSeconds: Double = 8.0): LiveStatus {
        val samples = prepare(rawSamples)
        if (samples.isEmpty()) return LiveStatus.EMPTY
        val endNs = samples.last().timestampNs
        val lastSecond = samples.filter { endNs - it.timestampNs <= 1_000_000_000L }
        val dominant = lastSecond.groupingBy(::check).eachCount().maxBy { it.value }.key
        val last = lastSecond.last()
        val fps = if (lastSecond.size >= 2) (lastSecond.size - 1) / ((endNs - lastSecond.first().timestampNs) / 1e9) else 0.0

        // Trailing stretch of good frames, at most [windowSeconds].
        var start = samples.size
        while (start > 0) {
            val s = samples[start - 1]
            if (!isCovered(s)) break
            if (start < samples.size && samples[start].timestampNs - s.timestampNs > (PpgConfig.MAX_FRAME_GAP_S * 1e9).toLong()) break
            if ((endNs - s.timestampNs) / 1e9 > windowSeconds) break
            start--
        }
        val run = samples.subList(start, samples.size)
        val stableS = if (run.size >= 2) (endNs - run.first().timestampNs) / 1e9 else 0.0
        val meanRed = Stats.mean(DoubleArray(lastSecond.size) { lastSecond[it].r })
        fun status(score: Int = 0, hr: Double? = null, wave: FloatArray = FloatArray(0), beat: Long? = null, rel: Double = 0.0, motion: Double = 0.0) =
            LiveStatus(
                check = dominant,
                quality = when {
                    dominant != FrameCheck.OK -> SignalQuality.POOR
                    score >= 60 -> SignalQuality.GOOD
                    score >= 30 -> SignalQuality.FAIR
                    else -> SignalQuality.POOR
                },
                score = if (dominant == FrameCheck.OK) score else 0,
                heartRateBpm = hr, waveform = wave, lastBeatNs = beat,
                meanRed = meanRed, relAmplitude = rel, motion = motion,
                coverage = last.coverage, clipped = last.clipped, fps = fps, stableS = stableS,
            )
        if (stableS < 2.5) return status()

        val t0 = run.first().timestampNs
        val resampled = Dsp.resample(times(run, t0), DoubleArray(run.size) { run[it].r }, PpgConfig.FS)
        val raw = UniformSignal(levelSteps(resampled.values), resampled.fs, resampled.startTimeS)
        val filtered = UniformSignal(bandPassInverted(raw.values), PpgConfig.FS, raw.startTimeS)
        val waveN = min(filtered.size, (waveformSeconds * PpgConfig.FS).toInt())
        val tail = filtered.values.copyOfRange(filtered.size - waveN, filtered.size)
        val peakAbs = tail.maxOfOrNull { abs(it) }?.takeIf { it > 1e-9 } ?: 1.0
        val waveform = FloatArray(waveN) { (tail[it] / peakAbs).toFloat() }

        val n3 = 3 * PpgConfig.FS.toInt()
        val rawTail = raw.values.copyOfRange(max(0, raw.size - n3), raw.size)
        val filtTail = filtered.values.copyOfRange(max(0, filtered.size - n3), filtered.size)
        val motion = motionFraction(rawTail, filtTail, PpgConfig.FS)
        val mean = Stats.mean(rawTail)
        val rel = if (mean > 0) Stats.std(filtTail) / mean else 0.0
        val period = Dsp.dominantPeriod(filtered.values, PpgConfig.FS, PpgConfig.MIN_PERIOD_S, PpgConfig.MAX_PERIOD_S)
        val peaks = if (period != null) {
            PeakDetector.detect(filtered, PpgConfig.MIN_DISTANCE_FRACTION * period.periodSamples / PpgConfig.FS)
        } else emptyList()
        val ibis = peaks.zipWithNext { a, b -> (b - a) * 1000 }.filter { it in IbiCleaner.MIN_IBI_MS..IbiCleaner.MAX_IBI_MS }
        val score = score(period?.strength ?: 0.0, rel, motion)
        val hr = if (ibis.size >= 3 && score >= 30) 60000.0 / Stats.median(ibis) else null
        // Ignore the last ~0.2 s: zero-phase filtering is least reliable at the very edge.
        val endS = (endNs - t0) / 1e9
        val lastBeat = peaks.lastOrNull { it <= endS - 0.2 }?.let { t0 + (it * 1e9).toLong() }
        return status(score, hr, waveform, lastBeat, rel, motion)
    }
}
