package nl.tippie.pixeltransfer.core

import nl.tippie.pixeltransfer.core.frame.CellRole
import nl.tippie.pixeltransfer.core.frame.FrameLayout
import nl.tippie.pixeltransfer.core.frame.FrameRasterizer
import nl.tippie.pixeltransfer.core.frame.Palette
import nl.tippie.pixeltransfer.core.frame.PaletteMode
import nl.tippie.pixeltransfer.core.pipeline.SenderConfig
import nl.tippie.pixeltransfer.core.pipeline.StreamEncoder
import nl.tippie.pixeltransfer.core.pipeline.TransferPreparation
import nl.tippie.pixeltransfer.core.vision.Binarizer
import nl.tippie.pixeltransfer.core.vision.CellFieldSampler
import nl.tippie.pixeltransfer.core.vision.ColorCalibrator
import nl.tippie.pixeltransfer.core.vision.FinderDetector
import nl.tippie.pixeltransfer.core.vision.GridFitter
import org.junit.Test
import kotlin.random.Random

/**
 * Compares what the decoder classifies each cell as against what the sender actually drew.
 *
 * A frame either decodes or it does not, which says nothing about *why*. This measures the raw
 * cell error rate and, more usefully, whether the errors are biased - every cell reading one
 * level high points at calibration, while a symmetric spread points at blur and noise.
 */
class ClassificationDiagnosticTest {

    private fun measure(mode: PaletteMode, channel: OpticalChannel, cellPx: Int = 10, sharpen: Boolean = true): String {
        val config = SenderConfig(gridCells = 96, cellSizePx = cellPx, paletteMode = mode)
        val bytes = ByteArray(120_000).also { Random(1234).nextBytes(it) }
        val prepared = TransferPreparation.prepare(
            "d.bin", "application/octet-stream", bytes, config, Random(7),
        )
        val trueCells = StreamEncoder(prepared).renderCells(5)
        val size = FrameRasterizer.imageSize(96, cellPx, SenderConfig.QUIET_CELLS)
        val pixels = FrameRasterizer.rasterize(trueCells, 96, cellPx, SenderConfig.QUIET_CELLS)
        val image = channel.capture(pixels, size)

        val bin = Binarizer(image.width, image.height)
        bin.binarize(image.lumaPlane())
        val points = FinderDetector(image.width, image.height).detectAdaptive(bin)
        val quad = FinderDetector.selectQuad(points) ?: return "$mode: no quad"
        val fit = GridFitter.fit(image, quad) ?: return "$mode: no grid"
        val layout = FrameLayout(fit.grid)
        val calibration = ColorCalibrator.calibrate(image, fit, layout) ?: return "$mode: no calibration"

        val levels = mode.levels
        var cells = 0
        var wrong = 0
        val bias = IntArray(2 * levels + 1)

        // Exactly the path the reader takes: sample the whole grid, measure the blur from the
        // timing strips, invert it, then classify.
        val field = CellFieldSampler.sample(image, fit, layout, calibration)
        val mx = CellFieldSampler.modulation(field, layout, horizontal = true)
        val my = CellFieldSampler.modulation(field, layout, horizontal = false)
        if (sharpen) CellFieldSampler.sharpen(field, mx, my)

        for (index in layout.dataCells) {
            if (layout.roles[index] != CellRole.DATA) continue
            val trueSymbol = Palette.symbolFromArgb(mode, trueCells[index])
            val gotSymbol = Palette.symbolFromCodes(
                mode, field.red[index], field.green[index], field.blue[index],
            )
            cells++
            if (trueSymbol != gotSymbol) wrong++

            val trueLevels = Palette.symbolToLevels(mode, trueSymbol)
            val gotLevels = Palette.symbolToLevels(mode, gotSymbol)
            for (ch in 0 until 3) {
                bias[(gotLevels[ch] - trueLevels[ch]) + levels] += 1
            }
        }

        val errRate = wrong.toDouble() / cells
        val biasText = (0..2 * levels)
            .filter { bias[it] > 0 }
            .joinToString(" ") { "${it - levels}:${"%.1f".format(100.0 * bias[it] / (cells * 3))}%" }
        return "${mode.label} cell=${"%.1f".format(fit.cellPixelSize())}px " +
            "symbolErr=${"%.1f".format(errRate * 100)}% residual=${"%.1f".format(calibration.residual)} " +
            "mod=${"%.2f".format(minOf(mx, my))} levelErr[$biasText]"
    }

    @Test
    fun `deconvolution recovers levels lost to blur`() {
        for (blur in listOf(1.2, 1.6, 2.0, 2.5, 3.0)) {
            val channel = OpticalChannel(fill = 0.6, blurSigma = blur, noiseSigma = 5.0, vignette = 0.25)
            val off = measure(PaletteMode.BALANCED, channel, sharpen = false)
            val on = measure(PaletteMode.BALANCED, channel, sharpen = true)
            println(
                "blur=$blur  off=${off.substringAfter("symbolErr=").substringBefore(" ")}" +
                    "  on=${on.substringAfter("symbolErr=").substringBefore(" ")}" +
                    "  ${on.substringAfter("mod=").substringBefore(" ")}",
            )
        }
    }

    @Test
    fun `cell error rate under a clean channel`() {
        for (mode in listOf(PaletteMode.ROBUST, PaletteMode.BALANCED)) {
            println(measure(mode, OpticalChannel(fill = 0.6, blurSigma = 0.8, noiseSigma = 5.0, vignette = 0.25)))
        }
    }

    @Test
    fun `cell error rate as blur increases`() {
        for (blur in listOf(0.8, 1.2, 1.6, 2.0)) {
            println(
                "blur=$blur  " +
                    measure(PaletteMode.BALANCED, OpticalChannel(fill = 0.6, blurSigma = blur, noiseSigma = 5.0, vignette = 0.25)),
            )
        }
    }

    @Test
    fun `cell error rate as exposure clips`() {
        for (gain in listOf(1.0, 1.3, 1.9)) {
            println(
                "gain=$gain  " +
                    measure(PaletteMode.BALANCED, OpticalChannel(fill = 0.6, blurSigma = 0.8, noiseSigma = 5.0, exposureGain = gain, vignette = 0.25)),
            )
        }
    }
}
