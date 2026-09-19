package nl.tippie.pixeltransfer.core

import nl.tippie.pixeltransfer.core.frame.FrameRasterizer
import nl.tippie.pixeltransfer.core.frame.PaletteMode
import nl.tippie.pixeltransfer.core.pipeline.SenderConfig
import nl.tippie.pixeltransfer.core.pipeline.StreamEncoder
import nl.tippie.pixeltransfer.core.pipeline.TransferPreparation
import nl.tippie.pixeltransfer.core.vision.ExposureGovernor
import nl.tippie.pixeltransfer.core.vision.ExposureMeter
import nl.tippie.pixeltransfer.core.vision.FrameReader
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Reproduces what an actual hand-held capture looked like: the camera metering a dark room and
 * then pointed at a bright display, so the pattern clips to pastel; visible defocus; and the code
 * occupying well under half the frame because the sender only filled three quarters of its own
 * screen width.
 *
 * The synthetic envelope tests pass one impairment at a time. This is the combination, which is
 * the only thing the field actually produces.
 */
class FieldConditionsTest {

    /** A capture that mirrors the reported failure. */
    private fun handHeld(fill: Double = 0.6, seed: Int = 3) = OpticalChannel(
        outWidth = 1920,
        outHeight = 1080,
        fill = fill,
        blurSigma = 2.0,
        noiseSigma = 5.0,
        exposureGain = 1.9,
        vignette = 0.25,
        glareStrength = 45.0,
        seed = seed,
    )

    private fun renderFrame(config: SenderConfig, sequence: Int = 5): Pair<IntArray, Int> {
        val bytes = ByteArray(120_000).also { Random(1234).nextBytes(it) }
        val prepared = TransferPreparation.prepare(
            "field.bin", "application/octet-stream", bytes, config, Random(7),
        )
        val cells = StreamEncoder(prepared).renderCells(sequence)
        val size = FrameRasterizer.imageSize(
            config.gridCells, config.cellSizePx, SenderConfig.QUIET_CELLS,
        )
        return FrameRasterizer.rasterize(
            cells, config.gridCells, config.cellSizePx, SenderConfig.QUIET_CELLS,
        ) to size
    }

    @Test
    fun `the exposure loop rescues an over-exposed capture`() {
        // The camera's own auto-exposure has metered the dark room and blown the screen out.
        val cameraChoseGain = 1.9
        val config = SenderConfig(gridCells = 96, cellSizePx = 10, paletteMode = PaletteMode.BALANCED)
        val (pixels, size) = renderFrame(config)

        // A typical phone: +/- 2 EV in thirds of a stop.
        val governor = ExposureGovernor(minIndex = -6, maxIndex = 6)
        val reader = FrameReader()

        var decodedBefore = false
        var decodedAfter = false
        var framesToConverge = -1

        for (frame in 0 until 40) {
            val gain = cameraChoseGain * Math.pow(2.0, governor.index / 3.0)
            val image = OpticalChannel(
                fill = 0.6, blurSigma = 1.4, noiseSigma = 5.0, exposureGain = gain,
                vignette = 0.25, seed = 31 + frame,
            ).capture(pixels, size)

            val result = reader.read(image)
            if (frame == 0) decodedBefore = result.isUsable
            if (result.isUsable && framesToConverge < 0) {
                framesToConverge = frame
                decodedAfter = true
            }
            governor.update(ExposureMeter.measure(image))
        }

        println(
            "exposure loop: startGain=$cameraChoseGain decodedAtStart=$decodedBefore " +
                "finalIndex=${governor.index} settled=${governor.isSettled} " +
                "firstDecodeAtFrame=$framesToConverge",
        )
        assertFalse("the over-exposed capture should not decode before correction", decodedBefore)
        assertTrue("the exposure loop must make the stream decodable", decodedAfter)
        assertTrue("convergence took $framesToConverge frames", framesToConverge in 0..25)
    }

    @Test
    fun `report what the reported configuration does under hand-held conditions`() {
        // The configuration that was actually shipped and failed.
        val shipped = SenderConfig(gridCells = 96, cellSizePx = 8, paletteMode = PaletteMode.BALANCED)
        val (pixels, size) = renderFrame(shipped)
        for (fill in listOf(0.8, 0.6, 0.45)) {
            val result = FrameReader().read(handHeld(fill).capture(pixels, size))
            println(
                "shipped grid=96 cell=8 fill=$fill -> ${result.status} " +
                    "symbols=${result.symbols.size} cellPx=${"%.1f".format(result.diagnostics.cellPixelSize)}",
            )
        }
    }

    @Test
    fun `isolate which impairment breaks the balanced palette`() {
        val config = SenderConfig(gridCells = 96, cellSizePx = 10, paletteMode = PaletteMode.BALANCED)
        val (pixels, size) = renderFrame(config)
        println("-- exposure gain sweep (blur 0.8, no glare) --")
        for (gain in listOf(1.0, 1.15, 1.3, 1.5, 1.9, 2.5)) {
            val channel = OpticalChannel(
                fill = 0.6, blurSigma = 0.8, noiseSigma = 5.0, exposureGain = gain, vignette = 0.25,
            )
            val r = FrameReader().read(channel.capture(pixels, size))
            println("  gain=$gain -> ${if (r.isUsable) "OK(${r.symbols.size})" else r.status} residual=${"%.1f".format(r.diagnostics.calibrationResidual)}")
        }
        println("-- blur sweep (gain 1.0) --")
        for (blur in listOf(0.8, 1.2, 1.6, 2.0, 2.5)) {
            val channel = OpticalChannel(
                fill = 0.6, blurSigma = blur, noiseSigma = 5.0, exposureGain = 1.0, vignette = 0.25,
            )
            val r = FrameReader().read(channel.capture(pixels, size))
            println("  blur=$blur -> ${if (r.isUsable) "OK(${r.symbols.size})" else r.status} residual=${"%.1f".format(r.diagnostics.calibrationResidual)}")
        }
        println("-- both, at the gain a working auto-exposure loop would reach --")
        for (blur in listOf(1.2, 1.6, 2.0)) {
            for (gain in listOf(1.0, 1.15)) {
                val channel = OpticalChannel(
                    fill = 0.6, blurSigma = blur, noiseSigma = 5.0, exposureGain = gain,
                    vignette = 0.25, glareStrength = 45.0,
                )
                val r = FrameReader().read(channel.capture(pixels, size))
                println("  blur=$blur gain=$gain -> ${if (r.isUsable) "OK(${r.symbols.size})" else r.status}")
            }
        }
    }

    @Test
    fun `report the decode envelope per palette mode`() {
        for (mode in listOf(PaletteMode.ROBUST, PaletteMode.BALANCED, PaletteMode.DENSE)) {
            val config = SenderConfig(gridCells = 96, cellSizePx = 10, paletteMode = mode)
            val (pixels, size) = renderFrame(config)
            val line = StringBuilder("${mode.label}: ")
            line.append("fill[")
            for (fill in listOf(0.85, 0.6, 0.45, 0.35)) {
                val r = FrameReader().read(
                    OpticalChannel(fill = fill, blurSigma = 0.8, noiseSigma = 4.0, vignette = 0.25).capture(pixels, size),
                )
                line.append("$fill=${if (r.isUsable) "ok" else "no"} ")
            }
            line.append("] blur[")
            for (blur in listOf(1.0, 1.5, 2.0, 2.5, 3.0)) {
                val r = FrameReader().read(
                    OpticalChannel(fill = 0.6, blurSigma = blur, noiseSigma = 4.0, vignette = 0.25).capture(pixels, size),
                )
                line.append("$blur=${if (r.isUsable) "ok" else "no"} ")
            }
            line.append("] yaw[")
            for (yaw in listOf(20.0, 30.0, 40.0)) {
                val r = FrameReader().read(
                    OpticalChannel(fill = 0.6, yawDegrees = yaw, blurSigma = 1.0, noiseSigma = 4.0).capture(pixels, size),
                )
                line.append("$yaw=${if (r.isUsable) "ok" else "no"} ")
            }
            line.append("] clip[")
            for (gain in listOf(1.2, 1.5, 1.9)) {
                val r = FrameReader().read(
                    OpticalChannel(fill = 0.6, blurSigma = 1.0, noiseSigma = 4.0, exposureGain = gain).capture(pixels, size),
                )
                line.append("$gain=${if (r.isUsable) "ok" else "no"} ")
            }
            line.append("]")
            println(line)
        }
    }

    @Test
    fun `report candidate configurations under the same conditions`() {
        for (grid in listOf(96, 80, 64)) {
            // Cell size chosen to fill a 1080 px wide sender screen, as the app now does.
            val cell = (1080 / (grid + 2 * SenderConfig.QUIET_CELLS))
                .coerceIn(SenderConfig.MIN_CELL_SIZE, SenderConfig.MAX_CELL_SIZE)
            for (mode in listOf(PaletteMode.BALANCED, PaletteMode.ROBUST)) {
                val config = SenderConfig(gridCells = grid, cellSizePx = cell, paletteMode = mode)
                val (pixels, size) = renderFrame(config)
                val line = StringBuilder("grid=$grid cell=$cell ${mode.label}: ")
                for (fill in listOf(0.8, 0.6, 0.45)) {
                    val result = FrameReader().read(handHeld(fill).capture(pixels, size))
                    line.append(
                        "fill$fill=${if (result.isUsable) "OK(${result.symbols.size})" else result.status} " +
                            "[${"%.1f".format(result.diagnostics.cellPixelSize)}px] ",
                    )
                }
                println(line)
            }
        }
    }
}
