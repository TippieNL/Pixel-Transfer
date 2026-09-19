package nl.tippie.pixeltransfer.core.vision

/**
 * Reads one logical cell out of the captured image.
 *
 * Never a single pixel: at typical distances a cell covers only a handful of camera pixels, the
 * chroma planes are half resolution, and sensor noise on one pixel is large compared with the
 * spacing between palette levels. Sampling a grid inside the middle half of the cell and taking
 * the per-channel median throws away edge bleed from neighbouring cells and outliers alike.
 */
object CellSampler {

    const val DEFAULT_SUBSAMPLES = 3

    /**
     * How far from the cell centre the sub-samples spread, as a fraction of the cell width.
     *
     * This is a direct trade between two error sources. Spreading wide averages away sensor noise
     * and tolerates a slightly misaligned grid; spreading narrow keeps the samples away from the
     * neighbouring cells, whose colour blur drags across the boundary. Under the optical blur a
     * hand-held phone actually produces, neighbour bleed dominates by a wide margin, so the
     * samples sit well inside the middle half.
     */
    const val DEFAULT_INSET = 0.14

    private const val MAX_SUBSAMPLES = 8

    private class Scratch {
        val r = IntArray(MAX_SUBSAMPLES * MAX_SUBSAMPLES)
        val g = IntArray(MAX_SUBSAMPLES * MAX_SUBSAMPLES)
        val b = IntArray(MAX_SUBSAMPLES * MAX_SUBSAMPLES)
        val rgb = IntArray(3)
    }

    private val scratch = ThreadLocal.withInitial { Scratch() }

    fun sampleRgb(
        image: CameraImage,
        fit: GridFit,
        cellX: Int,
        cellY: Int,
        subsamples: Int = DEFAULT_SUBSAMPLES,
        out: IntArray,
        inset: Double = DEFAULT_INSET,
    ) {
        val s = scratch.get()
        val n = subsamples.coerceIn(1, MAX_SUBSAMPLES)
        val total = n * n
        var i = 0
        for (sy in 0 until n) {
            val fy = cellY + 0.5 + offset(sy, n, inset)
            for (sx in 0 until n) {
                val fx = cellX + 0.5 + offset(sx, n, inset)
                val x = fit.pixelX(fx, fy).toInt().coerceIn(0, image.width - 1)
                val y = fit.pixelY(fx, fy).toInt().coerceIn(0, image.height - 1)
                val rgb = image.rgbAt(x, y)
                s.r[i] = (rgb ushr 16) and 0xFF
                s.g[i] = (rgb ushr 8) and 0xFF
                s.b[i] = rgb and 0xFF
                i++
            }
        }
        out[0] = median(s.r, total)
        out[1] = median(s.g, total)
        out[2] = median(s.b, total)
    }

    fun sampleLuma(
        image: CameraImage,
        fit: GridFit,
        cellX: Int,
        cellY: Int,
        subsamples: Int = DEFAULT_SUBSAMPLES,
    ): Int {
        val out = scratch.get().rgb
        sampleRgb(image, fit, cellX, cellY, subsamples, out)
        return (out[0] * 77 + out[1] * 151 + out[2] * 28) shr 8
    }

    private fun offset(index: Int, n: Int, inset: Double): Double =
        if (n == 1) 0.0 else -inset + 2.0 * inset * index / (n - 1)

    private fun median(values: IntArray, count: Int): Int {
        // Insertion sort: count is at most 64 and usually 9.
        for (i in 1 until count) {
            val v = values[i]
            var j = i - 1
            while (j >= 0 && values[j] > v) {
                values[j + 1] = values[j]
                j--
            }
            values[j + 1] = v
        }
        return values[count / 2]
    }
}
