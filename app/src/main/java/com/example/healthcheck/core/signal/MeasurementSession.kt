package com.example.healthcheck.core.signal

import kotlin.math.min

enum class SessionPhase { WAITING_FOR_FINGER, MEASURING, PAUSED, FINISHED }

enum class EndReason {
    /** The timer reached [PpgConfig.MEASUREMENT_S] seconds of good signal. */
    COMPLETE,
    /** No fingertip within [PpgConfig.GRACE_S]. */
    NO_FINGER_TIMEOUT,
    /** Paused longer than [PpgConfig.MAX_PAUSE_S]: what was collected is still analysed. */
    SIGNAL_LOST,
    /** Ran into [PpgConfig.MAX_TOTAL_S]: what was collected is still analysed. */
    TIME_LIMIT,
}

/** The one live hint shown to the user. */
enum class MeasureHint(val text: String) {
    COVER("Cover the lens and flash with your fingertip"),
    TOO_DARK("Too dark: press more lightly and cover the flash too"),
    TOO_BRIGHT("Too bright: adjusting the camera. Cover the flash fully"),
    FINGER_FOUND("Finger detected. Hold still…"),
    SEARCHING("Finding your pulse… keep still"),
    PRESS_LIGHTER("Press more lightly"),
    HOLD_STILL("Hold still"),
    GOOD("Looking good"),
}

/**
 * One measurement run, fed frame by frame. Pure Kotlin (no Android), created fresh for every run so
 * nothing can carry over from a previous one.
 *
 * - Waits up to [PpgConfig.GRACE_S] for a fingertip; the timer starts once it has been seen for [PpgConfig.FINGER_CONFIRM_S].
 * - The timer only counts good signal: it pauses on lost/dark/bright frames and resumes when they're good again.
 * - Asks the camera for exposure steps when the image is too bright or too dark, and for an AE/AWB lock once
 *   the image has been good for a while (unlocking again if it drifts out of range).
 * Times come from the camera's sensor timestamps, so the logic is the same in tests and on the phone.
 */
class MeasurementSession {

    var phase: SessionPhase = SessionPhase.WAITING_FOR_FINGER
        private set
    var endReason: EndReason? = null
        private set

    /** Seconds the timer has counted. */
    var measuredS: Double = 0.0
        private set
    /** Length of the current pause in seconds (0 when not paused). */
    var pausedS: Double = 0.0
        private set
    /** Seconds since the first frame (for the grace countdown). */
    var sinceFirstFrameS: Double = 0.0
        private set
    var pauses: Int = 0
        private set

    /** Requested exposure compensation, in the camera's steps. */
    var exposureSteps: Int = 0
        private set
    /** Requested AE/AWB lock. */
    var exposureLocked: Boolean = false
        private set
    private var evMin = 0
    private var evMax = 0

    private val collected = ArrayList<FrameSample>(4000)
    /** Frames collected since the timer started (pauses included; the analyser skips bad frames). */
    val samples: List<FrameSample> get() = collected

    /** The last ~10 s of frames, in every phase, for the live view. */
    private val recent = ArrayDeque<FrameSample>()
    private val waitingRun = ArrayList<FrameSample>()

    private var firstNs = -1L
    private var lastNs = -1L
    private var timerStartNs = -1L
    private var okSinceNs = -1L
    private var badSinceNs = -1L
    private var pauseStartNs = -1L

    private var exposureOkSinceNs = -1L
    private var brightSinceNs = -1L
    private var darkSinceNs = -1L
    private var lastExposureChangeNs = -1L

    /** The camera's supported exposure compensation range (0..0 when not supported). */
    fun setExposureRange(min: Int, max: Int) {
        evMin = minOf(min, 0)
        evMax = maxOf(max, 0)
        exposureSteps = exposureSteps.coerceIn(evMin, evMax)
    }

    /** Feeds one frame. Late or duplicate frames are ignored. */
    fun onFrame(s: FrameSample) {
        if (phase == SessionPhase.FINISHED) return
        val ts = s.timestampNs
        if (lastNs >= 0 && ts <= lastNs) return
        if (firstNs < 0) firstNs = ts
        val dt = if (lastNs < 0) 0.0 else min((ts - lastNs) / 1e9, MAX_FRAME_STEP_S)
        lastNs = ts
        sinceFirstFrameS = (ts - firstNs) / 1e9

        recent.addLast(s)
        while (recent.isNotEmpty() && ts - recent.first().timestampNs > RECENT_NS) recent.removeFirst()

        val check = PpgAnalyzer.check(s)
        updateExposure(s, check, ts)

        when (phase) {
            SessionPhase.WAITING_FOR_FINGER -> {
                if (check == FrameCheck.OK) {
                    if (okSinceNs < 0) okSinceNs = ts
                    waitingRun += s
                } else {
                    okSinceNs = -1L
                    waitingRun.clear()
                }
                if (okSinceNs >= 0 && ts - okSinceNs >= seconds(PpgConfig.FINGER_CONFIRM_S)) {
                    phase = SessionPhase.MEASURING
                    timerStartNs = ts
                    collected += waitingRun
                    waitingRun.clear()
                    badSinceNs = -1L
                } else if (okSinceNs < 0 && ts - firstNs >= seconds(PpgConfig.GRACE_S)) {
                    finish(EndReason.NO_FINGER_TIMEOUT)
                }
            }
            SessionPhase.MEASURING -> {
                collected += s
                measuredS += dt
                if (check == FrameCheck.OK) {
                    badSinceNs = -1L
                } else {
                    if (badSinceNs < 0) badSinceNs = ts
                    if (ts - badSinceNs >= seconds(PpgConfig.PAUSE_AFTER_S)) {
                        phase = SessionPhase.PAUSED
                        pauses++
                        pauseStartNs = badSinceNs
                        // The bad stretch before the pause doesn't count.
                        measuredS = (measuredS - (ts - badSinceNs) / 1e9).coerceAtLeast(0.0)
                        okSinceNs = -1L
                    }
                }
                if (measuredS >= PpgConfig.MEASUREMENT_S) finish(EndReason.COMPLETE)
            }
            SessionPhase.PAUSED -> {
                collected += s
                pausedS = (ts - pauseStartNs) / 1e9
                if (check == FrameCheck.OK) {
                    if (okSinceNs < 0) okSinceNs = ts
                    if (ts - okSinceNs >= seconds(PpgConfig.RESUME_AFTER_S)) {
                        phase = SessionPhase.MEASURING
                        pausedS = 0.0
                        badSinceNs = -1L
                    }
                } else {
                    okSinceNs = -1L
                }
                if (phase == SessionPhase.PAUSED && pausedS > PpgConfig.MAX_PAUSE_S) finish(EndReason.SIGNAL_LOST)
            }
            SessionPhase.FINISHED -> Unit
        }
        if (phase != SessionPhase.FINISHED && timerStartNs >= 0 && ts - timerStartNs > seconds(PpgConfig.MAX_TOTAL_S)) {
            finish(EndReason.TIME_LIMIT)
        }
    }

    private fun finish(reason: EndReason) {
        phase = SessionPhase.FINISHED
        endReason = reason
        pausedS = 0.0
    }

    private fun updateExposure(s: FrameSample, check: FrameCheck, ts: Long) {
        val bright = check == FrameCheck.TOO_BRIGHT
        // Only brighten a dark frame that still looks like a fingertip (not a phone lying face down).
        val dark = check == FrameCheck.TOO_DARK && s.r >= DARK_FINGER_MIN_RED &&
            s.g <= PpgConfig.MAX_GREEN_TO_RED * s.r
        brightSinceNs = if (bright) (if (brightSinceNs < 0) ts else brightSinceNs) else -1L
        darkSinceNs = if (dark) (if (darkSinceNs < 0) ts else darkSinceNs) else -1L
        exposureOkSinceNs = if (check == FrameCheck.OK) (if (exposureOkSinceNs < 0) ts else exposureOkSinceNs) else -1L
        val sinceChange = if (lastExposureChangeNs < 0) Long.MAX_VALUE else ts - lastExposureChangeNs

        if (exposureLocked) {
            val drifted = (brightSinceNs >= 0 && ts - brightSinceNs >= seconds(UNLOCK_AFTER_S)) ||
                (darkSinceNs >= 0 && ts - darkSinceNs >= seconds(UNLOCK_AFTER_S))
            if (drifted) {
                exposureLocked = false
                lastExposureChangeNs = ts
            }
            return
        }
        if (sinceChange >= seconds(EV_STEP_INTERVAL_S)) {
            if (brightSinceNs >= 0 && ts - brightSinceNs >= seconds(EV_STEP_INTERVAL_S) && exposureSteps > evMin) {
                exposureSteps--
                lastExposureChangeNs = ts
            } else if (darkSinceNs >= 0 && ts - darkSinceNs >= seconds(EV_STEP_INTERVAL_S) && exposureSteps < evMax) {
                exposureSteps++
                lastExposureChangeNs = ts
            }
        }
        val settled = lastExposureChangeNs < 0 || ts - lastExposureChangeNs >= seconds(LOCK_AFTER_CHANGE_S)
        if (exposureOkSinceNs >= 0 && ts - exposureOkSinceNs >= seconds(LOCK_AFTER_S) && settled) exposureLocked = true
    }

    /** Live analysis of the last few seconds. */
    fun live(): LiveStatus = PpgAnalyzer.live(recent.toList())

    /** Seconds left in the grace period (only while waiting). */
    val graceLeftS: Double get() = (PpgConfig.GRACE_S - sinceFirstFrameS).coerceAtLeast(0.0)

    fun hint(live: LiveStatus): MeasureHint {
        when (live.check) {
            FrameCheck.NO_FINGER -> return MeasureHint.COVER
            FrameCheck.TOO_DARK -> return MeasureHint.TOO_DARK
            FrameCheck.TOO_BRIGHT -> return MeasureHint.TOO_BRIGHT
            FrameCheck.OK -> Unit
        }
        return when {
            phase == SessionPhase.WAITING_FOR_FINGER || live.stableS < 2.5 -> MeasureHint.FINGER_FOUND
            live.motion > PpgConfig.LIVE_MOTION_HOLD_STILL -> MeasureHint.HOLD_STILL
            live.score >= 60 -> MeasureHint.GOOD
            live.stableS >= 4.0 && live.relAmplitude < PpgConfig.PRESS_LIGHTER_REL_AMPLITUDE -> MeasureHint.PRESS_LIGHTER
            else -> MeasureHint.SEARCHING
        }
    }

    private companion object {
        const val RECENT_NS = 10_000_000_000L
        /** A longer gap between two frames counts as this much (the camera stalled, not the user). */
        const val MAX_FRAME_STEP_S = 0.25
        const val DARK_FINGER_MIN_RED = 3.0
        const val EV_STEP_INTERVAL_S = 0.6
        const val LOCK_AFTER_S = 2.0
        const val LOCK_AFTER_CHANGE_S = 1.0
        const val UNLOCK_AFTER_S = 1.5

        fun seconds(s: Double) = (s * 1e9).toLong()
    }
}
