package nl.tippie.pixeltransfer.core.frame

import nl.tippie.pixeltransfer.core.codec.ByteIo
import nl.tippie.pixeltransfer.core.codec.CompressionType
import nl.tippie.pixeltransfer.core.codec.Crc32

/** Reed-Solomon redundancy applied inside each frame. */
enum class EccLevel(val id: Int, val nsym: Int, val label: String) {
    LOW(0, 26, "Low (~11%)"),
    MEDIUM(1, 52, "Medium (~20%)"),
    HIGH(2, 80, "High (~31%)"),
    MAX(3, 110, "Max (~43%)"),
    ;

    /** Byte errors correctable per 255-byte block. */
    val correctableBytes: Int get() = nsym / 2

    companion object {
        fun fromId(id: Int): EccLevel? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The fixed 32-byte frame header. It is protected by its own Reed-Solomon code and modulated in
 * the Robust palette regardless of what the payload uses, because the receiver has to read it
 * before it knows anything else about the stream.
 *
 * ```
 *  0  magic "PXT"                (3)
 *  3  format version             (1)
 *  4  stream id                  (4)
 *  8  total encoded object size  (4)
 * 12  K, source block count      (2)
 * 14  source block size          (2)
 * 16  palette mode               (1)
 * 17  cell size in pixels        (1)
 * 18  ecc level                  (1)
 * 19  compression type           (1)
 * 20  frame sequence number      (4)
 * 24  frame nonce                (2)
 * 26  symbols in this frame      (1)
 * 27  flags                      (1)
 * 28  crc32 of bytes 0..27       (4)
 * ```
 */
data class FrameHeader(
    val streamId: Int,
    val totalEncodedSize: Int,
    val sourceBlocks: Int,
    val blockSize: Int,
    val paletteMode: PaletteMode,
    val cellSize: Int,
    val eccLevel: EccLevel,
    val compression: CompressionType,
    val frameSequence: Int,
    val nonce: Int,
    val symbolsInFrame: Int,
    val flags: Int = 0,
) {

    fun encode(): ByteArray {
        val out = ByteArray(SIZE)
        out[0] = 'P'.code.toByte()
        out[1] = 'X'.code.toByte()
        out[2] = 'T'.code.toByte()
        out[3] = VERSION.toByte()
        ByteIo.putInt(out, 4, streamId)
        ByteIo.putInt(out, 8, totalEncodedSize)
        ByteIo.putShort(out, 12, sourceBlocks)
        ByteIo.putShort(out, 14, blockSize)
        out[16] = paletteMode.id.toByte()
        out[17] = cellSize.toByte()
        out[18] = eccLevel.id.toByte()
        out[19] = compression.id.toByte()
        ByteIo.putInt(out, 20, frameSequence)
        ByteIo.putShort(out, 24, nonce)
        out[26] = symbolsInFrame.toByte()
        out[27] = flags.toByte()
        ByteIo.putInt(out, 28, Crc32.of(out, 0, 28))
        return out
    }

    companion object {
        const val SIZE = 32
        const val VERSION = 1

        /** Parity bytes protecting the header, independent of the payload ECC level. */
        const val RS_PARITY = 16

        const val ENCODED_SIZE = SIZE + RS_PARITY

        /** Returns null when the magic, version, CRC or any enum field does not check out. */
        fun decode(bytes: ByteArray): FrameHeader? {
            if (bytes.size < SIZE) return null
            if (bytes[0] != 'P'.code.toByte() || bytes[1] != 'X'.code.toByte() || bytes[2] != 'T'.code.toByte()) return null
            if ((bytes[3].toInt() and 0xFF) != VERSION) return null
            if (Crc32.of(bytes, 0, 28) != ByteIo.getInt(bytes, 28)) return null

            val mode = PaletteMode.fromId(bytes[16].toInt() and 0xFF) ?: return null
            val ecc = EccLevel.fromId(bytes[18].toInt() and 0xFF) ?: return null
            val compression = CompressionType.fromId(bytes[19].toInt() and 0xFF) ?: return null
            val blocks = ByteIo.getShort(bytes, 12)
            val blockSize = ByteIo.getShort(bytes, 14)
            val total = ByteIo.getInt(bytes, 8)
            if (blocks <= 0 || blockSize <= 0 || total <= 0) return null

            return FrameHeader(
                streamId = ByteIo.getInt(bytes, 4),
                totalEncodedSize = total,
                sourceBlocks = blocks,
                blockSize = blockSize,
                paletteMode = mode,
                cellSize = bytes[17].toInt() and 0xFF,
                eccLevel = ecc,
                compression = compression,
                frameSequence = ByteIo.getInt(bytes, 20),
                nonce = ByteIo.getShort(bytes, 24),
                symbolsInFrame = bytes[26].toInt() and 0xFF,
                flags = bytes[27].toInt() and 0xFF,
            )
        }

        /** Deterministic tear-stripe bit for a nonce, row and stripe column. */
        fun stripeBit(nonce: Int, row: Int, column: Int): Boolean {
            var h = (nonce * 0x9E3779B1.toInt()) xor (row * 0x85EBCA6B.toInt())
            h = h xor (h ushr 15)
            h *= 0xC2B2AE35.toInt()
            h = h xor (h ushr 13)
            return ((h ushr (column and 15)) and 1) == 1
        }
    }
}
