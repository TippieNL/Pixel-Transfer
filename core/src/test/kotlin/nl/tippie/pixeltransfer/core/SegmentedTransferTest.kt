package nl.tippie.pixeltransfer.core

import nl.tippie.pixeltransfer.core.codec.FileMetadata
import nl.tippie.pixeltransfer.core.frame.FrameCodec
import nl.tippie.pixeltransfer.core.frame.Palette
import nl.tippie.pixeltransfer.core.frame.PaletteMode
import nl.tippie.pixeltransfer.core.pipeline.ByteArraySegmentSink
import nl.tippie.pixeltransfer.core.pipeline.SegmentPlan
import nl.tippie.pixeltransfer.core.pipeline.SenderConfig
import nl.tippie.pixeltransfer.core.pipeline.StreamDecoder
import nl.tippie.pixeltransfer.core.pipeline.StreamEncoder
import nl.tippie.pixeltransfer.core.pipeline.TransferPreparation
import nl.tippie.pixeltransfer.core.pipeline.TransferSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.min
import kotlin.random.Random

/**
 * Transfers larger than a couple of megabytes are cut into independently coded segments. These
 * tests cover the paths that only exist once there is more than one.
 */
class SegmentedTransferTest {

    /** Generates bytes on demand so a very large source costs no memory. */
    private class PatternSource(override val size: Long) : TransferSource {
        override fun read(offset: Long, into: ByteArray, length: Int): Int {
            if (offset >= size) return 0
            val count = min(length.toLong(), size - offset).toInt()
            for (i in 0 until count) {
                val position = offset + i
                into[i] = (position * 2654435761L shr 13).toByte()
            }
            return count
        }
    }

    private fun bytesOf(source: TransferSource): ByteArray {
        val out = ByteArray(source.size.toInt())
        source.read(0, out, out.size)
        return out
    }

    /** Reads a rendered frame back without any image processing. */
    private fun readFrame(encoder: StreamEncoder, cells: IntArray): Pair<IntArray, IntArray> {
        val layout = encoder.layout
        val plan = encoder.plan
        val header = IntArray(plan.headerCellCount) {
            Palette.symbolFromArgb(PaletteMode.ROBUST, cells[layout.dataCells[it]])
        }
        val payload = IntArray(plan.payloadCellCount) {
            Palette.symbolFromArgb(plan.paletteMode, cells[layout.dataCells[plan.headerCellCount + it]])
        }
        return header to payload
    }

    @Test
    fun `a multi-segment file round trips and verifies`() {
        val bytes = ByteArray(5 * 1024 * 1024).also { Random(11).nextBytes(it) }
        val config = SenderConfig(segmentSizeBytes = 1024 * 1024)
        val prepared = TransferPreparation.prepare(
            "clip.mp4", "video/mp4", bytes, config, Random(3),
        )
        assertTrue("expected several segments, got ${prepared.segments.count}", prepared.segments.count >= 5)

        val encoder = StreamEncoder(prepared)
        val codec = FrameCodec(prepared.plan)
        val decoder = StreamDecoder()

        var sequence = 0
        val limit = encoder.framesPerLoop * 3
        while (!decoder.isComplete && sequence < limit) {
            val (h, p) = readFrame(encoder, encoder.renderCells(sequence))
            val header = FrameCodec.decodeHeader(encoder.layout, h)!!
            decoder.accept(header, codec.decodePayload(p).symbols)
            sequence++
        }

        val result = decoder.result
        assertNotNull("multi-segment transfer did not complete in $sequence frames", result)
        assertTrue(result!!.sha256Verified)
        assertArrayEquals(bytes, result.bytes)
        assertEquals("clip.mp4", result.metadata.fileName)
        println(
            "5 MB in ${prepared.segments.count} segments: completed after $sequence frames " +
                "(one pass is ${encoder.framesPerLoop})",
        )
    }

    @Test
    fun `completed segments stream out to a sink without buffering the file`() {
        val bytes = ByteArray(3 * 1024 * 1024).also { Random(12).nextBytes(it) }
        val config = SenderConfig(segmentSizeBytes = 512 * 1024)
        val prepared = TransferPreparation.prepare(
            "photo.jpg", "image/jpeg", bytes, config, Random(4),
        )
        val sink = ByteArraySegmentSink(prepared.totalEncodedSize.toInt())
        val decoder = StreamDecoder(sink = sink)
        val encoder = StreamEncoder(prepared)
        val codec = FrameCodec(prepared.plan)

        var sequence = 0
        while (!decoder.isComplete && sequence < encoder.framesPerLoop * 3) {
            val (h, p) = readFrame(encoder, encoder.renderCells(sequence))
            val header = FrameCodec.decodeHeader(encoder.layout, h)!!
            decoder.accept(header, codec.decodePayload(p).symbols)
            sequence++
        }

        assertTrue("sink-backed transfer did not complete", decoder.allSegmentsRecovered)

        // The sink holds the coded object: metadata block followed by the payload.
        val parsed = FileMetadata.decode(sink.bytes)
        assertNotNull(parsed)
        val payload = sink.bytes.copyOfRange(
            parsed!!.payloadOffset,
            parsed.payloadOffset + parsed.metadata.compressedSize,
        )
        assertArrayEquals(bytes, payload)
        assertArrayEquals(parsed.metadata.sha256, FileMetadata.sha256(payload))
    }

    @Test
    fun `a receiver joining mid-transfer still completes`() {
        val bytes = ByteArray(4 * 1024 * 1024).also { Random(13).nextBytes(it) }
        val config = SenderConfig(segmentSizeBytes = 1024 * 1024)
        val prepared = TransferPreparation.prepare(
            "clip.mp4", "video/mp4", bytes, config, Random(5),
        )
        val encoder = StreamEncoder(prepared)
        val codec = FrameCodec(prepared.plan)
        val decoder = StreamDecoder()

        // Start part-way through the third segment and drop one frame in six.
        var sequence = encoder.framesPerLoop / 2 + 17
        val rng = Random(99)
        val limit = sequence + encoder.framesPerLoop * 4
        while (!decoder.isComplete && sequence < limit) {
            if (rng.nextInt(6) != 0) {
                val (h, p) = readFrame(encoder, encoder.renderCells(sequence))
                val header = FrameCodec.decodeHeader(encoder.layout, h)!!
                decoder.accept(header, codec.decodePayload(p).symbols)
            }
            sequence++
        }
        assertNotNull("mid-transfer join did not complete", decoder.result)
        assertArrayEquals(bytes, decoder.result!!.bytes)
    }

    @Test
    fun `a 150 MB video is addressable and prepares without holding the file`() {
        val size = 150L * 1024 * 1024
        val source = PatternSource(size)
        val config = SenderConfig(maxFileSizeBytes = 150 * 1024 * 1024)

        val before = usedHeapBytes()
        val prepared = TransferPreparation.prepare("holiday.mp4", "video/mp4", source, config, Random(7))
        val after = usedHeapBytes()

        assertEquals(size.toInt(), prepared.metadata.originalSize)
        assertTrue("a video must not be deflated", prepared.metadata.compression.name == "NONE")
        assertTrue(
            "segment count ${prepared.segments.count} must fit the header",
            prepared.segments.count <= SegmentPlan.MAX_SEGMENTS,
        )
        assertTrue(
            "blocks per segment ${prepared.blocksIn(0)} must fit the header's two bytes",
            prepared.blocksIn(0) <= 65535,
        )
        // Preparing must not pull the file into memory; allow generous slack for test noise.
        assertTrue(
            "preparation allocated ${(after - before) / (1024 * 1024)} MB",
            after - before < 32L * 1024 * 1024,
        )

        val minutes = prepared.estimatedTransferSeconds() / 60.0
        println(
            "150 MB video: ${prepared.segments.count} segments of ${config.segmentSizeBytes / 1024} KiB, " +
                "${prepared.blocksIn(0)} blocks each, ${prepared.bytesPerFrame} B/frame, " +
                "one pass = ${prepared.framesPerLoop} frames = ${"%.0f".format(minutes)} min at ${config.fps} fps",
        )
        assertTrue("a 150 MB transfer should be hours at most", minutes < 240)

        // What the available levers actually buy, so the UI can quote real numbers.
        for (fps in listOf(12, 20)) {
            for (grid in listOf(96, 128)) {
                for (mode in listOf(PaletteMode.BALANCED, PaletteMode.DENSE)) {
                    val tuned = SenderConfig(
                        maxFileSizeBytes = 150 * 1024 * 1024,
                        fps = fps, gridCells = grid, paletteMode = mode,
                        cellSizePx = (1080 / (grid + 4)).coerceIn(4, 16),
                    )
                    val t = TransferPreparation.prepare(
                        "holiday.mp4", "video/mp4", PatternSource(size), tuned, Random(7),
                    )
                    println(
                        "  ${mode.label} grid=$grid ${fps}fps: ${t.bytesPerFrame} B/frame, " +
                            "${"%.0f".format(t.estimatedTransferSeconds() / 60.0)} min",
                    )
                }
            }
        }
    }

    @Test
    fun `the sender cycles through every segment`() {
        val bytes = ByteArray(3 * 1024 * 1024).also { Random(14).nextBytes(it) }
        val config = SenderConfig(segmentSizeBytes = 512 * 1024)
        val prepared = TransferPreparation.prepare("a.bin", "application/octet-stream", bytes, config, Random(6))
        val encoder = StreamEncoder(prepared)

        val seen = HashSet<Int>()
        for (frame in 0 until encoder.framesPerLoop) seen.add(encoder.segmentOf(frame))
        assertEquals("one pass must visit every segment", prepared.segments.count, seen.size)

        // And the schedule must repeat, so a receiver that missed a segment gets another chance.
        assertEquals(encoder.segmentOf(0), encoder.segmentOf(encoder.framesPerLoop))
    }

    private fun usedHeapBytes(): Long {
        System.gc()
        Thread.sleep(50)
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }
}
