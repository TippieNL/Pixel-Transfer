package nl.tippie.pixeltransfer.receiver

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import nl.tippie.pixeltransfer.core.pipeline.FailureReason
import nl.tippie.pixeltransfer.core.vision.Hint
import nl.tippie.pixeltransfer.ui.Banner
import nl.tippie.pixeltransfer.ui.BannerTone
import nl.tippie.pixeltransfer.ui.StatRow
import nl.tippie.pixeltransfer.util.formatBytes
import nl.tippie.pixeltransfer.util.formatDuration
import java.util.concurrent.Executors

@Composable
fun ReceiverScreen(viewModel: ReceiverViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.state.collectAsState()

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) requestPermission.launch(Manifest.permission.CAMERA)
    }

    val saveFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> uri?.let(viewModel::save) }

    if (!hasPermission) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Camera access is required", style = MaterialTheme.typography.headlineSmall)
            Text(
                "PixelTransfer receives files by filming the sending phone's screen. It asks for " +
                    "no other permission - it has no networking permission at all.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { requestPermission.launch(Manifest.permission.CAMERA) }) {
                Text("Grant camera access")
            }
            OutlinedButton(onClick = onBack) { Text("Back") }
        }
        return
    }

    val executor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember {
        PreviewView(context).apply {
            // FIT_CENTER keeps preview coordinates a simple linear map of the analysis image, so
            // the detection overlay lands where the code actually is.
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }
    val controller = remember { CameraController(context, lifecycleOwner, executor) }
    var cameraError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(controller) {
        // The view model owns the exposure control law and the camera owns the hardware; these
        // two callbacks are the only thing joining them.
        viewModel.onExposureRequest = { index -> controller.setExposureCompensation(index) }
        viewModel.onRemeterRequest = { previewView.post { controller.meterCentre(previewView) } }

        controller.start(
            previewView,
            onReady = { viewModel.attachCamera(controller.exposureRange) },
            onFrame = { proxy ->
                viewModel.onFrame(proxy)
                if (viewModel.readyToLock && !controller.isLocked) {
                    previewView.post { controller.lockForDecoding(previewView) }
                }
            },
            onError = { cameraError = it.message ?: "camera failed to start" },
        )
        onDispose {
            viewModel.onExposureRequest = null
            viewModel.onRemeterRequest = null
            controller.stop()
            executor.shutdown()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .fillMaxSize()
                // Tapping re-runs focus and metering where the user pointed, which is the fastest
                // way out of a camera that has focused on the wrong thing.
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        controller.unlock()
                        controller.focusAt(previewView, offset.x, offset.y, lock = false)
                    }
                },
        )

        state.overlay?.let { corners -> DetectionOverlay(corners, state.overlayAspect) }

        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .safeDrawingPadding()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            cameraError?.let { Banner(it, BannerTone.ERROR) }
            state.error?.let { Banner(it, BannerTone.ERROR) }
            state.hints.forEach { hint -> HintBanner(hint) }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xE6101018))
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (state.phase) {
                ReceiverPhase.SEARCHING -> Text(
                    "Looking for a stream...",
                    style = MaterialTheme.typography.titleMedium,
                )

                ReceiverPhase.RECEIVING -> Text(
                    "Receiving",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )

                ReceiverPhase.COMPLETE -> Text(
                    "Complete",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )

                ReceiverPhase.FAILED -> Text(
                    "Failed",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            state.metadata?.let { meta ->
                StatRow("File", meta.fileName)
                StatRow("Type", meta.mimeType)
                StatRow("Size", formatBytes(meta.originalSize.toLong()))
            }

            if (state.phase == ReceiverPhase.RECEIVING || state.phase == ReceiverPhase.COMPLETE) {
                LinearProgressIndicator(
                    progress = { state.blockProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
                StatRow(
                    "Symbols",
                    "${state.symbolsUnique} / ${state.symbolsNeeded} unique",
                    emphasis = true,
                )
                StatRow(
                    "Source blocks",
                    "${state.blocksRecovered} / ${state.sourceBlocks} " +
                        "(${"%.0f".format(state.blockProgress * 100)}%)",
                )
                state.estimatedSecondsRemaining?.let {
                    StatRow("Estimated remaining", formatDuration(it))
                }
            }

            StatRow("Decode rate", "${"%.1f".format(state.goodFramesPerSecond)} good frames/s")
            StatRow(
                "Frames",
                "${state.framesDecoded} decoded · ${state.framesTorn} torn · " +
                    "${state.framesDropped} dropped",
            )
            if (state.symbolsDuplicate > 0) {
                StatRow("Duplicate symbols", "${state.symbolsDuplicate}")
            }
            if (state.framesFromOtherStream > 0) {
                StatRow("From another stream", "${state.framesFromOtherStream}")
            }
            StatRow(
                "Signal",
                "cell ${"%.1f".format(state.diagnostics.cellPixelSize)} px · " +
                    "${"%.0f".format(state.diagnostics.offAxisDegrees)}° off-axis · " +
                    "focus ${"%.0f".format(state.diagnostics.sharpness * 100)}%",
            )
            StatRow(
                "Exposure",
                "${if (state.exposureIndex > 0) "+" else ""}${state.exposureIndex} · " +
                    "${"%.0f".format(state.clippedFraction * 100)}% clipped" +
                    if (state.exposureSettled) " · settled" else " · adjusting",
            )
            Text(
                "Tap the preview to refocus.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (state.phase) {
                ReceiverPhase.COMPLETE -> {
                    Banner(
                        if (state.sha256Verified) {
                            "SHA-256 verified: the received file is byte-for-byte identical to the " +
                                "original."
                        } else {
                            "Reconstructed but not verified. Do not trust this file."
                        },
                        if (state.sha256Verified) BannerTone.INFO else BannerTone.ERROR,
                    )
                    state.savedTo?.let { Banner("Saved $it.", BannerTone.INFO) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { saveFile.launch(viewModel.suggestedFileName()) }) {
                            Text("Save file")
                        }
                        OutlinedButton(onClick = {
                            viewModel.reset()
                            controller.unlock()
                        }) { Text("Receive another") }
                        OutlinedButton(onClick = onBack) { Text("Done") }
                    }
                }

                ReceiverPhase.FAILED -> {
                    Banner(failureMessage(state.failure), BannerTone.ERROR)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            viewModel.reset()
                            controller.unlock()
                        }) { Text("Start over") }
                        OutlinedButton(onClick = onBack) { Text("Back") }
                    }
                }

                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            viewModel.reset()
                            controller.unlock()
                        }) { Text("Reset") }
                        OutlinedButton(onClick = onBack) { Text("Back") }
                    }
                }
            }
        }
    }
}

/**
 * Draws the located code area over the preview.
 *
 * The corners arrive normalised against the analysis image, while the preview is letterboxed
 * inside the view by FIT_CENTER. This repeats that fit so the outline lands on the pattern
 * rather than beside it.
 */
@Composable
private fun DetectionOverlay(corners: FloatArray, aspect: Float) {
    Canvas(Modifier.fillMaxSize()) {
        if (corners.size < 8 || aspect <= 0f) return@Canvas
        val viewAspect = size.width / size.height
        val fittedWidth = if (viewAspect > aspect) size.height * aspect else size.width
        val fittedHeight = if (viewAspect > aspect) size.height else size.width / aspect
        val left = (size.width - fittedWidth) / 2f
        val top = (size.height - fittedHeight) / 2f

        fun px(i: Int) = Offset(
            left + corners[i * 2] * fittedWidth,
            top + corners[i * 2 + 1] * fittedHeight,
        )

        val path = Path()
        val first = px(0)
        path.moveTo(first.x, first.y)
        for (i in 1 until 4) {
            val point = px(i)
            path.lineTo(point.x, point.y)
        }
        path.close()
        drawPath(path, Color(0xFF6FD3FF), style = Stroke(width = 3f))
        for (i in 0 until 4) {
            drawCircle(Color(0xFFFFC24D), radius = 8f, center = px(i))
        }
    }
}

@Composable
private fun HintBanner(hint: Hint) {
    val tone = when (hint.severity) {
        Hint.Severity.INFO -> BannerTone.INFO
        Hint.Severity.WARN -> BannerTone.WARNING
        Hint.Severity.ERROR -> BannerTone.ERROR
    }
    Banner(hint.message, tone)
}

private fun failureMessage(reason: FailureReason?): String = when (reason) {
    FailureReason.HASH_MISMATCH ->
        "Every block arrived but the SHA-256 does not match the sender's. The file is not " +
            "trustworthy and has been discarded. Start the transfer again."

    FailureReason.DECOMPRESSION_FAILED ->
        "The recovered stream did not decompress. Start the transfer again."

    FailureReason.CORRUPT_METADATA ->
        "The recovered stream had no readable file description. Start the transfer again."

    null -> "The transfer failed."
}
