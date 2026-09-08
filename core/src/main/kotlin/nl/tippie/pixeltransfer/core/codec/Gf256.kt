package nl.tippie.pixeltransfer.core.codec

/**
 * Arithmetic in GF(2^8) with the primitive polynomial x^8 + x^4 + x^3 + x^2 + 1 (0x11D),
 * the field used by the Reed-Solomon codec.
 *
 * Log/antilog tables are built once; [exp] is doubled so that `exp[log[a] + log[b]]` never
 * needs a modulo.
 */
internal object Gf256 {
    const val PRIMITIVE = 0x11D

    val exp = IntArray(512)
    val log = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            exp[i] = x
            log[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor PRIMITIVE
        }
        for (i in 255 until 512) exp[i] = exp[i - 255]
        // log[0] is mathematically undefined; mul()/div() guard against it.
        log[0] = 0
    }

    fun mul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]

    fun div(a: Int, b: Int): Int {
        require(b != 0) { "division by zero in GF(256)" }
        return if (a == 0) 0 else exp[(log[a] + 255 - log[b]) % 255]
    }

    fun inv(a: Int): Int {
        require(a != 0) { "no inverse for 0 in GF(256)" }
        return exp[255 - log[a]]
    }

    fun pow(a: Int, n: Int): Int {
        if (a == 0) return if (n == 0) 1 else 0
        var e = (log[a] * n) % 255
        if (e < 0) e += 255
        return exp[e]
    }
}

/**
 * Polynomials over GF(256), stored most-significant coefficient first (index 0 is the
 * highest degree term), matching the classic Reed-Solomon reference implementations.
 */
internal object GfPoly {

    fun scale(p: IntArray, x: Int): IntArray = IntArray(p.size) { Gf256.mul(p[it], x) }

    fun add(p: IntArray, q: IntArray): IntArray {
        val r = IntArray(maxOf(p.size, q.size))
        for (i in p.indices) r[i + r.size - p.size] = p[i]
        for (i in q.indices) r[i + r.size - q.size] = r[i + r.size - q.size] xor q[i]
        return r
    }

    fun mul(p: IntArray, q: IntArray): IntArray {
        val r = IntArray(p.size + q.size - 1)
        for (j in q.indices) {
            val qj = q[j]
            if (qj == 0) continue
            for (i in p.indices) {
                r[i + j] = r[i + j] xor Gf256.mul(p[i], qj)
            }
        }
        return r
    }

    /** Horner evaluation of [p] at [x]. */
    fun eval(p: IntArray, x: Int): Int {
        var y = p[0]
        for (i in 1 until p.size) y = Gf256.mul(y, x) xor p[i]
        return y
    }

    /** Returns (quotient, remainder) of [dividend] / [divisor]. */
    fun div(dividend: IntArray, divisor: IntArray): Pair<IntArray, IntArray> {
        val out = dividend.copyOf()
        val normalizer = divisor[0]
        for (i in 0 until dividend.size - divisor.size + 1) {
            out[i] = Gf256.div(out[i], normalizer)
            val coef = out[i]
            if (coef == 0) continue
            for (j in 1 until divisor.size) {
                if (divisor[j] == 0) continue
                out[i + j] = out[i + j] xor Gf256.mul(divisor[j], coef)
            }
        }
        val separator = dividend.size - divisor.size + 1
        return out.copyOfRange(0, separator) to out.copyOfRange(separator, out.size)
    }

    /** Generator polynomial for [nsym] parity symbols: prod (x - a^i) for i in 0 until nsym. */
    fun generator(nsym: Int): IntArray {
        var g = intArrayOf(1)
        for (i in 0 until nsym) {
            g = mul(g, intArrayOf(1, Gf256.pow(2, i)))
        }
        return g
    }
}
