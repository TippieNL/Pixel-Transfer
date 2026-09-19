package nl.tippie.pixeltransfer.core.vision

import nl.tippie.pixeltransfer.core.frame.FrameLayout

/**
 * Every cell of a rectified frame, sampled once and colour-corrected.
 *
 * Sampling the whole grid up front rather than cell-by-cell is what makes it possible to treat
 * the frame as a signal: the neighbours of a cell are known, so the optical blur that smears them
 * together can be measured and partly undone before anything is classified.
 */
class CellField(val grid: Int) {
    val red = IntArray(grid * grid)
    val green = IntArray(grid * grid)
    val blue = IntArray(grid * grid)

    /** Uncorrected luma, kept so exposure and glare can be judged on what the sensor saw. */
    val rawLuma = IntArray(grid * grid)

    fun luma(index: Int): Int =
        (red[index] * 77 + green[index] * 151 + blue[index] * 28) shr 8
}

/**
 * Samples a rectified frame into a [CellField] and sharpens it.
 *
 * The sharpening is not a cosmetic filter. The two timing strips are a known alternating pattern
 * at exactly one cell per period, so the contrast they come back with *is* the optical system's
 * response at the cell frequency - a direct, per-frame, per-axis measurement of how much each
 * cell has been contaminated by its neighbours. Inverting that measured blur recovers palette
 * levels that would otherwise land on the wrong side of a decision threshold.
 */
object CellFieldSampler {

    /**
     * Below this measured modulation the frame is too blurred for inversion to do anything but
     * amplify noise, so it is left alone and the decode is allowed to fail honestly.
     */
    private const val MIN_MODULATION = 0.35

    /** Above this there is essentially no blur to remove. */
    private const val MAX_USEFUL_MODULATION = 0.95

    private const val ITERATIONS = 2

    fun sample(
        image: CameraImage,
        fit: GridFit,
        layout: FrameLayout,
        calibration: ColorCalibration,
    ): CellField {
        val field = CellField(layout.grid)
        val rgb = IntArray(3)
        val corrected = IntArray(3)
        val span = (layout.grid - 1).toDouble()
        for (y in 0 until layout.grid) {
            for (x in 0 until layout.grid) {
                val index = layout.index(x, y)
                CellSampler.sampleRgb(image, fit, x, y, CellSampler.DEFAULT_SUBSAMPLES, rgb)
                field.rawLuma[index] = (rgb[0] * 77 + rgb[1] * 151 + rgb[2] * 28) shr 8
                calibration.correct(rgb[0], rgb[1], rgb[2], x / span, y / span, corrected)
                field.red[index] = corrected[0]
                field.green[index] = corrected[1]
                field.blue[index] = corrected[2]
            }
        }
        return field
    }

    /**
     * Measures how much contrast the alternating timing strip retains, 0..1.
     *
     * 1.0 means a cell is unaffected by its neighbours; 0.0 means adjacent cells have blurred
     * into a flat average and nothing is recoverable.
     */
    fun modulation(field: CellField, layout: FrameLayout, horizontal: Boolean): Double {
        var lightSum = 0L
        var darkSum = 0L
        var lightCount = 0
        var darkCount = 0
        for (i in layout.bandStart..layout.bandEnd) {
            val index = if (horizontal) {
                layout.index(i, layout.timingRow)
            } else {
                layout.index(layout.timingCol, i)
            }
            val luma = field.luma(index)
            if (FrameLayout.timingDarkAt(i)) {
                darkSum += luma
                darkCount++
            } else {
                lightSum += luma
                lightCount++
            }
        }
        if (lightCount == 0 || darkCount == 0) return 1.0
        val amplitude = (lightSum.toDouble() / lightCount) - (darkSum.toDouble() / darkCount)
        return (amplitude / 255.0).coerceIn(0.0, 1.0)
    }

    /**
     * Inverts the measured blur with a few Van Cittert iterations.
     *
     * A symmetric three-tap kernel per axis reproduces an alternating pattern at `2c - 1`, so the
     * modulation the timing strip reports fixes the kernel exactly; no guessing and no tunable
     * sharpening amount.
     */
    fun sharpen(field: CellField, modulationX: Double, modulationY: Double) {
        if (modulationX >= MAX_USEFUL_MODULATION && modulationY >= MAX_USEFUL_MODULATION) return
        val mx = modulationX.coerceIn(MIN_MODULATION, 1.0)
        val my = modulationY.coerceIn(MIN_MODULATION, 1.0)
        val centreX = (1.0 + mx) / 2.0
        val centreY = (1.0 + my) / 2.0
        val sideX = (1.0 - centreX) / 2.0
        val sideY = (1.0 - centreY) / 2.0
        if (sideX < 1e-4 && sideY < 1e-4) return

        for (channel in listOf(field.red, field.green, field.blue)) {
            deconvolve(channel, field.grid, centreX, sideX, centreY, sideY)
        }
    }

    private fun deconvolve(
        values: IntArray,
        grid: Int,
        centreX: Double,
        sideX: Double,
        centreY: Double,
        sideY: Double,
    ) {
        val observed = values.copyOf()
        val estimate = DoubleArray(values.size) { values[it].toDouble() }
        val blurred = DoubleArray(values.size)
        val scratch = DoubleArray(values.size)

        repeat(ITERATIONS) {
            // Horizontal pass into scratch, vertical pass into blurred.
            for (y in 0 until grid) {
                val row = y * grid
                for (x in 0 until grid) {
                    val left = estimate[row + if (x > 0) x - 1 else 0]
                    val right = estimate[row + if (x < grid - 1) x + 1 else grid - 1]
                    scratch[row + x] = centreX * estimate[row + x] + sideX * (left + right)
                }
            }
            for (y in 0 until grid) {
                val row = y * grid
                val up = (if (y > 0) y - 1 else 0) * grid
                val down = (if (y < grid - 1) y + 1 else grid - 1) * grid
                for (x in 0 until grid) {
                    blurred[row + x] =
                        centreY * scratch[row + x] + sideY * (scratch[up + x] + scratch[down + x])
                }
            }
            for (i in estimate.indices) {
                estimate[i] = (estimate[i] + observed[i] - blurred[i]).coerceIn(0.0, 255.0)
            }
        }
        for (i in values.indices) values[i] = estimate[i].toInt()
    }
}
