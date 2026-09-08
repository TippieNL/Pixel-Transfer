package nl.tippie.pixeltransfer.util

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.ByteArrayOutputStream
import java.util.Locale

/** A file picked by the user, loaded into memory. Transfers are capped at a couple of megabytes. */
class PickedFile(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val bytes: ByteArray,
)

object FileIo {

    class TooLarge(val size: Long, val limit: Int) : Exception("file is $size bytes, limit is $limit")

    /**
     * Reads a document the user picked through the Storage Access Framework.
     *
     * The size is checked before the read so that pointing the picker at a video does not push
     * the process into an out-of-memory kill.
     */
    fun read(context: Context, uri: Uri, limit: Int): PickedFile {
        val resolver = context.contentResolver
        val name = queryDisplayName(resolver, uri) ?: uri.lastPathSegment ?: "file.bin"
        val declaredSize = queryLength(resolver, uri)
        if (declaredSize != null && declaredSize > limit) throw TooLarge(declaredSize, limit)

        val bytes = resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "cannot open $uri" }
            val buffer = ByteArrayOutputStream(
                (declaredSize ?: 64 * 1024L).coerceAtMost(limit.toLong()).toInt(),
            )
            val chunk = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                total += read
                if (total > limit) throw TooLarge(total, limit)
                buffer.write(chunk, 0, read)
            }
            buffer.toByteArray()
        }

        val mime = resolver.getType(uri)
            ?: guessMimeFromName(name)
            ?: "application/octet-stream"
        return PickedFile(uri, name, mime, bytes)
    }

    fun write(context: Context, uri: Uri, bytes: ByteArray) {
        context.contentResolver.openOutputStream(uri, "wt").use { output ->
            requireNotNull(output) { "cannot write to $uri" }
            output.write(bytes)
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
    return if (total < 60) "${total}s" else "${total / 60}m ${total % 60}s"
}
