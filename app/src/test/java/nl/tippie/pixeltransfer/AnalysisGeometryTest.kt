package nl.tippie.pixeltransfer

import nl.tippie.pixeltransfer.receiver.AnalysisGeometry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rotation mapping is easy to get subtly wrong and impossible to notice without a device:
 * a mirrored overlay still looks plausible until you try to line the phones up with it.
 */
class AnalysisGeometryTest {

    private val width = 1920
    private val height = 1080

    private fun normalise(x: Double, y: Double, rotation: Int): Pair<Float, Float> {
        val out = FloatArray(2)
        AnalysisGeometry.normalise(x, y, width, height, rotation, out)
        return out[0] to out[1]
    }

    @Test
    fun `upright dimensions swap on quarter turns`() {
        assertEquals(1920, AnalysisGeometry.uprightWidth(width, height, 0))
        assertEquals(1080, AnalysisGeometry.uprightHeight(width, height, 0))
        assertEquals(1080, AnalysisGeometry.uprightWidth(width, height, 90))
        assertEquals(1920, AnalysisGeometry.uprightHeight(width, height, 90))
        assertEquals(1920, AnalysisGeometry.uprightWidth(width, height, 180))
        assertEquals("a full turn is the same as none", 1920, AnalysisGeometry.uprightWidth(width, height, 360))
    }

    @Test
    fun `no rotation is a plain normalisation`() {
        val (x, y) = normalise(960.0, 540.0, 0)
        assertEquals(0.5f, x, 1e-3f)
        assertEquals(0.5f, y, 1e-3f)
    }

    @Test
    fun `the centre stays the centre under every rotation`() {
        for (rotation in intArrayOf(0, 90, 180, 270)) {
            val (x, y) = normalise(width / 2.0, height / 2.0, rotation)
            assertEquals("rotation $rotation", 0.5f, x, 1e-3f)
            assertEquals("rotation $rotation", 0.5f, y, 1e-3f)
        }
    }

    @Test
    fun `a quarter turn clockwise moves the top-left corner to the top-right`() {
        val (x, y) = normalise(0.0, 0.0, 90)
        assertEquals(1f, x, 1e-3f)
        assertEquals(0f, y, 1e-3f)
    }

    @Test
    fun `rotations are distinct and cover the four corners`() {
        val mapped = intArrayOf(0, 90, 180, 270).map { normalise(0.0, 0.0, it) }.toSet()
        assertEquals("each rotation must send the origin somewhere different", 4, mapped.size)
    }

    @Test
    fun `results stay inside the unit square`() {
        for (rotation in intArrayOf(0, 90, 180, 270)) {
            for (point in listOf(0.0 to 0.0, width - 1.0 to 0.0, 0.0 to height - 1.0, width - 1.0 to height - 1.0)) {
                val (x, y) = normalise(point.first, point.second, rotation)
                assert(x in 0f..1f && y in 0f..1f) { "rotation $rotation produced ($x, $y)" }
            }
        }
    }
}
