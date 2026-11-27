package com.example.healthcheck.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Range
import android.util.Size
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.camera2.interop.applyCamera2InteropAsync
import androidx.camera.camera2.interop.cameraCharacteristics
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.lifecycle.LifecycleOwner
import com.example.healthcheck.core.signal.FrameSample
import com.example.healthcheck.core.signal.PpgConfig
import com.example.healthcheck.util.DebugLog
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

sealed interface CameraStartResult {
    /** [evMin]..[evMax]: supported exposure compensation steps (0..0 when not supported). */
    data class Ok(val evMin: Int, val evMax: Int) : CameraStartResult
    data object NoCamera : CameraStartResult
    data object NoTorch : CameraStartResult
    data class Error(val message: String) : CameraStartResult
}

/**
 * Back camera + torch for fingertip PPG. Each frame is reduced to a few numbers on a background thread
 * (see [meanRgb]); only those and the sensor timestamp leave this class. All bind/unbind calls happen on
 * the main thread.
 *
 * Uses CameraX's default back camera, whatever lens that is, and measures the whole frame, so it
 * doesn't depend on where the lenses or the flash are.
 *
 * Every run starts clean: CameraX keeps Camera2 interop options (such as an AE/AWB lock) on the camera
 * after unbinding, for the life of the process. So [start] explicitly unlocks AE/AWB and resets
 * exposure compensation, and [stop] clears them again. Without this, a second run in the same process
 * started with the previous run's frozen exposure.
 */
class PpgCamera(private val context: Context) {

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var analysis: ImageAnalysis? = null
    private var executor: ExecutorService? = null
    private var fpsRange: Range<Int>? = null
    private var evRange: IntRange = 0..0

    /** True once AE/AWB lock was accepted by the camera. */
    @Volatile var exposureLocked: Boolean = false
        private set

    @MainThread
    @OptIn(ExperimentalCamera2Interop::class)
    suspend fun start(owner: LifecycleOwner, onFrame: (FrameSample) -> Unit): CameraStartResult {
        return try {
            val p = ProcessCameraProvider.awaitInstance(context)
            provider = p
            val selector = CameraSelector.DEFAULT_BACK_CAMERA
            if (!p.hasCamera(selector)) return CameraStartResult.NoCamera
            val info: CameraInfo = selector.filter(p.availableCameraInfos).firstOrNull()
                ?: return CameraStartResult.NoCamera
            if (!info.hasFlashUnit()) return CameraStartResult.NoTorch

            val builder = ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(320, 240), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                        )
                        .build(),
                )
            fpsRange = pickFpsRange(info)
            val exposure = info.exposureState
            evRange = if (exposure.isExposureCompensationSupported) {
                exposure.exposureCompensationRange.let { it.lower..it.upper }
            } else 0..0
            val useCase = builder.build()
            val exec = Executors.newSingleThreadExecutor()
            executor = exec
            useCase.setAnalyzer(exec) { image ->
                try {
                    meanRgb(image)?.let(onFrame)
                } catch (t: Throwable) {
                    DebugLog.e("Camera", "frame analysis failed", t)
                } finally {
                    image.close()
                }
            }
            analysis = useCase
            p.unbindAll()
            val cam = p.bindToLifecycle(owner, selector, useCase)
            camera = cam
            exposureLocked = false
            // Fresh state: override anything a previous run left on this camera.
            cam.cameraControl.applyCamera2InteropAsync {
                clearAllCaptureRequestOptions()
                fpsRange?.let { setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, false)
                setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, false)
            }
            if (evRange != 0..0) cam.cameraControl.setExposureCompensationIndex(0)
            cam.cameraControl.enableTorch(true)
            CameraStartResult.Ok(evRange.first, evRange.last)
        } catch (e: CancellationException) {
            stop()
            throw e
        } catch (t: Throwable) {
            DebugLog.e("Camera", "start failed", t)
            stop()
            CameraStartResult.Error("The camera could not be opened. Close other apps that use the camera and try again.")
        }
    }

    /**
     * Freezes (or releases) auto-exposure and auto-white-balance so the camera's own brightness
     * adjustments don't look like pulse changes.
     */
    @MainThread
    @OptIn(ExperimentalCamera2Interop::class)
    fun setExposureLock(locked: Boolean) {
        val cam = camera ?: return
        try {
            val range = fpsRange
            val future = cam.cameraControl.applyCamera2InteropAsync {
                if (range != null) setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
                setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, locked)
                setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, locked)
            }
            future.addListener({
                val ok = runCatching { future.get(); true }.getOrDefault(false)
                exposureLocked = locked && ok
                DebugLog.d("Camera", "exposure lock=$locked accepted=$ok")
            }, Runnable::run)
        } catch (t: Throwable) {
            DebugLog.e("Camera", "exposure lock failed", t)
        }
    }

    /** Exposure compensation in the camera's steps (clamped to its range). Brightens or darkens the image. */
    @MainThread
    fun setExposureSteps(steps: Int) {
        val cam = camera ?: return
        if (evRange == 0..0) return
        runCatching { cam.cameraControl.setExposureCompensationIndex(steps.coerceIn(evRange)) }
            .onFailure { DebugLog.e("Camera", "exposure compensation failed", it) }
    }

    /** Torch off, interop options cleared, analyzer cleared, camera released. Safe to call repeatedly. */
    @MainThread
    @OptIn(ExperimentalCamera2Interop::class)
    fun stop() {
        val control = camera?.cameraControl
        runCatching { control?.enableTorch(false) }
        runCatching {
            control?.applyCamera2InteropAsync {
                clearAllCaptureRequestOptions()
                setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, false)
                setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, false)
            }
        }
        if (evRange != 0..0) runCatching { control?.setExposureCompensationIndex(0) }
        runCatching { analysis?.clearAnalyzer() }
        runCatching { provider?.unbindAll() }
        runCatching { executor?.shutdown() }
        camera = null
        analysis = null
        executor = null
        exposureLocked = false
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun pickFpsRange(info: CameraInfo): Range<Int>? = runCatching {
        val ranges = info.cameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: return null
        // Prefer a fixed 30 fps; otherwise the range topping out at 30 with the highest minimum.
        ranges.filter { it.upper == 30 }.maxByOrNull { it.lower }
            ?: ranges.filter { it.upper in 20..30 }.maxByOrNull { it.upper * 100 + it.lower }
    }.getOrNull()

    companion object {
        /**
         * Reduces a frame to a [FrameSample], over the WHOLE frame (every 4th pixel each way), so any lens
         * and any finger position work. Pixels that look like a lit fingertip (red-dominant, not too dark,
         * not clipped) are averaged on their own; that keeps a clipped spot next to the flash or an
         * uncovered corner from washing out the pulse. Falls back to the whole-frame mean when too few
         * pixels qualify, so "too dark", "too bright" and "no finger" can still be told apart.
         */
        fun meanRgb(image: ImageProxy): FrameSample? {
            val plane = image.planes.firstOrNull() ?: return null
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val w = image.width
            val h = image.height
            var rAll = 0L
            var gAll = 0L
            var bAll = 0L
            var nAll = 0L
            var rOk = 0L
            var gOk = 0L
            var bOk = 0L
            var nOk = 0L
            var nClipped = 0L
            val limit = buf.limit()
            var y = STEP / 2
            while (y < h) {
                val row = y * rowStride
                var x = STEP / 2
                while (x < w) {
                    val i = row + x * pixelStride
                    if (i + 2 < limit) {
                        val r = buf.get(i).toInt() and 0xFF
                        val g = buf.get(i + 1).toInt() and 0xFF
                        val b = buf.get(i + 2).toInt() and 0xFF
                        rAll += r; gAll += g; bAll += b; nAll++
                        if (r >= PpgConfig.SATURATED_RED) {
                            nClipped++
                        } else if (r >= PpgConfig.MIN_RED && g <= PpgConfig.MAX_GREEN_TO_RED * r && b <= PpgConfig.MAX_BLUE_TO_RED * r) {
                            rOk += r; gOk += g; bOk += b; nOk++
                        }
                    }
                    x += STEP
                }
                y += STEP
            }
            if (nAll == 0L) return null
            val coverage = nOk.toDouble() / nAll
            val clipped = nClipped.toDouble() / nAll
            val ts = image.imageInfo.timestamp
            return if (coverage >= PpgConfig.MIN_COVERAGE) {
                FrameSample(ts, rOk.toDouble() / nOk, gOk.toDouble() / nOk, bOk.toDouble() / nOk, coverage, clipped)
            } else {
                FrameSample(ts, rAll.toDouble() / nAll, gAll.toDouble() / nAll, bAll.toDouble() / nAll, coverage, clipped)
            }
        }

        private const val STEP = 4
    }
}
