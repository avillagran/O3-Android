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
        cache.remove(older)
        cache.close(older)
        assertEquals("new", cache.read(newer)!!.getJSONObject("projection").getString("value"))
    }

    @Test fun clearPreservesDraftOutboxAndUnsentTextAndBlocksLateWrites() = runBlocking {
        val directory = root.newFolder()
        val privateData = listOf("drafts.db", "outbox.db", "unsent.txt").map { root.newFile(it).apply { writeText("keep-$it") } }
        val cache = ThreadCache(directory)
        val lease = cache.open("env", "thread", "authority")!!
        cache.write(lease, snapshot())
        cache.clear()
        cache.write(lease, snapshot("late"))
        assertEquals(0L, cache.usage().bytes)
        privateData.forEach { assertEquals("keep-${it.name}", it.readText()) }
        val next = cache.open("env", "thread", "authority")!!
        cache.write(next, snapshot())
        assertNotNull(cache.read(next))
    }

    @Test fun removingEnvironmentDropsOnlyItsCacheAndRejectsLateOpen() = runBlocking {
        val cache = ThreadCache(root.newFolder())
        cache.retainEnvironments(setOf("a", "b"))
        val a = cache.open("a", "t", "s")!!
        val b = cache.open("b", "t", "s")!!
        cache.write(a, snapshot()); cache.write(b, snapshot())
        cache.retainEnvironments(setOf("b"))
        cache.write(a, snapshot("late"))
        assertNull(cache.open("a", "t", "s"))
        assertNotNull(cache.read(cache.open("b", "t", "s")!!))
        assertEquals(1, cache.usage().entries)
    }

    @Test fun pageWindowIsBoundedAndCannotResumeAcrossAnOmittedGap() = runBlocking {
        val cache = ThreadCache(root.newFolder())
        val lease = cache.open("a", "t", "s")!!
        val messages = org.json.JSONArray((0..600).map { JSONObject().put("id", "$it") })
        cache.write(lease, snapshot().put("projection", JSONObject().put("messages", messages))
            .put("historyCursor", "older").put("hasMoreHistory", true))
        val saved = cache.read(lease)!!
        assertEquals(500, saved.getJSONObject("projection").getJSONArray("messages").length())
        assertEquals("101", saved.getJSONObject("projection").getJSONArray("messages").getJSONObject(0).getString("id"))
        assertTrue(saved.getBoolean("cacheWindowed"))
        assertFalse(saved.getBoolean("hasMoreHistory"))
        assertFalse(saved.has("historyCursor"))
        assertEquals(601, messages.length())
    }

    @Test fun pinnedEntriesWinLruButNeverExceedTotalCap() = runBlocking {
        var now = 100L
        val cache = ThreadCache(root.newFolder(), maxEntries = 2, clock = { now++ })
        val pin = cache.open("a", "pin", "s")!!
        cache.write(pin, snapshot())
        cache.pin("a", setOf("pin"))
        val recent = cache.open("a", "recent", "s")!!; cache.write(recent, snapshot())
        val latest = cache.open("a", "latest", "s")!!; cache.write(latest, snapshot())
        assertNotNull(cache.read(pin)); assertNull(cache.read(recent)); assertNotNull(cache.read(latest))
        assertTrue(cache.usage().bytes <= cache.usage().limit)
    }

    @Test fun sidebarRestoresFromDiskBeforeAnyTransportAndSharesTheBudget() = runBlocking {
        val directory = root.newFolder()
        val cache = ThreadCache(directory)
        val shell = JSONObject().put("snapshotSequence", 12).put("projects", org.json.JSONArray())
            .put("threads", org.json.JSONArray().put(JSONObject().put("id", "t").put("title", "Cached title")))
        cache.writeSidebar("env", "authority", shell)
        cache.reconcile("env", setOf("t"))
        val restarted = ThreadCache(directory)
        val restored = dev.avillagran.ot3android.core.shell.ShellReducer.parse(restarted.readSidebar("env", "authority")!!)
        assertEquals("Cached title", restored.threads.single().title)
        assertEquals(1, restarted.usage().entries)
        assertNull(restarted.readSidebar("other-env", "authority"))
        restarted.clear()
        assertNull(restarted.readSidebar("env", "authority"))
    }
}
