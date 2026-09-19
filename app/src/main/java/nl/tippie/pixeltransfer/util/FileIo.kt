package nl.tippie.pixeltransfer.util

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.util.Locale

/**
 * A document the user picked, described but *not* read.
 *
 * Large transfers are streamed a segment at a time, so the bytes stay where they are until the
 * encoder asks for them.
 */
class PickedFile(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val size: Long,
)

object FileIo {

    class TooLarge(val size: Long, val limit: Long) :
        Exception("file is $size bytes, limit is $limit")

    class Unreadable(message: String) : Exception(message)

    /**
     * Reads a document's name, type and size without opening its contents.
     *
     * Where the provider does not report a size, the stream is measured by skipping through it -
     * still without materialising anything.
     */
    fun describe(context: Context, uri: Uri, limit: Long): PickedFile {
        val resolver = context.contentResolver
        val name = queryDisplayName(resolver, uri) ?: uri.lastPathSegment ?: "file.bin"
        val size = queryLength(resolver, uri) ?: measureLength(context, uri)
            ?: throw Unreadable("could not determine the size of that file")
        if (size <= 0) throw Unreadable("that file is empty")
        if (size > limit) throw TooLarge(size, limit)
        val mime = resolver.getType(uri)
            ?: guessMimeFromName(name)
            ?: "application/octet-stream"
        return PickedFile(uri, name, mime, size)
    }

    private fun measureLength(context: Context, uri: Uri): Long? =
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                var total = 0L
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                }
                total
            }
        }.getOrNull()

    fun write(context: Context, uri: Uri, bytes: ByteArray) {
        context.contentResolver.openOutputStream(uri, "wt").use { output ->
            requireNotNull(output) { "cannot write to $uri" }
            output.write(bytes)
            output.flush()
        }
    }

    /** Streams a local file to a destination the user picked, for transfers too big to hold. */
    fun copyTo(context: Context, source: java.io.File, target: Uri) {
        context.contentResolver.openOutputStream(target, "wt").use { output ->
            requireNotNull(output) { "cannot write to $target" }
            source.inputStream().use { input -> input.copyTo(output, 256 * 1024) }
            output.flush()
        }
    }

    fun guessMimeFromName(name: String): String? {
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension.isEmpty()) return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && !cursor.isNull(index)) cursor.getString(index) else null
            } else {
                null
            }
        }

    private fun queryLength(resolver: ContentResolver, uri: Uri): Long? =
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
            } else {
                null
            }
        }
}

/** Human-readable byte counts, used all over the two screens. */
fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.getDefault(), "%.2f MB", bytes / (1024.0 * 1024.0))
}

fun formatDuration(seconds: Double): String {
    if (!seconds.isFinite() || seconds < 0) return "-"
    val total = seconds.toInt()
    return when {
        total < 60 -> "${total}s"
        total < 3600 -> "${total / 60}m ${total % 60}s"
        else -> "${total / 3600}h ${(total % 3600) / 60}m"
    }
}
