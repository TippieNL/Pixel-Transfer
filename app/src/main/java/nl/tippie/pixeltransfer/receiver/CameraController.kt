package nl.tippie.pixeltransfer.receiver

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executor

/**
 * CameraX wiring for the receiver.
 *
 * Three things here are not optional, they are what makes the link work at all:
 *
 *  - analysis runs in YUV_420_888. Round-tripping through JPEG would add a second generation of
 *    chroma subsampling and blocking on top of what the display-to-lens path already imposes.
 *  - `STRATEGY_KEEP_ONLY_LATEST` means a slow decode drops frames instead of queueing them. The
 *    stream is rateless, so a dropped frame costs nothing while a growing backlog would make the
 *    preview lag reality and the user chase a stale image.
 *  - once the calibration patches are readable, auto-exposure and auto-white-balance are locked.
 *    Left alone they hunt between frames - the displayed pattern changes completely every 80 ms -
 *    and the per-frame colour calibration ends up chasing the camera instead of the screen.
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val analysisExecutor: Executor,
) {

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var analysis: ImageAnalysis? = null

    var isLocked: Boolean = false
        private set

    /** Resolution actually granted by the camera, once bound. */
    var analysisSize: Size? = null
        private set

    @SuppressLint("UnsafeOptInUsageError")
    fun start(previewView: PreviewView, onFrame: (ImageProxy) -> Unit, onError: (Throwable) -> Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                cameraProvider.unbindAll()

                // Preview and analysis are pinned to the same aspect ratio. The detection
                // overlay is drawn from coordinates measured in the *analysis* image but painted
                // over the *preview*; if the two streams had different shapes the outline would
                // sit visibly beside the pattern it claims to have found.
                val preview = Preview.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                            .build(),
                    )
                    .build()
                    .also { it.surfaceProvider = previewView.surfaceProvider }

                val resolution = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1920, 1080),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        ),
                    )
                    .build()

                val analysisBuilder = ImageAnalysis.Builder()
                    .setResolutionSelector(resolution)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)

                // Ask for the highest steady capture rate the device will give us. More camera
                // frames than displayed frames is the goal: it is what lets the receiver catch
                // each displayed frame at least once despite shutter drift.
                Camera2Interop.Extender(analysisBuilder)
                    .setCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30),
                    )

                val imageAnalysis = analysisBuilder.build().also {
                    it.setAnalyzer(analysisExecutor) { proxy -> onFrame(proxy) }
                }
                analysis = imageAnalysis

                camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis,
                )
                analysisSize = imageAnalysis.resolutionInfo?.resolution
            } catch (e: Throwable) {
                onError(e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Locks focus, exposure and white balance on the centre of the frame.
     *
     * Called once the decoder has read a frame successfully, which is the moment we know the
     * current settings are good enough - locking earlier risks freezing a bad exposure.
     */
    @SuppressLint("UnsafeOptInUsageError")
    fun lockForDecoding(previewView: PreviewView) {
        val active = camera ?: return
        if (isLocked) return
        isLocked = true

        runCatching {
            val factory = previewView.meteringPointFactory
            val point = factory.createPoint(previewView.width / 2f, previewView.height / 2f)
            val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                .disableAutoCancel()
                .build()
            active.cameraControl.startFocusAndMetering(action)
        }

        runCatching {
            Camera2CameraControl.from(active.cameraControl).setCaptureRequestOptions(
                CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
                    .build(),
            )
        }
    }

    /** Releases the locks so the camera can re-acquire, e.g. after the user moves. */
    @SuppressLint("UnsafeOptInUsageError")
    fun unlock() {
        val active = camera ?: return
        isLocked = false
        runCatching { active.cameraControl.cancelFocusAndMetering() }
        runCatching {
            Camera2CameraControl.from(active.cameraControl).setCaptureRequestOptions(
                CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, false)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, false)
                    .build(),
            )
        }
    }

    fun setTorch(on: Boolean) {
        runCatching { camera?.cameraControl?.enableTorch(on) }
    }

    fun stop() {
        analysis?.clearAnalyzer()
        provider?.unbindAll()
        camera = null
        analysis = null
        isLocked = false
    }
}
