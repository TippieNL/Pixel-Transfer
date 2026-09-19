package nl.tippie.pixeltransfer.core.vision

/**
 * Measures how a capture is exposed, cheaply enough to run on every camera frame regardless of
 * whether anything decoded.
 *
 * The statistics are taken over the *bright* part of the image rather than all of it. A phone
 * held in front of a sending screen sees a small brilliant rectangle on a mostly dark background;
 * an average over the whole frame is dominated by the room and says nothing about whether the
 * pattern itself is blown out.
 */
object ExposureMeter {

    /** Pixels at or above this are treated as clipped. */
    const val CLIPPING_LEVEL = 248

    /** Pixels below this are background, not part of the sending screen. */
    const val BRIGHT_FLOOR = 100

    data class Reading(
        /** Fraction of the bright region the sensor clipped to white, 0..1. */
        val clippedFraction: Double,
        /** 95th percentile of the bright region, 0..255. */
        val highlight: Int,
        /** Fraction of the frame that is bright enough to be the sending screen. */
        val brightFraction: Double,
    ) {
        /** True when there is no plausible screen in view and exposure cannot be judged. */
        val isEmpty: Boolean get() = brightFraction < 0.01
    }

    fun measure(image: CameraImage, step: Int = 8): Reading {
        val histogram = IntArray(256)
        var sampled = 0
        var y = 0
        while (y < image.height) {
            var x = 0
            while (x < image.width) {
                histogram[image.lumaAt(x, y)]++
                sampled++
                x += step
            }
            y += step
        }
        if (sampled == 0) return Reading(0.0, 0, 0.0)

        var bright = 0
        for (level in BRIGHT_FLOOR until 256) bright += histogram[level]
        if (bright == 0) return Reading(0.0, 0, 0.0)

        var clipped = 0
        for (level in CLIPPING_LEVEL until 256) clipped += histogram[level]

        // 95th percentile within the bright region.
        val target = (bright * 0.95).toInt()
        var running = 0
        var highlight = 255
        for (level in BRIGHT_FLOOR until 256) {
            running += histogram[level]
            if (running >= target) {
                highlight = level
                break
            }
        }

        return Reading(
            clippedFraction = clipped.toDouble() / bright,
            highlight = highlight,
            brightFraction = bright.toDouble() / sampled,
        )
    }
}

/**
 * Drives the camera's exposure compensation so the sending screen lands just below saturation.
 *
 * Phone auto-exposure meters the whole scene. Pointed at a bright display in a dim room it
 * exposes for the room and drives the display far past saturation, which collapses the top
 * palette levels into each other - the Balanced palette loses two of its four levels per channel
 * and nothing decodes, no matter how sharp or how close the capture is.
 *
 * The loop is deliberately slow and hysteretic: exposure changes take several frames to appear,
 * and chasing them produces oscillation that is worse than being slightly wrong.
 */
class ExposureGovernor(
    private val minIndex: Int,
    private val maxIndex: Int,
) {

    var index: Int = 0
        private set

    private var cooldown = 0

    /** True once the loop has stopped asking for changes. */
    var isSettled: Boolean = false
        private set

    fun reset() {
        index = 0
        cooldown = 0
        isSettled = false
    }

    /**
     * Feeds one measurement. Returns the new compensation index when it should change, or null to
     * leave the camera alone.
     */
    fun update(reading: ExposureMeter.Reading): Int? {
        if (reading.isEmpty) return null
        if (cooldown > 0) {
            cooldown--
            return null
        }
        if (minIndex == 0 && maxIndex == 0) {
            // The device exposes no compensation control; nothing to drive.
            isSettled = true
            return null
        }

        val desired = when {
            reading.clippedFraction > CLIPPED_MAX -> index - 1
            reading.highlight > HIGHLIGHT_MAX -> index - 1
            reading.clippedFraction < CLIPPED_MIN && reading.highlight < HIGHLIGHT_MIN -> index + 1
            else -> index
        }.coerceIn(minIndex, maxIndex)

        if (desired == index) {
            isSettled = true
            return null
        }
        index = desired
        cooldown = SETTLE_FRAMES
        isSettled = false
        return index
    }

    private companion object {
        /** Above this much clipping in the bright region, step exposure down. */
        const val CLIPPED_MAX = 0.02

        /** Below this, and dim, there is headroom to step back up. */
        const val CLIPPED_MIN = 0.005

        /** Keep the brightest palette level near, but under, saturation. */
        const val HIGHLIGHT_MAX = 246
        const val HIGHLIGHT_MIN = 200

        /** Frames to wait after a change before judging its effect. */
        const val SETTLE_FRAMES = 6
    }
}
