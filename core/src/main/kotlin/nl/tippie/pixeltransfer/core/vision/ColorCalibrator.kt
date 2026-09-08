package nl.tippie.pixeltransfer.core.vision

import nl.tippie.pixeltransfer.core.frame.CalibrationRamp
import nl.tippie.pixeltransfer.core.frame.CellRole
import nl.tippie.pixeltransfer.core.frame.FrameLayout

/**
 * Per-frame colour normalisation derived from the calibration patches.
 *
 * Three distortions have to be undone before a cell can be classified:
 *
 *  - the camera's colour matrix mixes the channels, so a pure red patch arrives with noticeable
 *    green in it. A 3x4 affine fit removes it.
 *  - display gamma and sensor response bend the level spacing. A per-channel piecewise-linear
 *    curve through the ramp anchors straightens it.
 *  - lens vignetting, uneven screen backlight and glare make the same colour read differently in
 *    different parts of the frame. The calibration bands run along the top and the left edge, so
 *    they span both axes and a per-channel black-level and gain *plane* can be fitted across the
 *    frame rather than a single global pair of numbers.
 *
 * All of it is re-fitted on every frame, because auto-white-balance, screen brightness and
 * ambient light drift throughout a transfer.
 */
class ColorCalibration(
    private val matrix: DoubleArray,
    private val curveX: Array<DoubleArray>,
    private val curveY: Array<DoubleArray>,
    /** Per channel: black level as a plane a + b*u + c*v over normalised cell coordinates. */
    private val blackPlane: Array<DoubleArray>,
    /** Per channel: gain as a plane over the same coordinates. */
    private val gainPlane: Array<DoubleArray>,
    val blackLuma: Double,
    val whiteLuma: Double,
    /** Mean absolute residual of the fit, in code units. Large values mean a poor capture. */
    val residual: Double,
) {

    val contrast: Double get() = whiteLuma - blackLuma

    /**
     * Maps a measured RGB triple, sampled at normalised frame position ([u], [v]), into display
     * code space.
     */
    fun correct(r: Int, g: Int, b: Int, u: Double, v: Double, out: IntArray) {
        for (ch in 0 until 3) {
            val base = ch * 4
            val linear = matrix[base] * r + matrix[base + 1] * g + matrix[base + 2] * b + matrix[base + 3]
            val curved = interpolate(curveX[ch], curveY[ch], linear)
            val black = plane(blackPlane[ch], u, v)
            val gain = plane(gainPlane[ch], u, v)
            out[ch] = ((curved - black) * gain).toInt().coerceIn(0, 255)
        }
    }

    /** True when a structural (dark/light) cell reads as light. */
    fun isLight(r: Int, g: Int, b: Int, u: Double, v: Double, out: IntArray): Boolean {
        correct(r, g, b, u, v, out)
        return (out[0] * 77 + out[1] * 151 + out[2] * 28) shr 8 > 127
    }

    private fun plane(p: DoubleArray, u: Double, v: Double): Double = p[0] + p[1] * u + p[2] * v

    private fun interpolate(xs: DoubleArray, ys: DoubleArray, x: Double): Double {
        if (xs.size < 2) return x
        if (x <= xs[0]) {
            val slope = (ys[1] - ys[0]) / (xs[1] - xs[0])
            return ys[0] + (x - xs[0]) * slope
        }
        val n = xs.size
        if (x >= xs[n - 1]) {
            val slope = (ys[n - 1] - ys[n - 2]) / (xs[n - 1] - xs[n - 2])
            return ys[n - 1] + (x - xs[n - 1]) * slope
        }
        var lo = 0
        var hi = n - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (xs[mid] <= x) lo = mid else hi = mid
        }
        val t = (x - xs[lo]) / (xs[hi] - xs[lo])
        return ys[lo] + t * (ys[hi] - ys[lo])
    }
}

object ColorCalibrator {

    /** One measured calibration cell. */
    private class Sample(val patch: Int, val u: Double, val v: Double, val rgb: IntArray)

    /**
     * Reads the calibration bands and fits the correction. Returns null when the patches are
     * unreadable - too dark, blown out, or the grid fit is wrong.
     */
    fun calibrate(image: CameraImage, fit: GridFit, layout: FrameLayout): ColorCalibration? {
        val patchCount = CalibrationRamp.PATCH_COUNT
        val samples = ArrayList<Sample>(layout.calibrationCellCount)
        val perPatch = Array(patchCount) { Array(3) { ArrayList<Int>(32) } }
        val span = (layout.grid - 1).toDouble()

        for (y in 0 until layout.grid) {
            for (x in 0 until layout.grid) {
                if (layout.roles[layout.index(x, y)] != CellRole.CALIBRATION) continue
                val patch = layout.calibrationPatchOf(x, y)
                val rgb = IntArray(3)
                CellSampler.sampleRgb(image, fit, x, y, CellSampler.DEFAULT_SUBSAMPLES, rgb)
                samples.add(Sample(patch, x / span, y / span, rgb))
                for (ch in 0 until 3) perPatch[patch][ch].add(rgb[ch])
            }
        }
        if (samples.isEmpty()) return null

        val measured = Array(patchCount) { DoubleArray(3) }
        for (p in 0 until patchCount) {
            for (ch in 0 until 3) {
                val list = perPatch[p][ch]
                if (list.isEmpty()) return null
                list.sort()
                measured[p][ch] = list[list.size / 2].toDouble()
            }
        }

        val matrix = fitMatrix(measured) ?: return null

        val corrected = Array(patchCount) { p ->
            DoubleArray(3) { ch -> applyMatrix(matrix, measured[p], ch) }
        }
        val curveX = Array(3) { DoubleArray(0) }
        val curveY = Array(3) { DoubleArray(0) }
        for (ch in 0 until 3) {
            val byCode = LinkedHashMap<Int, MutableList<Double>>()
            for (p in 0 until patchCount) {
                val channel = CalibrationRamp.channelOf(p)
                if (channel != ch && channel != 3) continue
                byCode.getOrPut(CalibrationRamp.codeOf(p)) { ArrayList() }.add(corrected[p][ch])
            }
            val codes = byCode.keys.sorted()
            val xs = DoubleArray(codes.size)
            val ys = DoubleArray(codes.size)
            for (i in codes.indices) {
                xs[i] = byCode[codes[i]]!!.average()
                ys[i] = codes[i].toDouble()
            }
            var monotone = xs.size >= 2
            for (i in 1 until xs.size) if (xs[i] <= xs[i - 1] + 1e-6) monotone = false
            if (monotone) {
                curveX[ch] = xs
                curveY[ch] = ys
            } else {
                // A non-monotone response means the fit is unusable; keep the linear map.
                curveX[ch] = doubleArrayOf(0.0, 255.0)
                curveY[ch] = doubleArrayOf(0.0, 255.0)
            }
        }

        // Spatial black level and gain, fitted per channel across the whole frame.
        val blackPlane = Array(3) { doubleArrayOf(0.0, 0.0, 0.0) }
        val gainPlane = Array(3) { doubleArrayOf(1.0, 0.0, 0.0) }
        val levels = CalibrationRamp.LEVELS.size
        for (ch in 0 until 3) {
            val darkSamples = samples.filter { CalibrationRamp.codeOf(it.patch) == 0 }
            val black = fitPlane(darkSamples) { s -> curved(matrix, curveX, curveY, s.rgb, ch) }
            blackPlane[ch] = black?.let { clampPlane(it, -60.0, 60.0) } ?: doubleArrayOf(0.0, 0.0, 0.0)

            // Every patch bright enough in this channel contributes, scaled to its own code, so
            // the gain plane is fitted over roughly a hundred cells rather than a handful.
            val brightSamples = samples.filter {
                val channel = CalibrationRamp.channelOf(it.patch)
                (channel == ch || channel == 3) && CalibrationRamp.codeOf(it.patch) >= 128
            }
            val gain = fitPlane(brightSamples) { s ->
                val value = curved(matrix, curveX, curveY, s.rgb, ch) -
                    (blackPlane[ch][0] + blackPlane[ch][1] * s.u + blackPlane[ch][2] * s.v)
                CalibrationRamp.codeOf(s.patch) / value.coerceAtLeast(12.0)
            }
            gainPlane[ch] = gain?.let { clampPlane(it, 0.5, 2.2) } ?: doubleArrayOf(1.0, 0.0, 0.0)
        }

        val blackLuma = luma(measured[3 * levels])
        val whiteLuma = luma(measured[3 * levels + levels - 1])

        // Keep the spatial correction only if it actually helps. The calibration bands cover two
        // edges, so on a frame with no real gradient the fitted planes can be pure noise; scoring
        // both against the measured patches decides it per frame instead of by assumption.
        val flat = ColorCalibration(
            matrix, curveX, curveY,
            Array(3) { doubleArrayOf(0.0, 0.0, 0.0) },
            Array(3) { doubleArrayOf(1.0, 0.0, 0.0) },
            blackLuma, whiteLuma, 0.0,
        )
        val spatial = ColorCalibration(
            matrix, curveX, curveY, blackPlane, gainPlane, blackLuma, whiteLuma, 0.0,
        )
        val flatResidual = residualOf(flat, samples)
        val spatialResidual = residualOf(spatial, samples)

        return if (spatialResidual < flatResidual) {
            ColorCalibration(
                matrix, curveX, curveY, blackPlane, gainPlane, blackLuma, whiteLuma, spatialResidual,
            )
        } else {
            ColorCalibration(
                matrix, curveX, curveY,
                Array(3) { doubleArrayOf(0.0, 0.0, 0.0) },
                Array(3) { doubleArrayOf(1.0, 0.0, 0.0) },
                blackLuma, whiteLuma, flatResidual,
            )
        }
    }

    /** Mean absolute error, in code units, between corrected and ideal calibration colours. */
    private fun residualOf(calibration: ColorCalibration, samples: List<Sample>): Double {
        val out = IntArray(3)
        var total = 0.0
        for (sample in samples) {
            calibration.correct(sample.rgb[0], sample.rgb[1], sample.rgb[2], sample.u, sample.v, out)
            val ideal = CalibrationRamp.colorOf(sample.patch)
            total += kotlin.math.abs(out[0] - ((ideal ushr 16) and 0xFF)).toDouble()
            total += kotlin.math.abs(out[1] - ((ideal ushr 8) and 0xFF)).toDouble()
            total += kotlin.math.abs(out[2] - (ideal and 0xFF)).toDouble()
        }
        return total / (samples.size * 3)
    }

    private fun applyMatrix(matrix: DoubleArray, rgb: DoubleArray, ch: Int): Double {
        val base = ch * 4
        return matrix[base] * rgb[0] + matrix[base + 1] * rgb[1] + matrix[base + 2] * rgb[2] +
            matrix[base + 3]
    }

    private fun curved(
        matrix: DoubleArray,
        curveX: Array<DoubleArray>,
        curveY: Array<DoubleArray>,
        rgb: IntArray,
        ch: Int,
    ): Double {
        val base = ch * 4
        val linear = matrix[base] * rgb[0] + matrix[base + 1] * rgb[1] + matrix[base + 2] * rgb[2] +
            matrix[base + 3]
        val xs = curveX[ch]
        val ys = curveY[ch]
        if (xs.size < 2) return linear
        if (linear <= xs[0]) {
            val slope = (ys[1] - ys[0]) / (xs[1] - xs[0])
            return ys[0] + (linear - xs[0]) * slope
        }
        val n = xs.size
        if (linear >= xs[n - 1]) {
            val slope = (ys[n - 1] - ys[n - 2]) / (xs[n - 1] - xs[n - 2])
            return ys[n - 1] + (linear - xs[n - 1]) * slope
        }
        var lo = 0
        var hi = n - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (xs[mid] <= linear) lo = mid else hi = mid
        }
        val t = (linear - xs[lo]) / (xs[hi] - xs[lo])
        return ys[lo] + t * (ys[hi] - ys[lo])
    }

    /**
     * Least-squares plane a + b*u + c*v through a set of measured cells.
     *
     * Each patch's own mean is removed before fitting. Patches differ slightly in how well the
     * global matrix reproduces them, and because a patch's cells occupy fixed positions along
     * the bands, leaving that bias in turns a per-patch offset into a spurious spatial gradient -
     * which is worse than not fitting a gradient at all.
     */
    private fun fitPlane(samples: List<Sample>, value: (Sample) -> Double): DoubleArray? {
        if (samples.size < 16) return null
        val values = DoubleArray(samples.size)
        val patchSum = HashMap<Int, Double>()
        val patchCount = HashMap<Int, Int>()
        var overall = 0.0
        var used = 0
        for (i in samples.indices) {
            val y = value(samples[i])
            values[i] = y
            if (!y.isFinite()) continue
            patchSum[samples[i].patch] = (patchSum[samples[i].patch] ?: 0.0) + y
            patchCount[samples[i].patch] = (patchCount[samples[i].patch] ?: 0) + 1
            overall += y
            used++
        }
        if (used < 16) return null
        overall /= used

        val ata = Array(3) { DoubleArray(3) }
        val atb = DoubleArray(3)
        for (i in samples.indices) {
            val y = values[i]
            if (!y.isFinite()) continue
            val patch = samples[i].patch
            val mean = (patchSum[patch] ?: 0.0) / (patchCount[patch] ?: 1)
            val centred = y - mean + overall
            val row = doubleArrayOf(1.0, samples[i].u, samples[i].v)
            for (a in 0 until 3) {
                for (b in 0 until 3) ata[a][b] += row[a] * row[b]
                atb[a] += row[a] * centred
            }
        }
        // Ridge only on the slope terms: shrink towards a flat field unless the data insists.
        var trace = 0.0
        for (i in 0 until 3) trace += ata[i][i]
        val ridge = (trace / 3.0) * SLOPE_RIDGE + 1e-9
        ata[1][1] += ridge
        ata[2][2] += ridge
        ata[0][0] += 1e-9
        val augmented = Array(3) { i -> DoubleArray(4) { j -> if (j < 3) ata[i][j] else atb[i] } }
        return Homography.solve(augmented, 3)
    }

    /**
     * How strongly the spatial slopes are shrunk towards flat. The calibration bands only run
     * along two edges, so an unconstrained plane extrapolates badly into the opposite corner.
     */
    private const val SLOPE_RIDGE = 0.05

    /** Rejects a plane whose value leaves [lo]..[hi] anywhere in the unit square. */
    private fun clampPlane(p: DoubleArray, lo: Double, hi: Double): DoubleArray? {
        for (u in intArrayOf(0, 1)) {
            for (v in intArrayOf(0, 1)) {
                val value = p[0] + p[1] * u + p[2] * v
                if (!value.isFinite() || value < lo || value > hi) return null
            }
        }
        return p
    }

    private fun luma(rgb: DoubleArray): Double =
        (rgb[0] * 77 + rgb[1] * 151 + rgb[2] * 28) / 256.0

    /** Ridge-regularised least squares for the 3x4 cross-talk correction. */
    private fun fitMatrix(measured: Array<DoubleArray>): DoubleArray? {
        val n = measured.size
        val ata = Array(4) { DoubleArray(4) }
        val atb = Array(3) { DoubleArray(4) }
        for (p in 0 until n) {
            val row = doubleArrayOf(measured[p][0], measured[p][1], measured[p][2], 1.0)
            val ideal = CalibrationRamp.colorOf(p)
            val target = doubleArrayOf(
                ((ideal ushr 16) and 0xFF).toDouble(),
                ((ideal ushr 8) and 0xFF).toDouble(),
                (ideal and 0xFF).toDouble(),
            )
            for (i in 0 until 4) {
                for (j in 0 until 4) ata[i][j] += row[i] * row[j]
                for (ch in 0 until 3) atb[ch][i] += row[i] * target[ch]
            }
        }
        var trace = 0.0
        for (i in 0 until 4) trace += ata[i][i]
        val ridge = (trace / 4.0) * 1e-6 + 1e-9
        for (i in 0 until 4) ata[i][i] += ridge

        val out = DoubleArray(12)
        for (ch in 0 until 3) {
            val augmented = Array(4) { i -> DoubleArray(5) { j -> if (j < 4) ata[i][j] else atb[ch][i] } }
            val solution = Homography.solve(augmented, 4) ?: return null
            solution.copyInto(out, ch * 4)
        }
        return out
    }
}
