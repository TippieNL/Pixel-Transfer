package nl.tippie.pixeltransfer.core

import nl.tippie.pixeltransfer.core.codec.RsCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ReedSolomonTest {

    @Test
    fun `round trip without errors`() {
        val rng = Random(1)
        for (nsym in listOf(2, 16, 32, 52, 80, 110)) {
            val codec = RsCodec(nsym)
            for (len in listOf(1, 5, 100, 203, 255, 1000, 4096)) {
                val data = ByteArray(len).also { rng.nextBytes(it) }
                val encoded = codec.encode(data)
                assertEquals(codec.encodedSize(len), encoded.size)
                val decoded = codec.decode(encoded, len)
                assertTrue("nsym=$nsym len=$len", decoded.allOk)
                assertArrayEquals(data, decoded.data)
            }
        }
    }

    @Test
    fun `corrects up to nsym over two errors per block`() {
        val rng = Random(7)
        val nsym = 52
        val codec = RsCodec(nsym)
        val dataPerBlock = 255 - nsym
        repeat(50) {
            val data = ByteArray(dataPerBlock * 3).also { rng.nextBytes(it) }
            val encoded = codec.encode(data)
            // Corrupt exactly nsym/2 bytes in every block.
            for (b in 0 until 3) {
                val base = b * 255
                val positions = (0 until 255).shuffled(rng).take(nsym / 2)
                for (p in positions) encoded[base + p] = (encoded[base + p].toInt() xor 0xFF).toByte()
            }
            val decoded = codec.decode(encoded, data.size)
            assertTrue(decoded.allOk)
            assertArrayEquals(data, decoded.data)
        }
    }

    @Test
    fun `flags blocks beyond repair instead of returning garbage`() {
        val rng = Random(11)
        val nsym = 32
        val codec = RsCodec(nsym)
        val data = ByteArray(400).also { rng.nextBytes(it) }
        val encoded = codec.encode(data)
        // Block 0 gets far more errors than it can correct.
        for (p in 0 until 60) encoded[p] = (encoded[p].toInt() xor 0x5A).toByte()
        val decoded = codec.decode(encoded, data.size)
        assertTrue("block 0 must be flagged bad", !decoded.blockOk[0])
        assertTrue("block 1 must survive", decoded.blockOk[1])
    }
}
