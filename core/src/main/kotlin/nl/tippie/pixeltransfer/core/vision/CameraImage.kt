package nl.tippie.pixeltransfer.core.vision

/**
 * A captured frame, addressed in pixels.
 *
 * The receiver works in the camera's native YUV: converting to JPEG and back would add a second
 * round of chroma subsampling and blocking artefacts on top of the ones the display->lens path
 * already imposes, and dense palette modes do not survive that.
 */
abstract class CameraImage(val width: Int, val height: Int) {

    /** Luma 0..255. */
    abstract fun lumaAt(x: Int, y: Int): Int

    /** Packed 0xRRGGBB. */
    abstract fun rgbAt(x: Int, y: Int): Int

    fun inBounds(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height

    fun clampX(x: Int): Int = x.coerceIn(0, width - 1)
    fun clampY(y: Int): Int = y.coerceIn(0, height - 1)

    /** Bilinear luma sample at a sub-pixel position. */
    fun lumaBilinear(x: Double, y: Double): Int {
        val x0 = kotlin.math.floor(x).toInt()
        val y0 = kotlin.math.floor(y).toInt()
        val fx = x - x0
        val fy = y - y0
        val a = lumaAt(clampX(x0), clampY(y0))
        val b = lumaAt(clampX(x0 + 1), clampY(y0))
        val c = lumaAt(clampX(x0), clampY(y0 + 1))
        val d = lumaAt(clampX(x0 + 1), clampY(y0 + 1))
        val top = a + (b - a) * fx
        val bottom = c + (d - c) * fx
        return (top + (bottom - top) * fy).toInt().coerceIn(0, 255)
    }

    /** Materialises a dense luma plane for the detector. */
    open fun lumaPlane(): ByteArray {
        val out = ByteArray(width * height)
        var i = 0
        for (y in 0 until height) for (x in 0 until width) out[i++] = lumaAt(x, y).toByte()
        return out
    }
}

/** Packed-RGB image; used by the exporter, the tests and the synthetic channel model. */
class RgbCameraImage(width: Int, height: Int, val pixels: IntArray) : CameraImage(width, height) {

    init {
        require(pixels.size >= width * height) { "pixel buffer too small" }
    }

    override fun lumaAt(x: Int, y: Int): Int {
        val p = pixels[y * width + x]
        val r = (p ushr 16) and 0xFF
        val g = (p ushr 8) and 0xFF
        val b = p and 0xFF
        return (r * 77 + g * 151 + b * 28) shr 8
    }

    override fun rgbAt(x: Int, y: Int): Int = pixels[y * width + x] and 0xFFFFFF
}

/**
 * CameraX `ImageAnalysis` hands over YUV_420_888 planes. Chroma is half resolution in both axes,
 * which is exactly why a cell is never smaller than 4x4 screen pixels and is always sampled by
 * voting over several pixels.
 */
class Yuv420CameraImage(
    width: Int,
    height: Int,
    private val y: ByteArray,
    private val u: ByteArray,
    private val v: ByteArray,
    private val yRowStride: Int,
    private val uvRowStride: Int,
    private val uvPixelStride: Int,
) : CameraImage(width, height) {

    override fun lumaAt(x: Int, y0: Int): Int = y[y0 * yRowStride + x].toInt() and 0xFF

    override fun rgbAt(x: Int, y0: Int): Int {
        val luma = lumaAt(x, y0)
        val cx = x / 2
        val cy = y0 / 2
        val idx = cy * uvRowStride + cx * uvPixelStride
        val cb = (u[idx].toInt() and 0xFF) - 128
        val cr = (v[idx].toInt() and 0xFF) - 128
        // Full-range BT.601, which is what Android camera pipelines emit for still analysis.
        val r = (luma + 1.402 * cr).toInt().coerceIn(0, 255)
        val g = (luma - 0.344136 * cb - 0.714136 * cr).toInt().coerceIn(0, 255)
        val b = (luma + 1.772 * cb).toInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
    }

    override fun lumaPlane(): ByteArray {
        if (yRowStride == width) return y
        val out = ByteArray(width * height)
        for (row in 0 until height) {
            System.arraycopy(y, row * yRowStride, out, row * width, width)
        }
        return out
    }
}
