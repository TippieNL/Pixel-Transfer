package nl.tippie.pixeltransfer.core.pipeline

import java.io.Closeable
import kotlin.math.min

/**
 * Random-access read over the bytes being sent.
 *
 * The sender never holds the whole file. A 150 MB transfer would otherwise need the file, a
 * compressed copy, the coded object and the fountain encoder's per-block copies all resident at
 * once - four times the file size, which no phone will give an app.
 */
interface TransferSource : Closeable {
    val size: Long

    /** Fills [into] with [length] bytes starting at [offset]. Returns bytes actually read. */
    fun read(offset: Long, into: ByteArray, length: Int): Int

    override fun close() {}
}

/** A [TransferSource] over bytes already in memory. Used for small files and by the tests. */
class ByteArrayTransferSource(private val bytes: ByteArray) : TransferSource {
    override val size: Long get() = bytes.size.toLong()

    override fun read(offset: Long, into: ByteArray, length: Int): Int {
        if (offset >= bytes.size) return 0
        val count = min(length.toLong(), bytes.size - offset).toInt()
        System.arraycopy(bytes, offset.toInt(), into, 0, count)
        return count
    }
}

/** Where the receiver puts recovered segments as they complete. */
interface SegmentSink : Closeable {
    /** Writes [length] bytes of [bytes] at [offset] in the reconstructed file. */
    fun write(offset: Long, bytes: ByteArray, length: Int)

    override fun close() {}
}

/** A [SegmentSink] that assembles into memory. Only suitable for small transfers. */
class ByteArraySegmentSink(totalSize: Int) : SegmentSink {
    val bytes = ByteArray(totalSize)

    override fun write(offset: Long, bytes: ByteArray, length: Int) {
        val start = offset.toInt()
        if (start >= this.bytes.size) return
        val count = min(length, this.bytes.size - start)
        System.arraycopy(bytes, 0, this.bytes, start, count)
    }
}

/**
 * How a coded object is cut into independently fountain-coded segments.
 *
 * Each segment is decoded, verified and written out on its own, so the receiver only ever holds a
 * segment's worth of state rather than the whole file, and the source block count stays inside the
 * two bytes the frame header gives it.
 */
class SegmentPlan(val objectSize: Long, val segmentSize: Int) {

    init {
        require(objectSize > 0) { "object must not be empty" }
        require(segmentSize > 0) { "segment size must be positive" }
    }

    val count: Int = (((objectSize + segmentSize - 1) / segmentSize).toInt()).coerceAtLeast(1)

    init {
        require(count <= MAX_SEGMENTS) {
            "$count segments exceeds the $MAX_SEGMENTS the frame header can address"
        }
    }

    fun offsetOf(index: Int): Long = index.toLong() * segmentSize

    fun sizeOf(index: Int): Int =
        min(segmentSize.toLong(), objectSize - offsetOf(index)).toInt()

    val isSingleSegment: Boolean get() = count == 1

    companion object {
        /** The frame header stores the segment index and count in two bytes each. */
        const val MAX_SEGMENTS = 65535

        /**
         * Default bytes per segment.
         *
         * Sized so the source block count stays near 2000 at the default 1 KiB block, which is
         * where the fountain decoder's elimination fallback is still quick, and so that neither
         * end has to hold more than a couple of megabytes of decode state per segment.
         */
        const val DEFAULT_SEGMENT_SIZE = 2 * 1024 * 1024

        /**
         * Largest payload compressed in one piece.
         *
         * Compressing across segments would mean holding the whole compressed copy in memory,
         * which is exactly what segmentation exists to avoid. Above this the payload is sent
         * uncompressed - which costs nothing in practice, because files this large are photos and
         * videos whose bytes are already entropy-coded.
         */
        const val IN_MEMORY_COMPRESSION_LIMIT = 8 * 1024 * 1024
    }
}

/**
 * Reads the coded object - the metadata block followed by the payload - as one addressable range,
 * without ever materialising it.
 */
class ObjectReader internal constructor(
    private val metadata: ByteArray,
    private val payload: TransferSource,
) {
    val size: Long get() = metadata.size + payload.size

    /** Reads [length] bytes of the object at [offset] into a fresh array. */
    fun read(offset: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        var written = 0
        var position = offset

        if (position < metadata.size) {
            val fromMetadata = min((metadata.size - position).toInt(), length)
            System.arraycopy(metadata, position.toInt(), out, 0, fromMetadata)
            written += fromMetadata
            position += fromMetadata
        }

        // The source may short-read; keep asking until it is filled or reports end of data.
        val chunk = ByteArray(CHUNK)
        while (written < length) {
            val want = min(CHUNK, length - written)
            val read = payload.read(position - metadata.size, chunk, want)
            if (read <= 0) break
            System.arraycopy(chunk, 0, out, written, read)
            written += read
            position += read
        }
        return out
    }

    private companion object {
        const val CHUNK = 64 * 1024
    }
}
