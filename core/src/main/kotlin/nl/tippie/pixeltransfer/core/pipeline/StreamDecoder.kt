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
 */
class StreamDecoder(
    /**
     * How many consecutive frames from a different stream it takes to abandon the current one.
     * Lets the user point the camera at a second transfer without restarting the app.
     */
    private val foreignFramesBeforeSwitch: Int = 8,
) {

    var header: FrameHeader? = null
        private set

    private var fountain: FountainDecoder? = null
    private var foreignRun = 0

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

    val isComplete: Boolean get() = result != null

    val sourceBlocks: Int get() = header?.sourceBlocks ?: 0

    val blocksRecovered: Int get() = fountain?.recoveredBlocks ?: 0

    /** Symbols still needed, including the fountain code's reception overhead. */
    val symbolsNeeded: Int
        get() = header?.let { ceil(it.sourceBlocks * PreparedTransfer.RECEPTION_OVERHEAD).toInt() } ?: 0

    /** 0..1 over source blocks recovered. */
    val progress: Float get() = fountain?.progress ?: 0f

    fun reset() {
        header = null
        fountain = null
        metadata = null
        result = null
        failure = null
        foreignRun = 0
        framesAccepted = 0
        framesFromOtherStream = 0
        symbolsUnique = 0
        symbolsDuplicate = 0
    }

    /**
     * Feeds one decoded frame. Returns true when at least one new symbol was absorbed.
     */
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
        }

        val decoder = fountain ?: return false
        val expected = header!!.blockSize
        var gained = false
        for (packet in symbols) {
            if (packet.data.size != expected) continue
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
        framesAccepted++

        if (gained) {
            peekMetadata(decoder)
            if (!decoder.isComplete && symbolsUnique >= header!!.sourceBlocks) decoder.tryFinish()
            if (decoder.isComplete) finish(decoder)
        }
        return gained
    }

    private fun adopt(frameHeader: FrameHeader) {
        header = frameHeader
        foreignRun = 0
        fountain = FountainDecoder(
            FountainParams(frameHeader.sourceBlocks, frameHeader.blockSize, frameHeader.streamId),
        )
    }

    /** Surfaces the filename as soon as the leading blocks are in, long before the whole file. */
    private fun peekMetadata(decoder: FountainDecoder) {
        if (metadata != null) return
        val prefix = decoder.recoveredPrefix()
        if (prefix.size < FileMetadata.MIN_ENCODED_SIZE) return
        metadata = FileMetadata.decode(prefix)?.metadata
    }

    private fun finish(decoder: FountainDecoder) {
        val head = header ?: return
        val obj = decoder.assemble(head.totalEncodedSize)
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
    }
}
