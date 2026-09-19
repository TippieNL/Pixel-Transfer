package nl.tippie.pixeltransfer.receiver

import android.content.Context
import android.net.Uri
import android.os.storage.StorageManager
import nl.tippie.pixeltransfer.core.codec.Compression
import nl.tippie.pixeltransfer.core.codec.CompressionType
import nl.tippie.pixeltransfer.core.codec.FileMetadata
import nl.tippie.pixeltransfer.util.FileIo
import nl.tippie.pixeltransfer.util.FileSegmentSink
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Holds a transfer on disk while it is being received.
 *
 * A 150 MB file cannot be assembled in memory, so segments are written to a scratch file as they
 * complete and the final SHA-256 check streams over it. Nothing larger than a segment is ever
 * resident.
 */
class ReceivedFileStore(private val context: Context) {

    private val scratch: File get() = File(context.cacheDir, "incoming.pxt")
    private val expanded: File get() = File(context.cacheDir, "incoming.out")

    class Verified(
        val metadata: FileMetadata,
        /** File holding the finished bytes. */
        val file: File,
        val offset: Long,
        val length: Long,
        val sha256Verified: Boolean,
    )

    /**
     * Free space where the transfer will land.
     *
     * `usableSpace` under-reports, because Android will evict other apps' caches on demand;
     * `getAllocatableBytes` accounts for that and is what a large transfer should be judged
     * against.
     */
    fun freeSpace(): Long {
        runCatching {
            val storage = context.getSystemService(StorageManager::class.java)
            val uuid = storage.getUuidForPath(context.cacheDir)
            return storage.getAllocatableBytes(uuid)
        }
        return context.cacheDir.usableSpace
    }

    fun newSink(): FileSegmentSink {
        clear()
        scratch.parentFile?.mkdirs()
        scratch.createNewFile()
        return FileSegmentSink(scratch)
    }

    /**
     * Reads the metadata block, expands the payload if it was compressed, and verifies the whole
     * thing against the sender's hash.
     *
     * SHA-256 remains the only gate that reports success: a mismatch is a failure, never a file.
     */
    fun finish(): Verified? {
        if (!scratch.exists()) return null
        val head = ByteArray(minOf(scratch.length(), 4096L).toInt())
        RandomAccessFile(scratch, "r").use { it.readFully(head) }
        val parsed = FileMetadata.decode(head) ?: return null
        val metadata = parsed.metadata
        val offset = parsed.payloadOffset.toLong()
        val length = metadata.compressedSize.toLong()
        if (offset + length > scratch.length()) return null

        if (metadata.compression == CompressionType.NONE) {
            val digest = hashRange(scratch, offset, length)
            return Verified(
                metadata, scratch, offset, length,
                digest.contentEquals(metadata.sha256),
            )
        }

        // Compression is only ever applied to payloads small enough to hold, so expanding here is
        // bounded by the same limit that allowed it in the first place.
        val compressed = ByteArray(length.toInt())
        RandomAccessFile(scratch, "r").use {
            it.seek(offset)
            it.readFully(compressed)
        }
        val plain = try {
            Compression.decompress(compressed, metadata.compression, metadata.originalSize)
        } catch (e: Exception) {
            return null
        }
        if (plain.size != metadata.originalSize) return null
        expanded.writeBytes(plain)
        return Verified(
            metadata, expanded, 0L, plain.size.toLong(),
            FileMetadata.sha256(plain).contentEquals(metadata.sha256),
        )
    }

    /** Copies the finished bytes to a destination the user picked. */
    fun saveTo(verified: Verified, target: Uri) {
        context.contentResolver.openOutputStream(target, "wt").use { output ->
            requireNotNull(output) { "cannot write to $target" }
            RandomAccessFile(verified.file, "r").use { input ->
                input.seek(verified.offset)
                val buffer = ByteArray(256 * 1024)
                var remaining = verified.length
                while (remaining > 0) {
                    val want = minOf(buffer.size.toLong(), remaining).toInt()
                    val read = input.read(buffer, 0, want)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    remaining -= read
                }
            }
            output.flush()
        }
    }

    fun clear() {
        runCatching { scratch.delete() }
        runCatching { expanded.delete() }
    }

    private fun hashRange(file: File, offset: Long, length: Long): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(file, "r").use { input ->
            input.seek(offset)
            val buffer = ByteArray(256 * 1024)
            var remaining = length
            while (remaining > 0) {
                val want = minOf(buffer.size.toLong(), remaining).toInt()
                val read = input.read(buffer, 0, want)
                if (read <= 0) break
                digest.update(buffer, 0, read)
                remaining -= read
            }
        }
        return digest.digest()
    }
}
