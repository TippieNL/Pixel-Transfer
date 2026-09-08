package nl.tippie.pixeltransfer.core.codec

import java.security.MessageDigest

/**
 * The metadata block that is prepended to the compressed payload *before* fountain coding, so
 * it travels inside the encoded stream rather than beside it.
 *
 * Layout (big-endian):
 * ```
 *  0  magic "PXTM"                (4)
 *  4  version                     (1)
 *  5  compression type            (1)
 *  6  original size               (4)
 * 10  compressed size             (4)
 * 14  sha-256 of the original     (32)
 * 46  mime length m               (1)
 * 47  name length n               (2)
 * 49  mime bytes                  (m)
 *     name bytes, UTF-8           (n)
 *     crc32 of everything above   (4)
 * ```
 */
data class FileMetadata(
    val fileName: String,
    val mimeType: String,
    val originalSize: Int,
    val compressedSize: Int,
    val compression: CompressionType,
    val sha256: ByteArray,
) {
    init {
        require(sha256.size == SHA256_LEN) { "sha256 must be 32 bytes" }
    }

    val compressionRatio: Double
        get() = if (originalSize == 0) 1.0 else compressedSize.toDouble() / originalSize

    fun encode(): ByteArray {
        val mimeBytes = mimeType.toByteArray(Charsets.UTF_8)
        val nameBytes = fileName.toByteArray(Charsets.UTF_8)
        require(mimeBytes.size <= 255) { "mime type too long" }
        require(nameBytes.size <= 65535) { "file name too long" }

        val body = 49 + mimeBytes.size + nameBytes.size
        val out = ByteArray(body + 4)
        MAGIC.copyInto(out, 0)
        out[4] = VERSION.toByte()
        out[5] = compression.id.toByte()
        ByteIo.putInt(out, 6, originalSize)
        ByteIo.putInt(out, 10, compressedSize)
        sha256.copyInto(out, 14)
        out[46] = mimeBytes.size.toByte()
        ByteIo.putShort(out, 47, nameBytes.size)
        mimeBytes.copyInto(out, 49)
        nameBytes.copyInto(out, 49 + mimeBytes.size)
        ByteIo.putInt(out, body, Crc32.of(out, 0, body))
        return out
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FileMetadata) return false
        return fileName == other.fileName && mimeType == other.mimeType &&
            originalSize == other.originalSize && compressedSize == other.compressedSize &&
            compression == other.compression && sha256.contentEquals(other.sha256)
    }

    override fun hashCode(): Int {
        var result = fileName.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + originalSize
        result = 31 * result + compressedSize
        result = 31 * result + compression.hashCode()
        result = 31 * result + sha256.contentHashCode()
        return result
    }

    companion object {
        const val SHA256_LEN = 32
        const val VERSION = 1
        private val MAGIC = byteArrayOf('P'.code.toByte(), 'X'.code.toByte(), 'T'.code.toByte(), 'M'.code.toByte())

        /** Smallest possible encoded metadata block, used to know when a peek is premature. */
        const val MIN_ENCODED_SIZE = 53

        fun sha256(data: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(data)

        /**
         * Parses a metadata block from the front of [data].
         *
         * Returns null when the buffer is too short, the magic does not match, or the CRC32
         * fails. This is called speculatively on partially recovered data, so "not yet" and
         * "corrupt" are both simply null.
         */
        fun decode(data: ByteArray): Parsed? {
            if (data.size < MIN_ENCODED_SIZE) return null
            for (i in MAGIC.indices) if (data[i] != MAGIC[i]) return null
            if ((data[4].toInt() and 0xFF) != VERSION) return null
            val compression = CompressionType.fromId(data[5].toInt() and 0xFF) ?: return null
            val originalSize = ByteIo.getInt(data, 6)
            val compressedSize = ByteIo.getInt(data, 10)
            if (originalSize < 0 || compressedSize < 0) return null
            val sha = data.copyOfRange(14, 46)
            val mimeLen = data[46].toInt() and 0xFF
            val nameLen = ByteIo.getShort(data, 47)
            val body = 49 + mimeLen + nameLen
            if (data.size < body + 4) return null
            if (Crc32.of(data, 0, body) != ByteIo.getInt(data, body)) return null
            val mime = String(data, 49, mimeLen, Charsets.UTF_8)
            val name = String(data, 49 + mimeLen, nameLen, Charsets.UTF_8)
            return Parsed(
                FileMetadata(name, mime, originalSize, compressedSize, compression, sha),
                body + 4,
            )
        }
    }

    /** A decoded metadata block plus the offset at which the compressed payload begins. */
    data class Parsed(val metadata: FileMetadata, val payloadOffset: Int)
}
