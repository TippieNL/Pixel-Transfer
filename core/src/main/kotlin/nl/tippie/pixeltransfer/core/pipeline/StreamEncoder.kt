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
import kotlin.math.ceil
import kotlin.random.Random

/** A file that has been compressed, described and split, ready to be streamed. */
class PreparedTransfer(
    val metadata: FileMetadata,
    val config: SenderConfig,
    val streamId: Int,
    /** Metadata block followed by the (possibly compressed) file bytes. */
    internal val encodedObject: ByteArray,
    val fountainParams: FountainParams,
    val plan: FramePlan,
) {
    val sourceBlocks: Int get() = fountainParams.k
    val totalEncodedSize: Int get() = encodedObject.size
    val symbolsPerFrame: Int get() = plan.symbolsPerFrame

    /** Symbols a receiver needs, including the fountain code's reception overhead. */
    val symbolsNeeded: Int get() = ceil(sourceBlocks * RECEPTION_OVERHEAD).toInt()

    /** Frames in one nominal loop of the stream. The stream itself never actually repeats. */
    val framesPerLoop: Int get() = ceil(symbolsNeeded.toDouble() / symbolsPerFrame).toInt()

    /** Payload bytes carried by one frame. */
    val bytesPerFrame: Int get() = symbolsPerFrame * fountainParams.symbolSize

    /**
     * True when frame 0 alone carries the whole file.
     *
     * Symbols below K are systematic - symbol i *is* source block i - so when there are at most
     * `symbolsPerFrame` blocks, the first frame contains every one of them and no fountain
     * overhead is needed. This is the only case where a single still image is a complete
     * transfer, and the only case where exporting one is honest.
     */
    val fitsInOneFrame: Boolean get() = sourceBlocks <= symbolsPerFrame

    /** Best-case transfer time in seconds: every displayed frame decoded, no losses. */
    fun idealTransferSeconds(fps: Int = config.fps): Double =
        framesPerLoop.toDouble() / fps

    /**
     * Realistic estimate. A receiver typically loses some frames to shutter tearing, refocus and
     * hand shake; [captureEfficiency] is the fraction of displayed frames that decode.
     */
    fun estimatedTransferSeconds(fps: Int = config.fps, captureEfficiency: Double = 0.7): Double =
        idealTransferSeconds(fps) / captureEfficiency.coerceIn(0.05, 1.0)

    companion object {
        /** Measured worst case for the fountain code is ~1.5%; 5% leaves headroom. */
        const val RECEPTION_OVERHEAD = 1.05
    }
}

/** Builds a [PreparedTransfer] from raw file bytes. */
object TransferPreparation {

    class TooLarge(val size: Int, val limit: Int) :
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

    fun prepare(
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        config: SenderConfig = SenderConfig(),
        random: Random = Random.Default,
    ): PreparedTransfer {
        if (bytes.size > config.maxFileSizeBytes) throw TooLarge(bytes.size, config.maxFileSizeBytes)

        val layout = FrameLayout(config.gridCells)
        val plan = fitPlan(layout, config)

        val requested = if (config.compression != null &&
            Compression.isLikelyIncompressible(mimeType, fileName)
        ) {
            CompressionType.NONE
        } else {
            config.compression
        }
        val (compressionUsed, payload) = Compression.pick(bytes, requested)

        val metadata = FileMetadata(
            fileName = fileName,
            mimeType = mimeType.ifBlank { "application/octet-stream" },
            originalSize = bytes.size,
            compressedSize = payload.size,
            compression = compressionUsed,
            sha256 = FileMetadata.sha256(bytes),
        )

        val header = metadata.encode()
        val encodedObject = ByteArray(header.size + payload.size)
        header.copyInto(encodedObject, 0)
        payload.copyInto(encodedObject, header.size)

        val k = FountainEncoder.blockCount(encodedObject.size, plan.blockSize)
        require(k <= 65535) { "too many source blocks ($k); use a larger block size" }

        val streamId = random.nextInt()
        return PreparedTransfer(
            metadata = metadata,
            config = config,
            streamId = streamId,
            encodedObject = encodedObject,
            fountainParams = FountainParams(k, plan.blockSize, streamId),
            plan = plan,
        )
    }
}

/**
 * Produces the endless frame sequence for a prepared transfer.
 *
 * Frame `n` carries symbols `n * symbolsPerFrame` upwards, so the ESI space is never reused and
 * the stream stays rateless: a receiver that starts at an arbitrary point simply collects
 * whatever distinct symbols pass in front of it.
 */
class StreamEncoder(val transfer: PreparedTransfer) {

    private val fountain = FountainEncoder(transfer.fountainParams, transfer.encodedObject)
    private val codec = FrameCodec(transfer.plan)

    val plan: FramePlan get() = transfer.plan
    val layout: FrameLayout get() = transfer.plan.layout

    fun headerFor(sequence: Int): FrameHeader = FrameHeader(
        streamId = transfer.streamId,
        totalEncodedSize = transfer.totalEncodedSize,
        sourceBlocks = transfer.sourceBlocks,
        blockSize = transfer.fountainParams.symbolSize,
        paletteMode = transfer.config.paletteMode,
        cellSize = transfer.config.cellSizePx,
        eccLevel = transfer.config.eccLevel,
        compression = transfer.metadata.compression,
        frameSequence = sequence,
        nonce = sequence and 0xFFFF,
        symbolsInFrame = plan.symbolsPerFrame,
    )

    fun symbolsFor(sequence: Int): List<SymbolPacket> {
        val base = sequence.toLong() * plan.symbolsPerFrame
        return List(plan.symbolsPerFrame) { i ->
            val esi = ((base + i) % Int.MAX_VALUE).toInt()
            SymbolPacket(esi, fountain.symbol(esi))
        }
    }

    /** ARGB per cell, row-major, for frame [sequence]. */
    fun renderCells(sequence: Int): IntArray =
        codec.render(headerFor(sequence), symbolsFor(sequence))
}
