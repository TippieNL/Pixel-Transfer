package nl.tippie.pixeltransfer.core.frame

/**
 * How many bits each colour cell carries, and which colours are used to carry them.
 *
 * Bytes are never mapped straight onto 8-bit R/G/B: no phone camera recovers 24 bits per pixel
 * through lens optics, sensor noise, display gamma and YUV chroma subsampling. Instead each
 * channel is quantised to a small number of levels, and the levels are Gray-coded so that a
 * channel misread by one level costs a single bit rather than several.
 */
enum class PaletteMode(
    val id: Int,
    val bitsPerChannel: Int,
    val grayscale: Boolean,
    val label: String,
    val description: String,
) {
    ROBUST(0, 1, false, "Robust", "8 colours, 3 bits/cell - poor light, cheap cameras, angled shots"),
    BALANCED(1, 2, false, "Balanced", "64 colours, 6 bits/cell - normal indoor use"),
    DENSE(2, 4, false, "Dense", "4096 colours, 12 bits/cell - good phones, controlled light, short range"),
    GRAYSCALE_2(3, 1, true, "Grayscale 2", "2 levels, 1 bit/cell - monochrome or very low light"),
    GRAYSCALE_4(4, 2, true, "Grayscale 4", "4 levels, 2 bits/cell - low light fallback"),
    ;

    /** Levels per colour channel. */
    val levels: Int get() = 1 shl bitsPerChannel

    /** Payload bits carried by one cell. */
    val bitsPerCell: Int get() = if (grayscale) bitsPerChannel else bitsPerChannel * 3

    /** Number of distinct cell symbols. */
    val symbolCount: Int get() = 1 shl bitsPerCell

    companion object {
        fun fromId(id: Int): PaletteMode? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Symbol <-> colour conversion.
 *
 * A symbol is split into per-channel fields, each field is Gray-decoded into a level, and the
 * level is spread evenly across the 0..255 code range. The receiver reverses this after
 * normalising the captured colours against the calibration patches.
 */
object Palette {

    /** ARGB for the darkest cell (all channels at level 0). */
    const val DARK: Int = 0xFF000000.toInt()

    /** ARGB for the brightest cell (all channels at maximum level). */
    const val LIGHT: Int = 0xFFFFFFFF.toInt()

    fun binaryToGray(value: Int): Int = value xor (value ushr 1)

    fun grayToBinary(gray: Int, bits: Int): Int {
        var b = gray
        var shift = 1
        while (shift < bits) {
            b = b xor (b ushr shift)
            shift = shift shl 1
        }
        return b and ((1 shl bits) - 1)
    }

    /** Maps a channel level (0 until levels) onto an 8-bit display code. */
    fun levelToCode(level: Int, levels: Int): Int =
        if (levels <= 1) 0 else (level * 255) / (levels - 1)

    /** Nearest channel level for an 8-bit code. */
    fun codeToLevel(code: Int, levels: Int): Int {
        if (levels <= 1) return 0
        val scaled = (code.coerceIn(0, 255) * (levels - 1) + 127) / 255
        return scaled.coerceIn(0, levels - 1)
    }

    /** Splits a symbol into per-channel levels. Grayscale modes return the same level thrice. */
    fun symbolToLevels(mode: PaletteMode, symbol: Int): IntArray {
        val bits = mode.bitsPerChannel
        val mask = (1 shl bits) - 1
        return if (mode.grayscale) {
            val level = grayToBinary(symbol and mask, bits)
            intArrayOf(level, level, level)
        } else {
            val r = grayToBinary((symbol ushr (bits * 2)) and mask, bits)
            val g = grayToBinary((symbol ushr bits) and mask, bits)
            val b = grayToBinary(symbol and mask, bits)
            intArrayOf(r, g, b)
        }
    }

    /** Inverse of [symbolToLevels]. */
    fun levelsToSymbol(mode: PaletteMode, r: Int, g: Int, b: Int): Int {
        val bits = mode.bitsPerChannel
        return if (mode.grayscale) {
            binaryToGray(r)
        } else {
            (binaryToGray(r) shl (bits * 2)) or (binaryToGray(g) shl bits) or binaryToGray(b)
        }
    }

    /** ARGB colour that renders [symbol] in [mode]. */
    fun colorOf(mode: PaletteMode, symbol: Int): Int {
        val levels = symbolToLevels(mode, symbol)
        val n = mode.levels
        val r = levelToCode(levels[0], n)
        val g = levelToCode(levels[1], n)
        val b = levelToCode(levels[2], n)
        return argb(r, g, b)
    }

    fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    fun red(argb: Int): Int = (argb ushr 16) and 0xFF
    fun green(argb: Int): Int = (argb ushr 8) and 0xFF
    fun blue(argb: Int): Int = argb and 0xFF

    /**
     * Classifies an already-normalised 8-bit RGB triple into a cell symbol by quantising each
     * channel independently to the nearest palette level. Grayscale modes use the luma average.
     */
    fun symbolFromCodes(mode: PaletteMode, r: Int, g: Int, b: Int): Int {
        val n = mode.levels
        return if (mode.grayscale) {
            val luma = (r * 77 + g * 151 + b * 28) shr 8
            val level = codeToLevel(luma, n)
            levelsToSymbol(mode, level, level, level)
        } else {
            levelsToSymbol(mode, codeToLevel(r, n), codeToLevel(g, n), codeToLevel(b, n))
        }
    }

    fun symbolFromArgb(mode: PaletteMode, argb: Int): Int =
        symbolFromCodes(mode, red(argb), green(argb), blue(argb))

    /** All symbols of a mode, in symbol order. Only used for previews and tests. */
    fun allColors(mode: PaletteMode): IntArray =
        IntArray(mode.symbolCount) { colorOf(mode, it) }
}

/**
 * The fixed calibration ramp rendered into every frame.
 *
 * The ramps are deliberately *independent of the palette mode*: the receiver has to normalise
 * colours before it can read the header that tells it which mode the payload uses. Four ramps
 * (R, G, B, neutral) at five fixed code levels give enough information to fit both the 3x4
 * cross-talk correction and a per-channel response curve, from which any palette level in any
 * mode can be predicted.
 */
object CalibrationRamp {

    /** Display code values used by every ramp. */
    val LEVELS = intArrayOf(0, 64, 128, 191, 255)

    /** R, G, B, neutral. */
    const val CHANNELS = 4

    val PATCH_COUNT = CHANNELS * LEVELS.size

    /** ARGB for calibration patch [index] (0 until [PATCH_COUNT]). */
    fun colorOf(index: Int): Int {
        val channel = index / LEVELS.size
        val code = LEVELS[index % LEVELS.size]
        return when (channel) {
            0 -> Palette.argb(code, 0, 0)
            1 -> Palette.argb(0, code, 0)
            2 -> Palette.argb(0, 0, code)
            else -> Palette.argb(code, code, code)
        }
    }

    /** The channel a patch drives (0=R, 1=G, 2=B, 3=neutral). */
    fun channelOf(index: Int): Int = index / LEVELS.size

    /** The display code a patch is drawn at. */
    fun codeOf(index: Int): Int = LEVELS[index % LEVELS.size]
}
