package dev.avillagran.ot3android.core.bridge

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Pass a directory under Context.filesDir. No tokens, shared storage or desktop files. */
class FileBridgeCatalogCache(
    private val directory: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : BridgeCatalogCache {
    override suspend fun read(identity: BridgeCatalogIdentity): JSONObject? = withContext(io) {
        val file = path(identity)
        synchronized(lock) {
            if (!file.exists()) return@synchronized null
            require(file.length() <= MAX_BYTES) { "bridge_catalog_cache_too_large" }
            JSONObject(file.readText(Charsets.UTF_8))
        }
    }

    override suspend fun replace(identity: BridgeCatalogIdentity, snapshot: JSONObject) = withContext(io) {
        val file = path(identity)
        val bytes = snapshot.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "bridge_catalog_cache_too_large" }
        synchronized(lock) {
            check(directory.isDirectory || directory.mkdirs()) { "bridge_catalog_cache_unavailable" }
            val temporary = File.createTempFile(".catalog-", ".tmp", directory)
            try {
                check(temporary.setReadable(false, false) && temporary.setWritable(false, false) &&
                    temporary.setReadable(true, true) && temporary.setWritable(true, true)) { "bridge_catalog_cache_permissions" }
                FileOutputStream(temporary).use { stream -> stream.write(bytes); stream.fd.sync() }
                // Same private filesystem: rename replaces the whole previous document on Android/Linux.
                check(temporary.renameTo(file)) { "bridge_catalog_cache_replace_failed" }
            } finally {
                temporary.delete()
            }
        }
        Unit
    }

    private fun path(identity: BridgeCatalogIdentity): File {
        val key = JSONArray().put(identity.bridgeId).put(identity.clientId).toString()
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return File(directory, digest.joinToString("") { "%02x".format(it) } + ".json")
    }

    private companion object {
        const val MAX_BYTES = 1024 * 1024
        val lock = Any()
    }
}
