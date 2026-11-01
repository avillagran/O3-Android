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

/** What a spooled file needs for its upload; the bytes are the spool file itself. */
data class SpooledAttachment(val name: String, val mimeType: String, val sizeBytes: Long)

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
        val staged = File.createTempFile("composer-", ".upload", app.cacheDir)
        try {
            val spooled = spool(app, uriText, staged, io)
            upload(native, staged, spooled.name, spooled.mimeType, io)
        } finally {
            staged.delete()
        }
    }
    companion object {
        const val MAX_BYTES = 50L * 1024 * 1024

        /** Copies a granted URI to [target], whole or not at all. Needs no connection. */
        suspend fun spool(context: Context, uriText: String, target: File,
            io: CoroutineDispatcher = Dispatchers.IO,
        ): SpooledAttachment = withContext(io) {
            val uri = Uri.parse(uriText)
            require(uri.scheme == "content") { "unsupported_attachment_uri" }
            val resolver = context.contentResolver
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }?.substringAfterLast('/')?.substringAfterLast('\\')?.filter { !it.isISOControl() }?.take(200)?.takeIf { it.isNotBlank() } ?: "attachment"
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            val partial = File(target.path + ".part")
            try {
                var count = 0L
                resolver.openInputStream(uri)?.use { source ->
                    partial.outputStream().use { sink ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = source.read(buffer)
                            if (read < 0) break
                            count += read
                            require(count <= MAX_BYTES) { "attachment_too_large" }
                            sink.write(buffer, 0, read)
                        }
                    }
                } ?: error("attachment_unreadable")
                require(count > 0) { "attachment_empty" }
                check(partial.renameTo(target)) { "attachment_spool_failed" }
                SpooledAttachment(name, mime, count)
            } finally {
                partial.delete()
            }
        }

        /** Sends spooled bytes through one host's native upload. The caller owns [file]. */
        suspend fun upload(native: ComposerBinaryUpload, file: File, name: String, mimeType: String,
            io: CoroutineDispatcher = Dispatchers.IO,
        ): JSONObject = withContext(io) {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                native.upload(descriptor.fd, name, mimeType, file.length())
            }
        }
    }
}
