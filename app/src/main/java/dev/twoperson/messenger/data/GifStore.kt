package dev.twoperson.messenger.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.twoperson.messenger.protocol.AppCodec
import java.io.File

/**
 * The user's local GIF gallery.
 *
 * Files are copied into app-private storage so the gallery does not depend on a Storage Access
 * Framework grant that can be revoked later. Nothing is uploaded or scanned: the gallery is a
 * plain directory of validated GIF files on this device.
 */
class GifStore(context: Context) {

    sealed interface ImportResult {
        data class Added(val file: File) : ImportResult
        data object Invalid : ImportResult
        data object TooLarge : ImportResult
        data object Failed : ImportResult
    }

    private val directory: File = File(context.filesDir, "gifs").apply { mkdirs() }

    /** Newest first, so a just-imported GIF is the first tile. */
    fun files(): List<File> = directory.listFiles()
        ?.filter { it.isFile && it.extension.equals("gif", ignoreCase = true) }
        ?.sortedByDescending { it.lastModified() }
        .orEmpty()

    fun import(resolver: ContentResolver, uri: Uri): ImportResult {
        val displayName = displayName(resolver, uri)
        val mime = runCatching { resolver.getType(uri) }.getOrNull()
        val looksLikeGif = displayName?.endsWith(".gif", ignoreCase = true) == true ||
            mime.equals("image/gif", ignoreCase = true)
        if (!looksLikeGif) return ImportResult.Invalid

        val temp = File(directory, ".import-${System.nanoTime()}.tmp")
        try {
            val input = resolver.openInputStream(uri) ?: return ImportResult.Failed
            var total = 0L
            var tooLarge = false
            input.use { source ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        total += read
                        if (total > MAX_BYTES) {
                            tooLarge = true
                            break
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (tooLarge) {
                temp.delete()
                return ImportResult.TooLarge
            }
            if (!hasGifSignature(temp)) {
                temp.delete()
                return ImportResult.Invalid
            }
            val target = uniqueFile(displayName ?: "knot-gif.gif")
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = false)
                temp.delete()
            }
            if (!target.isFile || target.length() <= 0) {
                target.delete()
                return ImportResult.Failed
            }
            target.setLastModified(System.currentTimeMillis())
            return ImportResult.Added(target)
        } catch (e: Exception) {
            temp.delete()
            return ImportResult.Failed
        }
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String? {
        try {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && !cursor.isNull(index)) {
                        cursor.getString(index)?.takeIf { it.isNotBlank() }?.let { return it }
                    }
                }
            }
        } catch (e: Exception) {
            // Fall back to the URI's last segment below.
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    }

    private fun hasGifSignature(file: File): Boolean = try {
        val header = ByteArray(6)
        val read = file.inputStream().use { it.read(header) }
        if (read != header.size) {
            false
        } else {
            val signature = String(header, Charsets.US_ASCII)
            signature == "GIF87a" || signature == "GIF89a"
        }
    } catch (e: Exception) {
        false
    }

    private fun uniqueFile(displayName: String): File {
        val cleaned = AppCodec.sanitizeFileName(displayName) ?: "knot-gif.gif"
        val safe = if (cleaned.endsWith(".gif", ignoreCase = true)) cleaned else "$cleaned.gif"
        var candidate = File(directory, safe)
        if (!candidate.exists()) return candidate
        val dot = safe.lastIndexOf('.')
        val base = if (dot > 0) safe.substring(0, dot) else safe
        val extension = if (dot > 0) safe.substring(dot) else ".gif"
        var counter = 1
        while (candidate.exists()) {
            candidate = File(directory, "$base ($counter)$extension")
            counter++
        }
        return candidate
    }

    companion object {
        /** Large enough for real GIFs, small enough to keep animated decoding responsive. */
        const val MAX_BYTES = 50L * 1024 * 1024
        private const val COPY_BUFFER_BYTES = 64 * 1024
    }
}
