package com.example.healthcheck.ui.measure

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.healthcheck.camera.CameraStartResult
import com.example.healthcheck.camera.PpgCamera
import com.example.healthcheck.core.signal.AnalysisDiagnostics
import com.example.healthcheck.core.signal.LiveStatus
import com.example.healthcheck.core.signal.MeasureHint
import com.example.healthcheck.core.signal.PpgConfig
import com.example.healthcheck.core.signal.SessionPhase
import com.example.healthcheck.core.signal.SignalQuality
import com.example.healthcheck.core.companion.CompanionMood
import com.example.healthcheck.ui.companion.CompanionPanel
import com.example.healthcheck.ui.companion.LocalCompanionSettings
import com.example.healthcheck.ui.components.AppCard
import com.example.healthcheck.ui.components.Disclaimer
import com.example.healthcheck.ui.components.PrimaryButton
import com.example.healthcheck.ui.components.SecondaryButton
import com.example.healthcheck.ui.theme.AppTheme
import kotlinx.coroutines.isActive
import kotlin.math.ceil
import kotlin.math.roundToInt

@Composable
fun MeasureScreen(
    viewModel: MeasureViewModel,
    onClose: () -> Unit,
    onAddManual: () -> Unit,
    showSignalDetails: Boolean = false,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            viewModel.begin()
        } else {
            val activity = context.findActivity()
            // After a denial, "no rationale" means the system will not ask again.
            val permanent = activity != null &&
                !activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
            viewModel.onPermissionDenied(permanent)
        }
    }
    val start = {
        if (hasCameraPermission(context)) viewModel.begin()
        else permissionLauncher.launch(Manifest.permission.CAMERA)
    }
    val close = {
        viewModel.cancel()
        onClose()
    }

    BackHandler { close() }

    // Leaving the screen entirely (e.g. navigation) cancels a running measurement.
    DisposableEffect(Unit) { onDispose { viewModel.cancel() } }

    // Backgrounding cancels; returning from Settings re-checks the permission.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> viewModel.onLeftApp()
                Lifecycle.Event.ON_RESUME -> if (hasCameraPermission(context)) viewModel.onPermissionGrantedLater()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (state.cameraActive) {
        // A new run id always means a brand-new camera object (and the old one fully stopped first).
        key(state.sessionId) {
            CameraSession(viewModel, state.sessionId, state.exposureSteps, state.exposureLocked)
        }
        KeepScreenOn()
    }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = close) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = AppTheme.colors.ink) }
            Text("Measurement", style = MaterialTheme.typography.titleLarge, color = AppTheme.colors.ink)
        }
        Box(Modifier.fillMaxSize()) {
            when (state.phase) {
                MeasurePhase.INSTRUCTIONS -> Instructions(onStart = start)
                MeasurePhase.PERMISSION_DENIED -> PermissionDenied(
                    permanently = state.permanentlyDenied,
                    onRetry = start,
                    onOpenSettings = { openAppSettings(context) },
                    onBack = close,
                )
                MeasurePhase.STARTING, MeasurePhase.MEASURING -> Measuring(state, showSignalDetails, onCancel = close)
                MeasurePhase.ANALYZING -> Centered {
                    CircularProgressIndicator(color = AppTheme.colors.accent)
                    Spacer(Modifier.height(16.dp))
                    Text("Analysing your pulse…", color = AppTheme.colors.sub)
                }
                MeasurePhase.SUCCESS -> Success(state, showSignalDetails, onAddManual = onAddManual, onDone = close)
                MeasurePhase.FAILED -> Failed(
                    state,
                    showSignalDetails,
                    onRetry = { viewModel.reset(); start() },
                    onBack = close,
                )
            }
        }
    }
}

/**
 * Binds the camera while composed; releases it (torch off, analyzer cleared, AE/AWB unlocked, unbound)
 * when this leaves composition: on finish, cancel, back, error, leaving the app or the screen.
 */
@Composable
private fun CameraSession(viewModel: MeasureViewModel, sessionId: Int, exposureSteps: Int, exposureLocked: Boolean) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val camera = remember(sessionId) { PpgCamera(context.applicationContext) }
    var started by remember(sessionId) { mutableStateOf(false) }
    DisposableEffect(camera) { onDispose { camera.stop() } }
    LaunchedEffect(camera) {
        val r = camera.start(owner) { viewModel.onFrame(sessionId, it) }
        if (!isActive) return@LaunchedEffect
        when (r) {
            is CameraStartResult.Ok -> {
                viewModel.onCameraStarted(sessionId, r.evMin, r.evMax)
                started = true
            }
            CameraStartResult.NoCamera -> viewModel.onCameraUnavailable(
                sessionId, "No rear camera", "This phone's rear camera isn't available, so a measurement isn't possible.", canRetry = false,
            )
            CameraStartResult.NoTorch -> viewModel.onCameraUnavailable(
                sessionId,
                "No flash available",
                "The rear camera has no flash/torch, which is needed to shine light through your fingertip. " +
                    "Measurement isn't possible on this device.",
                canRetry = false,
            )
            is CameraStartResult.Error -> viewModel.onCameraUnavailable(sessionId, "Camera unavailable", r.message, canRetry = true)
        }
    }
    // Brightness steps and the AE/AWB lock are decided by the session; the camera just applies them.
    LaunchedEffect(started, exposureSteps) { if (started) camera.setExposureSteps(exposureSteps) }
    LaunchedEffect(started, exposureLocked) { if (started) camera.setExposureLock(exposureLocked) }
}

@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { content() }
}

@Composable
private fun Instructions(onStart: () -> Unit) {
    val c = AppTheme.colors
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Before you start", style = MaterialTheme.typography.headlineSmall, color = c.ink)
        AppCard(Modifier.fillMaxWidth()) {
            val steps = listOf(
                "Rest quietly for 2–3 minutes first.",
                "Sit down and rest your hand and phone on a table.",
                "Cover the rear camera lens AND the flash with one fingertip.",
                "Press lightly, not hard. Pressing hard squeezes the blood out.",
                "Keep still, breathe normally and don't talk for the full minute.",
            )
            steps.forEachIndexed { i, s ->
                Row(Modifier.padding(vertical = 6.dp)) {
                    Box(Modifier.size(26.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.size(26.dp)) { drawCircle(c.accentSoft) }
                        Text("${i + 1}", color = c.onAccentSoft, style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(s, style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.weight(1f))
                }
            }
        }
        Text(
            "The flash will turn on and get slightly warm. Any rear lens works. The 60-second timer starts once your fingertip " +
                "is detected, and pauses by itself if the signal drops for a moment.",
            style = MaterialTheme.typography.bodyMedium, color = c.sub,
        )
        Spacer(Modifier.height(4.dp))
        PrimaryButton("Start", onClick = onStart, modifier = Modifier.fillMaxWidth())
        Disclaimer()
    }
}

@Composable
private fun PermissionDenied(permanently: Boolean, onRetry: () -> Unit, onOpenSettings: () -> Unit, onBack: () -> Unit) {
    val c = AppTheme.colors
    Centered {
        Text("Camera access needed", style = MaterialTheme.typography.headlineSmall, color = c.ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            "Health Check uses the rear camera and flash to see the tiny colour changes in your fingertip with each heartbeat. " +
                "No images are stored or sent anywhere; only the average colour of each frame is used, on this phone.",
            color = c.sub, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        if (permanently) {
            Text(
                "Camera permission is turned off permanently for this app. To measure, open the app settings and allow Camera.",
                color = c.warning, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            PrimaryButton("Open app settings", onClick = onOpenSettings, modifier = Modifier.fillMaxWidth())
        } else {
            Spacer(Modifier.height(8.dp))
            PrimaryButton("Allow camera", onClick = onRetry, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            SecondaryButton("Open app settings", onClick = onOpenSettings, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(10.dp))
        SecondaryButton("Back", onClick = onBack, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Measuring(state: MeasureUiState, showDetails: Boolean, onCancel: () -> Unit) {
    val c = AppTheme.colors
    val reduce = AppTheme.reduceMotion
    val total = PpgConfig.MEASUREMENT_S
    val progress = (state.measuredS / total).toFloat().coerceIn(0f, 1f)
    val remainingS = ceil(total - state.measuredS).toInt().coerceAtLeast(0)
    val starting = state.phase == MeasurePhase.STARTING
    val waiting = !starting && state.session == SessionPhase.WAITING_FOR_FINGER
    val paused = !starting && state.session == SessionPhase.PAUSED

    // Pulse circle: a soft bump on each detected beat.
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(state.beatCount) {
        if (state.beatCount > 0 && !reduce) {
            pulse.animateTo(1.10f, tween(110, easing = FastOutSlowInEasing))
            pulse.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        AppCard(Modifier.fillMaxWidth(), contentPadding = 16.dp) {
            CompanionPanel(
                LocalCompanionSettings.current, CompanionMood.MEASURING,
                beatCount = state.beatCount, showEcg = true,
            )
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(184.dp).scale(pulse.value)) { drawCircle(c.accentSoft) }
            Canvas(Modifier.size(220.dp)) {
                val stroke = 12.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(c.line, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                drawArc(
                    if (paused) c.sub else c.accent, -90f, 360f * progress, false, Offset(inset, inset), arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    starting -> Text("Starting…", style = MaterialTheme.typography.titleMedium, color = c.onAccentSoft)
                    waiting -> {
                        Text("Place your", style = MaterialTheme.typography.titleMedium, color = c.onAccentSoft)
                        Text("fingertip", style = MaterialTheme.typography.titleMedium, color = c.onAccentSoft)
                    }
                    else -> {
                        Text(
                            String.format(java.util.Locale.ROOT, "%d:%02d", remainingS / 60, remainingS % 60),
                            fontSize = 48.sp, fontWeight = FontWeight.SemiBold, color = c.onAccentSoft,
                            modifier = Modifier.semantics { contentDescription = "$remainingS seconds remaining" },
                        )
                        val hr = state.live?.heartRateBpm
                        Text(
                            when {
                                paused -> "Paused"
                                hr != null -> "${hr.roundToInt()} bpm"
                                else -> "– bpm"
                            },
                            style = MaterialTheme.typography.titleMedium, color = c.onAccentSoft,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        HintCard(state, starting, waiting, paused)
        Spacer(Modifier.height(12.dp))
        AppCard(Modifier.fillMaxWidth(), contentPadding = 16.dp) {
            QualityBar(state.live)
            Spacer(Modifier.height(14.dp))
            Text("Pulse", style = MaterialTheme.typography.labelMedium, color = c.sub)
            Spacer(Modifier.height(6.dp))
            Waveform(state.live?.waveform ?: FloatArray(0), Modifier.fillMaxWidth().height(72.dp))
        }
        if (showDetails) {
            Spacer(Modifier.height(12.dp))
            SignalDetails(state)
        }
        Spacer(Modifier.height(20.dp))
        SecondaryButton("Cancel", onClick = onCancel, modifier = Modifier.fillMaxWidth())
    }
}

/** The one thing the user should do right now, in words. */
@Composable
private fun HintCard(state: MeasureUiState, starting: Boolean, waiting: Boolean, paused: Boolean) {
    val c = AppTheme.colors
    val hint = state.hint
    val (title, detail) = when {
        starting -> "Opening the camera and turning on the flash…" to "This takes a moment."
        waiting -> (hint?.text ?: MeasureHint.COVER.text) to
            "The timer starts when your fingertip is detected (${ceil(state.graceLeftS).toInt()} s left)."
        paused -> "Timer paused: ${(hint ?: MeasureHint.COVER).text.replaceFirstChar { it.lowercase() }}" to
            "It resumes by itself when the signal is back (${ceil((PpgConfig.MAX_PAUSE_S - state.pausedS).coerceAtLeast(0.0)).toInt()} s left)."
        else -> (hint?.text ?: MeasureHint.SEARCHING.text) to "Keep still and breathe normally."
    }
    val good = !starting && !paused && hint == MeasureHint.GOOD
    val problem = paused || hint in setOf(
        MeasureHint.COVER, MeasureHint.TOO_DARK, MeasureHint.TOO_BRIGHT, MeasureHint.PRESS_LIGHTER, MeasureHint.HOLD_STILL,
    )
    AppCard(Modifier.fillMaxWidth(), contentPadding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(12.dp)) {
                drawCircle(
                    when {
                        good -> c.accent
                        problem -> c.warning
                        else -> c.sub
                    },
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.semantics(mergeDescendants = true) {}) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = c.ink)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = c.sub)
            }
        }
    }
}

/** 0..100 signal-quality bar. */
@Composable
private fun QualityBar(live: LiveStatus?) {
    val c = AppTheme.colors
    val score = live?.score ?: 0
    val quality = live?.quality
    val color = when (quality) {
        SignalQuality.GOOD -> c.accent
        SignalQuality.FAIR, SignalQuality.LOW -> c.sub
        SignalQuality.POOR, null -> c.warning
    }
    val animated by animateFloatAsState(score / 100f, tween(if (AppTheme.reduceMotion) 0 else 250), label = "quality")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Signal quality", style = MaterialTheme.typography.labelMedium, color = c.sub, modifier = Modifier.weight(1f))
        Text(
            if (live == null) "–" else "${quality?.label ?: "–"} · $score",
            style = MaterialTheme.typography.labelMedium, color = c.ink,
        )
    }
    Spacer(Modifier.height(6.dp))
    Canvas(
        Modifier.fillMaxWidth().height(10.dp).semantics { contentDescription = "Signal quality $score out of 100" },
    ) {
        val r = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(c.line, cornerRadius = r)
        if (animated > 0f) drawRoundRect(color, size = Size(size.width * animated, size.height), cornerRadius = r)
    }
}

/** Raw numbers (Settings > Measurement > Show signal details). */
@Composable
private fun SignalDetails(state: MeasureUiState) {
    val c = AppTheme.colors
    val l = state.live
    val lines = buildList {
        if (l == null) {
            add("waiting for frames…")
        } else {
            add("frame        ${l.check.name.lowercase()}")
            add("red level    ${fmt(l.meanRed, 1)}   coverage ${pct(l.coverage)}   clipped ${pct(l.clipped)}")
            add("amplitude    ${fmt(l.relAmplitude * 100, 3)} %   motion ${fmt(l.motion, 2)}")
            add("quality      ${l.score}/100   stable ${fmt(l.stableS, 1)} s   ${fmt(l.fps, 1)} fps")
        }
        add("exposure     ${signed(state.exposureSteps)} steps, ${if (state.exposureLocked) "locked" else "auto"}")
        add("timer        ${fmt(state.measuredS, 1)} s   ${state.session.name.lowercase()}")
    }
    AppCard(Modifier.fillMaxWidth(), contentPadding = 16.dp) {
        Text("Signal details", style = MaterialTheme.typography.labelMedium, color = c.sub)
        Spacer(Modifier.height(6.dp))
        lines.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = c.ink) }
    }
}

@Composable
private fun DiagnosticsCard(code: String?, d: AnalysisDiagnostics?) {
    val c = AppTheme.colors
    if (code == null && d == null) return
    val lines = buildList {
        code?.let { add("reason       $it") }
        d?.let {
            add("usable       ${fmt(it.usableS, 1)} s of ${fmt(it.totalS, 1)} s, ${it.segments} segment(s)")
            add("lost         no finger ${fmt(it.lostNoFingerS, 1)} s, dark ${fmt(it.lostDarkS, 1)} s, bright ${fmt(it.lostBrightS, 1)} s")
            add("             motion ${fmt(it.lostMotionS, 1)} s, weak ${fmt(it.lostWeakS, 1)} s")
            add("signal       red ${fmt(it.meanRed, 1)}, amplitude ${fmt(it.relAmplitude * 100, 3)} %, regularity ${fmt(it.periodicity, 2)}")
            add("beats        ${it.validBeats} kept (${pct(it.keptRatio)}), ${fmt(it.fps, 1)} fps")
        }
    }
    AppCard(Modifier.fillMaxWidth(), contentPadding = 16.dp) {
        Text("Signal details", style = MaterialTheme.typography.labelMedium, color = c.sub)
        Spacer(Modifier.height(6.dp))
        lines.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = c.ink) }
    }
}

private fun fmt(v: Double, decimals: Int) = String.format(java.util.Locale.ROOT, "%.${decimals}f", v)
private fun pct(v: Double) = "${(v * 100).roundToInt()}%"
private fun signed(v: Int) = if (v > 0) "+$v" else "$v"

/** Scrolling pulse trace; between updates it keeps sliding left so motion looks continuous. */
@Composable
private fun Waveform(data: FloatArray, modifier: Modifier) {
    val c = AppTheme.colors
    val reduce = AppTheme.reduceMotion
    val updatedAt = remember(data) { SystemClock.elapsedRealtime() }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while (true) withFrameMillis { now = SystemClock.elapsedRealtime() } }
    val currentData by rememberUpdatedState(data)
    Canvas(modifier) {
        val d = currentData
        drawLine(c.line, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx())
        if (d.size < 2) return@Canvas
        val lead = 3f // samples hidden at the right edge (one update ≈ 3 samples at 30 Hz)
        val dx = size.width / (d.size - 1 - lead)
        val shift = if (reduce) 0f else ((now - updatedAt) / 100f).coerceIn(0f, 1f) * lead
        val path = Path()
        for (i in d.indices) {
            val x = (i - shift) * dx
            val y = size.height / 2 - d[i] * size.height * 0.45f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        clipRect {
            drawPath(path, c.accent, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
private fun Success(state: MeasureUiState, showDetails: Boolean, onAddManual: () -> Unit, onDone: () -> Unit) {
    val c = AppTheme.colors
    val reduce = AppTheme.reduceMotion
    val m = state.result ?: return
    val check = remember { Animatable(if (reduce) 1f else 0f) }
    val cardScale by animateFloatAsState(
        targetValue = if (check.value > 0.6f) 1f else 0.9f,
        animationSpec = if (reduce) tween(0) else tween(300, easing = FastOutSlowInEasing),
        label = "cardScale",
    )
    LaunchedEffect(Unit) { if (!reduce) check.animateTo(1f, tween(450, easing = FastOutSlowInEasing)) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        AppCard(Modifier.fillMaxWidth(), contentPadding = 16.dp) {
            Box(contentAlignment = Alignment.TopEnd) {
                CompanionPanel(LocalCompanionSettings.current, CompanionMood.SAVED)
                AnimatedCheck(check.value, Modifier.size(36.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        AppCard(Modifier.fillMaxWidth().scale(cardScale)) {
            Text("Measurement saved", style = MaterialTheme.typography.headlineSmall, color = c.ink)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth()) {
                ResultValue("Heart rate", "${m.heartRateBpm.roundToInt()}", "bpm", Modifier.weight(1f))
                ResultValue(
                    if (m.hrvLowConfidence) "HRV (low confidence)" else "HRV (RMSSD)",
                    m.rmssdMs?.roundToInt()?.toString() ?: "–", "ms", Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                ResultValue("Breathing", m.breathingRatePerMin?.roundToInt()?.toString() ?: "–", "/min", Modifier.weight(1f))
                ResultValue("Signal", if (m.quality == SignalQuality.LOW) "Low" else m.quality.label, "", Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            if (m.quality == SignalQuality.LOW) {
                Text(
                    "Lower confidence: ${m.usableSeconds.roundToInt()} s of clear signal were used. " +
                        "The heart rate is still useful; treat HRV with care.",
                    style = MaterialTheme.typography.bodyMedium, color = c.warning,
                )
                Spacer(Modifier.height(6.dp))
            } else if (m.rmssdMs == null) {
                Text(
                    "Too few clean beat pairs for HRV, so only heart rate was saved.",
                    style = MaterialTheme.typography.bodyMedium, color = c.sub,
                )
                Spacer(Modifier.height(6.dp))
            }
            Text(
                "${m.validBeats} clean beats from ${m.usableSeconds.roundToInt()} s of signal. Saved to today's entry.",
                style = MaterialTheme.typography.bodySmall, color = c.sub,
            )
        }
        if (showDetails) {
            Spacer(Modifier.height(12.dp))
            DiagnosticsCard("ACCEPTED (${m.quality.name})", state.diagnostics)
        }
        Spacer(Modifier.height(20.dp))
        PrimaryButton("Add today's steps & sleep", onClick = onAddManual, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        SecondaryButton("Done", onClick = onDone, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Disclaimer()
    }
}

@Composable
private fun ResultValue(label: String, value: String, unit: String, modifier: Modifier) {
    val c = AppTheme.colors
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = c.sub)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = c.ink)
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                Text(unit, style = MaterialTheme.typography.bodyMedium, color = c.sub, modifier = Modifier.padding(bottom = 4.dp))
            }
        }
    }
}

@Composable
private fun AnimatedCheck(progress: Float, modifier: Modifier) {
    val c = AppTheme.colors
    Canvas(modifier.semantics { contentDescription = "Success" }) {
        drawCircle(c.accent, alpha = progress.coerceIn(0f, 1f))
        val path = Path().apply {
            moveTo(size.width * 0.28f, size.height * 0.52f)
            lineTo(size.width * 0.44f, size.height * 0.67f)
            lineTo(size.width * 0.73f, size.height * 0.36f)
        }
        val measure = PathMeasure().apply { setPath(path, false) }
        val partial = Path()
        measure.getSegment(0f, measure.length * progress, partial, true)
        drawPath(partial, c.onAccent, style = Stroke(size.width * 0.08f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun Failed(state: MeasureUiState, showDetails: Boolean, onRetry: () -> Unit, onBack: () -> Unit) {
    val c = AppTheme.colors
    Centered {
        Canvas(Modifier.size(72.dp)) {
            drawCircle(c.warning)
            val w = size.width
            drawLine(c.onWarning, Offset(w / 2, w * 0.26f), Offset(w / 2, w * 0.58f), w * 0.09f, StrokeCap.Round)
            drawCircle(c.onWarning, w * 0.055f, Offset(w / 2, w * 0.74f))
        }
        Spacer(Modifier.height(20.dp))
        Text(state.failureTitle ?: "Measurement failed", style = MaterialTheme.typography.headlineSmall, color = c.ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(state.failureMessage.orEmpty(), color = c.sub, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        if (state.canRetry) {
            PrimaryButton("Try again", onClick = onRetry, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
        }
        SecondaryButton("Back", onClick = onBack, modifier = Modifier.fillMaxWidth())
        if (showDetails) {
            Spacer(Modifier.height(16.dp))
            DiagnosticsCard(state.failureCode, state.diagnostics)
        }
    }
}

private fun hasCameraPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
