package nl.tippie.pixeltransfer.core

import nl.tippie.pixeltransfer.core.frame.FrameRasterizer
import nl.tippie.pixeltransfer.core.frame.PaletteMode
import nl.tippie.pixeltransfer.core.pipeline.PreparedTransfer
import nl.tippie.pixeltransfer.core.pipeline.SenderConfig
import nl.tippie.pixeltransfer.core.pipeline.StreamDecoder
import nl.tippie.pixeltransfer.core.pipeline.StreamEncoder
import nl.tippie.pixeltransfer.core.pipeline.TransferPreparation
import nl.tippie.pixeltransfer.core.vision.FrameReader
import nl.tippie.pixeltransfer.core.vision.Guidance
import nl.tippie.pixeltransfer.core.vision.ReadStatus
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * End-to-end transfers through the simulated camera, checked against the acceptance criteria in
 * the specification.
 */
class AcceptanceTest {

    private class Sender(bytes: ByteArray, config: SenderConfig, seed: Int = 21) {
        val prepared: PreparedTransfer = TransferPreparation.prepare(
            "holiday.bin", "application/octet-stream", bytes, config, Random(seed),
        )
        val encoder = StreamEncoder(prepared)
        private val imageSize = FrameRasterizer.imageSize(
            config.gridCells, config.cellSizePx, SenderConfig.QUIET_CELLS,
        )
        private val buffer = IntArray(imageSize * imageSize)

        val size: Int get() = imageSize

        fun display(sequence: Int): IntArray {
            FrameRasterizer.rasterize(
                encoder.renderCells(sequence),
                prepared.config.gridCells,
                prepared.config.cellSizePx,
                SenderConfig.QUIET_CELLS,
                buffer,
            )
            return buffer
        }
    }

    private class Run(
        val decoded: StreamDecoder,
        val framesDisplayed: Int,
        val framesDecoded: Int,
        val statuses: Map<ReadStatus, Int>,
    )

    /** Plays the stream through [channel] until the file is reconstructed or [maxFrames] pass. */
    private fun transfer(
        bytes: ByteArray,
        config: SenderConfig,
        channel: OpticalChannel,
        startFrame: Int = 0,
        maxFrames: Int = 400,
    ): Run {
        val sender = Sender(bytes, config)
        val reader = FrameReader()
        val decoder = StreamDecoder()
        val statuses = HashMap<ReadStatus, Int>()
        var displayed = 0
        var decodedFrames = 0
        var sequence = startFrame
        while (!decoder.isComplete && displayed < maxFrames) {
            val image = channel.capture(sender.display(sequence), sender.size)
            val result = reader.read(image)
            statuses[result.status] = (statuses[result.status] ?: 0) + 1
            if (result.isUsable) {
                decoder.accept(result.header!!, result.symbols)
                decodedFrames++
            }
            sequence++
            displayed++
        }
        return Run(decoder, displayed, decodedFrames, statuses)
    }

    /** 720p capture keeps the simulation affordable; ~6 camera pixels per cell at fill 0.8. */
    private fun indoor(seed: Int = 3) = OpticalChannel(
        outWidth = 1280, outHeight = 720, fill = 0.8, blurSigma = 0.7, noiseSigma = 3.5,
        vignette = 0.2, seed = seed,
    )

    @Test
    fun `256 KB transfers in under 45 seconds in balanced mode`() {
        val bytes = ByteArray(256 * 1024).also { Random(1).nextBytes(it) }
        val config = SenderConfig(paletteMode = PaletteMode.BALANCED, fps = 12)
        val run = transfer(bytes, config, indoor(), maxFrames = 300)

        val result = run.decoded.result
        assertNotNull("transfer did not complete: ${run.statuses}", result)
        assertTrue(result!!.sha256Verified)
        assertArrayEquals(bytes, result.bytes)

        val seconds = run.framesDisplayed / config.fps.toDouble()
        println(
            "256 KB: ${run.framesDisplayed} frames displayed, ${run.framesDecoded} decoded, " +
                "${"%.1f".format(seconds)}s at ${config.fps} fps, statuses=${run.statuses}",
        )
        assertTrue("took ${"%.1f".format(seconds)}s", seconds < 45.0)
    }

    @Test
    fun `transfers at 35 degrees off axis in robust mode`() {
        val bytes = ByteArray(24 * 1024).also { Random(2).nextBytes(it) }
        val config = SenderConfig(paletteMode = PaletteMode.ROBUST)
        val channel = OpticalChannel(
            outWidth = 1280, outHeight = 720, fill = 0.8, yawDegrees = 35.0, pitchDegrees = 8.0,
            blurSigma = 0.9, noiseSigma = 4.0, vignette = 0.25, seed = 5,
        )
        val run = transfer(bytes, config, channel, maxFrames = 200)
        assertNotNull("no transfer at 35 degrees: ${run.statuses}", run.decoded.result)
        assertArrayEquals(bytes, run.decoded.result!!.bytes)
        println("35 degrees robust: ${run.framesDisplayed} frames, statuses=${run.statuses}")
    }

    @Test
    fun `transfers when the receiver starts at an arbitrary point in the loop`() {
        val bytes = ByteArray(48 * 1024).also { Random(4).nextBytes(it) }
        val config = SenderConfig()
        val run = transfer(bytes, config, indoor(seed = 9), startFrame = 987_654, maxFrames = 200)
        assertNotNull("no transfer from mid-loop: ${run.statuses}", run.decoded.result)
        assertArrayEquals(bytes, run.decoded.result!!.bytes)
    }

    @Test
    fun `metadata appears before the file is complete`() {
        val bytes = ByteArray(64 * 1024).also { Random(6).nextBytes(it) }
        val sender = Sender(bytes, SenderConfig())
        val reader = FrameReader()
        val decoder = StreamDecoder()
        val channel = indoor(seed = 11)
        var sequence = 0
        var sawEarlyMetadata = false
        while (!decoder.isComplete && sequence < 200) {
            val result = reader.read(channel.capture(sender.display(sequence), sender.size))
            if (result.isUsable) decoder.accept(result.header!!, result.symbols)
            if (!decoder.isComplete && decoder.metadata != null) sawEarlyMetadata = true
            sequence++
        }
        assertNotNull(decoder.result)
        assertTrue("filename should surface before the transfer finishes", sawEarlyMetadata)
        assertEquals("holiday.bin", decoder.metadata!!.fileName)
    }

    @Test
    fun `torn captures never contribute wrong symbols`() {
        val bytes = ByteArray(32 * 1024).also { Random(8).nextBytes(it) }
        val config = SenderConfig()
        val sender = Sender(bytes, config)
        val reader = FrameReader()
        val channel = indoor(seed = 13)

        var torn = 0
        var accepted = 0
        val statuses = HashMap<ReadStatus, Int>()
        // Sweep the tear across the whole capture, including the band where the header survives
        // but the payload does not - the case the tear stripe exists for.
        for (step in 0 until 30) {
            val tearAt = 0.05 + step * 0.03
            val first = sender.display(step).copyOf()
            val second = sender.display(step + 1)
            val result = reader.read(channel.capture(first, sender.size, second, tearAt))
            statuses[result.status] = (statuses[result.status] ?: 0) + 1
            if (result.status == ReadStatus.TORN) torn++
            // Whatever survives a tear must still be genuinely correct.
            val expected = sender.encoder.symbolsFor(step) + sender.encoder.symbolsFor(step + 1)
            for (packet in result.symbols) {
                accepted++
                val match = expected.firstOrNull { it.esi == packet.esi }
                assertNotNull("accepted an ESI belonging to no displayed frame", match)
                assertArrayEquals(
                    "torn capture produced a corrupted symbol for ESI ${packet.esi}",
                    match!!.data, packet.data,
                )
            }
        }
        println("torn sweep: statuses=$statuses, $accepted symbols accepted and all correct")
        assertTrue("the tear stripe never fired", torn > 0)
    }

    @Test
    fun `a small file exports as one still frame and decodes from it`() {
        // The degenerate case the sender offers a single PNG for: symbols below K are systematic,
        // so frame 0 alone carries every source block.
        val bytes = ByteArray(1200).also { Random(23).nextBytes(it) }
        val config = SenderConfig(paletteMode = PaletteMode.ROBUST)
        val sender = Sender(bytes, config)
        assertTrue(
            "a ${bytes.size}-byte file should fit in one Robust frame",
            sender.prepared.fitsInOneFrame,
        )

        val reader = FrameReader()
        val decoder = StreamDecoder()
        val result = reader.read(indoor(seed = 29).capture(sender.display(0), sender.size))
        assertEquals(ReadStatus.OK, result.status)
        decoder.accept(result.header!!, result.symbols)

        assertNotNull("one still frame must be a complete transfer", decoder.result)
        assertTrue(decoder.result!!.sha256Verified)
        assertArrayEquals(bytes, decoder.result!!.bytes)
    }

    @Test
    fun `noise and unrelated images never produce a false success`() {
        val reader = FrameReader()
        val decoder = StreamDecoder()
        val rng = Random(15)
        for (i in 0 until 12) {
            val size = 512
            val pixels = IntArray(size * size) {
                (0xFF shl 24) or (rng.nextInt(0x1000000))
            }
            val image = OpticalChannel(outWidth = 640, outHeight = 480, seed = i).capture(pixels, size)
            val result = reader.read(image)
            if (result.isUsable) decoder.accept(result.header!!, result.symbols)
        }
        assertNull("random images must never yield a file", decoder.result)
    }

    @Test
    fun `a hopeless capture fails informatively rather than hanging`() {
        val bytes = ByteArray(32 * 1024).also { Random(17).nextBytes(it) }
        val config = SenderConfig(paletteMode = PaletteMode.DENSE)
        // Far too far away and far too blurred for a 12 bit/cell palette.
        val channel = OpticalChannel(
            outWidth = 640, outHeight = 480, fill = 0.3, blurSigma = 2.5, noiseSigma = 10.0, seed = 19,
        )
        val run = transfer(bytes, config, channel, maxFrames = 6)
        assertNull(run.decoded.result)
        val worst = run.statuses.maxByOrNull { it.value }!!.key
        val hints = Guidance.hints(worst, nl.tippie.pixeltransfer.core.vision.FrameDiagnostics(), 0, 60)
        println("hopeless capture: statuses=${run.statuses} hints=${hints.map { it.message }}")
        assertTrue("the user must be told something actionable", hints.isNotEmpty())
    }
}
