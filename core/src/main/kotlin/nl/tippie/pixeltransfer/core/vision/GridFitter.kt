package nl.tippie.pixeltransfer.core.vision

import nl.tippie.pixeltransfer.core.frame.FrameLayout
import kotlin.math.abs

/**
 * A rectified frame: which homography maps cell coordinates to pixels, how many cells the grid
 * has, and the per-axis lens correction.
 */
class GridFit(
    val homography: Homography,
    val grid: Int,
    val uCorrection: AxisCorrection,
    val vCorrection: AxisCorrection,
    val timingScore: Double,
    val cornerScore: Double,
    val corners: Array<DoubleArray>,
) {
    val score: Double get() = timingScore + cornerScore

    /** Normalised coordinate of a cell centre along one axis. */
    fun norm(cell: Int): Double = (cell - 3.0) / (grid - 7.0)

    fun pixelX(cellX: Double, cellY: Double): Double =
        homography.mapX(uCorrection.apply(normOf(cellX)), vCorrection.apply(normOf(cellY)))

    fun pixelY(cellX: Double, cellY: Double): Double =
        homography.mapY(uCorrection.apply(normOf(cellX)), vCorrection.apply(normOf(cellY)))

    /**
     * Cell coordinates run so that cell index c spans [c, c+1]; the top-left finder centre sits
     * at 3.5, which is the origin of the normalised space the homography was fitted in.
     */
    private fun normOf(cell: Double): Double = (cell - 3.5) / (grid - 7.0)

    /** Size of one cell in image pixels, measured at the centre of the frame. */
    fun cellPixelSize(): Double {
        val cx = grid / 2.0
        val cy = grid / 2.0
        val x0 = pixelX(cx, cy)
        val y0 = pixelY(cx, cy)
        val dx = kotlin.math.hypot(pixelX(cx + 1.0, cy) - x0, pixelY(cx + 1.0, cy) - y0)
        val dy = kotlin.math.hypot(pixelX(cx, cy + 1.0) - x0, pixelY(cx, cy + 1.0) - y0)
        return (dx + dy) / 2.0
    }
}

/**
 * Resolves grid size, rotation and flip in one pass.
 *
 * The four finder markers are identical, so a detected quad admits eight readings (four
 * rotations times a mirror). Rather than guessing, every reading is scored against the two
 * timing strips - which only line up on the true reading - and against the corner-id patches,
 * whose light-cell counts of 1, 3, 5 and 7 are what finally distinguishes a mirrored capture
 * from an upright one.
 */
object GridFitter {

    /** Grid sizes the receiver will consider. Must be even and at least [FrameLayout.MIN_GRID]. */
    val CANDIDATE_GRIDS: IntArray = (FrameLayout.MIN_GRID..192 step 2).toList().toIntArray()

    fun fit(
        image: CameraImage,
        quad: List<FinderPoint>,
        candidateGrids: IntArray = CANDIDATE_GRIDS,
        minScore: Double = 0.45,
    ): GridFit? {
        if (quad.size != 4) return null
        val points = quad.map { doubleArrayOf(it.x, it.y) }
        val grids = narrowGrids(quad, candidateGrids)

        var best: GridFit? = null
        for (rotation in 0 until 4) {
            for (flip in 0 until 2) {
                val corners = Array(4) { i ->
                    val index = if (flip == 0) (rotation + i) % 4 else (rotation + 4 - i) % 4
                    points[index]
                }
                val homography = Homography.fromUnitSquare(corners) ?: continue
                for (grid in grids) {
                    val timing = timingScore(image, homography, grid)
                    if (timing < minScore) continue
                    val corner = cornerScore(image, homography, grid)
                    if (corner < 0.0) continue
                    val candidate = GridFit(
                        homography, grid, AxisCorrection.IDENTITY, AxisCorrection.IDENTITY,
                        timing, corner, corners,
                    )
                    if (best == null || candidate.score > best!!.score) best = candidate
                }
            }
        }

        val chosen = best ?: return null
        return refine(image, chosen)
    }

    /**
     * Narrows the grid search using the module size the finder detector already measured.
     *
     * The finder centres are exactly `grid - 7` cells apart, so the quad's side length divided by
     * the module size is a direct estimate of the grid size. Searching a window around it instead
     * of the whole range is both faster and less likely to lock onto a harmonic of the true grid.
     */
    private fun narrowGrids(quad: List<FinderPoint>, candidates: IntArray): IntArray {
        var perimeter = 0.0
        for (i in 0 until 4) {
            val a = quad[i]
            val b = quad[(i + 1) % 4]
            perimeter += kotlin.math.hypot(b.x - a.x, b.y - a.y)
        }
        val side = perimeter / 4.0
        val module = quad.sumOf { it.moduleSize } / quad.size
        if (module <= 0.5 || side <= 0.0) return candidates
        val estimate = 7.0 + side / module
        if (!estimate.isFinite() || estimate < 8.0 || estimate > 400.0) return candidates
        val window = (estimate * 0.15).coerceAtLeast(8.0)
        val narrowed = candidates.filter { kotlin.math.abs(it - estimate) <= window }
        return if (narrowed.size < 3) candidates else narrowed.toIntArray()
    }

    /**
     * Correlates the two timing strips against the alternating pattern the grid size predicts.
     * A wrong grid size, or a rotation that puts payload cells where a timing strip should be,
     * scores near zero.
     */
    private fun timingScore(image: CameraImage, h: Homography, grid: Int): Double {
        val span = grid - 7.0
        val first = FrameLayout.bandStartFor(grid)
        val last = FrameLayout.bandEndFor(grid)
        val perStrip = last - first + 1
        if (perStrip < 8) return 0.0
        val values = DoubleArray(2 * perStrip)
        val expectDark = BooleanArray(values.size)
        var i = 0
        var mean = 0.0
        for (c in first..last) {
            val u = (c - 3.0) / span
            values[i] = image.lumaBilinear(h.mapX(u, 0.0), h.mapY(u, 0.0)).toDouble()
            expectDark[i] = FrameLayout.timingDarkAt(c)
            mean += values[i]
            i++
        }
        for (r in first..last) {
            val v = (r - 3.0) / span
            values[i] = image.lumaBilinear(h.mapX(0.0, v), h.mapY(0.0, v)).toDouble()
            expectDark[i] = FrameLayout.timingDarkAt(r)
            mean += values[i]
            i++
        }
        mean /= i
        var deviation = 0.0
        for (j in 0 until i) deviation += abs(values[j] - mean)
        deviation /= i
        if (deviation < 4.0) return 0.0
        var sum = 0.0
        for (j in 0 until i) sum += if (expectDark[j]) mean - values[j] else values[j] - mean
        return (sum / i) / deviation
    }

    /** Scores the four corner-id patches by their light-cell counts (1, 3, 5, 7). */
    private fun cornerScore(image: CameraImage, h: Homography, grid: Int): Double {
        val span = grid - 7.0
        val size = FrameLayout.CORNER_ID_SIZE
        val origins = FrameLayout.cornerIdOrigins(grid)
        val total = 4 * size * size
        val samples = DoubleArray(total)
        val owner = IntArray(total)
        var i = 0
        for (corner in 0 until 4) {
            val origin = origins[corner]
            for (dy in 0 until size) {
                for (dx in 0 until size) {
                    val u = (origin[0] + dx - 3.0) / span
                    val v = (origin[1] + dy - 3.0) / span
                    samples[i] = image.lumaBilinear(h.mapX(u, v), h.mapY(u, v)).toDouble()
                    owner[i] = corner
                    i++
                }
            }
        }
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (s in samples) {
            if (s < lo) lo = s
            if (s > hi) hi = s
        }
        if (hi - lo < 20.0) return -1.0
        val threshold = (hi + lo) / 2.0
        val counts = IntArray(4)
        for (j in samples.indices) if (samples[j] > threshold) counts[owner[j]]++
        var error = 0
        for (corner in 0 until 4) error += abs(counts[corner] - FrameLayout.cornerIdLightCountFor(corner))
        // A perfect reading scores 1.0; a mirrored or rotated one lands well below zero.
        return 1.0 - error / 6.0
    }

    /** Fits the per-axis lens correction from the observed timing transitions. */
    private fun refine(image: CameraImage, fit: GridFit): GridFit {
        val u = fitAxis(image, fit, horizontal = true)
        val v = fitAxis(image, fit, horizontal = false)
        return GridFit(fit.homography, fit.grid, u, v, fit.timingScore, fit.cornerScore, fit.corners)
    }

    private fun fitAxis(image: CameraImage, fit: GridFit, horizontal: Boolean): AxisCorrection {
        val grid = fit.grid
        val span = grid - 7.0
        val h = fit.homography
        val oversample = 8
        val first = FrameLayout.bandStartFor(grid)
        val last = FrameLayout.bandEndFor(grid)
        val n = (last - first) * oversample + 1
        if (n < 16) return AxisCorrection.IDENTITY
        val values = DoubleArray(n)
        val positions = DoubleArray(n)
        for (i in 0 until n) {
            val cell = first + i.toDouble() / oversample
            val t = (cell - 3.0) / span
            positions[i] = t
            values[i] = if (horizontal) {
                image.lumaBilinear(h.mapX(t, 0.0), h.mapY(t, 0.0)).toDouble()
            } else {
                image.lumaBilinear(h.mapX(0.0, t), h.mapY(0.0, t)).toDouble()
            }
        }
        var mean = 0.0
        for (value in values) mean += value
        mean /= n

        // Observed transitions: sign changes of (value - mean), located by linear interpolation.
        val observed = ArrayList<Double>()
        for (i in 1 until n) {
            val a = values[i - 1] - mean
            val b = values[i] - mean
            if (a == 0.0 || (a < 0) == (b < 0)) continue
            val frac = a / (a - b)
            observed.add(positions[i - 1] + frac * (positions[i] - positions[i - 1]))
        }

        // Ideal transitions sit on the boundary between consecutive timing cells.
        val ideal = ArrayList<Double>()
        for (c in first until last) ideal.add((c + 1 - 3.5) / span)

        if (observed.size != ideal.size) return AxisCorrection.IDENTITY
        return AxisCorrection.fit(ideal.toDoubleArray(), observed.toDoubleArray())
    }
}
