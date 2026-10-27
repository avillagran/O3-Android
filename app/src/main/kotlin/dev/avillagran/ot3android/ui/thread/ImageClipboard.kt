package dev.avillagran.ot3android.ui.thread

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** A single, private cache subtree. No attachment name or remote path becomes a local path. */
class ImageClipboardProvider : FileProvider() {
    override fun getType(uri: Uri): String = ImageClipboard.mimeForExtension(uri.lastPathSegment.orEmpty().substringAfterLast('.'))
        ?: "application/octet-stream"

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("read_only")

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        require(mode == "r") { "read_only" }
        return super.openFile(uri, mode)
    }
}

internal object ImageClipboard {
    private val extensions = mapOf("image/png" to "png", "image/jpeg" to "jpg", "image/gif" to "gif",
        "image/webp" to "webp", "image/heic" to "heic", "image/heif" to "heif", "image/avif" to "avif")
    internal const val MAX_FILES = 8
    internal const val MAX_AGE_MS = 24 * 60 * 60 * 1000L
    internal fun mimeForExtension(extension: String) = extensions.entries.firstOrNull { it.value == extension }?.key
    internal fun directory(context: Context) = File(context.cacheDir, "clipboard-images")
    private fun uri(context: Context, file: File) = FileProvider.getUriForFile(context, "${context.packageName}.clipboard-images", file)

    /** Run on IO at app startup and before another copy. Revokes old grants before removing files. */
    @Synchronized
    internal fun cleanup(context: Context, now: Long = System.currentTimeMillis(), keep: Int = MAX_FILES) {
        val files = directory(context).listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.forEachIndexed { index, file ->
            if (index >= keep || now - file.lastModified() >= MAX_AGE_MS) {
                context.revokeUriPermission(uri(context, file), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                check(file.delete() || !file.exists()) { "image_cache_cleanup_failed" }
            }
        }
    }

    /** Called only by an explicit copy action; original validated bytes are never re-encoded. */
    internal suspend fun copy(context: Context, bytes: ByteArray, mime: String, label: String, current: () -> Boolean) {
        val extension = requireNotNull(extensions[mime]) { "unsupported_image" }
        require(bytes.isNotEmpty() && bytes.size <= 10 * 1024 * 1024) { "image_too_large" }
        var published = false
        val file = File(directory(context), "${UUID.randomUUID()}.$extension")
        try {
            withContext(Dispatchers.IO) {
                synchronized(this@ImageClipboard) {
                    cleanup(context, keep = MAX_FILES - 1)
                    check(directory(context).mkdirs() || directory(context).isDirectory)
                    file.outputStream().use { it.write(bytes) }
                }
            }
            coroutineContext.ensureActive()
            withContext(Dispatchers.Main.immediate) {
                coroutineContext.ensureActive()
                check(current()) { "image_connection_changed" }
                val clip = ClipData(ClipDescription(label, arrayOf(mime)), ClipData.Item(uri(context, file)))
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                published = true
            }
        } finally {
            if (!published) file.delete()
        }
    }
}
