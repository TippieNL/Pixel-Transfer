package nl.tippie.pixeltransfer.core.pipeline

import nl.tippie.pixeltransfer.core.codec.Compression
import nl.tippie.pixeltransfer.core.codec.CompressionType
import nl.tippie.pixeltransfer.core.codec.FileMetadata
import nl.tippie.pixeltransfer.core.codec.FountainEncoder
import nl.tippie.pixeltransfer.core.codec.FountainParams
import nl.tippie.pixeltransfer.core.frame.FrameCodec
import nl.tippie.pixeltransfer.core.frame.FrameHeader
import nl.tippie.pixeltransfer.core.frame.FrameLayout
import nl.tippie.pixeltransfer.core.frame.FramePlan
import nl.tippie.pixeltransfer.core.frame.SymbolPacket
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.random.Random

/** A file that has been described, segmented and made ready to stream. */
class PreparedTransfer(
    val metadata: FileMetadata,
    val config: SenderConfig,
    val streamId: Int,
    private val objectReader: ObjectReader,
    val segments: SegmentPlan,
    val plan: FramePlan,
) {
    /** Reads one segment of the coded object. Kept internal: it is the sender's own plumbing. */
    internal fun readSegment(index: Int): ByteArray =
        objectReader.read(segments.offsetOf(index), segments.sizeOf(index))

    val blockSize: Int get() = plan.blockSize

    val symbolsPerFrame: Int get() = plan.symbolsPerFrame

    /** Total size of the coded object: the metadata block plus the payload. */
    val totalEncodedSize: Long get() = segments.objectSize

    /** Source blocks in a given segment. */
    fun blocksIn(segment: Int): Int =
        FountainEncoder.blockCount(segments.sizeOf(segment), blockSize)

    /** Source blocks in the first segment; every full segment has the same count. */
    val sourceBlocks: Int get() = blocksIn(0)

    /** Symbols a receiver needs for one segment, including the fountain overhead. */
    fun symbolsNeeded(segment: Int): Int =
        ceil(blocksIn(segment) * RECEPTION_OVERHEAD).toInt()

    val symbolsNeeded: Int get() = symbolsNeeded(0)

    /**
     * Frames the sender spends on one segment before moving to the next.
     *
     * There is no back channel, so the sender cannot know when a segment has been received. It
     * gives each segment enough frames to cover the fountain overhead plus a margin for the frames
     * the receiver will miss, then moves on and comes back on the next pass.
     */
    fun framesFor(segment: Int): Int {
        val needed = symbolsNeeded(segment) * config.segmentRedundancy
        return ceil(needed / symbolsPerFrame).toInt().coerceAtLeast(1)
    }

    /** Frames in one full pass over every segment. */
    val framesPerLoop: Int by lazy {
        (0 until segments.count).sumOf { framesFor(it) }
    }

    /** Payload bytes carried by one frame. */
    val bytesPerFrame: Int get() = symbolsPerFrame * blockSize

    /**
     * True when frame 0 alone carries the whole file.
     *
     * Symbols below K are systematic - symbol i *is* source block i - so when there are at most
     * `symbolsPerFrame` blocks, the first frame contains every one of them and no fountain
     * overhead is needed. This is the only case where a single still image is a complete
     * transfer, and the only case where exporting one is honest.
     */
    val fitsInOneFrame: Boolean
        get() = segments.isSingleSegment && sourceBlocks <= symbolsPerFrame

    /** Best-case transfer time in seconds: every displayed frame decoded, no losses. */
    fun idealTransferSeconds(fps: Int = config.fps): Double {
        val minimumFrames = (0 until segments.count).sumOf {
            ceil(symbolsNeeded(it).toDouble() / symbolsPerFrame).toInt()
        }
        return minimumFrames.toDouble() / fps
    }

    /**
     * Realistic estimate: one full pass, which already carries the configured redundancy margin.
     * A receiver that misses more than the margin allows needs another pass.
     */
    fun estimatedTransferSeconds(fps: Int = config.fps): Double =
        framesPerLoop.toDouble() / fps

    companion object {
        /** Measured worst case for the fountain code is ~1.5%; 5% leaves headroom. */
        const val RECEPTION_OVERHEAD = 1.05
    }
}

/** Builds a [PreparedTransfer] from a file. */
object TransferPreparation {

    class TooLarge(val size: Long, val limit: Long) :
        IllegalArgumentException("file is $size bytes, limit is $limit")

    class TooSmallGrid(message: String) : IllegalArgumentException(message)

    /**
     * Picks the largest source block size at or below the requested one that still lets a whole
     * symbol packet fit in a frame. Low-density modes (Grayscale 2 in particular) simply cannot
     * carry a 1 KiB symbol on a 96x96 grid, and silently shrinking the block is far better than
     * refusing the transfer.
     */
    fun fitPlan(layout: FrameLayout, config: SenderConfig): FramePlan {
        var blockSize = config.blockSize
        while (blockSize >= MIN_BLOCK_SIZE) {
            val plan = FramePlan(layout, config.paletteMode, config.eccLevel, blockSize)
            if (plan.isUsable) return plan
            blockSize /= 2
        }
        throw TooSmallGrid(
            "a ${config.gridCells}x${config.gridCells} grid in ${config.paletteMode.label} at " +
                "${config.eccLevel.label} cannot carry even a $MIN_BLOCK_SIZE-byte symbol; " +
                "use a larger grid, a denser palette, or a lower ECC level",
        )
    }

    const val MIN_BLOCK_SIZE = 64

    /** Convenience for small, in-memory payloads. */
    fun prepare(
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        config: SenderConfig = SenderConfig(),
        random: Random = Random.Default,
    ): PreparedTransfer = prepare(
        fileName, mimeType, ByteArrayTransferSource(bytes), config, random,
    )

    /**
     * Describes, optionally compresses and segments a transfer.
     *
     * The source is streamed twice at most - once to hash it, once as it is sent - and never held
     * whole, so the memory cost is one segment regardless of how large the file is.
     */
    fun prepare(
        fileName: String,
        mimeType: String,
        source: TransferSource,
        config: SenderConfig = SenderConfig(),
        random: Random = Random.Default,
        onHashProgress: (Long, Long) -> Unit = { _, _ -> },
    ): PreparedTransfer {
        if (source.size > config.maxFileSizeBytes) {
            throw TooLarge(source.size, config.maxFileSizeBytes.toLong())
        }
        require(source.size > 0) { "cannot send an empty file" }

        val layout = FrameLayout(config.gridCells)
        val plan = fitPlan(layout, config)

        val sha256 = hash(source, onHashProgress)

        // Compression only where the whole payload fits in memory; above that, segmentation
        // exists precisely to avoid holding a second full copy.
        val compressible = source.size <= SegmentPlan.IN_MEMORY_COMPRESSION_LIMIT &&
            config.compression != null &&
            !Compression.isLikelyIncompressible(mimeType, fileName)

        var compression = CompressionType.NONE
        var payload: TransferSource = source
        var payloadSize = source.size
        if (compressible) {
            val raw = ByteArray(source.size.toInt())
            source.read(0, raw, raw.size)
            val (used, compressed) = Compression.pick(raw, config.compression)
            compression = used
            payload = ByteArrayTransferSource(compressed)
            payloadSize = compressed.size.toLong()
        }

        val metadata = FileMetadata(
            fileName = fileName,
            mimeType = mimeType.ifBlank { "application/octet-stream" },
            originalSize = source.size.toInt(),
            compressedSize = payloadSize.toInt(),
            compression = compression,
            sha256 = sha256,
        )

        val reader = ObjectReader(metadata.encode(), payload)
        val segmentSize = config.segmentSizeBytes.coerceAtMost(reader.size.toInt().coerceAtLeast(1))
        val segments = SegmentPlan(reader.size, segmentSize)

        val blocksPerSegment = FountainEncoder.blockCount(segments.sizeOf(0), plan.blockSize)
        require(blocksPerSegment <= 65535) {
            "$blocksPerSegment source blocks per segment exceeds what the header can address; " +
                "use a smaller segment size or a larger block size"
        }

        return PreparedTransfer(
            metadata = metadata,
            config = config,
            streamId = random.nextInt(),
            objectReader = reader,
            segments = segments,
            plan = plan,
        )
    }

    private fun hash(source: TransferSource, onProgress: (Long, Long) -> Unit): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(256 * 1024)
        var position = 0L
        while (position < source.size) {
            val read = source.read(position, buffer, buffer.size)
            if (read <= 0) break
            digest.update(buffer, 0, read)
            position += read
            onProgress(position, source.size)
        }
        return digest.digest()
    }
}

/**
 * Produces the endless frame sequence for a prepared transfer.
 *
 * Within a segment the ESI space is never reused, so the stream stays rateless: a receiver that
 * starts at an arbitrary point simply collects whatever distinct symbols pass in front of it.
 * Across segments the sender cycles - there is no back channel, so it cannot know which segment a
 * given receiver still needs, and giving every segment its turn is the only strategy that
 * terminates.
 */
class StreamEncoder(
    val transfer: PreparedTransfer,
    /**
     * Cell size to advertise, which may differ from the prepared transfer's if the sender has
     * since resized the pattern. Cell size affects only how the frame is drawn, never how it is
     * coded, so changing it must not force a re-encode and a new stream id.
     */
    private val cellSizePx: Int = transfer.config.cellSizePx,
) {

    private val codec = FrameCodec(transfer.plan)

    /** Start frame of each segment within one pass, plus the total as a final entry. */
    private val schedule: IntArray = IntArray(transfer.segments.count + 1).also { starts ->
        var running = 0
        for (i in 0 until transfer.segments.count) {
            starts[i] = running
            running += transfer.framesFor(i)
        }
        starts[transfer.segments.count] = running
    }

    private var cachedSegment = -1
    private var cachedEncoder: FountainEncoder? = null

    val plan: FramePlan get() = transfer.plan
    val layout: FrameLayout get() = transfer.plan.layout

    /** Frames in one pass over every segment. */
    val framesPerLoop: Int get() = schedule[transfer.segments.count]

    /** Which segment frame [sequence] belongs to. */
    fun segmentOf(sequence: Int): Int {
        val within = Math.floorMod(sequence, framesPerLoop)
        var low = 0
        var high = transfer.segments.count - 1
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (schedule[mid] <= within) low = mid else high = mid - 1
        }
        return low
    }

    /** How far into its segment frame [sequence] is. */
    private fun offsetWithinSegment(sequence: Int, segment: Int): Int =
        Math.floorMod(sequence, framesPerLoop) - schedule[segment]

    /**
     * Segments are coded independently, each with its own PRNG stream, so a symbol from one can
     * never be mistaken for a symbol of another even though they share a stream id.
     */
    private fun paramsFor(segment: Int): FountainParams = FountainParams(
        k = transfer.blocksIn(segment),
        symbolSize = transfer.blockSize,
        streamId = segmentStreamId(transfer.streamId, segment),
    )

    private fun encoderFor(segment: Int): FountainEncoder {
        val cached = cachedEncoder
        if (cached != null && cachedSegment == segment) return cached
        val encoder = FountainEncoder(paramsFor(segment), transfer.readSegment(segment))
        cachedSegment = segment
        cachedEncoder = encoder
        return encoder
    }

    fun headerFor(sequence: Int): FrameHeader {
        val segment = segmentOf(sequence)
        return FrameHeader(
            streamId = transfer.streamId,
            totalEncodedSize = transfer.segments.sizeOf(segment),
            sourceBlocks = transfer.blocksIn(segment),
            blockSize = transfer.blockSize,
            paletteMode = transfer.config.paletteMode,
            cellSize = cellSizePx,
            eccLevel = transfer.config.eccLevel,
            compression = transfer.metadata.compression,
            frameSequence = sequence,
            nonce = sequence and 0xFFFF,
            symbolsInFrame = plan.symbolsPerFrame,
            segmentIndex = segment,
            segmentCount = transfer.segments.count,
        )
    }

    fun symbolsFor(sequence: Int): List<SymbolPacket> {
        val segment = segmentOf(sequence)
        val encoder = encoderFor(segment)
        val base = offsetWithinSegment(sequence, segment).toLong() * plan.symbolsPerFrame
        return List(plan.symbolsPerFrame) { i ->
            val esi = ((base + i) % Int.MAX_VALUE).toInt()
            SymbolPacket(esi, encoder.symbol(esi))
        }
    }

    /** ARGB per cell, row-major, for frame [sequence]. */
    fun renderCells(sequence: Int): IntArray =
        codec.render(headerFor(sequence), symbolsFor(sequence))

    companion object {
        /** Gives each segment an independent symbol plan derived from the shared stream id. */
        fun segmentStreamId(streamId: Int, segment: Int): Int {
            var z = streamId xor (segment * -0x61c88647)
            z = (z xor (z ushr 16)) * -0x7ee3623b
            z = (z xor (z ushr 13)) * -0x3d4d51cb
            return z xor (z ushr 16)
        }
    }
}
