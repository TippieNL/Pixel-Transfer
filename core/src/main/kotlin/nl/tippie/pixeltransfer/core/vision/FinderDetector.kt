package nl.tippie.pixeltransfer.core.vision

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** A located finder marker: sub-pixel centre plus the module size it implies. */
data class FinderPoint(val x: Double, val y: Double, val moduleSize: Double, val score: Int)

/**
 * Finds the four concentric corner markers.
 *
 * A PixelTransfer finder is a 7x7 marker whose centre row reads dark-light-dark-dark-dark-light-
 * dark, i.e. run lengths of 1:1:3:1:1 - the same scale-invariant signature that makes concentric
 * markers cheap to find, chosen here because it survives blur and perspective far better than
 * corner-point detection does. All four are identical; which corner is which is settled later by
 * the 3x3 corner-id patches, once the frame has been rectified.
 */
class FinderDetector(private val width: Int, private val height: Int) {

    /**
     * Scans at [rowStep] and, if that did not yield four markers, again at every row. The finer
     * pass costs twice as much but only runs when the cheap one came up short, which is exactly
     * the steep-angle case where the far markers are only a few pixels per module.
     */
    fun detectAdaptive(binarizer: Binarizer): List<FinderPoint> {
        val coarse = detect(binarizer, rowStep = 2)
        if (coarse.size >= 4) return coarse
        val fine = detect(binarizer, rowStep = 1)
        return if (fine.size > coarse.size) fine else coarse
    }

    fun detect(binarizer: Binarizer, rowStep: Int = 2): List<FinderPoint> {
        val candidates = ArrayList<FinderPoint>()
        val counts = IntArray(5)

        var y = rowStep
        while (y < height - rowStep) {
            java.util.Arrays.fill(counts, 0)
            var state = 0
            for (x in 0 until width) {
                val isDark = binarizer.isDark(x, y)
                if (isDark == (state % 2 == 0)) {
                    // Still inside the current run.
                    counts[state]++
                } else {
                    if (state == 4) {
                        if (matchesRatio(counts)) {
                            val point = refine(binarizer, counts, x, y)
                            if (point != null) candidates.add(point)
                        }
                        // Shift by two so the tail of this pattern can start the next one.
                        counts[0] = counts[2]
                        counts[1] = counts[3]
                        counts[2] = counts[4]
                        counts[3] = 1
                        counts[4] = 0
                        state = 3
                    } else {
                        state++
                        counts[state] = 1
                    }
                }
            }
            if (state == 4 && matchesRatio(counts)) {
                refine(binarizer, counts, width, y)?.let { candidates.add(it) }
            }
            y += rowStep
        }
        return cluster(candidates)
    }

    /** Checks run lengths against the 1:1:3:1:1 signature with a generous tolerance. */
    private fun matchesRatio(counts: IntArray): Boolean {
        var total = 0
        for (c in counts) {
            if (c == 0) return false
            total += c
        }
        if (total < 7) return false
        val module = total / 7.0
        val tolerance = module * 0.6
        return abs(module - counts[0]) < tolerance &&
            abs(module - counts[1]) < tolerance &&
            abs(3 * module - counts[2]) < 3 * tolerance &&
            abs(module - counts[3]) < tolerance &&
            abs(module - counts[4]) < tolerance
    }

    /** Cross-checks a horizontal hit vertically and diagonally and returns a refined centre. */
    private fun refine(binarizer: Binarizer, counts: IntArray, endX: Int, y: Int): FinderPoint? {
        val total = counts.sum()
        val centerX = endX - counts[4] - counts[3] - counts[2] / 2.0
        val module = total / 7.0

        val centerY = verticalCheck(binarizer, centerX.toInt(), y, total) ?: return null
        val recheckX = horizontalCheck(binarizer, centerX.toInt(), centerY.toInt(), total) ?: return null
        if (!diagonalCheck(binarizer, recheckX.toInt(), centerY.toInt(), total)) return null
        return FinderPoint(recheckX, centerY, module, total)
    }

    private fun verticalCheck(b: Binarizer, x: Int, y: Int, originalTotal: Int): Double? {
        if (x < 0 || x >= width) return null
        val counts = IntArray(5)
        var yy = y
        while (yy >= 0 && b.isDark(x, yy)) { counts[2]++; yy-- }
        if (yy < 0) return null
        while (yy >= 0 && !b.isDark(x, yy) && counts[1] <= originalTotal) { counts[1]++; yy-- }
        if (yy < 0 || counts[1] > originalTotal) return null
        while (yy >= 0 && b.isDark(x, yy) && counts[0] <= originalTotal) { counts[0]++; yy-- }
        if (counts[0] > originalTotal) return null

        yy = y + 1
        while (yy < height && b.isDark(x, yy)) { counts[2]++; yy++ }
        if (yy == height) return null
        while (yy < height && !b.isDark(x, yy) && counts[3] < originalTotal) { counts[3]++; yy++ }
        if (yy == height || counts[3] >= originalTotal) return null
        while (yy < height && b.isDark(x, yy) && counts[4] < originalTotal) { counts[4]++; yy++ }
        if (counts[4] >= originalTotal) return null

        val total = counts.sum()
        // A marker seen at 35 degrees is foreshortened along one axis only, so the vertical run
        // total legitimately differs from the horizontal one by a large factor.
        if (5 * abs(total - originalTotal) >= 4 * originalTotal) return null
        if (!matchesRatio(counts)) return null
        return yy - counts[4] - counts[3] - counts[2] / 2.0
    }

    private fun horizontalCheck(b: Binarizer, x: Int, y: Int, originalTotal: Int): Double? {
        if (y < 0 || y >= height) return null
        val counts = IntArray(5)
        var xx = x
        while (xx >= 0 && b.isDark(xx, y)) { counts[2]++; xx-- }
        if (xx < 0) return null
        while (xx >= 0 && !b.isDark(xx, y) && counts[1] <= originalTotal) { counts[1]++; xx-- }
        if (xx < 0 || counts[1] > originalTotal) return null
        while (xx >= 0 && b.isDark(xx, y) && counts[0] <= originalTotal) { counts[0]++; xx-- }
        if (counts[0] > originalTotal) return null

        xx = x + 1
        while (xx < width && b.isDark(xx, y)) { counts[2]++; xx++ }
        if (xx == width) return null
        while (xx < width && !b.isDark(xx, y) && counts[3] < originalTotal) { counts[3]++; xx++ }
        if (xx == width || counts[3] >= originalTotal) return null
        while (xx < width && b.isDark(xx, y) && counts[4] < originalTotal) { counts[4]++; xx++ }
        if (counts[4] >= originalTotal) return null

        val total = counts.sum()
        if (5 * abs(total - originalTotal) >= 3 * originalTotal) return null
        if (!matchesRatio(counts)) return null
        return xx - counts[4] - counts[3] - counts[2] / 2.0
    }

    /** Rejects stripes and text that happen to have the right horizontal and vertical profile. */
    private fun diagonalCheck(b: Binarizer, x: Int, y: Int, originalTotal: Int): Boolean {
        val counts = IntArray(5)
        var i = 0
        while (x >= i && y >= i && b.isDark(x - i, y - i)) { counts[2]++; i++ }
        while (x >= i && y >= i && !b.isDark(x - i, y - i) && counts[1] <= originalTotal) { counts[1]++; i++ }
        while (x >= i && y >= i && b.isDark(x - i, y - i) && counts[0] <= originalTotal) { counts[0]++; i++ }

        i = 1
        while (x + i < width && y + i < height && b.isDark(x + i, y + i)) { counts[2]++; i++ }
        while (x + i < width && y + i < height && !b.isDark(x + i, y + i) && counts[3] <= originalTotal) { counts[3]++; i++ }
        while (x + i < width && y + i < height && b.isDark(x + i, y + i) && counts[4] <= originalTotal) { counts[4]++; i++ }

        val total = counts.sum()
        return abs(total - originalTotal) < 3 * originalTotal && matchesRatio(counts)
    }

    /** Merges the many row hits that a single marker produces. */
    private fun cluster(candidates: List<FinderPoint>): List<FinderPoint> {
        val merged = ArrayList<MutableList<FinderPoint>>()
        for (c in candidates) {
            val bucket = merged.firstOrNull { group ->
                val first = group[0]
                hypot(first.x - c.x, first.y - c.y) < first.moduleSize * 2.5
            }
            if (bucket != null) bucket.add(c) else merged.add(mutableListOf(c))
        }
        return merged
            .filter { it.size >= 1 }
            .map { group ->
                FinderPoint(
                    x = group.sumOf { it.x } / group.size,
                    y = group.sumOf { it.y } / group.size,
                    moduleSize = group.sumOf { it.moduleSize } / group.size,
                    score = group.size,
                )
            }
            .sortedByDescending { it.score }
    }

    companion object {

        /**
         * Picks the four markers that most plausibly bound one frame: consistent module size,
         * and the extreme points of that group so a stray fifth detection cannot skew the quad.
         */
        fun selectQuad(points: List<FinderPoint>): List<FinderPoint>? =
            candidateQuads(points, limit = 1).firstOrNull()

        /**
         * Ranked quad hypotheses, best first.
         *
         * The payload is a field of arbitrary colours, so the binarised image always contains a
         * few accidental concentric patterns. Rather than trying to be clever about rejecting
         * them here, the reader tries the top few hypotheses in order: a wrong quad is thrown out
         * within a millisecond by the timing-strip score, and being able to fall through to the
         * next one is what keeps a stray detection from costing a whole frame.
         */
        fun candidateQuads(points: List<FinderPoint>, limit: Int = 4): List<List<FinderPoint>> {
            if (points.size < 4) return emptyList()
            if (points.size == 4) return listOf(orderClockwise(points))

            val scored = ArrayList<Pair<Double, List<FinderPoint>>>()
            val seen = HashSet<Set<FinderPoint>>()

            fun consider(quad: List<FinderPoint>) {
                if (quad.size != 4) return
                val key = quad.toSet()
                if (key.size != 4 || !seen.add(key)) return
                val sizes = quad.map { it.moduleSize }
                val spread = (sizes.max() - sizes.min()) / sizes.average()
                val ordered = orderClockwise(quad)
                val area = polygonArea(ordered)
                if (area <= 1.0) return
                // Prefer consistent module sizes and, among those, the largest quad: the real
                // code area is nearly always the biggest concentric-marker rectangle in view.
                scored.add((spread * 4.0 - kotlin.math.ln(area)) to ordered)
            }

            for (anchor in points) {
                val group = points.filter { it.moduleSize / anchor.moduleSize in 0.4..2.5 }
                if (group.size >= 4) consider(extremes(group))
            }
            if (points.size <= 8) {
                val n = points.size
                for (a in 0 until n) for (b in a + 1 until n) for (c in b + 1 until n) {
                    for (d in c + 1 until n) consider(listOf(points[a], points[b], points[c], points[d]))
                }
            }
            return scored.sortedBy { it.first }.take(limit).map { it.second }
        }

        private fun polygonArea(points: List<FinderPoint>): Double {
            var area = 0.0
            for (i in points.indices) {
                val a = points[i]
                val b = points[(i + 1) % points.size]
                area += a.x * b.y - b.x * a.y
            }
            return abs(area) / 2.0
        }

        private fun extremes(group: List<FinderPoint>): List<FinderPoint> {
            val a = group.minByOrNull { it.x + it.y }!!
            val b = group.maxByOrNull { it.x - it.y }!!
            val c = group.maxByOrNull { it.x + it.y }!!
            val d = group.minByOrNull { it.x - it.y }!!
            val distinct = linkedSetOf(a, b, c, d)
            return distinct.toList()
        }

        /** Orders four points into a consistent cycle around their centroid. */
        fun orderClockwise(points: List<FinderPoint>): List<FinderPoint> {
            val cx = points.sumOf { it.x } / points.size
            val cy = points.sumOf { it.y } / points.size
            return points.sortedBy { atan2(it.y - cy, it.x - cx) }
        }
    }
}
