package dev.avillagran.ot3android.core.theme

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileThemeCacheTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val cache by lazy { FileThemeCache(folder.root.resolve("live-theme"), Dispatchers.Unconfined) }
    private val theme = CachedTheme("tokyo-night", "Tokyo Night", "background = \"#1a1b26\"\n", JSONObject().put("appearance", "dark").toString())

    @Test
    fun aThemeRoundTripsPerEnvironment() = runBlocking {
        cache.write("env/1", theme)
        cache.write("env-2", theme.copy(themeName = "nord", displayName = "Nord"))

        assertEquals(theme, cache.read("env/1"))
        assertEquals("nord", cache.read("env-2")?.themeName)
        // File names are hashes: no environment id (or slash) reaches the file system.
        assertFalse(folder.root.resolve("live-theme").list()!!.any { it.contains("env") })
    }

    @Test
    fun aMissingOrBrokenEntryReadsAsNothing() = runBlocking {
        assertNull(cache.read("unknown"))
        cache.write("env-1", theme)
        folder.root.resolve("live-theme").listFiles()!!.single().writeText("{")

        assertNull(cache.read("env-1"))
    }

    @Test
    fun removeDropsTheEntry() = runBlocking {
        cache.write("env-1", theme)
        cache.remove("env-1")

        assertNull(cache.read("env-1"))
    }
}
