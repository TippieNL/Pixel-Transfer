package nl.tippie.pixeltransfer.sender

import android.graphics.Bitmap
import nl.tippie.pixeltransfer.core.frame.FrameRasterizer
import nl.tippie.pixeltransfer.core.pipeline.SenderConfig
import nl.tippie.pixeltransfer.core.pipeline.StreamEncoder

/**
 * Turns a frame sequence number into a bitmap.
 *
 * The intermediate pixel buffer is reused across frames: at 12 fps a fresh 800x800 int array per
 * frame would be 30 MB/s of garbage, which is enough to make the collector visible as a stutter
 * in the stream - and a stutter is a dropped frame at the receiver.
 */
class FrameBitmapRenderer(
    private val encoder: StreamEncoder,
    private val config: SenderConfig,
) {

    val imageSize: Int = FrameRasterizer.imageSize(
        config.gridCells, config.cellSizePx, SenderConfig.QUIET_CELLS,
    )

    private val pixels = IntArray(imageSize * imageSize)

    fun newBitmap(): Bitmap = Bitmap.createBitmap(imageSize, imageSize, Bitmap.Config.ARGB_8888)

    /** Renders frame [sequence] into [target], which must be [imageSize] square. */
    fun render(sequence: Int, target: Bitmap) {
        FrameRasterizer.rasterize(
            encoder.renderCells(sequence),
            config.gridCells,
            config.cellSizePx,
            SenderConfig.QUIET_CELLS,
            pixels,
        )
        target.setPixels(pixels, 0, imageSize, 0, 0, imageSize, imageSize)
    }

    /** Renders into a freshly allocated bitmap. Used by the exporter, not by playback. */
    fun renderToNewBitmap(sequence: Int): Bitmap = newBitmap().also { render(sequence, it) }
}
