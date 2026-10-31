package dev.avillagran.ot3android.ui.thread.composer

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.coroutineContext

/** Adapter to the connected T3Client.upload ABI. The descriptor is borrowed for this call only. */
fun interface ComposerBinaryUpload {
    suspend fun upload(fd: Int, name: String, mimeType: String, sizeBytes: Long): JSONObject
}

/**
 * Picker and clipboard share one bounded import. Copy while the transient content grant is
 * live; never persist content URIs or reopen them after restart. The native call owns upload
 * validation/authentication; no Kotlin HTTP or base64 transport. Bind one importer per host.
 */
class ComposerAttachmentImporter(context: Context, private val native: ComposerBinaryUpload,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val app = context.applicationContext
    suspend fun upload(uriText: String): JSONObject = withContext(io) {
        val uri = Uri.parse(uriText)
        require(uri.scheme == "content") { "unsupported_attachment_uri" }
        val resolver = app.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.substringAfterLast('/')?.substringAfterLast('\\')?.filter { !it.isISOControl() }?.take(200)?.takeIf { it.isNotBlank() } ?: "attachment"
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val staged = File.createTempFile("composer-", ".upload", app.cacheDir)
        try {
            var count = 0L
            resolver.openInputStream(uri)?.use { source ->
                staged.outputStream().use { target ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = source.read(buffer)
                        if (read < 0) break
                        count += read
                        require(count <= MAX_BYTES) { "attachment_too_large" }
                        target.write(buffer, 0, read)
                    }
                }
            } ?: error("attachment_unreadable")
            require(count > 0) { "attachment_empty" }
            ParcelFileDescriptor.open(staged, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                native.upload(descriptor.fd, name, mime, count)
            }
        } finally {
            staged.delete()
        }
    }
    companion object { const val MAX_BYTES = 50L * 1024 * 1024 }
}
