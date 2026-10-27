package dev.avillagran.ot3android.core.thread

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ThreadCacheTest {
    @get:Rule val root = TemporaryFolder()
    private fun snapshot(value: String = "hello") = JSONObject().put("snapshotSequence", 10)
        .put("projection", JSONObject().put("value", value))

    @Test fun restartScopeAndAuthorityAreIsolated() = runBlocking {
        val directory = root.newFolder()
        val first = ThreadCache(directory)
        first.open("a", "same", "server-a")!!.let { first.write(it, snapshot()); first.close(it) }
        val restarted = ThreadCache(directory)
        val a = restarted.open("a", "same", "server-a")!!
        assertEquals("hello", restarted.read(a)!!.getJSONObject("projection").getString("value"))
        assertNull(restarted.read(restarted.open("b", "same", "server-b")!!))
        assertNull(restarted.read(restarted.open("a", "same", "replacement-server")!!))
    }

    @Test fun byteLimitLruAgeAndOversizedEntriesAreBounded() = runBlocking {
        var now = 1_000L
        val directory = root.newFolder()
        val cache = ThreadCache(directory, maxBytes = 700, maxEntryBytes = 800, maxAgeMs = 1_000, clock = { now })
        val a = cache.open("a", "one", "s")!!
        cache.write(a, snapshot("x".repeat(250)))
        now++
        val b = cache.open("a", "two", "s")!!
        cache.write(b, snapshot("x".repeat(250)))
        assertTrue(directory.listFiles()!!.sumOf { it.length() } <= 700)
        assertNull(cache.read(a))
        assertNotNull(cache.read(b))
        cache.write(b, snapshot("x".repeat(2_000)))
        assertNull(cache.read(b))
        cache.write(b, snapshot())
        now += 1_001
        assertNull(cache.read(b))
    }

    @Test fun readingTouchesLruWithoutExtendingPayloadAge() = runBlocking {
        var now = 1_000L
        val cache = ThreadCache(root.newFolder(), maxEntries = 2, maxAgeMs = 100, clock = { now })
        val a = cache.open("a", "one", "s")!!; cache.write(a, snapshot())
        now++
        val b = cache.open("a", "two", "s")!!; cache.write(b, snapshot())
        now++
        assertNotNull(cache.read(a))
        now++
        val c = cache.open("a", "three", "s")!!; cache.write(c, snapshot())
        assertNull(cache.read(b))
        assertNotNull(cache.read(a))
        now += 98
        assertNull(cache.read(a))
    }

    @Test fun closurePreventsLateWritesAndKeepsSiblingDraftData() = runBlocking {
        val directory = root.newFolder()
        val draft = root.newFile("draft.txt").apply { writeText("keep") }
        val cache = ThreadCache(directory)
        val lease = cache.open("a", "closed", "s")!!
        cache.write(lease, snapshot())
        cache.reconcile("a", emptySet())
        cache.write(lease, snapshot("late"))
        assertNull(cache.read(lease))
        assertNull(cache.open("a", "closed", "s"))
        assertEquals("keep", draft.readText())
    }

    @Test fun anOlderReaderCannotOverwriteTheNewReader() = runBlocking {
        val cache = ThreadCache(root.newFolder())
        val older = cache.open("a", "one", "s")!!
        val newer = cache.open("a", "one", "s")!!
        cache.write(newer, snapshot("new"))
        cache.write(older, snapshot("old"))
        assertEquals("new", cache.read(newer)!!.getJSONObject("projection").getString("value"))
    }
}
