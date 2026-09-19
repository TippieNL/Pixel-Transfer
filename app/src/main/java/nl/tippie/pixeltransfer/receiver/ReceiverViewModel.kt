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
enum class ReceiverPhase { SEARCHING, RECEIVING, VERIFYING, COMPLETE, FAILED }

data class ReceiverUiState(
    val phase: ReceiverPhase = ReceiverPhase.SEARCHING,
    val metadata: FileMetadata? = null,
    val sourceBlocks: Int = 0,
    val segmentCount: Int = 0,
    val segmentsComplete: Int = 0,
    val currentSegment: Int = -1,
    /** Overall progress across every segment, 0..1. */
    val overallProgress: Float = 0f,
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
    /** Set when the incoming file will not fit in the space available. */
    val insufficientSpace: Boolean = false,
    val error: String? = null,
) {
    val blockProgress: Float
        get() = if (sourceBlocks == 0) 0f else blocksRecovered.toFloat() / sourceBlocks

    val isSegmented: Boolean get() = segmentCount > 1

    val symbolProgress: Float
        get() = if (symbolsNeeded == 0) 0f else (symbolsUnique.toFloat() / symbolsNeeded).coerceAtMost(1f)

    /** Seconds remaining at the current effective decode rate, or null when not yet estimable. */
    val estimatedSecondsRemaining: Double?
        get() {
            if (phase != ReceiverPhase.RECEIVING) return null
            if (goodFramesPerSecond <= 0.01f || framesDecoded == 0 || overallProgress <= 0.001f) {
                return null
            }
            // Extrapolate from progress actually made rather than from the current segment alone,
            // which would read as "nearly done" once per segment on a long transfer.
            val elapsedRate = overallProgress / framesDecoded.toDouble()
            val framesLeft = (1.0 - overallProgress) / elapsedRate
            return framesLeft / goodFramesPerSecond
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
    private val store = ReceivedFileStore(application)

    // Segments are written straight to disk as they complete, so a 150 MB transfer needs only a
    // segment's worth of memory rather than the whole file.
    private var decoder = StreamDecoder(sink = store.newSink())
    private val decoding = AtomicBoolean(false)
    private var verified: ReceivedFileStore.Verified? = null
    private val verifying = AtomicBoolean(false)

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
                if (decoder.allSegmentsRecovered) verifyInBackground()
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

        val finished = verified
        val phase = when {
            finished != null -> if (finished.sha256Verified) ReceiverPhase.COMPLETE else ReceiverPhase.FAILED
            decoder.allSegmentsRecovered -> ReceiverPhase.VERIFYING
            decoder.failure != null -> ReceiverPhase.FAILED
            decoder.header != null -> ReceiverPhase.RECEIVING
            else -> ReceiverPhase.SEARCHING
        }

        _state.update { previous ->
            previous.copy(
                phase = phase,
                metadata = finished?.metadata ?: decoder.metadata ?: previous.metadata,
                insufficientSpace = (decoder.metadata?.originalSize?.toLong() ?: 0L) > freeSpace(),
                segmentCount = decoder.segmentCount,
                segmentsComplete = decoder.segmentsComplete,
                currentSegment = decoder.currentSegment,
                overallProgress = decoder.progress,
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
                sha256Verified = finished?.sha256Verified ?: false,
                exposureIndex = governor?.index ?: 0,
                exposureSettled = governor?.isSettled ?: false,
                clippedFraction = exposure.clippedFraction,
                failure = decoder.failure,
            )
        }
    }

    private fun verifyInBackground() {
        if (!verifying.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.IO) {
            val outcome = runCatching { store.finish() }.getOrNull()
            verified = outcome
            _state.update {
                it.copy(
                    phase = when {
                        outcome == null -> ReceiverPhase.FAILED
                        outcome.sha256Verified -> ReceiverPhase.COMPLETE
                        else -> ReceiverPhase.FAILED
                    },
                    metadata = outcome?.metadata ?: it.metadata,
                    sha256Verified = outcome?.sha256Verified ?: false,
                    failure = if (outcome != null && !outcome.sha256Verified) {
                        FailureReason.HASH_MISMATCH
                    } else if (outcome == null) {
                        FailureReason.CORRUPT_METADATA
                    } else {
                        null
                    },
                    overallProgress = 1f,
                )
            }
        }
    }

    /** Free space where the incoming file will land. */
    fun freeSpace(): Long = store.freeSpace()

    fun reset() {
        verified = null
        verifying.set(false)
        decoder = StreamDecoder(sink = store.newSink())
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
        val finished = verified ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { store.saveTo(finished, target) }
                _state.update { it.copy(savedTo = finished.metadata.fileName, error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Could not save the file: ${e.message}") }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // The scratch file is only useful while the screen is open.
        if (verified == null) store.clear()
    }

    fun suggestedFileName(): String = verified?.metadata?.fileName ?: "received.bin"

    private companion object {
        const val RATE_WINDOW_NANOS = 3_000_000_000L

        /** Successive good frames required before the camera's settings are frozen. */
        const val GOOD_FRAMES_BEFORE_LOCK = 3

        /** Frames of failure before asking the camera to refocus and re-meter. */
        const val REMETER_INTERVAL_FRAMES = 45
    }
}
