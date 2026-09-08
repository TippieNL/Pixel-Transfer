package nl.tippie.pixeltransfer.core.frame

/** MSB-first packing between byte buffers and fixed-width cell symbols. */
internal object Bits {

    fun bytesToSymbols(data: ByteArray, bitsPerSymbol: Int, symbolCount: Int): IntArray {
        val out = IntArray(symbolCount)
        var bit = 0
        val totalBits = data.size * 8
        for (i in 0 until symbolCount) {
            var value = 0
            for (b in 0 until bitsPerSymbol) {
                val v = if (bit < totalBits) {
                    (data[bit ushr 3].toInt() ushr (7 - (bit and 7))) and 1
                } else {
                    0
                }
                value = (value shl 1) or v
                bit++
            }
            out[i] = value
        }
        return out
    }

    fun symbolsToBytes(symbols: IntArray, bitsPerSymbol: Int, byteCount: Int): ByteArray {
        val out = ByteArray(byteCount)
        val totalBits = byteCount * 8
        var bit = 0
        for (symbol in symbols) {
            for (b in bitsPerSymbol - 1 downTo 0) {
                if (bit >= totalBits) return out
                if ((symbol ushr b) and 1 == 1) {
                    out[bit ushr 3] = (out[bit ushr 3].toInt() or (1 shl (7 - (bit and 7)))).toByte()
                }
                bit++
            }
        }
        return out
    }

    fun symbolsNeeded(byteCount: Int, bitsPerSymbol: Int): Int =
        (byteCount * 8 + bitsPerSymbol - 1) / bitsPerSymbol
}
