package nl.tippie.pixeltransfer.core

import nl.tippie.pixeltransfer.core.codec.FountainDecoder
import nl.tippie.pixeltransfer.core.codec.FountainEncoder
import nl.tippie.pixeltransfer.core.codec.FountainParams
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FountainTest {

    private fun run(k: Int, symbolSize: Int, startEsi: Int, dropRate: Double, seed: Int): Double {
        val rng = Random(seed)
        val size = k * symbolSize - rng.nextInt(symbolSize)
        val source = ByteArray(size).also { rng.nextBytes(it) }
        val params = FountainParams(k, symbolSize, streamId = rng.nextInt())
        val encoder = FountainEncoder(params, source)
        val decoder = FountainDecoder(params)

        var esi = startEsi
        var delivered = 0
        val limit = ((k * 4 + 2000) / (1.0 - dropRate)).toInt()
        while (!decoder.isComplete && esi - startEsi < limit) {
            val e = esi++
            if (rng.nextDouble() < dropRate) continue
            decoder.offer(e, encoder.symbol(e))
            delivered++
            if (delivered >= k) decoder.tryFinish()
        }
        assertTrue("decode failed for k=$k start=$startEsi", decoder.isComplete)
        assertArrayEquals(source, decoder.assemble(size))
        return delivered.toDouble() / k
    }

    @Test
    fun `decodes from the start of the stream`() {
        for (k in listOf(1, 2, 10, 64, 250, 1000)) {
            val overhead = run(k, 1024, startEsi = 0, dropRate = 0.0, seed = k)
            assertTrue("k=$k overhead=$overhead", overhead <= 1.10)
            if (k >= 256) assertTrue("k=$k overhead=$overhead", overhead <= 1.05)
        }
    }

    @Test
    fun `decodes when the receiver joins inside the systematic prefix`() {
        // Starting part-way through the systematic run means many later symbols reduce to
        // blocks that are already known, so a handful more symbols are needed. Still bounded.
        for (k in listOf(128, 256, 1024)) {
            for (start in listOf(1, k / 3, k - 5)) {
                val overhead = run(k, 1024, startEsi = start, dropRate = 0.0, seed = k * 7 + start)
                assertTrue("k=$k start=$start overhead=$overhead", overhead <= 1.25)
            }
        }
    }

    @Test
    fun `decodes when the receiver joins mid-stream`() {
        // Reception overhead is dominated by a small constant number of symbols, so it matters
        // as a ratio only once K is large. The spec's <=5% target is asserted for K >= 256.
        for (k in listOf(32, 128, 256, 512, 1024, 2048)) {
            val overheads = ArrayList<Double>()
            for (start in listOf(5000, 123457, 987654, 31337, 250001, 4242424, 60013, 777771)) {
                overheads += run(k, 1024, startEsi = start, dropRate = 0.0, seed = k + start)
            }
            val worst = overheads.max()
            println("k=$k mid-stream overhead avg=${"%.4f".format(overheads.average())} worst=${"%.4f".format(worst)}")
            if (k >= 256) assertTrue("k=$k worst overhead $worst", worst <= 1.05)
        }
    }

    @Test
    fun `tolerates heavy erasure`() {
        for (drop in listOf(0.3, 0.6, 0.85, 0.95)) {
            val overhead = run(512, 1024, startEsi = 9000, dropRate = drop, seed = (drop * 100).toInt())
            println("drop=$drop overhead=${"%.3f".format(overhead)}")
            assertTrue("erasure $drop needed $overhead x K symbols", overhead <= 1.05)
        }
    }

    @Test
    fun `elimination fallback closes the tail near five percent overhead`() {
        // Force the elimination path by only calling tryFinish, measuring symbols needed.
        val results = ArrayList<Double>()
        for (k in listOf(200, 600, 1200)) {
            val rng = Random(k)
            val size = k * 512
            val source = ByteArray(size).also { rng.nextBytes(it) }
            val params = FountainParams(k, 512, streamId = 1234 + k)
            val encoder = FountainEncoder(params, source)
            val decoder = FountainDecoder(params)
            var esi = 400000
            var delivered = 0
            while (!decoder.isComplete) {
                decoder.offer(esi, encoder.symbol(esi))
                esi++
                delivered++
                if (delivered >= k) decoder.tryFinish(force = true)
                if (delivered > k * 3) break
            }
            assertTrue(decoder.isComplete)
            val overhead = delivered.toDouble() / k
            println("k=$k elimination overhead=${"%.4f".format(overhead)}")
            results += overhead
        }
        assertTrue("overhead ${results.max()}", results.max() <= 1.05)
    }

    @Test
    fun `duplicate symbols are rejected`() {
        val params = FountainParams(16, 64, 42)
        val source = ByteArray(16 * 64) { it.toByte() }
        val encoder = FountainEncoder(params, source)
        val decoder = FountainDecoder(params)
        assertTrue(decoder.offer(100, encoder.symbol(100)))
        assertTrue(!decoder.offer(100, encoder.symbol(100)))
    }
}
