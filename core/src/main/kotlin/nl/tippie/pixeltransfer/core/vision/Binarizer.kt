package nl.tippie.pixeltransfer.core.vision

/**
 * Local-mean adaptive threshold built on an integral image.
 *
 * A global threshold fails immediately on a phone screen: the display is far brighter than the
 * surround, glare hotspots blow out one corner, and PWM dimming and vignetting make the "same"
 * colour vary by a factor of two across the frame.
 */
class Binarizer(private val width: Int, private val height: Int) {

    private val integral = LongArray((width + 1) * (height + 1))

    /** Packed one bit per pixel; true means dark. */
    val dark = java.util.BitSet(width * height)

    fun binarize(luma: ByteArray, windowRadius: Int = defaultRadius(width, height), bias: Int = 6) {
        val w1 = width + 1
        java.util.Arrays.fill(integral, 0L)
        for (y in 0 until height) {
            var rowSum = 0L
            val src = y * width
            val dst = (y + 1) * w1
            for (x in 0 until width) {
                rowSum += (luma[src + x].toInt() and 0xFF).toLong()
                integral[dst + x + 1] = integral[dst - w1 + x + 1] + rowSum
            }
        }

        dark.clear()
        for (y in 0 until height) {
            val y0 = (y - windowRadius).coerceAtLeast(0)
            val y1 = (y + windowRadius).coerceAtMost(height - 1)
            for (x in 0 until width) {
                val x0 = (x - windowRadius).coerceAtLeast(0)
                val x1 = (x + windowRadius).coerceAtMost(width - 1)
                val area = (x1 - x0 + 1).toLong() * (y1 - y0 + 1).toLong()
                val sum = integral[(y1 + 1) * w1 + x1 + 1] -
                    integral[y0 * w1 + x1 + 1] -
                    integral[(y1 + 1) * w1 + x0] +
                    integral[y0 * w1 + x0]
                val mean = (sum / area).toInt()
                val value = luma[y * width + x].toInt() and 0xFF
                if (value < mean - bias) dark.set(y * width + x)
            }
        }
    }

    fun isDark(x: Int, y: Int): Boolean =
        x in 0 until width && y in 0 until height && dark.get(y * width + x)

    companion object {
        /** Roughly a sixteenth of the short edge: wide enough to span several cells. */
        fun defaultRadius(width: Int, height: Int): Int =
            (minOf(width, height) / 32).coerceIn(4, 40)
    }
}
