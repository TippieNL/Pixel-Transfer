package nl.tippie.pixeltransfer.core.vision

/**
 * A plane-to-plane projective map. The screen is flat, so a homography is the exact model for
 * "phone held at an angle"; anything left over is lens distortion, which the timing strips
 * correct separately.
 */
class Homography private constructor(private val h: DoubleArray) {

    /** Maps a source point into image space. */
    fun map(u: Double, v: Double, out: DoubleArray) {
        val denom = h[6] * u + h[7] * v + 1.0
        out[0] = (h[0] * u + h[1] * v + h[2]) / denom
        out[1] = (h[3] * u + h[4] * v + h[5]) / denom
    }

    fun mapX(u: Double, v: Double): Double {
        val denom = h[6] * u + h[7] * v + 1.0
        return (h[0] * u + h[1] * v + h[2]) / denom
    }

    fun mapY(u: Double, v: Double): Double {
        val denom = h[6] * u + h[7] * v + 1.0
        return (h[3] * u + h[4] * v + h[5]) / denom
    }

    companion object {

        /**
         * Solves for the homography taking the unit square corners (0,0), (1,0), (1,1), (0,1)
         * to [dst], given in that order. Returns null when the four points are degenerate.
         */
        fun fromUnitSquare(dst: Array<DoubleArray>): Homography? {
            require(dst.size == 4)
            val src = arrayOf(
                doubleArrayOf(0.0, 0.0),
                doubleArrayOf(1.0, 0.0),
                doubleArrayOf(1.0, 1.0),
                doubleArrayOf(0.0, 1.0),
            )
            return from(src, dst)
        }

        fun from(src: Array<DoubleArray>, dst: Array<DoubleArray>): Homography? {
            val a = Array(8) { DoubleArray(9) }
            for (i in 0 until 4) {
                val (u, v) = src[i]
                val x = dst[i][0]
                val y = dst[i][1]
                a[i * 2][0] = u; a[i * 2][1] = v; a[i * 2][2] = 1.0
                a[i * 2][6] = -u * x; a[i * 2][7] = -v * x; a[i * 2][8] = x
                a[i * 2 + 1][3] = u; a[i * 2 + 1][4] = v; a[i * 2 + 1][5] = 1.0
                a[i * 2 + 1][6] = -u * y; a[i * 2 + 1][7] = -v * y; a[i * 2 + 1][8] = y
            }
            val h = solve(a, 8) ?: return null
            return Homography(h)
        }

        /** Gaussian elimination with partial pivoting on an n x (n+1) augmented matrix. */
        internal fun solve(a: Array<DoubleArray>, n: Int): DoubleArray? {
            for (col in 0 until n) {
                var pivot = col
                for (row in col + 1 until n) {
                    if (kotlin.math.abs(a[row][col]) > kotlin.math.abs(a[pivot][col])) pivot = row
                }
                if (kotlin.math.abs(a[pivot][col]) < 1e-12) return null
                val tmp = a[col]; a[col] = a[pivot]; a[pivot] = tmp
                val diag = a[col][col]
                for (k in col..n) a[col][k] /= diag
                for (row in 0 until n) {
                    if (row == col) continue
                    val factor = a[row][col]
                    if (factor == 0.0) continue
                    for (k in col..n) a[row][k] -= factor * a[col][k]
                }
            }
            return DoubleArray(n) { a[it][n] }
        }
    }
}

/**
 * A quadratic remap of one normalised axis, fitted to the observed timing-strip transitions.
 *
 * The homography already handles perspective exactly; what is left is the camera's radial lens
 * distortion, which along a single axis is dominated by a quadratic term. Fitting it here is
 * what keeps cell sampling on-centre near the edges of a wide-angle phone lens.
 */
class AxisCorrection private constructor(
    private val a: Double,
    private val b: Double,
    private val c: Double,
) {
    fun apply(t: Double): Double = a * t * t + b * t + c

    companion object {
        val IDENTITY = AxisCorrection(0.0, 1.0, 0.0)

        /** Least-squares quadratic through (ideal, observed) pairs. */
        fun fit(ideal: DoubleArray, observed: DoubleArray, maxDeviation: Double = 0.05): AxisCorrection {
            val n = ideal.size
            if (n < 6 || n != observed.size) return IDENTITY
            var s0 = 0.0; var s1 = 0.0; var s2 = 0.0; var s3 = 0.0; var s4 = 0.0
            var t0 = 0.0; var t1 = 0.0; var t2 = 0.0
            for (i in 0 until n) {
                val x = ideal[i]
                val y = observed[i]
                val x2 = x * x
                s0 += 1.0; s1 += x; s2 += x2; s3 += x2 * x; s4 += x2 * x2
                t0 += y; t1 += x * y; t2 += x2 * y
            }
            val m = arrayOf(
                doubleArrayOf(s4, s3, s2, t2),
                doubleArrayOf(s3, s2, s1, t1),
                doubleArrayOf(s2, s1, s0, t0),
            )
            val solution = Homography.solve(m, 3) ?: return IDENTITY
            val fit = AxisCorrection(solution[0], solution[1], solution[2])
            // Reject wild fits: a bad transition match is worse than no correction at all.
            for (i in 0 until n) {
                if (kotlin.math.abs(fit.apply(ideal[i]) - observed[i]) > maxDeviation) return IDENTITY
            }
            if (kotlin.math.abs(fit.apply(0.0)) > maxDeviation) return IDENTITY
            if (kotlin.math.abs(fit.apply(1.0) - 1.0) > maxDeviation) return IDENTITY
            return fit
        }
    }
}
