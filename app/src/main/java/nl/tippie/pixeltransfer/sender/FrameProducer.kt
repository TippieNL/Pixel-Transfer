package nl.tippie.pixeltransfer.sender

import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A rendered frame and the bitmap holding it. */
class RenderedFrame(val sequence: Int, val bitmap: Bitmap)

/**
 * Renders frames one or two ahead of playback on a background dispatcher, recycling a small pool
 * of bitmaps.
 *
 * Rendering a frame means generating fresh fountain symbols, Reed-Solomon encoding them and
 * filling nearly a million pixels. Doing that on the frame callback would make the stream jitter;
 * jitter shows up at the receiver as torn captures.
 */
class FrameProducer(
    private val renderer: FrameBitmapRenderer,
    private val scope: CoroutineScope,
    poolSize: Int = 3,
) {

    private val free = Channel<Bitmap>(poolSize)
    private val ready = Channel<RenderedFrame>(poolSize - 1)
    private var job: Job? = null

    init {
        repeat(poolSize) { free.trySend(renderer.newBitmap()) }
    }

    /** Starts (or restarts) production at frame [from]. */
    fun start(from: Int) {
        job?.cancel()
        // Drain anything queued for the previous position back into the pool.
        while (true) {
            val queued = ready.tryReceive().getOrNull() ?: break
            free.trySend(queued.bitmap)
        }
        job = scope.launch(Dispatchers.Default) {
            var sequence = from
            while (isActive) {
                val bitmap = free.receive()
                renderer.render(sequence, bitmap)
                ready.send(RenderedFrame(sequence, bitmap))
                sequence++
            }
        }
    }

    suspend fun next(): RenderedFrame = ready.receive()

    /** Returns a bitmap to the pool once it is no longer on screen. */
    fun recycle(frame: RenderedFrame) {
        free.trySend(frame.bitmap)
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
