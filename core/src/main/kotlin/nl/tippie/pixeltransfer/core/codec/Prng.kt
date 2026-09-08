package nl.tippie.pixeltransfer.core.codec

/**
 * SplitMix64. Deterministic and identical on both ends of the link, which matters because the
 * receiver has to reproduce the exact neighbour set the sender used for an encoded symbol and
 * there is no back channel to ask.
 */
internal class Prng(seed: Long) {

    private var state: Long = seed

    fun nextLong(): Long {
        state += -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L // 0x94D049BB133111EB
        return z xor (z ushr 31)
    }

    /** Uniform in [0, bound). */
    fun nextInt(bound: Int): Int {
        require(bound > 0)
        val r = (nextLong() ushr 1)
        return (r % bound).toInt()
    }

    /** Uniform in [0, 1). */
    fun nextDouble(): Double = (nextLong() ushr 11).toDouble() * (1.0 / (1L shl 53))

    companion object {
        /** Mixes a stream id and an encoded symbol id into a seed. */
        fun seedFor(streamId: Int, esi: Int): Long {
            var z = (streamId.toLong() shl 32) xor (esi.toLong() and 0xFFFFFFFFL)
            z = (z xor (z ushr 33)) * -0x7ee3623a03d3c83fL // 0xFF51AFD7ED558CCD
            z = (z xor (z ushr 33)) * -0x3b314601e57a13adL // 0xC4CEB9FE1A85EC53
            return z xor (z ushr 33)
        }
    }
}
