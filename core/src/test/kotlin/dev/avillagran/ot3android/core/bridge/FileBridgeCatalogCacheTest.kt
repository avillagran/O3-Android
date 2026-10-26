package dev.avillagran.ot3android.core.bridge

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileBridgeCatalogCacheTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun publisherAndRecipientCachesAreSeparatedAndSurviveRecreation() = runBlocking {
        val dir = folder.newFolder()
        val a = BridgeCatalogIdentity("b", "one")
        val b = BridgeCatalogIdentity("b", "two")
        val c = BridgeCatalogIdentity("another", "one")
        val cache = FileBridgeCatalogCache(dir)
        cache.replace(a, JSONObject().put("revision", 1))
        cache.replace(b, JSONObject().put("revision", 2))
        assertNull(cache.read(c))
        cache.replace(a, JSONObject().put("revision", 3))
        val recreated = FileBridgeCatalogCache(dir)
        assertEquals(3, recreated.read(a)!!.getInt("revision"))
        assertEquals(2, recreated.read(b)!!.getInt("revision"))
        assertEquals(2, dir.listFiles()!!.size)
        assertTrue(dir.listFiles()!!.all { it.name.matches(Regex("[0-9a-f]{64}\\.json")) })
    }
    @Test fun oversizedReplacementDoesNotDestroyPriorSnapshot() = runBlocking {
        val cache = FileBridgeCatalogCache(folder.newFolder())
        val id = BridgeCatalogIdentity("bridge", "client")
        cache.replace(id, JSONObject().put("revision", 1))
        try { cache.replace(id, JSONObject().put("padding", "x".repeat(1024 * 1024))); fail("must reject") }
        catch (_: IllegalArgumentException) { }
        assertEquals(1, cache.read(id)!!.getInt("revision"))
    }
}
