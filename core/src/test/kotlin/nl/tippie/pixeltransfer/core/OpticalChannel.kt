package nl.tippie.pixeltransfer.core

import nl.tippie.pixeltransfer.core.vision.CameraImage
import nl.tippie.pixeltransfer.core.vision.Yuv420CameraImage
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * A deliberately unkind model of "one phone's screen photographed by another phone's camera".
 *
 * It composes the impairments the spec calls out - perspective up to and beyond 35 degrees,
 * optical blur, sensor noise, display gamma, camera white balance and channel cross-talk,
 * vignetting, glare hotspots, rolling-shutter tearing, PWM banding and 4:2:0 chroma subsampling
 * - and hands back a YUV_420_888 image shaped exactly like the one CameraX delivers.
 */
class OpticalChannel(
    val outWidth: Int = 1920,
    val outHeight: Int = 1080,
    /** Fraction of the output height the code area spans when viewed head-on. */
    val fill: Double = 0.75,
    val yawDegrees: Double = 0.0,
    val pitchDegrees: Double = 0.0,
    val rollDegrees: Double = 0.0,
    val offsetX: Double = 0.0,
    val offsetY: Double = 0.0,
    /** Gaussian blur sigma in output pixels. */
    val blurSigma: Double = 0.8,
    /** Per-channel Gaussian noise standard deviation, in code units. */
    val noiseSigma: Double = 3.0,
    /** Combined display + sensor gamma. 1.0 is a perfectly linear chain. */
    val gamma: Double = 1.15,
    /** Camera auto-white-balance gains, R/G/B. */
    val whiteBalance: DoubleArray = doubleArrayOf(1.10, 1.0, 0.88),
    /** Corner falloff; 0 is none, 0.4 is a heavy phone lens. */
    val vignette: Double = 0.18,
    /** Peak brightness added by a specular hotspot, in code units. */
    val glareStrength: Double = 0.0,
    val glareX: Double = 0.7,
    val glareY: Double = 0.3,
    /**
     * Sensor gain before clipping. A phone metering a dark room and then pointed at an emissive
     * display drives the screen well past saturation: values above 1.0 clip the bright end, which
     * is what turns the palette into washed-out pastels and collapses the top calibration levels
     * into each other.
     */
    val exposureGain: Double = 1.0,
    /** Amplitude of PWM backlight banding, in code units. */
    val pwmBanding: Double = 0.0,
    val pwmPeriodPx: Double = 37.0,
    val seed: Int = 1,
) {

    /** Cross-talk of a typical phone sensor: a pure primary always leaks into its neighbours. */
    private val crossTalk = arrayOf(
        doubleArrayOf(0.92, 0.06, 0.02),
        doubleArrayOf(0.05, 0.90, 0.05),
        doubleArrayOf(0.03, 0.09, 0.88),
    )

    /**
     * Captures one displayed image.
     *
     * When [second] is given, the capture tears: rows above [tearAt] come from [source] and rows
     * below come from [second], exactly as a rolling shutter samples a display mid-refresh.
     */
    fun capture(
        source: IntArray,
        sourceSize: Int,
        second: IntArray? = null,
        tearAt: Double = 0.5,
    ): CameraImage {
        val rng = Random(seed)
        val quad = projectQuad()
        // Map output pixels back to source pixels.
        val inverse = nl.tippie.pixeltransfer.core.vision.Homography.from(
            quad,
            arrayOf(
                doubleArrayOf(0.0, 0.0),
                doubleArrayOf(sourceSize.toDouble(), 0.0),
                doubleArrayOf(sourceSize.toDouble(), sourceSize.toDouble()),
                doubleArrayOf(0.0, sourceSize.toDouble()),
            ),
        ) ?: error("degenerate projection")

        val tearRow = (tearAt * outHeight).toInt()
        val rgb = FloatArray(outWidth * outHeight * 3)

        for (y in 0 until outHeight) {
            val plane = if (second != null && y >= tearRow) second else source
            for (x in 0 until outWidth) {
                val sx = inverse.mapX(x + 0.5, y + 0.5)
                val sy = inverse.mapY(x + 0.5, y + 0.5)
                val base = (y * outWidth + x) * 3
                if (sx < 0 || sy < 0 || sx >= sourceSize || sy >= sourceSize) {
                    // Everything outside the code is the sender's black background.
                    rgb[base] = 6f; rgb[base + 1] = 6f; rgb[base + 2] = 6f
                } else {
                    val p = bilinear(plane, sourceSize, sx, sy)
                    rgb[base] = ((p ushr 16) and 0xFF).toFloat()
                    rgb[base + 1] = ((p ushr 8) and 0xFF).toFloat()
                    rgb[base + 2] = (p and 0xFF).toFloat()
                }
            }
        }

        if (blurSigma > 0.05) gaussianBlur(rgb, outWidth, outHeight, blurSigma)

        val cx = outWidth / 2.0
        val cy = outHeight / 2.0
        val maxR = kotlin.math.hypot(cx, cy)
        val gx = glareX * outWidth
        val gy = glareY * outHeight
        val glareSigma = outHeight * 0.12

        for (y in 0 until outHeight) {
            for (x in 0 until outWidth) {
                val base = (y * outWidth + x) * 3
                var r = rgb[base].toDouble()
                var g = rgb[base + 1].toDouble()
                var b = rgb[base + 2].toDouble()

                // Display gamma, then the sensor's channel mixing and white balance.
                r = 255.0 * Math.pow(r / 255.0, gamma)
                g = 255.0 * Math.pow(g / 255.0, gamma)
                b = 255.0 * Math.pow(b / 255.0, gamma)
                val mr = crossTalk[0][0] * r + crossTalk[0][1] * g + crossTalk[0][2] * b
                val mg = crossTalk[1][0] * r + crossTalk[1][1] * g + crossTalk[1][2] * b
                val mb = crossTalk[2][0] * r + crossTalk[2][1] * g + crossTalk[2][2] * b
                r = mr * whiteBalance[0]
                g = mg * whiteBalance[1]
                b = mb * whiteBalance[2]

                if (vignette > 0.0) {
                    val d = kotlin.math.hypot(x - cx, y - cy) / maxR
                    val falloff = 1.0 - vignette * d * d
                    r *= falloff; g *= falloff; b *= falloff
                }
                if (pwmBanding > 0.0) {
                    val band = pwmBanding * sin(2.0 * Math.PI * y / pwmPeriodPx)
                    r += band; g += band; b += band
                }
                if (glareStrength > 0.0) {
                    val d2 = ((x - gx) * (x - gx) + (y - gy) * (y - gy)) / (2 * glareSigma * glareSigma)
                    val add = glareStrength * exp(-d2)
                    r += add; g += add; b += add
                }
                if (exposureGain != 1.0) {
                    r *= exposureGain
                    g *= exposureGain
                    b *= exposureGain
                }
                if (noiseSigma > 0.0) {
                    r += gaussian(rng) * noiseSigma
                    g += gaussian(rng) * noiseSigma
                    b += gaussian(rng) * noiseSigma
                }

                rgb[base] = r.coerceIn(0.0, 255.0).toFloat()
                rgb[base + 1] = g.coerceIn(0.0, 255.0).toFloat()
                rgb[base + 2] = b.coerceIn(0.0, 255.0).toFloat()
            }
        }

        return toYuv420(rgb, outWidth, outHeight)
    }

    /** Where the four corners of the displayed image land in the captured image. */
    fun projectQuad(): Array<DoubleArray> {
        val yaw = Math.toRadians(yawDegrees)
        val pitch = Math.toRadians(pitchDegrees)
        val roll = Math.toRadians(rollDegrees)
        val focal = outHeight.toDouble()
        val distance = 1.0 / fill

        val corners = arrayOf(
            doubleArrayOf(-0.5, -0.5),
            doubleArrayOf(0.5, -0.5),
            doubleArrayOf(0.5, 0.5),
            doubleArrayOf(-0.5, 0.5),
        )
        return Array(4) { i ->
            var x = corners[i][0]
            var y = corners[i][1]
            var z = 0.0
            // Roll about the view axis.
            val rx = x * cos(roll) - y * sin(roll)
            val ry = x * sin(roll) + y * cos(roll)
            x = rx; y = ry
            // Yaw about the vertical axis.
            val yx = x * cos(yaw) + z * sin(yaw)
            val yz = -x * sin(yaw) + z * cos(yaw)
            x = yx; z = yz
            // Pitch about the horizontal axis.
            val py = y * cos(pitch) - z * sin(pitch)
            val pz = y * sin(pitch) + z * cos(pitch)
            y = py; z = pz

            val zc = z + distance
            doubleArrayOf(
                focal * x / zc + outWidth / 2.0 + offsetX,
                focal * y / zc + outHeight / 2.0 + offsetY,
            )
        }
    }

    private fun bilinear(pixels: IntArray, size: Int, x: Double, y: Double): Int {
        val x0 = x.toInt().coerceIn(0, size - 1)
        val y0 = y.toInt().coerceIn(0, size - 1)
        val x1 = (x0 + 1).coerceAtMost(size - 1)
        val y1 = (y0 + 1).coerceAtMost(size - 1)
        val fx = (x - x0).coerceIn(0.0, 1.0)
        val fy = (y - y0).coerceIn(0.0, 1.0)
        var out = 0
        for (shift in intArrayOf(16, 8, 0)) {
            val a = (pixels[y0 * size + x0] ushr shift) and 0xFF
            val b = (pixels[y0 * size + x1] ushr shift) and 0xFF
            val c = (pixels[y1 * size + x0] ushr shift) and 0xFF
            val d = (pixels[y1 * size + x1] ushr shift) and 0xFF
            val top = a + (b - a) * fx
            val bottom = c + (d - c) * fx
            val v = (top + (bottom - top) * fy).toInt().coerceIn(0, 255)
            out = out or (v shl shift)
        }
        return out
    }

    private fun gaussianBlur(rgb: FloatArray, width: Int, height: Int, sigma: Double) {
        val radius = kotlin.math.ceil(sigma * 3).toInt().coerceAtLeast(1)
        val kernel = DoubleArray(radius * 2 + 1)
        var sum = 0.0
        for (i in kernel.indices) {
            val d = (i - radius).toDouble()
            kernel[i] = exp(-d * d / (2 * sigma * sigma))
            sum += kernel[i]
        }
        for (i in kernel.indices) kernel[i] /= sum

        val tmp = FloatArray(rgb.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                for (ch in 0 until 3) {
                    var acc = 0.0
                    for (k in kernel.indices) {
                        val xx = (x + k - radius).coerceIn(0, width - 1)
                        acc += kernel[k] * rgb[(y * width + xx) * 3 + ch]
                    }
                    tmp[(y * width + x) * 3 + ch] = acc.toFloat()
                }
            }
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                for (ch in 0 until 3) {
                    var acc = 0.0
                    for (k in kernel.indices) {
                        val yy = (y + k - radius).coerceIn(0, height - 1)
                        acc += kernel[k] * tmp[(yy * width + x) * 3 + ch]
                    }
                    rgb[(y * width + x) * 3 + ch] = acc.toFloat()
                }
            }
        }
    }

    /** Converts to full-range BT.601 with 4:2:0 chroma, matching CameraX YUV_420_888. */
    private fun toYuv420(rgb: FloatArray, width: Int, height: Int): CameraImage {
        val y = ByteArray(width * height)
        val cw = width / 2
        val ch = height / 2
        val uAcc = DoubleArray(cw * ch)
        val vAcc = DoubleArray(cw * ch)
        val counts = IntArray(cw * ch)

        for (row in 0 until height) {
            for (col in 0 until width) {
                val base = (row * width + col) * 3
                val r = rgb[base].toDouble()
                val g = rgb[base + 1].toDouble()
                val b = rgb[base + 2].toDouble()
                val luma = 0.299 * r + 0.587 * g + 0.114 * b
                y[row * width + col] = luma.coerceIn(0.0, 255.0).toInt().toByte()
                val ci = (row / 2) * cw + (col / 2)
                if (ci < counts.size) {
                    uAcc[ci] += -0.168736 * r - 0.331264 * g + 0.5 * b + 128.0
                    vAcc[ci] += 0.5 * r - 0.418688 * g - 0.081312 * b + 128.0
                    counts[ci]++
                }
            }
        }
        val u = ByteArray(cw * ch)
        val v = ByteArray(cw * ch)
        for (i in u.indices) {
            val n = counts[i].coerceAtLeast(1)
            u[i] = (uAcc[i] / n).coerceIn(0.0, 255.0).toInt().toByte()
            v[i] = (vAcc[i] / n).coerceIn(0.0, 255.0).toInt().toByte()
        }
        return Yuv420CameraImage(width, height, y, u, v, width, cw, 1)
    }

    private var spare: Double? = null

    private fun gaussian(rng: Random): Double {
        spare?.let { spare = null; return it }
        var u: Double
        var v: Double
        var s: Double
        do {
            u = rng.nextDouble() * 2 - 1
            v = rng.nextDouble() * 2 - 1
            s = u * u + v * v
        } while (s >= 1.0 || s == 0.0)
        val factor = kotlin.math.sqrt(-2.0 * kotlin.math.ln(s) / s)
        spare = v * factor
        return u * factor
    }
}
