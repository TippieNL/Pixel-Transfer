package nl.tippie.pixeltransfer.util

import android.content.Context
import android.net.Uri
import nl.tippie.pixeltransfer.core.pipeline.SegmentSink
import nl.tippie.pixeltransfer.core.pipeline.TransferSource
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/**
 * Reads a picked document without ever holding it in memory.
 *
 * A 150 MB transfer cannot be read into a `ByteArray`: the app would need the file, a compressed
 * copy and the encoder's block copies resident at once. The sender only ever needs the segment it
 * is currently showing, so this exposes random access instead.
 */
class UriTransferSource(
    context: Context,
    private val uri: Uri,
    override val size: Long,
) : TransferSource {

    private val resolver = context.contentResolver

    /**
     * Most providers hand back a real file descriptor, which gives genuine random access. Those
     * that do not fall back to reopening the stream and skipping, which is slower but correct.
     */
    private val descriptor = runCatching { resolver.openFileDescriptor(uri, "r") }.getOrNull()
    private val channel: FileChannel? = descriptor?.let {
        runCatching { FileInputStream(it.fileDescriptor).channel }.getOrNull()
    }

    private var stream: java.io.InputStream? = null
    private var streamPosition = -1L

    override fun read(offset: Long, into: ByteArray, length: Int): Int {
        if (offset >= size || length <= 0) return 0
        channel?.let { return readFromChannel(it, offset, into, length) }
        return readFromStream(offset, into, length)
    }

    private fun readFromChannel(
        channel: FileChannel,
        offset: Long,
        into: ByteArray,
        length: Int,
    ): Int {
        val buffer = java.nio.ByteBuffer.wrap(into, 0, length)
        var total = 0
        var position = offset
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, position)
            if (read <= 0) break
            total += read
            position += read
        }
        return total
    }

    private fun readFromStream(offset: Long, into: ByteArray, length: Int): Int {
        // Reopen only when asked to go backwards; the sender reads forward within a pass.
        if (stream == null || offset < streamPosition) {
            stream?.close()
            stream = resolver.openInputStream(uri)
            streamPosition = 0L
        }
        val input = stream ?: return 0
        while (streamPosition < offset) {
            val skipped = input.skip(offset - streamPosition)
            if (skipped <= 0) {
                if (input.read() < 0) return 0
                streamPosition++
            } else {
                streamPosition += skipped
            }
        }
        var total = 0
        while (total < length) {
            val read = input.read(into, total, length - total)
            if (read < 0) break
            total += read
            streamPosition += read
        }
        return total
    }

    override fun close() {
        runCatching { stream?.close() }
        runCatching { channel?.close() }
        runCatching { descriptor?.close() }
    }
}

/**
 * Writes recovered segments straight to a file as they complete, so the receiver never holds more
 * than a segment of the transfer in memory.
 */
class FileSegmentSink(private val file: File) : SegmentSink {

    private val handle = RandomAccessFile(file, "rw")

    override fun write(offset: Long, bytes: ByteArray, length: Int) {
        synchronized(handle) {
            handle.seek(offset)
            handle.write(bytes, 0, length)
        }
    }

    override fun close() {
        synchronized(handle) { runCatching { handle.close() } }
    }
}
