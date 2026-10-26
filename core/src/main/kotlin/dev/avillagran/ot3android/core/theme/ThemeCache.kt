package dev.avillagran.ot3android.core.theme

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** The last theme read from an environment, so the app paints it at once on launch. */
data class CachedTheme(val themeName: String, val displayName: String, val colors: String, val tokens: String)

/** Per-environment theme cache. Failures are not errors: a missing cache only costs a read. */
interface ThemeCache {
    suspend fun read(environmentId: String): CachedTheme?
    suspend fun write(environmentId: String, theme: CachedTheme)
    suspend fun remove(environmentId: String)
}

/**
 * [ThemeCache] as one small JSON file per environment under [directory] (app storage,
 * `files/live-theme`), named by a hash of the environment id. Holds colours only, never a
 * credential or a path.
 */
class FileThemeCache(
    private val directory: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ThemeCache {

    override suspend fun read(environmentId: String): CachedTheme? = withContext(io) {
        runCatching {
            val json = JSONObject(file(environmentId).readText())
            CachedTheme(
                themeName = json.getString("themeName"),
                displayName = json.getString("displayName"),
                colors = json.getString("colors"),
                tokens = json.getJSONObject("tokens").toString(),
            )
        }.getOrNull()
    }

    override suspend fun write(environmentId: String, theme: CachedTheme) = withContext(io) {
        runCatching {
            directory.mkdirs()
            val json = JSONObject()
                .put("themeName", theme.themeName)
                .put("displayName", theme.displayName)
                .put("colors", theme.colors)
                .put("tokens", JSONObject(theme.tokens))
            val target = file(environmentId)
            val temp = File(directory, target.name + ".tmp")
            temp.writeText(json.toString())
            if (!temp.renameTo(target)) temp.delete()
        }
        Unit
    }

    override suspend fun remove(environmentId: String) = withContext(io) {
        file(environmentId).delete()
        Unit
    }

    private fun file(environmentId: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(environmentId.toByteArray())
        return File(directory, digest.joinToString("") { "%02x".format(it) }.take(32) + ".json")
    }
}
