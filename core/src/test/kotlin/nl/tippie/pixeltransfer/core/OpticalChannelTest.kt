package nl.tippie.pixeltransfer.core

import nl.tippie.pixeltransfer.core.frame.FrameRasterizer
import nl.tippie.pixeltransfer.core.pipeline.SenderConfig
import nl.tippie.pixeltransfer.core.pipeline.StreamEncoder
import nl.tippie.pixeltransfer.core.pipeline.TransferPreparation
import nl.tippie.pixeltransfer.core.vision.FrameReader
import nl.tippie.pixeltransfer.core.vision.ReadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Renders one frame and pushes it through the simulated camera; reports what came back. */
class OpticalChannelTest {

    private fun renderFrame(config: SenderConfig, sequence: Int): Pair<IntArray, Int> {
        val bytes = ByteArray(200_000).also { Random(1234).nextBytes(it) }
        val prepared = TransferPreparation.prepare(
            "sample.bin", "application/octet-stream", bytes, config, Random(7),
        )
        val encoder = StreamEncoder(prepared)
        val cells = encoder.renderCells(sequence)
        val size = FrameRasterizer.imageSize(
            config.gridCells, config.cellSizePx, SenderConfig.QUIET_CELLS,
        )
        return FrameRasterizer.rasterize(
            cells, config.gridCells, config.cellSizePx, SenderConfig.QUIET_CELLS,
        ) to size
    }

    private fun read(config: SenderConfig, channel: OpticalChannel, sequence: Int = 5): Pair<ReadStatus, Int> {
        val (pixels, size) = renderFrame(config, sequence)
        val image = channel.capture(pixels, size)
        val result = FrameReader().read(image)
        return result.status to result.symbols.size
    }

    @Test
    fun `head on capture in balanced mode`() {
        val config = SenderConfig()
        val (status, symbols) = read(config, OpticalChannel())
        assertEquals(ReadStatus.OK, status)
        assertEquals(4, symbols)
    }

    @Test
    fun `report the operating envelope`() {
        val config = SenderConfig()
        for (fill in listOf(0.95, 0.8, 0.65, 0.5, 0.4, 0.3)) {
            val (status, symbols) = read(config, OpticalChannel(fill = fill))
            println("fill=$fill -> $status ($symbols symbols)")
        }
        for (yaw in listOf(0.0, 10.0, 20.0, 30.0, 35.0, 45.0)) {
            val (status, symbols) = read(config, OpticalChannel(yawDegrees = yaw))
            println("yaw=$yaw -> $status ($symbols symbols)")
        }
        for (blur in listOf(0.5, 1.0, 1.5, 2.0, 3.0)) {
            val (status, symbols) = read(config, OpticalChannel(blurSigma = blur))
            println("blur=$blur -> $status ($symbols symbols)")
        }
        for (noise in listOf(2.0, 5.0, 8.0, 12.0)) {
            val (status, symbols) = read(config, OpticalChannel(noiseSigma = noise))
            println("noise=$noise -> $status ($symbols symbols)")
        }
    }
}
