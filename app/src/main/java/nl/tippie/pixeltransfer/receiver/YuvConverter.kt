package nl.tippie.pixeltransfer.receiver

import androidx.camera.core.ImageProxy
import nl.tippie.pixeltransfer.core.vision.Yuv420CameraImage

/**
 * Copies a CameraX `ImageProxy` into the decoder's image type, reusing its buffers between
 * frames.
 *
 * The frame is deliberately *not* rotated. Rotating 1080p costs several milliseconds per frame
 * and buys nothing: the corner-id patches resolve rotation and mirroring during rectification, so
 * the decoder is indifferent to which way up the phone is held.
 */
class YuvConverter {

    private var luma: ByteArray? = null
    private var chromaU: ByteArray? = null
    private var chromaV: ByteArray? = null

    fun convert(proxy: ImageProxy): Yuv420CameraImage {
        val planes = proxy.planes
        require(planes.size >= 3) { "expected a YUV_420_888 image" }

        val y = copyPlane(planes[0].buffer, luma).also { luma = it }
        val u = copyPlane(planes[1].buffer, chromaU).also { chromaU = it }
        val v = copyPlane(planes[2].buffer, chromaV).also { chromaV = it }

        return Yuv420CameraImage(
            width = proxy.width,
            height = proxy.height,
            y = y,
            u = u,
            v = v,
            yRowStride = planes[0].rowStride,
            uvRowStride = planes[1].rowStride,
            uvPixelStride = planes[1].pixelStride,
        )
    }

    private fun copyPlane(buffer: java.nio.ByteBuffer, existing: ByteArray?): ByteArray {
        buffer.rewind()
        val size = buffer.remaining()
        val target = if (existing != null && existing.size == size) existing else ByteArray(size)
        buffer.get(target)
        buffer.rewind()
        return target
    }
}

/**
 * Maps a point in the analysis image onto the upright, normalised (0..1) space the preview
 * overlay draws in.
 */
object AnalysisGeometry {

    fun uprightWidth(width: Int, height: Int, rotationDegrees: Int): Int =
        if (rotationDegrees % 180 == 0) width else height

    fun uprightHeight(width: Int, height: Int, rotationDegrees: Int): Int =
        if (rotationDegrees % 180 == 0) height else width

    /** Returns normalised x, y in the upright frame. */
    fun normalise(
        x: Double,
        y: Double,
        width: Int,
        height: Int,
        rotationDegrees: Int,
        out: FloatArray,
    ) {
        val (rx, ry) = when (((rotationDegrees % 360) + 360) % 360) {
            90 -> (height - y) to x
            180 -> (width - x) to (height - y)
            270 -> y to (width - x)
            else -> x to y
        }
        val w = uprightWidth(width, height, rotationDegrees).toDouble()
        val h = uprightHeight(width, height, rotationDegrees).toDouble()
        out[0] = (rx / w).toFloat().coerceIn(0f, 1f)
        out[1] = (ry / h).toFloat().coerceIn(0f, 1f)
    }
}
