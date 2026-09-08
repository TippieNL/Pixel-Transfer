package nl.tippie.pixeltransfer.core.frame

/**
 * Expands a grid of cell colours into a pixel buffer.
 *
 * Every logical cell becomes an N x N block of screen pixels; the quiet border is drawn light so
 * the dark outer ring of each finder has something to contrast against.
 */
object FrameRasterizer {

    fun imageSize(grid: Int, cellSizePx: Int, quietCells: Int): Int =
        (grid + 2 * quietCells) * cellSizePx

    /**
     * Writes ARGB pixels for [cells] into [out], which must hold [imageSize]^2 entries.
     */
    fun rasterize(
        cells: IntArray,
        grid: Int,
        cellSizePx: Int,
        quietCells: Int,
        out: IntArray,
    ) {
        val size = imageSize(grid, cellSizePx, quietCells)
        require(out.size >= size * size) { "output buffer too small: ${out.size} < ${size * size}" }
        require(cells.size == grid * grid) { "expected ${grid * grid} cells, got ${cells.size}" }

        java.util.Arrays.fill(out, 0, size * size, Palette.LIGHT)

        val offset = quietCells * cellSizePx
        for (cy in 0 until grid) {
            val y0 = offset + cy * cellSizePx
            for (cx in 0 until grid) {
                val color = cells[cy * grid + cx]
                val x0 = offset + cx * cellSizePx
                for (dy in 0 until cellSizePx) {
                    val rowStart = (y0 + dy) * size + x0
                    java.util.Arrays.fill(out, rowStart, rowStart + cellSizePx, color)
                }
            }
        }
    }

    fun rasterize(cells: IntArray, grid: Int, cellSizePx: Int, quietCells: Int): IntArray {
        val size = imageSize(grid, cellSizePx, quietCells)
        val out = IntArray(size * size)
        rasterize(cells, grid, cellSizePx, quietCells, out)
        return out
    }
}
