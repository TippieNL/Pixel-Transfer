package nl.tippie.pixeltransfer.core

import nl.tippie.pixeltransfer.core.frame.EccLevel
import nl.tippie.pixeltransfer.core.frame.FrameCodec
import nl.tippie.pixeltransfer.core.frame.Palette
import nl.tippie.pixeltransfer.core.frame.PaletteMode
import nl.tippie.pixeltransfer.core.pipeline.SenderConfig
import nl.tippie.pixeltransfer.core.pipeline.StreamDecoder
import nl.tippie.pixeltransfer.core.pipeline.StreamEncoder
import nl.tippie.pixeltransfer.core.pipeline.TransferPreparation
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Exercises the whole coding stack with a perfect optical channel: cells are read back exactly
 * as rendered. Camera impairments are covered separately in OpticalChannelTest.
 */
class CodecRoundTripTest {

    /** Reads a rendered frame back without any image processing. */
    private fun readFrame(encoder: StreamEncoder, sequence: Int, cells: IntArray) =
        readCells(encoder, cells)

    private fun readCells(encoder: StreamEncoder, cells: IntArray): Pair<IntArray, IntArray> {
        val layout = encoder.layout
        val plan = encoder.plan
        val headerSymbols = IntArray(plan.headerCellCount) {
            Palette.symbolFromArgb(PaletteMode.ROBUST, cells[layout.dataCells[it]])
        }
        val payloadSymbols = IntArray(plan.payloadCellCount) {
            Palette.symbolFromArgb(plan.paletteMode, cells[layout.dataCells[plan.headerCellCount + it]])
        }
        return headerSymbols to payloadSymbols
    }

    private fun transferFile(
        bytes: ByteArray,
        config: SenderConfig,
        startFrame: Int = 0,
        dropEvery: Int = 0,
    ): StreamDecoder {
        val prepared = TransferPreparation.prepare(
            "photo.bin", "application/octet-stream", bytes, config, Random(99),
        )
        val encoder = StreamEncoder(prepared)
        val codec = FrameCodec(prepared.plan)
        val decoder = StreamDecoder()

        var seq = startFrame
        val limit = startFrame + prepared.framesPerLoop * 4 + 50
        while (!decoder.isComplete && seq < limit) {
            val n = seq++
            if (dropEvery > 0 && n % dropEvery == 0) continue
            val cells = encoder.renderCells(n)
            val (headerSymbols, payloadSymbols) = readFrame(encoder, n, cells)
            val header = FrameCodec.decodeHeader(encoder.layout, headerSymbols)
            assertNotNull("header must decode on a clean channel at frame $n", header)
            val payload = codec.decodePayload(payloadSymbols)
            assertTrue("footer must validate on a clean channel", payload.footerValid)
            assertEquals(prepared.plan.symbolsPerFrame, payload.symbols.size)
            decoder.accept(header!!, payload.symbols)
        }
        return decoder
    }

    @Test
    fun `default configuration matches the reference geometry`() {
        val config = SenderConfig()
        val prepared = TransferPreparation.prepare(
            "x.bin", "application/octet-stream", ByteArray(256 * 1024).also { Random(3).nextBytes(it) }, config,
        )
        assertEquals(768 + 2 * 2 * 8, config.imageSizePx)
        println(
            "grid=${config.gridCells} cells/frame=${prepared.plan.layout.dataCells.size} " +
                "payloadBytes=${prepared.plan.payloadDataSize} symbols/frame=${prepared.symbolsPerFrame} " +
                "bytes/frame=${prepared.bytesPerFrame} framesPerLoop=${prepared.framesPerLoop} " +
                "ideal=${"%.1f".format(prepared.idealTransferSeconds())}s",
        )
        // Spec: roughly 4-5 KB of payload per frame in Balanced mode.
        assertTrue("bytes/frame ${prepared.bytesPerFrame}", prepared.bytesPerFrame in 4000..5200)
        // Spec: 256 KB in under 45 s at 12 fps.
        assertTrue(
            "ideal ${prepared.idealTransferSeconds()}s",
            prepared.idealTransferSeconds() < 45.0,
        )
    }

    @Test
    fun `round trips a compressible file`() {
        val bytes = ByteArray(120_000) { (it % 61).toByte() }
        val decoder = transferFile(bytes, SenderConfig())
        val result = decoder.result
        assertNotNull(result)
        assertTrue(result!!.sha256Verified)
        assertArrayEquals(bytes, result.bytes)
        assertEquals("photo.bin", result.metadata.fileName)
        assertTrue(result.metadata.compressionRatio < 0.2)
    }

    @Test
    fun `round trips incompressible data in every palette mode`() {
        val bytes = ByteArray(80_000).also { Random(4).nextBytes(it) }
        for (mode in PaletteMode.entries) {
            val config = SenderConfig(paletteMode = mode, gridCells = 96)
            val decoder = transferFile(bytes, config)
            assertNotNull("mode $mode", decoder.result)
            assertArrayEquals(bytes, decoder.result!!.bytes)
        }
    }

    @Test
    fun `round trips at every ecc level and cell size`() {
        val bytes = ByteArray(40_000).also { Random(5).nextBytes(it) }
        for (ecc in EccLevel.entries) {
            val decoder = transferFile(bytes, SenderConfig(eccLevel = ecc))
            assertNotNull("ecc $ecc", decoder.result)
            assertArrayEquals(bytes, decoder.result!!.bytes)
        }
        for (cell in listOf(4, 8, 16)) {
            val config = SenderConfig(cellSizePx = cell)
            val decoder = transferFile(bytes, config)
            assertNotNull("cell $cell", decoder.result)
        }
    }

    @Test
    fun `receiver joining at an arbitrary point still completes`() {
        val bytes = ByteArray(60_000).also { Random(6).nextBytes(it) }
        for (start in listOf(1, 37, 5000, 999_999)) {
            val decoder = transferFile(bytes, SenderConfig(), startFrame = start)
            assertNotNull("start $start", decoder.result)
            assertArrayEquals(bytes, decoder.result!!.bytes)
        }
    }

    @Test
    fun `dropped frames are simply skipped`() {
        val bytes = ByteArray(60_000).also { Random(7).nextBytes(it) }
        val decoder = transferFile(bytes, SenderConfig(), startFrame = 400, dropEvery = 3)
        assertNotNull(decoder.result)
        assertArrayEquals(bytes, decoder.result!!.bytes)
    }

    @Test
    fun `frames from another stream are rejected`() {
        val a = ByteArray(20_000).also { Random(8).nextBytes(it) }
        val b = ByteArray(20_000).also { Random(9).nextBytes(it) }
        val config = SenderConfig()
        val ta = TransferPreparation.prepare("a.bin", "application/octet-stream", a, config, Random(1))
        val tb = TransferPreparation.prepare("b.bin", "application/octet-stream", b, config, Random(2))
        val ea = StreamEncoder(ta)
        val eb = StreamEncoder(tb)
        val ca = FrameCodec(ta.plan)
        val cb = FrameCodec(tb.plan)
        val decoder = StreamDecoder()

        // Prime with stream A, then interleave a single foreign frame per two good ones.
        var seq = 0
        while (!decoder.isComplete && seq < ta.framesPerLoop * 3) {
            val cells = ea.renderCells(seq)
            val (h, p) = readCells(ea, cells)
            decoder.accept(FrameCodec.decodeHeader(ea.layout, h)!!, ca.decodePayload(p).symbols)
            if (seq % 2 == 0) {
                val fc = eb.renderCells(seq)
                val (fh, fp) = readCells(eb, fc)
                decoder.accept(FrameCodec.decodeHeader(eb.layout, fh)!!, cb.decodePayload(fp).symbols)
            }
            seq++
        }
        assertNotNull(decoder.result)
        assertArrayEquals(a, decoder.result!!.bytes)
        assertTrue(decoder.framesFromOtherStream > 0)
    }

    @Test
    fun `oversized files are refused`() {
        val config = SenderConfig(maxFileSizeBytes = 1000)
        try {
            TransferPreparation.prepare("big.bin", "application/octet-stream", ByteArray(2000), config)
            throw AssertionError("expected TooLarge")
        } catch (e: TransferPreparation.TooLarge) {
            assertEquals(2000, e.size)
        }
    }

    @Test
    fun `already compressed content is not deflated again`() {
        val jpegish = ByteArray(30_000).also { Random(10).nextBytes(it) }
        val prepared = TransferPreparation.prepare("p.jpg", "image/jpeg", jpegish, SenderConfig())
        assertEquals(
            nl.tippie.pixeltransfer.core.codec.CompressionType.NONE,
            prepared.metadata.compression,
        )
    }

    @Test
    fun `a corrupted header does not decode`() {
        val prepared = TransferPreparation.prepare(
            "x.bin", "application/octet-stream", ByteArray(5000), SenderConfig(),
        )
        val encoder = StreamEncoder(prepared)
        val cells = encoder.renderCells(0)
        // Wreck far more header cells than the RS parity can absorb.
        for (i in 0 until 60) cells[encoder.layout.dataCells[i]] = Palette.LIGHT
        val (h, _) = readCells(encoder, cells)
        assertNull(FrameCodec.decodeHeader(encoder.layout, h))
    }
}
