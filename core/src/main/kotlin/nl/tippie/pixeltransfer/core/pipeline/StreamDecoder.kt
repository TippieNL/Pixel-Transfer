package nl.tippie.pixeltransfer.core.pipeline

import nl.tippie.pixeltransfer.core.codec.Compression
import nl.tippie.pixeltransfer.core.codec.FileMetadata
import nl.tippie.pixeltransfer.core.codec.FountainDecoder
import nl.tippie.pixeltransfer.core.codec.FountainParams
import nl.tippie.pixeltransfer.core.frame.FrameHeader
import nl.tippie.pixeltransfer.core.frame.SymbolPacket
import kotlin.math.ceil

/** A fully reconstructed and verified file. */
class ReceivedFile(
    val metadata: FileMetadata,
    val bytes: ByteArray,
    val sha256Verified: Boolean,
)

/** Why a transfer ended without a file. */
enum class FailureReason {
    /** The recovered object did not contain a readable metadata block. */
    CORRUPT_METADATA,

    /** Decompression of a fully recovered object failed. */
    DECOMPRESSION_FAILED,

    /** Every block arrived but the SHA-256 did not match. Never reported as a success. */
    HASH_MISMATCH,
}

/**
 * Accumulates fountain symbols recovered from captured frames and reconstructs the file.
 *
 * This class deliberately knows nothing about cameras or images: it is fed whatever the frame
 * decoder managed to read, in whatever order, with whatever gaps.
 *
 * A transfer may be cut into segments, each coded independently. Only a bounded number of
 * partially-decoded segments are kept alive at once; the sender cycles through segments anyway, so
 * dropping the least recently seen partial state costs one more pass at worst, whereas keeping
 * every segment resident would need as much memory as the whole file.
 */
class StreamDecoder(
    /** Where completed segments are written. Null keeps the whole object in memory. */
    private val sink: SegmentSink? = null,
    private val maxLiveSegments: Int = DEFAULT_LIVE_SEGMENTS,
    /**
     * How many consecutive frames from a different stream it takes to abandon the current one.
     * Lets the user point the camera at a second transfer without restarting the app.
     */
    private val foreignFramesBeforeSwitch: Int = 8,
) {

    var header: FrameHeader? = null
        private set

    /** Fountain decoders for segments still in progress, most recently used last. */
    private val live = LinkedHashMap<Int, FountainDecoder>()

    /** Completed segment bytes, held only when there is no sink to write them to. */
    private val buffered = HashMap<Int, ByteArray>()

    private var completed: BooleanArray = BooleanArray(0)
    private var fullSegmentSize = -1
    private var foreignRun = 0

    var segmentCount: Int = 0
        private set

    var metadata: FileMetadata? = null
        private set

    var result: ReceivedFile? = null
        private set

    var failure: FailureReason? = null
        private set

    // Live statistics for the receiver UI.
    var framesAccepted: Int = 0
        private set
    var framesFromOtherStream: Int = 0
        private set
    var symbolsUnique: Int = 0
        private set
    var symbolsDuplicate: Int = 0
        private set

    /** True once every segment has been recovered. With a sink the bytes are already written. */
    var allSegmentsRecovered: Boolean = false
        private set

    val isComplete: Boolean get() = result != null || (sink != null && allSegmentsRecovered)

    val segmentsComplete: Int get() = completed.count { it }

    /** Index of the segment currently being received, or -1. */
    var currentSegment: Int = -1
        private set

    val sourceBlocks: Int get() = header?.sourceBlocks ?: 0

    val blocksRecovered: Int get() = live[currentSegment]?.recoveredBlocks ?: 0

    /** Symbols still needed for the current segment, including the fountain overhead. */
    val symbolsNeeded: Int
        get() = header?.let { ceil(it.sourceBlocks * PreparedTransfer.RECEPTION_OVERHEAD).toInt() } ?: 0

    /** Overall progress across every segment, 0..1. */
    val progress: Float
        get() {
            if (segmentCount == 0) return 0f
            val within = live[currentSegment]?.progress ?: 0f
            val done = segmentsComplete
            val partial = if (currentSegment >= 0 && !completedAt(currentSegment)) within else 0f
            return ((done + partial) / segmentCount).coerceIn(0f, 1f)
        }

    private fun completedAt(index: Int): Boolean =
        index in completed.indices && completed[index]

    fun reset() {
        header = null
        live.clear()
        buffered.clear()
        completed = BooleanArray(0)
        fullSegmentSize = -1
        segmentCount = 0
        currentSegment = -1
        metadata = null
        result = null
        failure = null
        allSegmentsRecovered = false
        foreignRun = 0
        framesAccepted = 0
        framesFromOtherStream = 0
        symbolsUnique = 0
        symbolsDuplicate = 0
    }

    /** Feeds one decoded frame. Returns true when at least one new symbol was absorbed. */
    fun accept(frameHeader: FrameHeader, symbols: List<SymbolPacket>): Boolean {
        if (isComplete) return false

        val current = header
        if (current == null) {
            adopt(frameHeader)
        } else if (current.streamId != frameHeader.streamId) {
            framesFromOtherStream++
            foreignRun++
            val stale = progress < 0.05f && foreignRun >= foreignFramesBeforeSwitch
            if (!stale) return false
            reset()
            adopt(frameHeader)
        } else {
            foreignRun = 0
            header = frameHeader
        }

        val segment = frameHeader.segmentIndex
        if (segment !in completed.indices) return false
        currentSegment = segment
        framesAccepted++
        if (completed[segment]) return false

        if (frameHeader.segmentIndex < frameHeader.segmentCount - 1) {
            fullSegmentSize = frameHeader.totalEncodedSize
        }

        val decoder = decoderFor(segment, frameHeader)
        var gained = false
        for (packet in symbols) {
            if (packet.data.size != frameHeader.blockSize) continue
            if (packet.esi < 0) continue
            if (decoder.hasSeen(packet.esi)) {
                symbolsDuplicate++
                continue
            }
            if (decoder.offer(packet.esi, packet.data)) {
                symbolsUnique++
                gained = true
            }
        }

        if (gained) {
            if (segment == 0) peekMetadata(decoder)
            if (!decoder.isComplete && decoder.symbolsAccepted >= frameHeader.sourceBlocks) {
                decoder.tryFinish()
            }
            if (decoder.isComplete) finishSegment(segment, frameHeader, decoder)
        }
        return gained
    }

    private fun adopt(frameHeader: FrameHeader) {
        header = frameHeader
        segmentCount = frameHeader.segmentCount
        completed = BooleanArray(segmentCount)
        foreignRun = 0
    }

    private fun decoderFor(segment: Int, frameHeader: FrameHeader): FountainDecoder {
        live[segment]?.let {
            // Refresh recency so the segment being worked on is never the one evicted.
            live.remove(segment)
            live[segment] = it
            return it
        }
        while (live.size >= maxLiveSegments) {
            val oldest = live.keys.first()
            live.remove(oldest)
        }
        val decoder = FountainDecoder(
            FountainParams(
                frameHeader.sourceBlocks,
                frameHeader.blockSize,
                StreamEncoder.segmentStreamId(frameHeader.streamId, segment),
            ),
        )
        live[segment] = decoder
        return decoder
    }

    /** Surfaces the filename as soon as the leading blocks are in, long before the whole file. */
    private fun peekMetadata(decoder: FountainDecoder) {
        if (metadata != null) return
        val prefix = decoder.recoveredPrefix()
        if (prefix.size < FileMetadata.MIN_ENCODED_SIZE) return
        metadata = FileMetadata.decode(prefix)?.metadata
    }

    private fun finishSegment(segment: Int, frameHeader: FrameHeader, decoder: FountainDecoder) {
        val bytes = decoder.assemble(frameHeader.totalEncodedSize)
        completed[segment] = true
        live.remove(segment)

        if (sink != null) {
            val offset = offsetOf(segment)
            if (offset >= 0) {
                sink.write(offset, bytes, bytes.size)
            } else {
                // The segment size is not known yet; hold on until a full segment identifies it.
                buffered[segment] = bytes
            }
            flushBuffered()
        } else {
            buffered[segment] = bytes
        }

        if (segment == 0) metadata = FileMetadata.decode(bytes)?.metadata ?: metadata

        if (completed.all { it }) {
            allSegmentsRecovered = true
            if (sink == null) assembleInMemory()
        }
    }

    private fun offsetOf(segment: Int): Long = when {
        segmentCount == 1 -> 0L
        fullSegmentSize > 0 -> segment.toLong() * fullSegmentSize
        else -> -1L
    }

    private fun flushBuffered() {
        if (sink == null || buffered.isEmpty()) return
        val iterator = buffered.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val offset = offsetOf(entry.key)
            if (offset < 0) continue
            sink.write(offset, entry.value, entry.value.size)
            iterator.remove()
        }
    }

    /** Small transfers are reconstructed, decompressed and verified here. */
    private fun assembleInMemory() {
        val totalSize = buffered.values.sumOf { it.size }
        val obj = ByteArray(totalSize)
        var offset = 0
        for (index in 0 until segmentCount) {
            val part = buffered[index] ?: return
            part.copyInto(obj, offset)
            offset += part.size
        }

        val parsed = FileMetadata.decode(obj)
        if (parsed == null) {
            failure = FailureReason.CORRUPT_METADATA
            return
        }
        metadata = parsed.metadata
        val meta = parsed.metadata
        val payload = obj.copyOfRange(
            parsed.payloadOffset,
            (parsed.payloadOffset + meta.compressedSize).coerceAtMost(obj.size),
        )

        val plain = try {
            Compression.decompress(payload, meta.compression, meta.originalSize)
        } catch (e: Exception) {
            failure = FailureReason.DECOMPRESSION_FAILED
            return
        }
        if (plain.size != meta.originalSize) {
            failure = FailureReason.DECOMPRESSION_FAILED
            return
        }

        // The only gate that decides success. A reported success always means the bytes on
        // disk hash to exactly what the sender hashed.
        val digest = FileMetadata.sha256(plain)
        if (!digest.contentEquals(meta.sha256)) {
            failure = FailureReason.HASH_MISMATCH
            return
        }
        result = ReceivedFile(meta, plain, sha256Verified = true)
        buffered.clear()
    }

    private companion object {
        /**
         * Partially-decoded segments kept resident. The sender cycles, so evicting the least
         * recently seen costs one more pass at worst.
         */
        const val DEFAULT_LIVE_SEGMENTS = 2
    }
}
