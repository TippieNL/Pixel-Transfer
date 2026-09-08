package nl.tippie.pixeltransfer.core.frame

/** What a given cell of the grid is used for. */
enum class CellRole {
    FINDER,
    CORNER_ID,
    TIMING,
    CALIBRATION,
    NONCE_STRIPE,
    DATA,
}

/**
 * Geometry of a PixelTransfer frame. This is a custom layout - it deliberately shares nothing
 * with any QR standard beyond the idea of a concentric finder, because the payload here is a
 * colour field rather than a bitmap.
 *
 * ```
 *   +-------------------------------------------------+
 *   | F F F F F F F  t t t t t t t t t t t t  F F F F |  rows 0..2  finder + horizontal timing
 *   | F F F F F F F  (row 3 = timing, on the finder-centre line)   |
 *   | F F F F F F F  A A A A A A A A A A A A  F F F F |  rows 4..6  calibration band A
 *   | t . B B B . I I I . . . . . . . . I I I . N N . |  rows 7..   corner ids, band B, nonce
 *   | t . B B B . . . . . data . . . . . . . . N N . |
 *   | F F F F F F F  . . . . . . . . . . . .  F F F F |  bottom finders
 *   +-------------------------------------------------+
 * ```
 *
 * All four finders are identical so a single detector finds them; the 3x3 corner-id patches
 * next to each one carry a different number of light cells (1, 3, 5, 7) so rotation *and* flip
 * are resolved unambiguously once the frame has been rectified.
 */
class FrameLayout(val grid: Int) {

    init {
        require(grid >= MIN_GRID) { "grid must be at least $MIN_GRID cells, was $grid" }
        require(grid % 2 == 0) { "grid must be even so the timing strips alternate cleanly" }
    }

    /** Inclusive band coordinates, expressed once so renderer and decoder cannot drift apart. */
    val finderSize = FINDER_SIZE
    val finderBlock = FINDER_BLOCK
    val timingRow = 3
    val timingCol = 3
    val bandStart = FINDER_BLOCK
    val bandEnd = grid - FINDER_BLOCK - 1
    val calibRowsA = 4..6
    val calibColsB = 4..6
    val cornerIdSize = CORNER_ID_SIZE
    val nonceCols = (grid - 4 - NONCE_STRIPE_WIDTH + 1)..(grid - 4)

    val roles: Array<CellRole> = Array(grid * grid) { CellRole.DATA }

    /** Cells carrying header and payload bits, already interleaved (see [interleave]). */
    val dataCells: IntArray

    init {
        // Finders, each inside an 8x8 block whose inward row and column are a light separator.
        // Without it the first timing cell would merge with the marker's dark outer ring and
        // destroy the 1:1:3:1:1 run-length signature the detector looks for.
        markRect(0, 0, FINDER_BLOCK, FINDER_BLOCK, CellRole.FINDER)
        markRect(grid - FINDER_BLOCK, 0, FINDER_BLOCK, FINDER_BLOCK, CellRole.FINDER)
        markRect(0, grid - FINDER_BLOCK, FINDER_BLOCK, FINDER_BLOCK, CellRole.FINDER)
        markRect(grid - FINDER_BLOCK, grid - FINDER_BLOCK, FINDER_BLOCK, FINDER_BLOCK, CellRole.FINDER)

        // Timing strips, on the lines through the finder centres.
        for (x in bandStart..bandEnd) roles[index(x, timingRow)] = CellRole.TIMING
        for (y in bandStart..bandEnd) roles[index(timingCol, y)] = CellRole.TIMING

        // Calibration bands: three rows under the top edge, three columns along the left edge.
        for (y in calibRowsA) for (x in bandStart..bandEnd) roles[index(x, y)] = CellRole.CALIBRATION
        for (x in calibColsB) for (y in bandStart..bandEnd) roles[index(x, y)] = CellRole.CALIBRATION

        // Corner id patches, tucked diagonally inside each finder.
        for ((cx, cy) in cornerIdOrigins()) {
            markRect(cx, cy, cornerIdSize, cornerIdSize, CellRole.CORNER_ID)
        }

        // Tear-detection stripe down the right-hand side.
        for (x in nonceCols) for (y in bandStart..bandEnd) roles[index(x, y)] = CellRole.NONCE_STRIPE

        val free = ArrayList<Int>(grid * grid)
        for (i in roles.indices) if (roles[i] == CellRole.DATA) free.add(i)
        dataCells = interleave(free.toIntArray())
    }

    fun index(x: Int, y: Int): Int = y * grid + x
    fun xOf(index: Int): Int = index % grid
    fun yOf(index: Int): Int = index / grid

    /** Origins of the four 3x3 corner-id patches, in TL, TR, BR, BL order. */
    fun cornerIdOrigins(): Array<IntArray> = cornerIdOrigins(grid)

    /** Number of calibration cells available; each patch index repeats across the bands. */
    val calibrationCellCount: Int = (calibRowsA.count() + calibColsB.count()) * (bandEnd - bandStart + 1)

    /** Which calibration patch a calibration cell belongs to. */
    fun calibrationPatchOf(x: Int, y: Int): Int {
        val along = if (y in calibRowsA) x - bandStart else y - bandStart
        return Math.floorMod(along, CalibrationRamp.PATCH_COUNT)
    }

    /** True when the timing cell at ([x], [y]) should be drawn dark. */
    fun timingIsDark(x: Int, y: Int): Boolean =
        if (y == timingRow) (x % 2 == 0) else (y % 2 == 0)

    /**
     * True when the finder-block cell at ([x], [y]) is dark. Cells in the separator row/column
     * of the block are light, which is what isolates the marker from the payload field.
     */
    fun finderIsDark(x: Int, y: Int): Boolean {
        val leftBlock = x < FINDER_BLOCK
        val topBlock = y < FINDER_BLOCK
        val lx = if (leftBlock) x else x - (grid - FINDER_BLOCK)
        val ly = if (topBlock) y else y - (grid - FINDER_BLOCK)
        // The marker fills the outward 7x7 of the block; the inward edge is the separator.
        val mx = if (leftBlock) lx else lx - 1
        val my = if (topBlock) ly else ly - 1
        if (mx !in 0 until FINDER_SIZE || my !in 0 until FINDER_SIZE) return false
        val ring = maxOf(kotlin.math.abs(mx - 3), kotlin.math.abs(my - 3))
        // ring 3 = dark outer, ring 2 = light, rings 0..1 = dark 3x3 core.
        return ring == 3 || ring <= 1
    }

    /** Number of light cells in the corner-id patch for corner [corner] (0=TL, 1=TR, 2=BR, 3=BL). */
    fun cornerIdLightCount(corner: Int): Int = CORNER_ID_LIGHTS[corner]

    /** True when the corner-id cell at local offset ([lx], [ly]) is light. */
    fun cornerIdIsLight(corner: Int, lx: Int, ly: Int): Boolean =
        CORNER_ID_PATTERNS[corner][ly * cornerIdSize + lx]

    /** Which corner (0..3) a corner-id cell belongs to, or -1. */
    fun cornerOfIdCell(x: Int, y: Int): Int {
        cornerIdOrigins().forEachIndexed { i, (ox, oy) ->
            if (x >= ox && x < ox + cornerIdSize && y >= oy && y < oy + cornerIdSize) return i
        }
        return -1
    }

    /**
     * Spreads consecutive payload bytes across the whole frame.
     *
     * Damage from glare, a finger, or a torn capture is spatially contiguous. Written in raster
     * order that damage would land inside one or two Reed-Solomon blocks and destroy them;
     * interleaved, it is distributed over every block as a handful of correctable byte errors.
     */
    private fun interleave(cells: IntArray): IntArray {
        val n = cells.size
        if (n < 3) return cells
        var stride = (n * 0.6180339887).toInt().coerceAtLeast(2)
        while (gcd(stride, n) != 1) stride++
        val out = IntArray(n)
        var pos = 0
        for (i in 0 until n) {
            out[i] = cells[pos]
            pos += stride
            if (pos >= n) pos -= n
        }
        return out
    }

    private fun gcd(a: Int, b: Int): Int {
        var x = a
        var y = b
        while (y != 0) {
            val t = x % y
            x = y
            y = t
        }
        return x
    }

    private fun markRect(x0: Int, y0: Int, w: Int, h: Int, role: CellRole) {
        for (y in y0 until y0 + h) for (x in x0 until x0 + w) roles[index(x, y)] = role
    }

    companion object {
        const val FINDER_SIZE = 7

        /** Marker plus its one-cell light separator. */
        const val FINDER_BLOCK = 8

        /**
         * Width of the tear-detection stripe, in cells. Four columns per row give enough
         * independent bits that a row carrying the wrong frame's nonce is flagged reliably
         * rather than only about a quarter of the time.
         */
        const val NONCE_STRIPE_WIDTH = 4

        const val MIN_GRID = 48

        /** 1, 3, 5 and 7 light cells respectively - identifiable by counting alone. */
        private val CORNER_ID_PATTERNS: Array<BooleanArray> = arrayOf(
            booleanArrayOf(false, false, false, false, true, false, false, false, false),
            booleanArrayOf(false, false, false, true, true, true, false, false, false),
            booleanArrayOf(false, true, false, true, true, true, false, true, false),
            booleanArrayOf(true, false, true, true, true, true, true, false, true),
        )

        private val CORNER_ID_LIGHTS = CORNER_ID_PATTERNS.map { pattern -> pattern.count { it } }.toIntArray()

        const val CORNER_ID_SIZE = 3

        /**
         * Geometry helpers that do not need a full layout instance. The grid fitter evaluates
         * dozens of candidate grid sizes per frame, and materialising a layout for each would
         * dominate the receiver's frame budget.
         */
        fun bandStartFor(grid: Int): Int = FINDER_BLOCK

        fun bandEndFor(grid: Int): Int = grid - FINDER_BLOCK - 1

        /** True when the timing cell at position [index] along a strip is dark. */
        fun timingDarkAt(index: Int): Boolean = index % 2 == 0

        fun cornerIdOrigins(grid: Int): Array<IntArray> {
            val near = FINDER_BLOCK
            val far = grid - FINDER_BLOCK - CORNER_ID_SIZE
            return arrayOf(
                intArrayOf(near, near),
                intArrayOf(far, near),
                intArrayOf(far, far),
                intArrayOf(near, far),
            )
        }

        fun cornerIdLightCountFor(corner: Int): Int = CORNER_ID_LIGHTS[corner]

        /** Smallest grid that can hold a full calibration ramp on each band. */
        fun minimumGridFor(): Int = MIN_GRID
    }
}
