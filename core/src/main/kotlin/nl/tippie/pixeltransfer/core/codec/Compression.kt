package nl.tippie.pixeltransfer.core.codec

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Compression applied before the file enters the fountain code.
 *
 * Brotli is deliberately absent: neither the JDK nor the Android platform ships a Brotli
 * encoder, and pulling in a native one would add an ABI-specific dependency for a few percent
 * on payloads that are usually already compressed. [pick] auto-selects between NONE and
 * DEFLATE by measuring, which captures the same benefit.
 */
enum class CompressionType(val id: Int) {
    NONE(0),
    DEFLATE(1),
    ;

    companion object {
        fun fromId(id: Int): CompressionType? = entries.firstOrNull { it.id == id }
    }
}

object Compression {

    /** Deflate level used for the stream; 9 costs little at these payload sizes. */
    private const val LEVEL = 9

    /** Below this ratio compression is considered worthwhile. */
    private const val WORTHWHILE_RATIO = 0.95

    fun compress(data: ByteArray, type: CompressionType): ByteArray = when (type) {
        CompressionType.NONE -> data
        CompressionType.DEFLATE -> deflate(data)
    }

    fun decompress(data: ByteArray, type: CompressionType, expectedSize: Int): ByteArray =
        when (type) {
            CompressionType.NONE -> data
            CompressionType.DEFLATE -> inflate(data, expectedSize)
        }

    /**
     * Chooses a compression type by trying it. Files that are already compressed (JPEG, PNG,
     * MP4, ZIP, ...) come back within a few percent of their original size and are sent raw,
     * which is the "auto-skip if already compressed" rule.
     */
    fun pick(data: ByteArray, requested: CompressionType?): Pair<CompressionType, ByteArray> {
        if (requested == CompressionType.NONE) return CompressionType.NONE to data
        if (data.isEmpty()) return CompressionType.NONE to data
        val deflated = deflate(data)
        val useful = deflated.size < data.size * WORTHWHILE_RATIO
        return if (requested == CompressionType.DEFLATE && deflated.size < data.size) {
            CompressionType.DEFLATE to deflated
        } else if (useful) {
            CompressionType.DEFLATE to deflated
        } else {
            CompressionType.NONE to data
        }
    }

    /** True for content types whose payload is already entropy-coded. */
    fun isLikelyIncompressible(mimeType: String?, fileName: String?): Boolean {
        val mime = mimeType?.lowercase().orEmpty()
        if (mime.startsWith("image/") && !mime.contains("bmp") && !mime.contains("svg")) return true
        if (mime.startsWith("video/") || mime.startsWith("audio/")) return true
        if (mime in INCOMPRESSIBLE_MIME) return true
        val ext = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return ext in INCOMPRESSIBLE_EXT
    }

    private val INCOMPRESSIBLE_MIME = setOf(
        "application/zip", "application/gzip", "application/x-7z-compressed",
        "application/x-rar-compressed", "application/x-xz", "application/x-bzip2",
        "application/pdf",
    )

    private val INCOMPRESSIBLE_EXT = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif",
        "mp3", "aac", "ogg", "opus", "flac", "m4a",
        "mp4", "mkv", "webm", "mov", "avi",
        "zip", "gz", "bz2", "xz", "7z", "rar", "zst", "apk", "jar",
    )

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(LEVEL, true)
        try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream(maxOf(64, data.size / 2))
            val buffer = ByteArray(16 * 1024)
            while (!deflater.finished()) {
                val n = deflater.deflate(buffer)
                if (n == 0 && deflater.finished()) break
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun inflate(data: ByteArray, expectedSize: Int): ByteArray {
        val inflater = Inflater(true)
        try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream(maxOf(64, expectedSize))
            val buffer = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                }
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }
}
