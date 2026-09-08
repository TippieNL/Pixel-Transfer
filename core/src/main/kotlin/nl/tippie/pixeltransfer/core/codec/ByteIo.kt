package nl.tippie.pixeltransfer.core.codec

import java.util.zip.CRC32

/** Big-endian byte readers/writers plus the CRC32 used at every layer of the stack. */
internal object ByteIo {

    fun putInt(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = (value ushr 24).toByte()
        dst[offset + 1] = (value ushr 16).toByte()
        dst[offset + 2] = (value ushr 8).toByte()
        dst[offset + 3] = value.toByte()
    }

    fun getInt(src: ByteArray, offset: Int): Int =
        ((src[offset].toInt() and 0xFF) shl 24) or
            ((src[offset + 1].toInt() and 0xFF) shl 16) or
            ((src[offset + 2].toInt() and 0xFF) shl 8) or
            (src[offset + 3].toInt() and 0xFF)

    fun putShort(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = (value ushr 8).toByte()
        dst[offset + 1] = value.toByte()
    }

    fun getShort(src: ByteArray, offset: Int): Int =
        ((src[offset].toInt() and 0xFF) shl 8) or (src[offset + 1].toInt() and 0xFF)
}

/** CRC32 helpers. Used for the frame header, every symbol packet, and the metadata block. */
object Crc32 {

    fun of(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        val crc = CRC32()
        crc.update(data, offset, length)
        return crc.value.toInt()
    }

    fun matches(data: ByteArray, offset: Int, length: Int, expected: Int): Boolean =
        of(data, offset, length) == expected
}
