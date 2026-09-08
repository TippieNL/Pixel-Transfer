package nl.tippie.pixeltransfer.sender

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.tippie.pixeltransfer.core.frame.PaletteMode
import nl.tippie.pixeltransfer.core.pipeline.PreparedTransfer
import nl.tippie.pixeltransfer.core.pipeline.StreamEncoder
import java.io.BufferedOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Formats the stream can be saved as, for playback on a monitor or another device. */
enum class ExportFormat(val label: String, val extension: String, val mimeType: String) {
    /** One lossless PNG per frame, zipped. Safe in every palette mode. */
    PNG_SEQUENCE("Lossless PNG sequence (.zip)", "zip", "application/zip"),

    /** Lossless WebP per frame, zipped. Same fidelity as PNG, roughly half the size. */
    WEBP_SEQUENCE("Lossless WebP sequence (.zip)", "zip", "application/zip"),

    /** H.264 at a deliberately extreme bitrate, all keyframes. */
    MP4("H.264 video (.mp4)", "mp4", "video/mp4"),

    /** A single still PNG. Only meaningful when one frame carries the whole file. */
    SINGLE_FRAME("Single still frame (.png)", "png", "image/png"),
    ;
}

class ExportResult(val frames: Int, val bytes: Long)

/**
 * Writes the stream out as a file.
 *
 * Video export is the compromised option and is treated as such. H.264 subsamples chroma to
 * 4:2:0 and quantises, which is survivable for the Robust and Balanced palettes - the camera
 * path subsamples chroma anyway - but it destroys Dense, where adjacent levels are seventeen
 * code values apart. So Dense is refused for video rather than silently produced broken, and the
 * encoder is pinned to an all-keyframe, very high bitrate configuration.
 */
object StreamExporter {

    /** Deliberately extravagant: a stream that is unreadable is worth nothing at any size. */
    private const val MP4_BITS_PER_PIXEL_PER_FRAME = 1.6

    class UnsupportedForPalette(val mode: PaletteMode) :
        IllegalArgumentException("${mode.label} cannot survive video compression")

    class NotASingleFrame :
        IllegalArgumentException("this file needs more than one frame")

    fun isSupported(format: ExportFormat, mode: PaletteMode): Boolean = when (format) {
        // Video subsampling destroys the two densest palettes.
        ExportFormat.MP4 -> mode == PaletteMode.ROBUST || mode == PaletteMode.BALANCED
        // A still image cannot loop, so it has to be read in one go - which only the most
        // forgiving palette makes realistic.
        ExportFormat.SINGLE_FRAME -> mode == PaletteMode.ROBUST
        else -> true
    }

    suspend fun export(
        context: Context,
        target: Uri,
        transfer: PreparedTransfer,
        format: ExportFormat,
        /** Frames to write. One nominal loop is enough for a receiver that sees all of them. */
        frameCount: Int,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): ExportResult = withContext(Dispatchers.Default) {
        if (!isSupported(format, transfer.config.paletteMode)) {
            throw UnsupportedForPalette(transfer.config.paletteMode)
        }
        val renderer = FrameBitmapRenderer(StreamEncoder(transfer), transfer.config)
        when (format) {
            ExportFormat.PNG_SEQUENCE ->
                exportZip(context, target, renderer, frameCount, Bitmap.CompressFormat.PNG, "png", onProgress)

            ExportFormat.WEBP_SEQUENCE ->
                exportZip(context, target, renderer, frameCount, losslessWebp(), "webp", onProgress)

            ExportFormat.MP4 ->
                exportMp4(context, target, renderer, transfer.config.fps, frameCount, onProgress)

            ExportFormat.SINGLE_FRAME -> {
                if (!transfer.fitsInOneFrame) throw NotASingleFrame()
                exportStillFrame(context, target, renderer)
            }
        }
    }

    /**
     * Writes frame 0, which carries the systematic symbols - source block i verbatim - and so is
     * a complete transfer on its own when the file is small enough.
     */
    private fun exportStillFrame(
        context: Context,
        target: Uri,
        renderer: FrameBitmapRenderer,
    ): ExportResult {
        val bitmap = renderer.newBitmap()
        var written = 0L
        try {
            renderer.render(0, bitmap)
            context.contentResolver.openOutputStream(target, "wt").use { raw ->
                requireNotNull(raw) { "cannot write to $target" }
                val counting = CountingOutputStream(BufferedOutputStream(raw, 256 * 1024))
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, counting)
                counting.flush()
                written = counting.count
            }
        } finally {
            bitmap.recycle()
        }
        return ExportResult(1, written)
    }

    @Suppress("DEPRECATION")
    private fun losslessWebp(): Bitmap.CompressFormat =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSLESS
        } else {
            Bitmap.CompressFormat.PNG
        }

    private fun exportZip(
        context: Context,
        target: Uri,
        renderer: FrameBitmapRenderer,
        frameCount: Int,
        compressFormat: Bitmap.CompressFormat,
        extension: String,
        onProgress: (Int, Int) -> Unit,
    ): ExportResult {
        var written = 0L
        val bitmap = renderer.newBitmap()
        context.contentResolver.openOutputStream(target, "wt").use { raw ->
            requireNotNull(raw) { "cannot write to $target" }
            val counting = CountingOutputStream(BufferedOutputStream(raw, 256 * 1024))
            ZipOutputStream(counting).use { zip ->
                // The frames are already incompressible; storing them keeps the export quick.
                zip.setLevel(0)
                for (i in 0 until frameCount) {
                    renderer.render(i, bitmap)
                    // Locale.ROOT: entry names must be ASCII digits whatever the device locale.
                    zip.putNextEntry(ZipEntry(String.format(Locale.ROOT, "frame_%05d.%s", i, extension)))
                    // Quality is ignored for PNG and for lossless WebP.
                    bitmap.compress(compressFormat, 100, zip)
                    zip.closeEntry()
                    onProgress(i + 1, frameCount)
                }
            }
            written = counting.count
        }
        bitmap.recycle()
        return ExportResult(frameCount, written)
    }

    private fun exportMp4(
        context: Context,
        target: Uri,
        renderer: FrameBitmapRenderer,
        fps: Int,
        frameCount: Int,
        onProgress: (Int, Int) -> Unit,
    ): ExportResult {
        val size = renderer.imageSize
        // H.264 requires even dimensions; the rasteriser always produces a multiple of the cell
        // size, but guard anyway.
        require(size % 2 == 0) { "frame size must be even for video export" }

        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        val colorFormat = pickColorFormat(mime)
        val format = MediaFormat.createVideoFormat(mime, size, size).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger(
                MediaFormat.KEY_BIT_RATE,
                (size * size * fps * MP4_BITS_PER_PIXEL_PER_FRAME).toInt(),
            )
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            // Every frame a keyframe: the receiver may start anywhere, and inter-frame prediction
            // between two unrelated pixel fields is pure noise anyway.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0)
        }

        val codec = MediaCodec.createEncoderByType(mime)
        var descriptor: ParcelFileDescriptor? = null
        var muxer: MediaMuxer? = null
        var written = 0L
        val bitmap = renderer.newBitmap()
        val argb = IntArray(size * size)
        val yuv = ByteArray(size * size * 3 / 2)

        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            descriptor = context.contentResolver.openFileDescriptor(target, "rw")
                ?: error("cannot open $target")
            muxer = MediaMuxer(descriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            var trackIndex = -1
            var muxerStarted = false
            val info = MediaCodec.BufferInfo()
            var frame = 0
            var inputDone = false

            while (true) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        if (frame >= frameCount) {
                            codec.queueInputBuffer(
                                index, 0, 0, presentationTime(frame, fps),
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            renderer.render(frame, bitmap)
                            bitmap.getPixels(argb, 0, size, 0, 0, size, size)
                            argbToYuv420(argb, size, size, colorFormat, yuv)
                            buffer.clear()
                            buffer.put(yuv)
                            codec.queueInputBuffer(index, 0, yuv.size, presentationTime(frame, fps), 0)
                            frame++
                            onProgress(frame, frameCount)
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }

                    outIndex >= 0 -> {
                        val encoded = codec.getOutputBuffer(outIndex)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            info.size = 0
                        }
                        if (info.size > 0 && muxerStarted) {
                            encoded.position(info.offset)
                            encoded.limit(info.offset + info.size)
                            muxer.writeSampleData(trackIndex, encoded, info)
                            written += info.size
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            return ExportResult(frameCount, written)
        } finally {
            runCatching { codec.stop() }
            codec.release()
            runCatching { muxer?.stop() }
            muxer?.release()
            descriptor?.close()
            bitmap.recycle()
        }
    }

    private fun presentationTime(frame: Int, fps: Int): Long =
        frame.toLong() * 1_000_000L / fps

    /**
     * The two planar constants are marked deprecated in favour of COLOR_FormatYUV420Flexible, but
     * flexible only describes Image-based input. Feeding an encoder through ByteBuffers - which is
     * what avoids an EGL pipeline here - still requires naming the concrete layout.
     */
    @Suppress("DEPRECATION")
    private fun pickColorFormat(mime: String): Int {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in list.codecInfos) {
            if (!info.isEncoder) continue
            if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
            val formats = info.getCapabilitiesForType(mime).colorFormats
            for (candidate in intArrayOf(
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
            )) {
                if (formats.contains(candidate)) return candidate
            }
        }
        return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
    }

    /** BT.601 full-range conversion, matching what the receiver assumes when reading YUV. */
    @Suppress("DEPRECATION")
    private fun argbToYuv420(argb: IntArray, width: Int, height: Int, colorFormat: Int, out: ByteArray) {
        val frameSize = width * height
        val semiPlanar =
            colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        var uIndex = frameSize
        var vIndex = if (semiPlanar) frameSize + 1 else frameSize + frameSize / 4

        for (y in 0 until height) {
            for (x in 0 until width) {
                val p = argb[y * width + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val luma = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)
                out[y * width + x] = luma.toByte()
                if (y % 2 == 0 && x % 2 == 0) {
                    val cb = (-0.168736 * r - 0.331264 * g + 0.5 * b + 128).toInt().coerceIn(0, 255)
                    val cr = (0.5 * r - 0.418688 * g - 0.081312 * b + 128).toInt().coerceIn(0, 255)
                    if (semiPlanar) {
                        out[uIndex] = cb.toByte()
                        out[uIndex + 1] = cr.toByte()
                        uIndex += 2
                    } else {
                        out[uIndex++] = cb.toByte()
                        out[vIndex++] = cr.toByte()
                    }
                }
            }
        }
    }

    private class CountingOutputStream(private val delegate: java.io.OutputStream) :
        java.io.OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            delegate.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            count += len
        }

        override fun flush() = delegate.flush()
        override fun close() = delegate.close()
    }
}
