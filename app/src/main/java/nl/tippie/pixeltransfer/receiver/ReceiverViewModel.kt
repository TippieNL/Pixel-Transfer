package nl.tippie.pixeltransfer.receiver

import android.app.Application
import android.net.Uri
import androidx.camera.core.ImageProxy
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.tippie.pixeltransfer.core.codec.FileMetadata
import nl.tippie.pixeltransfer.core.pipeline.FailureReason
import nl.tippie.pixeltransfer.core.pipeline.StreamDecoder
import nl.tippie.pixeltransfer.core.vision.ExposureGovernor
import nl.tippie.pixeltransfer.core.vision.ExposureMeter
import nl.tippie.pixeltransfer.core.vision.FrameDiagnostics
import nl.tippie.pixeltransfer.core.vision.FrameReader
import nl.tippie.pixeltransfer.core.vision.Guidance
import nl.tippie.pixeltransfer.core.vision.Hint
import nl.tippie.pixeltransfer.core.vision.ReadStatus
import java.util.concurrent.atomic.AtomicBoolean

/** Where the receiver is in the transfer. */
enum class ReceiverPhase { SEARCHING, RECEIVING, COMPLETE, FAILED }

data class ReceiverUiState(
    val phase: ReceiverPhase = ReceiverPhase.SEARCHING,
    val metadata: FileMetadata? = null,
    val sourceBlocks: Int = 0,
    val blocksRecovered: Int = 0,
    val symbolsUnique: Int = 0,
    val symbolsNeeded: Int = 0,
    val symbolsDuplicate: Int = 0,
    val framesAttempted: Int = 0,
    val framesDecoded: Int = 0,
    val framesTorn: Int = 0,
    val framesDropped: Int = 0,
    val framesFromOtherStream: Int = 0,
    /** Frames per second that actually yielded symbols, over a short rolling window. */
    val goodFramesPerSecond: Float = 0f,
    val lastStatus: ReadStatus = ReadStatus.NO_FINDERS,
    val diagnostics: FrameDiagnostics = FrameDiagnostics(),
    val hints: List<Hint> = emptyList(),
    /** Corners of the located frame in upright normalised coordinates, for the overlay. */
    val overlay: FloatArray? = null,
    /** Width / height of the upright analysis image, so the overlay can match FIT_CENTER. */
    val overlayAspect: Float = 0f,
    val sha256Verified: Boolean = false,
    /** Applied exposure compensation index, shown so a stuck loop is visible rather than silent. */
    val exposureIndex: Int = 0,
    val exposureSettled: Boolean = false,
    val clippedFraction: Double = 0.0,
    val failure: FailureReason? = null,
    val savedTo: String? = null,
    val error: String? = null,
) {
    val blockProgress: Float
        get() = if (sourceBlocks == 0) 0f else blocksRecovered.toFloat() / sourceBlocks

    val symbolProgress: Float
        get() = if (symbolsNeeded == 0) 0f else (symbolsUnique.toFloat() / symbolsNeeded).coerceAtMost(1f)

    /** Seconds remaining at the current effective decode rate, or null when not yet estimable. */
    val estimatedSecondsRemaining: Double?
        get() {
            if (phase != ReceiverPhase.RECEIVING) return null
            val remaining = symbolsNeeded - symbolsUnique
            if (remaining <= 0) return 0.0
            if (goodFramesPerSecond <= 0.01f || framesDecoded == 0) return null
            val symbolsPerGoodFrame = symbolsUnique.toDouble() / framesDecoded
            if (symbolsPerGoodFrame <= 0.0) return null
            return remaining / (symbolsPerGoodFrame * goodFramesPerSecond)
        }
}

/**
 * Drives the optical receive loop.
 *
 * Frames arrive on the camera's analysis executor and are decoded there; nothing about the
 * decode touches the main thread. State is published through a [StateFlow], which the UI
 * collects at its own pace.
 */
class ReceiverViewModel(application: Application) : AndroidViewModel(application) {

    private val reader = FrameReader()
    private val converter = YuvConverter()
    private val decoder = StreamDecoder()
    private val decoding = AtomicBoolean(false)

    private var governor: ExposureGovernor? = null
    private var consecutiveGood = 0
    private var framesSinceRemeter = 0

    /** Wired by the screen to the camera; the view model owns the control law, not the camera. */
    var onExposureRequest: ((Int) -> Unit)? = null

    /** Wired by the screen; asks for a fresh focus and metering pass. */
    var onRemeterRequest: (() -> Unit)? = null

    /** Called once the camera reports what exposure compensation it supports. */
    fun attachCamera(exposureRange: IntRange) {
        governor = ExposureGovernor(exposureRange.first, exposureRange.last)
    }

    /** Timestamps of recently decoded frames, for the effective rate readout. */
    private val recentGood = ArrayDeque<Long>()
    private var recentAttempts = 0

    private val _state = MutableStateFlow(ReceiverUiState())
    val state: StateFlow<ReceiverUiState> = _state.asStateFlow()

    /**
     * Set once several frames in a row have decoded *and* the exposure loop has settled, so the
     * camera locks a known-good state rather than whatever happened to be in effect at the first
     * fluke success.
     */
    @Volatile
    var readyToLock: Boolean = false
        private set

    fun onFrame(proxy: ImageProxy) {
        // The analyser is single-threaded, but guard anyway: a re-entrant decode would corrupt
        // the reader's cached scratch buffers.
        if (!decoding.compareAndSet(false, true)) {
            proxy.close()
            _state.update { it.copy(framesDropped = it.framesDropped + 1) }
            return
        }
        try {
            if (_state.value.phase == ReceiverPhase.COMPLETE) return
            val rotation = proxy.imageInfo.rotationDegrees
            val width = proxy.width
            val height = proxy.height
            val image = converter.convert(proxy)

            // Exposure is judged on every frame, decoded or not. A blown-out capture cannot
            // decode, so waiting for a successful decode before correcting exposure would never
            // correct anything.
            val exposure = ExposureMeter.measure(image)
            governor?.let { active ->
                active.update(exposure)?.let { index -> onExposureRequest?.invoke(index) }
            }

            val result = reader.read(image)
            recentAttempts++

            val overlay = result.corners?.let { corners ->
                val out = FloatArray(8)
                val point = FloatArray(2)
                for (i in 0 until 4) {
                    AnalysisGeometry.normalise(
                        corners[i][0], corners[i][1], width, height, rotation, point,
                    )
                    out[i * 2] = point[0]
                    out[i * 2 + 1] = point[1]
                }
                out
            }

            val aspect = AnalysisGeometry.uprightWidth(width, height, rotation).toFloat() /
                AnalysisGeometry.uprightHeight(width, height, rotation).toFloat()

            var gained = false
            if (result.isUsable) {
                gained = decoder.accept(result.header!!, result.symbols)
                consecutiveGood++
                framesSinceRemeter = 0
                readyToLock = consecutiveGood >= GOOD_FRAMES_BEFORE_LOCK &&
                    (governor?.isSettled ?: true)
                val now = System.nanoTime()
                recentGood.addLast(now)
                while (recentGood.isNotEmpty() && now - recentGood.first() > RATE_WINDOW_NANOS) {
                    recentGood.removeFirst()
                }
            }

            if (!result.isUsable) {
                consecutiveGood = 0
                framesSinceRemeter++
                // Nothing is decoding and nothing is improving: nudge focus and metering again
                // rather than staring at a blurred frame indefinitely.
                if (framesSinceRemeter >= REMETER_INTERVAL_FRAMES) {
                    framesSinceRemeter = 0
                    onRemeterRequest?.invoke()
                }
            }

            publish(result.status, result.diagnostics, overlay, aspect, exposure, gained)
        } catch (e: Throwable) {
            _state.update { it.copy(error = e.message) }
        } finally {
            decoding.set(false)
            proxy.close()
        }
    }

    private fun publish(
        status: ReadStatus,
        diagnostics: FrameDiagnostics,
        overlay: FloatArray?,
        aspect: Float,
        exposure: ExposureMeter.Reading,
        gained: Boolean,
    ) {
        val rate = if (recentGood.size < 2) {
            0f
        } else {
            val span = (recentGood.last() - recentGood.first()).coerceAtLeast(1L)
            (recentGood.size - 1) * 1_000_000_000f / span
        }

        val result = decoder.result
        val phase = when {
            result != null -> ReceiverPhase.COMPLETE
            decoder.failure != null -> ReceiverPhase.FAILED
            decoder.header != null -> ReceiverPhase.RECEIVING
            else -> ReceiverPhase.SEARCHING
        }

        _state.update { previous ->
            previous.copy(
                phase = phase,
                metadata = decoder.metadata ?: previous.metadata,
                sourceBlocks = decoder.sourceBlocks,
                blocksRecovered = decoder.blocksRecovered,
                symbolsUnique = decoder.symbolsUnique,
                symbolsNeeded = decoder.symbolsNeeded,
                symbolsDuplicate = decoder.symbolsDuplicate,
                framesAttempted = previous.framesAttempted + 1,
                framesDecoded = if (status == ReadStatus.OK) previous.framesDecoded + 1 else previous.framesDecoded,
                framesTorn = if (status == ReadStatus.TORN) previous.framesTorn + 1 else previous.framesTorn,
                framesFromOtherStream = decoder.framesFromOtherStream,
                goodFramesPerSecond = rate,
                lastStatus = status,
                diagnostics = diagnostics,
                hints = Guidance.hints(status, diagnostics, recentGood.size, recentAttempts),
                overlay = overlay ?: previous.overlay.takeIf { status != ReadStatus.NO_FINDERS },
                overlayAspect = aspect,
                sha256Verified = result?.sha256Verified ?: false,
                exposureIndex = governor?.index ?: 0,
                exposureSettled = governor?.isSettled ?: false,
                clippedFraction = exposure.clippedFraction,
                failure = decoder.failure,
            )
        }
    }

    fun reset() {
        decoder.reset()
        recentGood.clear()
        recentAttempts = 0
        consecutiveGood = 0
        framesSinceRemeter = 0
        readyToLock = false
        governor?.reset()
        _state.value = ReceiverUiState()
    }

    /** Writes the received file to a location the user picked. */
    fun save(target: Uri) {
        val received = decoder.result ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    nl.tippie.pixeltransfer.util.FileIo.write(getApplication(), target, received.bytes)
                }
                _state.update { it.copy(savedTo = received.metadata.fileName, error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Could not save the file: ${e.message}") }
            }
        }
    }

    fun suggestedFileName(): String = decoder.result?.metadata?.fileName ?: "received.bin"

    fun suggestedMimeType(): String =
        decoder.result?.metadata?.mimeType ?: "application/octet-stream"

    private companion object {
        const val RATE_WINDOW_NANOS = 3_000_000_000L

        /** Successive good frames required before the camera's settings are frozen. */
        const val GOOD_FRAMES_BEFORE_LOCK = 3

        /** Frames of failure before asking the camera to refocus and re-meter. */
        const val REMETER_INTERVAL_FRAMES = 45
    }
}
