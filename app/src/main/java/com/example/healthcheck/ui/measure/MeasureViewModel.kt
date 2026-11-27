package com.example.healthcheck.ui.measure

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.healthcheck.core.signal.AnalysisDiagnostics
import com.example.healthcheck.core.signal.EndReason
import com.example.healthcheck.core.signal.FrameSample
import com.example.healthcheck.core.signal.LiveStatus
import com.example.healthcheck.core.signal.MeasureHint
import com.example.healthcheck.core.signal.MeasurementSession
import com.example.healthcheck.core.signal.PpgAnalyzer
import com.example.healthcheck.core.signal.PpgConfig
import com.example.healthcheck.core.signal.PpgMeasurement
import com.example.healthcheck.core.signal.PpgOutcome
import com.example.healthcheck.core.signal.RejectReason
import com.example.healthcheck.core.signal.SessionPhase
import com.example.healthcheck.data.EntryRepository
import com.example.healthcheck.data.MeasurementValues
import com.example.healthcheck.util.DebugLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

enum class MeasurePhase { INSTRUCTIONS, PERMISSION_DENIED, STARTING, MEASURING, ANALYZING, SUCCESS, FAILED }

data class MeasureUiState(
    val phase: MeasurePhase = MeasurePhase.INSTRUCTIONS,
    val permanentlyDenied: Boolean = false,
    /** Identifies the run: the camera is re-created for every new id. */
    val sessionId: Int = 0,
    /** Waiting for the finger, counting, or paused (while [phase] is MEASURING). */
    val session: SessionPhase = SessionPhase.WAITING_FOR_FINGER,
    val measuredS: Double = 0.0,
    val pausedS: Double = 0.0,
    val graceLeftS: Double = PpgConfig.GRACE_S,
    val live: LiveStatus? = null,
    val hint: MeasureHint? = null,
    /** Increments on every newly detected beat (drives the pulsing circle). */
    val beatCount: Int = 0,
    val exposureSteps: Int = 0,
    val exposureLocked: Boolean = false,
    val result: PpgMeasurement? = null,
    val diagnostics: AnalysisDiagnostics? = null,
    val failureTitle: String? = null,
    val failureMessage: String? = null,
    /** Short machine-style reason for the signal-details readout. */
    val failureCode: String? = null,
    val canRetry: Boolean = true,
) {
    val cameraActive: Boolean get() = phase == MeasurePhase.STARTING || phase == MeasurePhase.MEASURING
}

/**
 * Runs one measurement at a time. Every [begin] bumps the session id and builds a new channel and a new
 * [MeasurementSession], so nothing from an earlier run (frames, timers, detectors, exposure state) can
 * leak into the next; frames tagged with an old id are dropped.
 */
class MeasureViewModel(
    private val repo: EntryRepository,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
    private val clockMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) : ViewModel() {

    private val _state = MutableStateFlow(MeasureUiState())
    val state: StateFlow<MeasureUiState> = _state.asStateFlow()

    private var job: Job? = null
    @Volatile private var frames: Channel<FrameSample>? = null
    @Volatile private var evRange: IntRange = 0..0

    /** Bumped on every start/stop so a late background update can never revive an old session. */
    private val session = AtomicInteger(0)

    private inline fun updateIf(id: Int, crossinline f: (MeasureUiState) -> MeasureUiState) {
        _state.update { if (session.get() == id && it.sessionId == id) f(it) else it }
    }

    /** Called from the camera's analyzer thread; frames from an older run are dropped. */
    fun onFrame(sessionId: Int, sample: FrameSample) {
        if (sessionId != session.get()) return
        frames?.trySend(sample)
    }

    fun onCameraStarted(sessionId: Int, evMin: Int, evMax: Int) {
        if (sessionId == session.get()) evRange = evMin..evMax
    }

    fun onPermissionDenied(permanently: Boolean) {
        stopJob()
        _state.value = MeasureUiState(MeasurePhase.PERMISSION_DENIED, permanentlyDenied = permanently, sessionId = session.get())
    }

    fun onPermissionGrantedLater() {
        if (_state.value.phase == MeasurePhase.PERMISSION_DENIED) _state.value = MeasureUiState(sessionId = session.get())
    }

    /** Permission is granted: open the camera and start a brand-new run. Always allowed. */
    fun begin() {
        stopJob()
        val id = session.get()
        val date = LocalDate.now() // the day the measurement belongs to
        val channel = Channel<FrameSample>(Channel.UNLIMITED)
        frames = channel
        evRange = 0..0
        _state.value = MeasureUiState(MeasurePhase.STARTING, sessionId = id)
        job = viewModelScope.launch(worker) {
            try {
                val run = collect(id, channel) ?: return@launch
                frames = null
                channel.close()
                when (run.endReason) {
                    EndReason.NO_FINGER_TIMEOUT -> {
                        fail(
                            id, "No fingertip detected",
                            "We waited ${PpgConfig.GRACE_S.toInt()} seconds but couldn't see a fingertip. " +
                                "Cover the camera lens and the flash with one fingertip, then press Try again.",
                            code = "NO_FINGER_TIMEOUT",
                        )
                        return@launch
                    }
                    else -> Unit
                }
                updateIf(id) { it.copy(phase = MeasurePhase.ANALYZING) }
                when (val outcome = PpgAnalyzer.analyze(run.samples)) {
                    is PpgOutcome.Valid -> save(id, date, outcome.measurement, outcome.diagnostics)
                    is PpgOutcome.Invalid -> fail(
                        id,
                        "Couldn't get a reliable reading",
                        outcome.reason.message + usableNote(outcome.reason, outcome.diagnostics) + " Nothing was saved.",
                        code = outcome.reason.name + (run.endReason?.let { " / ended: ${it.name}" } ?: ""),
                        diagnostics = outcome.diagnostics,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ClosedReceiveChannelException) {
                // Stopped by the user; state was already reset.
            } catch (e: Exception) {
                DebugLog.e("Measure", "processing failed", e)
                fail(id, "Something went wrong", "The reading could not be processed. Nothing was saved.", code = "EXCEPTION")
            }
        }
    }

    private fun usableNote(reason: RejectReason, d: AnalysisDiagnostics): String =
        if (reason == RejectReason.TOO_SHORT || reason == RejectReason.LOW_FRAME_RATE) ""
        else " (${d.usableS.toInt()} s of clear signal; ${PpgConfig.MIN_USABLE_S.toInt()} s are needed.)"

    private class Run(val samples: List<FrameSample>, val endReason: EndReason?)

    private suspend fun collect(id: Int, channel: Channel<FrameSample>): Run? {
        val run = MeasurementSession()
        var gotFirst = false
        var lastLiveMs = 0L
        var lastBeatNs = Long.MIN_VALUE
        var beats = 0
        while (currentCoroutineContext().isActive) {
            val timeout = if (!gotFirst) FIRST_FRAME_TIMEOUT_MS else FRAME_GAP_TIMEOUT_MS
            val s = withTimeoutOrNull(timeout) { channel.receive() }
            if (s == null) {
                fail(id, "Camera stopped", "The camera stopped sending images. Nothing was saved. Please try again.", code = "NO_FRAMES")
                return null
            }
            if (!gotFirst) {
                gotFirst = true
                updateIf(id) { it.copy(phase = MeasurePhase.MEASURING) }
            }
            evRange.let { run.setExposureRange(it.first, it.last) }
            run.onFrame(s)
            if (run.phase == SessionPhase.FINISHED) return Run(run.samples.toList(), run.endReason)

            val now = clockMs()
            if (now - lastLiveMs >= LIVE_INTERVAL_MS) {
                lastLiveMs = now
                val live = run.live()
                live.lastBeatNs?.let { beatNs ->
                    if (run.phase == SessionPhase.MEASURING && beatNs - lastBeatNs > MIN_BEAT_GAP_NS) {
                        lastBeatNs = beatNs
                        beats++
                    }
                }
                val hint = run.hint(live)
                updateIf(id) {
                    it.copy(
                        session = run.phase, measuredS = run.measuredS, pausedS = run.pausedS, graceLeftS = run.graceLeftS,
                        live = live, hint = hint, beatCount = beats,
                        exposureSteps = run.exposureSteps, exposureLocked = run.exposureLocked,
                    )
                }
            }
        }
        return null
    }

    private suspend fun save(id: Int, date: LocalDate, m: PpgMeasurement, d: AnalysisDiagnostics) {
        try {
            // Saving must finish even if the user leaves right now.
            withContext(NonCancellable) {
                repo.saveMeasurement(
                    date,
                    MeasurementValues(m.heartRateBpm, m.rmssdMs, m.breathingRatePerMin, m.quality.label),
                )
            }
            updateIf(id) { it.copy(phase = MeasurePhase.SUCCESS, result = m, diagnostics = d) }
        } catch (e: Exception) {
            DebugLog.e("Measure", "save failed", e)
            fail(id, "Couldn't save", "The reading was fine but could not be saved. Please try again.", code = "SAVE_FAILED")
        }
    }

    private fun fail(
        id: Int,
        title: String,
        message: String,
        canRetry: Boolean = true,
        code: String? = null,
        diagnostics: AnalysisDiagnostics? = null,
    ) {
        updateIf(id) {
            MeasureUiState(
                MeasurePhase.FAILED, sessionId = id, failureTitle = title, failureMessage = message,
                failureCode = code, diagnostics = diagnostics, canRetry = canRetry, live = it.live,
            )
        }
    }

    fun onCameraUnavailable(sessionId: Int, title: String, message: String, canRetry: Boolean) {
        if (sessionId != session.get()) return
        stopJob()
        failNow(title, message, canRetry, "CAMERA_UNAVAILABLE")
    }

    /** Ends the current run (already stopped) with a failure, whatever its id was. */
    private fun failNow(title: String, message: String, canRetry: Boolean, code: String) {
        _state.value = MeasureUiState(
            MeasurePhase.FAILED, sessionId = session.get(), failureTitle = title, failureMessage = message,
            failureCode = code, canRetry = canRetry, live = _state.value.live,
        )
    }

    /** App went to background / screen turned off while measuring. */
    fun onLeftApp() {
        if (!_state.value.cameraActive) return
        stopJob()
        failNow("Measurement cancelled", "The measurement stopped because you left the app. Nothing was saved.", true, "LEFT_APP")
    }

    /** Cancel button, back, or leaving the screen. */
    fun cancel() {
        stopJob()
        _state.value = MeasureUiState(sessionId = session.get())
    }

    /** Back to the instruction screen after a result. */
    fun reset() {
        if (_state.value.cameraActive || _state.value.phase == MeasurePhase.ANALYZING) return
        stopJob()
        _state.value = MeasureUiState(sessionId = session.get())
    }

    private fun stopJob() {
        session.incrementAndGet()
        job?.cancel()
        job = null
        frames?.close()
        frames = null
    }

    override fun onCleared() {
        stopJob()
    }

    companion object {
        private const val FIRST_FRAME_TIMEOUT_MS = 8_000L
        private const val FRAME_GAP_TIMEOUT_MS = 3_000L
        private const val LIVE_INTERVAL_MS = 100L
        private const val MIN_BEAT_GAP_NS = 250_000_000L
    }
}
