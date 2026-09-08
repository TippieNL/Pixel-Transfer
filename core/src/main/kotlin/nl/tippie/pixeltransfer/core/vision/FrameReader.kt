package nl.tippie.pixeltransfer.core.vision

import nl.tippie.pixeltransfer.core.frame.CellRole
import nl.tippie.pixeltransfer.core.frame.FrameCodec
import nl.tippie.pixeltransfer.core.frame.FrameHeader
import nl.tippie.pixeltransfer.core.frame.FrameLayout
import nl.tippie.pixeltransfer.core.frame.FramePlan
import nl.tippie.pixeltransfer.core.frame.Palette
import nl.tippie.pixeltransfer.core.frame.PaletteMode
import nl.tippie.pixeltransfer.core.frame.SymbolPacket
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot

/** How far a captured frame got through the pipeline. */
enum class ReadStatus {
    /** Fewer than four corner markers were found. */
    NO_FINDERS,

    /** Markers found, but no grid size and orientation explained the timing strips. */
    NO_GRID,

    /** The calibration patches were unreadable. */
    NO_CALIBRATION,

    /** The header did not survive its Reed-Solomon code. */
    HEADER_FAILED,

    /** The capture straddles two displayed frames. */
    TORN,

    /** The frame decoded but every symbol failed its CRC. */
    NO_SYMBOLS,

    OK,
}

/** Everything the receiver UI needs to tell the user what to change. */
data class FrameDiagnostics(
    val brightness: Double = 0.0,
    val contrast: Double = 0.0,
    val calibrationResidual: Double = 0.0,
    /** Approximate off-axis angle in degrees, from the foreshortening of the code area. */
    val offAxisDegrees: Double = 0.0,
    val cellPixelSize: Double = 0.0,
    /** Fraction of sampled cells that are blown out to white. */
    val glareFraction: Double = 0.0,
    /** Adjacent-timing-cell modulation relative to full contrast; low means blurred. */
    val sharpness: Double = 0.0,
    val tornRowFraction: Double = 0.0,
    val rsBlocksTotal: Int = 0,
    val rsBlocksFailed: Int = 0,
    val symbolsRecovered: Int = 0,
    val byteErrorsCorrected: Int = 0,
)

/** Result of trying to read one captured image. */
class FrameReadResult(
    val status: ReadStatus,
    val header: FrameHeader? = null,
    val symbols: List<SymbolPacket> = emptyList(),
    val diagnostics: FrameDiagnostics = FrameDiagnostics(),
    /** Detected code corners in image space, for the preview overlay. */
    val corners: Array<DoubleArray>? = null,
) {
    val isUsable: Boolean get() = status == ReadStatus.OK && symbols.isNotEmpty()
}

/**
 * Turns one captured image into fountain symbols.
 *
 * The stages run in the order the spec lays out: locate markers, rectify, resolve rotation and
 * flip, correct lens distortion from the timing strips, normalise colour from the calibration
 * patches, sample and classify cells, then Reed-Solomon and CRC. Every stage that can fail does
 * so by returning a status rather than by guessing, because a frame that decodes into plausible
 * garbage is far more expensive than one that is dropped: the stream loops anyway.
 */
class FrameReader(
    private val candidateGrids: IntArray = GridFitter.CANDIDATE_GRIDS,
    /** Fraction of tear-stripe rows that may disagree before the frame is discarded. */
    private val tearTolerance: Double = 0.04,
) {

    private var binarizer: Binarizer? = null
    private var detector: FinderDetector? = null
    private var lastWidth = -1
    private var lastHeight = -1

    /** Caches the grid fit; layouts are rebuilt only when the grid size actually changes. */
    private var cachedLayout: FrameLayout? = null

    fun read(image: CameraImage): FrameReadResult {
        if (image.width != lastWidth || image.height != lastHeight) {
            binarizer = Binarizer(image.width, image.height)
            detector = FinderDetector(image.width, image.height)
            lastWidth = image.width
            lastHeight = image.height
        }
        val bin = binarizer!!
        bin.binarize(image.lumaPlane())

        val points = detector!!.detectAdaptive(bin)
        val quads = FinderDetector.candidateQuads(points, QUAD_HYPOTHESES)
        if (quads.isEmpty()) return FrameReadResult(ReadStatus.NO_FINDERS)

        var fit: GridFit? = null
        for (quad in quads) {
            fit = GridFitter.fit(image, quad, candidateGrids)
            if (fit != null) break
        }
        if (fit == null) {
            val first = quads.first()
            return FrameReadResult(
                ReadStatus.NO_GRID,
                corners = first.map { doubleArrayOf(it.x, it.y) }.toTypedArray(),
            )
        }

        val layout = layoutFor(fit.grid)
        val calibration = ColorCalibrator.calibrate(image, fit, layout)
            ?: return FrameReadResult(ReadStatus.NO_CALIBRATION, corners = fit.corners)

        val geometry = measureGeometry(image, fit, layout, calibration)

        // Header: always Robust palette, interleaved across the whole frame.
        val headerCells = FrameCodec.headerCellCount()
        if (layout.dataCells.size <= headerCells) {
            return FrameReadResult(ReadStatus.NO_GRID, corners = fit.corners, diagnostics = geometry)
        }
        val rgb = IntArray(3)
        val corrected = IntArray(3)
        val headerSymbols = IntArray(headerCells) { i ->
            val cell = layout.dataCells[i]
            classify(image, fit, layout, calibration, cell, PaletteMode.ROBUST, rgb, corrected)
        }
        val header = FrameCodec.decodeHeader(layout, headerSymbols)
            ?: return FrameReadResult(ReadStatus.HEADER_FAILED, corners = fit.corners, diagnostics = geometry)

        val tornFraction = tearFraction(image, fit, layout, calibration, header.nonce)
        val diagnostics = geometry.copy(tornRowFraction = tornFraction)
        if (tornFraction > tearTolerance) {
            return FrameReadResult(ReadStatus.TORN, header, emptyList(), diagnostics, fit.corners)
        }

        val plan = FramePlan(layout, header.paletteMode, header.eccLevel, header.blockSize)
        if (!plan.isUsable || plan.symbolsPerFrame != header.symbolsInFrame) {
            // The header decoded but describes a frame this layout cannot hold: the grid fit or
            // the header is wrong in a way the CRC happened not to catch. Drop it.
            return FrameReadResult(ReadStatus.HEADER_FAILED, corners = fit.corners, diagnostics = diagnostics)
        }

        val payloadSymbols = IntArray(plan.payloadCellCount) { i ->
            val cell = layout.dataCells[headerCells + i]
            classify(image, fit, layout, calibration, cell, header.paletteMode, rgb, corrected)
        }
        val payload = FrameCodec(plan).decodePayload(payloadSymbols)

        val full = diagnostics.copy(
            rsBlocksTotal = payload.rsBlocksTotal,
            rsBlocksFailed = payload.rsBlocksFailed,
            symbolsRecovered = payload.symbols.size,
            byteErrorsCorrected = payload.byteErrorsCorrected,
        )
        val status = if (payload.symbols.isEmpty()) ReadStatus.NO_SYMBOLS else ReadStatus.OK
        return FrameReadResult(status, header, payload.symbols, full, fit.corners)
    }

    private companion object {
        /** Quad hypotheses tried before giving up on a capture. */
        const val QUAD_HYPOTHESES = 4

        /** Shortest run of rows that can be called a tear rather than a noise cluster. */
        const val MIN_TEAR_ROWS = 4

        /** Difference in stripe error rate between the two sides that indicates a real tear. */
        const val TEAR_SEPARATION = 0.25
    }

    private fun layoutFor(grid: Int): FrameLayout {
        val cached = cachedLayout
        if (cached != null && cached.grid == grid) return cached
        val layout = FrameLayout(grid)
        cachedLayout = layout
        return layout
    }

    private fun classify(
        image: CameraImage,
        fit: GridFit,
        layout: FrameLayout,
        calibration: ColorCalibration,
        cell: Int,
        mode: PaletteMode,
        rgb: IntArray,
        corrected: IntArray,
    ): Int {
        val x = layout.xOf(cell)
        val y = layout.yOf(cell)
        CellSampler.sampleRgb(image, fit, x, y, CellSampler.DEFAULT_SUBSAMPLES, rgb)
        val span = (layout.grid - 1).toDouble()
        calibration.correct(rgb[0], rgb[1], rgb[2], x / span, y / span, corrected)
        return Palette.symbolFromCodes(mode, corrected[0], corrected[1], corrected[2])
    }

    /**
     * Estimates how much of the capture came from a different displayed frame.
     *
     * A rolling shutter tear is *contiguous*: every row past some scanline carries the next
     * frame's nonce. Misread cells, by contrast, are scattered. So rather than counting
     * disagreements, this looks for the horizontal split that best separates agreeing rows from
     * disagreeing ones. Uniform noise produces no such split and is correctly not called a tear,
     * which matters because discarding readable frames is expensive.
     *
     * Returns the fraction of stripe rows that belong to the other frame, or 0 when there is no
     * evidence of a tear.
     */
    private fun tearFraction(
        image: CameraImage,
        fit: GridFit,
        layout: FrameLayout,
        calibration: ColorCalibration,
        nonce: Int,
    ): Double {
        val rgb = IntArray(3)
        val corrected = IntArray(3)
        val span = (layout.grid - 1).toDouble()
        val rowMismatch = ArrayList<Int>(layout.grid)
        var cellsPerRow = 0

        for (row in layout.bandStart..layout.bandEnd) {
            var disagreements = 0
            var cells = 0
            for (col in layout.nonceCols) {
                if (layout.roles[layout.index(col, row)] != CellRole.NONCE_STRIPE) continue
                CellSampler.sampleRgb(image, fit, col, row, CellSampler.DEFAULT_SUBSAMPLES, rgb)
                val observed = calibration.isLight(rgb[0], rgb[1], rgb[2], col / span, row / span, corrected)
                val expected = FrameHeader.stripeBit(nonce, row, col - layout.nonceCols.first)
                if (observed != expected) disagreements++
                cells++
            }
            if (cells == 0) continue
            cellsPerRow = cells
            rowMismatch.add(disagreements)
        }

        val rows = rowMismatch.size
        if (rows < MIN_TEAR_ROWS * 2 || cellsPerRow == 0) return 0.0

        val prefix = IntArray(rows + 1)
        for (i in 0 until rows) prefix[i + 1] = prefix[i] + rowMismatch[i]

        var bestFraction = 0.0
        var bestSeparation = 0.0
        for (split in MIN_TEAR_ROWS..rows - MIN_TEAR_ROWS) {
            val topRate = prefix[split].toDouble() / (split * cellsPerRow)
            val bottomRate = (prefix[rows] - prefix[split]).toDouble() /
                ((rows - split) * cellsPerRow)
            val separation = kotlin.math.abs(topRate - bottomRate)
            if (separation > bestSeparation) {
                bestSeparation = separation
                bestFraction = if (topRate > bottomRate) {
                    split.toDouble() / rows
                } else {
                    (rows - split).toDouble() / rows
                }
            }
        }
        // A wrong nonce flips each stripe bit with probability one half, so a genuine tear shows
        // a separation approaching 0.5; scattered misreads stay far below it.
        return if (bestSeparation >= TEAR_SEPARATION) bestFraction else 0.0
    }

    /** Brightness, glare, sharpness and off-axis angle, all from cells we already have to read. */
    private fun measureGeometry(
        image: CameraImage,
        fit: GridFit,
        layout: FrameLayout,
        calibration: ColorCalibration,
    ): FrameDiagnostics {
        val corners = fit.corners
        val sides = DoubleArray(4)
        for (i in 0 until 4) {
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            sides[i] = hypot(b[0] - a[0], b[1] - a[1])
        }
        val horizontal = (sides[0] + sides[2]) / 2.0
        val vertical = (sides[1] + sides[3]) / 2.0
        val aspect = if (vertical > 0) horizontal / vertical else 1.0
        val foreshortening = minOf(aspect, 1.0 / aspect).coerceIn(0.05, 1.0)
        val offAxis = Math.toDegrees(acos(foreshortening))

        // Sharpness from the top timing strip: how much of full contrast survives between
        // adjacent alternating cells.
        var modulation = 0.0
        var pairs = 0
        var previous = -1
        var blown = 0
        var sampled = 0
        var brightnessSum = 0.0
        for (col in layout.bandStart..layout.bandEnd) {
            val luma = CellSampler.sampleLuma(image, fit, col, layout.timingRow)
            brightnessSum += luma
            sampled++
            if (luma >= 250) blown++
            if (previous >= 0) {
                modulation += abs(luma - previous)
                pairs++
            }
            previous = luma
        }
        val contrast = calibration.contrast
        val sharpness = if (pairs == 0 || contrast <= 1.0) 0.0 else (modulation / pairs) / contrast

        return FrameDiagnostics(
            brightness = if (sampled == 0) 0.0 else brightnessSum / sampled,
            contrast = contrast,
            calibrationResidual = calibration.residual,
            offAxisDegrees = offAxis,
            cellPixelSize = fit.cellPixelSize(),
            glareFraction = if (sampled == 0) 0.0 else blown.toDouble() / sampled,
            sharpness = sharpness.coerceIn(0.0, 2.0),
        )
    }
}
