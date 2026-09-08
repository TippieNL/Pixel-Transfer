package nl.tippie.pixeltransfer.core.codec

/**
 * Systematic Reed-Solomon over GF(256) with first consecutive root a^0 and generator a=2.
 *
 * A codeword is `k` data bytes followed by `nsym` parity bytes and can correct up to
 * `nsym / 2` byte errors at unknown positions. Messages longer than `255 - nsym` are
 * automatically split into interleaved-by-chunk blocks by [RsCodec].
 *
 * Reed-Solomon is the *error* half of PixelTransfer's protection: it repairs cells that the
 * camera misclassified inside a frame that arrived. Whole missing frames are the fountain
 * code's job.
 */
internal object ReedSolomon {

    fun encode(data: ByteArray, nsym: Int): ByteArray {
        require(data.size + nsym <= 255) { "RS block too long: ${data.size} + $nsym" }
        val gen = GfPoly.generator(nsym)
        val out = IntArray(data.size + nsym)
        for (i in data.indices) out[i] = data[i].toInt() and 0xFF
        for (i in data.indices) {
            val coef = out[i]
            if (coef == 0) continue
            for (j in 1 until gen.size) {
                out[i + j] = out[i + j] xor Gf256.mul(gen[j], coef)
            }
        }
        // The systematic prefix was clobbered by the division; restore it.
        for (i in data.indices) out[i] = data[i].toInt() and 0xFF
        return ByteArray(out.size) { out[it].toByte() }
    }

    class DecodeResult(val data: ByteArray?, val errorsCorrected: Int) {
        val ok: Boolean get() = data != null
    }

    /**
     * Corrects up to `nsym / 2` errors in [codeword] and returns the systematic data part.
     * Returns a failed result (data == null) when the codeword is beyond repair; it never
     * returns silently wrong data without at least having verified the syndromes vanish.
     */
    fun decode(codeword: ByteArray, nsym: Int): DecodeResult {
        require(codeword.size <= 255) { "RS codeword too long: ${codeword.size}" }
        val k = codeword.size - nsym
        if (k <= 0) return DecodeResult(null, 0)

        val msg = IntArray(codeword.size) { codeword[it].toInt() and 0xFF }
        val synd = syndromes(msg, nsym)
        if (synd.all { it == 0 }) {
            return DecodeResult(ByteArray(k) { msg[it].toByte() }, 0)
        }

        val sigma = berlekampMassey(synd, nsym) ?: return DecodeResult(null, 0)
        val positions = chienSearch(sigma, msg.size) ?: return DecodeResult(null, 0)
        if (positions.isEmpty()) return DecodeResult(null, 0)

        forney(msg, synd, positions, nsym)

        // Re-verify: a mis-correction almost always leaves non-zero syndromes.
        val check = syndromes(msg, nsym)
        if (check.any { it != 0 }) return DecodeResult(null, 0)
        return DecodeResult(ByteArray(k) { msg[it].toByte() }, positions.size)
    }

    /** Syndromes S_i = C(a^i), most-significant-first order, index 0 == S_0. */
    private fun syndromes(msg: IntArray, nsym: Int): IntArray =
        IntArray(nsym) { GfPoly.eval(msg, Gf256.pow(2, it)) }

    /** Returns the error locator polynomial, or null if the error count exceeds the code's power. */
    private fun berlekampMassey(synd: IntArray, nsym: Int): IntArray? {
        var sigma = intArrayOf(1)
        var old = intArrayOf(1)
        for (i in 0 until nsym) {
            old = intArrayOf(*old, 0)
            var delta = synd[i]
            for (j in 1 until sigma.size) {
                delta = delta xor Gf256.mul(sigma[sigma.size - 1 - j], synd[i - j])
            }
            if (delta != 0) {
                if (old.size > sigma.size) {
                    val newSigma = GfPoly.scale(old, delta)
                    old = GfPoly.scale(sigma, Gf256.inv(delta))
                    sigma = newSigma
                }
                sigma = GfPoly.add(sigma, GfPoly.scale(old, delta))
            }
        }
        // Strip leading zeros.
        var start = 0
        while (start < sigma.size - 1 && sigma[start] == 0) start++
        sigma = sigma.copyOfRange(start, sigma.size)
        val errs = sigma.size - 1
        if (errs * 2 > nsym) return null
        return sigma
    }

    /** Returns error positions as indices into the most-significant-first codeword. */
    private fun chienSearch(sigma: IntArray, msgLen: Int): IntArray? {
        val errs = sigma.size - 1
        val positions = ArrayList<Int>(errs)
        for (i in 0 until msgLen) {
            // Root check: sigma(a^-i) == 0 => error at position msgLen - 1 - i.
            if (GfPoly.eval(sigma, Gf256.pow(2, 255 - i)) == 0) {
                positions.add(msgLen - 1 - i)
            }
        }
        if (positions.size != errs) return null
        return positions.toIntArray()
    }

    /** Forney algorithm: computes error magnitudes and applies them to [msg] in place. */
    private fun forney(msg: IntArray, synd: IntArray, positions: IntArray, nsym: Int) {
        // Reversed syndrome polynomial, most-significant-first.
        val syndPoly = IntArray(nsym) { synd[nsym - 1 - it] }

        // Error locator from positions.
        var errLoc = intArrayOf(1)
        for (p in positions) {
            val x = Gf256.pow(2, msg.size - 1 - p)
            errLoc = GfPoly.mul(errLoc, intArrayOf(x, 1))
        }

        // Error evaluator omega = (synd * errLoc) mod x^nsym.
        val prod = GfPoly.mul(syndPoly, errLoc)
        val omega = prod.copyOfRange(prod.size - nsym, prod.size)

        // Formal derivative of errLoc: keep the odd-degree terms.
        val derivative = IntArray(errLoc.size - 1)
        for (i in derivative.indices) {
            // Coefficient of x^(deg-i) in errLoc becomes x^(deg-i-1); in GF(2) even multiples vanish.
            val degree = errLoc.size - 1 - i
            derivative[i] = if (degree % 2 == 1) errLoc[i] else 0
        }
        val derivShifted = derivative

        for (p in positions) {
            val xi = Gf256.pow(2, msg.size - 1 - p)
            val xiInv = Gf256.inv(xi)
            val num = GfPoly.eval(omega, xiInv)
            val den = GfPoly.eval(derivShifted, xiInv)
            if (den == 0) continue
            val magnitude = Gf256.mul(xi, Gf256.div(num, den))
            msg[p] = msg[p] xor magnitude
        }
    }
}

/**
 * Splits arbitrary-length payloads into RS(255) blocks. The last block is shortened.
 *
 * Wire layout is block-sequential (data|parity, data|parity, ...) so that a decoder can
 * repair each block independently and still surface the parts that survived when one block
 * is beyond repair.
 */
class RsCodec(val nsym: Int) {
    init {
        require(nsym in 2..254 && nsym % 2 == 0) { "nsym must be even and in 2..254, was $nsym" }
    }

    private val dataPerBlock = 255 - nsym

    fun blockCount(dataLength: Int): Int =
        if (dataLength == 0) 0 else (dataLength + dataPerBlock - 1) / dataPerBlock

    fun encodedSize(dataLength: Int): Int = dataLength + blockCount(dataLength) * nsym

    fun encode(data: ByteArray): ByteArray {
        val out = ByteArray(encodedSize(data.size))
        var src = 0
        var dst = 0
        while (src < data.size) {
            val len = minOf(dataPerBlock, data.size - src)
            val block = ReedSolomon.encode(data.copyOfRange(src, src + len), nsym)
            block.copyInto(out, dst)
            src += len
            dst += block.size
        }
        return out
    }

    class Decoded(
        val data: ByteArray,
        /** One flag per RS block; false means that block could not be repaired. */
        val blockOk: BooleanArray,
        val errorsCorrected: Int,
    ) {
        val allOk: Boolean get() = blockOk.all { it }
        val dataPerBlock: Int get() = if (blockOk.isEmpty()) 0 else (data.size + blockOk.size - 1) / blockOk.size
    }

    /**
     * Decodes an encoded buffer of exactly [encodedSize] bytes for the given [dataLength].
     * Blocks that fail are copied through unrepaired and flagged; downstream CRCs decide
     * whether the bytes are usable.
     */
    fun decode(encoded: ByteArray, dataLength: Int): Decoded {
        require(encoded.size == encodedSize(dataLength)) {
            "expected ${encodedSize(dataLength)} encoded bytes, got ${encoded.size}"
        }
        val blocks = blockCount(dataLength)
        val out = ByteArray(dataLength)
        val ok = BooleanArray(blocks)
        var corrected = 0
        var src = 0
        var dst = 0
        for (b in 0 until blocks) {
            val len = minOf(dataPerBlock, dataLength - dst)
            val codeword = encoded.copyOfRange(src, src + len + nsym)
            val result = ReedSolomon.decode(codeword, nsym)
            if (result.ok) {
                result.data!!.copyInto(out, dst)
                ok[b] = true
                corrected += result.errorsCorrected
            } else {
                codeword.copyInto(out, dst, 0, len)
                ok[b] = false
            }
            src += len + nsym
            dst += len
        }
        return Decoded(out, ok, corrected)
    }

    /** Maps a byte offset in the decoded data to the RS block that carried it. */
    fun blockOfDataOffset(offset: Int): Int = offset / dataPerBlock
}
