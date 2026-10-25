package dev.avillagran.ot3android.core.shell

import dev.avillagran.ot3android.core.shell.ShellFixtures.identityRefresh
import dev.avillagran.ot3android.core.shell.ShellFixtures.project
import dev.avillagran.ot3android.core.shell.ShellFixtures.projectRemoved
import dev.avillagran.ot3android.core.shell.ShellFixtures.projectUpdated
import dev.avillagran.ot3android.core.shell.ShellFixtures.snapshot
import dev.avillagran.ot3android.core.shell.ShellFixtures.thread
import dev.avillagran.ot3android.core.shell.ShellFixtures.threadRemoved
import dev.avillagran.ot3android.core.shell.ShellFixtures.threadUpdated
import dev.avillagran.ot3android.core.transport.ThreadUpdates
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ShellReducerTest {

    private val base = snapshot(5, listOf(project("p1", identity = "repo")), listOf(thread("t1"), thread("t2")))

    private fun reduced(vararg items: JSONObject): ShellState? = apply(null, *items)

    private fun apply(state: ShellState?, vararg items: JSONObject): ShellState? =
        ShellReducer.reduce(state, ThreadUpdates.batch(*items))

    @Test
    fun snapshotReplacesTheShellAndSetsTheCursor() {
        val shell = reduced(base)!!

        assertEquals(5L, shell.sequence)
        assertEquals(listOf("t1", "t2"), shell.threads.map { it.id })
        assertEquals("""{"canonicalKey":"repo"}""", shell.projects.single().repositoryIdentity)

        val replaced = apply(shell, snapshot(9, listOf(project("p2")), listOf(thread("t9"))))!!
        assertEquals(9L, replaced.sequence)
        assertEquals(listOf("t9"), replaced.threads.map { it.id })
    }

    @Test
    fun rowsAreIndexedAndKeepTheirRawJson() {
        val row = thread("t1", "p1", "modelSelection" to JSONObject().put("model", "m-1"), "runtimeMode" to "full-access")
        val shell = reduced(base, threadUpdated(6, row))!!

        assertEquals("Thread t1", shell.thread("t1")?.title)
        assertNull(shell.thread("missing"))
        assertEquals("m-1", shell.thread("t1")?.raw?.optJSONObject("modelSelection")?.optString("model"))
        assertEquals("full-access", shell.thread("t1")?.raw?.optString("runtimeMode"))
        assertEquals("/w/p1", shell.project("p1")?.raw?.optString("workspaceRoot"))
        assertNull(shell.project("p9"))
    }

    @Test
    fun parsesTheFieldsTheSidebarReads() {
        val row = thread(
            "t1", "p1",
            "settledOverride" to true,
            "pendingRuntimeRequest" to JSONObject().put("id", "r1").put("kind", "approval").put("blocking", true),
            "lineage" to JSONObject().put("parentThreadId", "t0").put("relationshipToParent", "fork").put("rootThreadId", "t0"),
            "lastError" to JSONObject().put("message", "boom"),
            "unknownFutureField" to 1,
        )
        val parsed = reduced(snapshot(1, emptyList(), listOf(row, JSONObject().put("title", "no id"))))!!.threads.single()

        assertEquals("settled", parsed.settledOverride)
        assertEquals(PendingRequest("r1", "approval", true), parsed.pendingRuntimeRequest)
        assertEquals("t0", parsed.parentThreadId)
        assertEquals("fork", parsed.relationshipToParent)
        assertEquals("""{"message":"boom"}""", parsed.lastError)
    }

    @Test
    fun timestampsAreReadAsEpochMillis() {
        val row = thread(
            "t1", "p1",
            "createdAt" to "2026-10-24T10:00:00.123Z",
            "settledAt" to "2026-10-24T12:30:00+02:00",
            "pinnedAt" to 1_761_300_000_000L,
            "archivedAt" to "not a date",
            "deletedAt" to null,
        )
        val parsed = reduced(snapshot(1, emptyList(), listOf(row)))!!.threads.single()

        assertEquals(1_792_836_000_123L, parsed.createdAt)
        assertEquals("the offset is applied", 1_792_837_800_000L, parsed.settledAt)
        assertEquals(1_761_300_000_000L, parsed.pinnedAt)
        assertEquals("present but unreadable still counts as set", 0L, parsed.archivedAt)
        assertNull(parsed.deletedAt)
        assertEquals(0L, ShellReducer.parseTimestamp("1970-01-01T00:00:00Z"))
        assertEquals(951_782_400_000L, ShellReducer.parseTimestamp("2000-02-29T00:00:00.000Z"))
        assertEquals(-86_400_000L, ShellReducer.parseTimestamp("1969-12-31T00:00:00Z"))
        assertNull(ShellReducer.parseTimestamp("yesterday"))
    }

    @Test
    fun threadUpdatedUpsertsInPlaceAndMovesTheCursor() {
        val renamed = thread("t1", "p1", "title" to "Renamed")
        val shell = reduced(base, threadUpdated(6, renamed), threadUpdated(7, thread("t3")))!!

        assertEquals(7L, shell.sequence)
        assertEquals(listOf("t1", "t2", "t3"), shell.threads.map { it.id })
        assertEquals("Renamed", shell.threads.first().title)
    }

    @Test
    fun anArchiveLocatedUpdateRemovesTheThread() {
        val shell = reduced(base, threadUpdated(6, thread("t1"), location = "archive"))!!
        assertEquals(listOf("t2"), shell.threads.map { it.id })
    }

    @Test
    fun threadRemovedAndProjectEvents() {
        val shell = reduced(
            base,
            threadRemoved(6, "t2"),
            projectUpdated(7, project("p2", title = "Second")),
            projectRemoved(8, "p1"),
        )!!

        assertEquals(listOf("t1"), shell.threads.map { it.id })
        assertEquals(listOf("p2"), shell.projects.map { it.id })
        assertEquals(8L, shell.sequence)
    }

    @Test
    fun eventsAtOrBelowTheCursorAreIgnored() {
        val shell = reduced(base, threadRemoved(6, "t2"))!!

        assertSame(shell, apply(shell, threadRemoved(6, "t1")))
        assertSame(shell, apply(shell, threadUpdated(3, thread("t9"))))
    }

    @Test
    fun eventsWithoutAShellWaitForTheSnapshot() {
        assertNull(reduced(threadUpdated(6, thread("t1")), identityRefresh(7, listOf("/w/p1"), emptyList())))
    }

    @Test
    fun metadataOnlySnapshotKeepsThreadsAndCursorAndMergesIdentities() {
        val unresolved = snapshot(5, listOf(project("p1")), listOf(thread("t1")))
        // The live server sends both frames in one batch, the metadata one with no threads.
        val refresh = identityRefresh(40, listOf("/w/p1"), listOf(project("p1", identity = "resolved")))
        val shell = reduced(unresolved, refresh)!!

        assertEquals("a resume prefix must not skip replayed events", 5L, shell.sequence)
        assertEquals(listOf("t1"), shell.threads.map { it.id })
        assertEquals("""{"canonicalKey":"resolved"}""", shell.projects.single().repositoryIdentity)

        val again = apply(shell, refresh)
        assertSame("nothing new to merge", shell, again)
    }

    @Test
    fun aProjectUpdateWithoutIdentityKeepsTheResolvedOne() {
        val shell = reduced(base, projectUpdated(6, project("p1", title = "Renamed")))!!

        assertEquals("Renamed", shell.projects.single().title)
        assertEquals("""{"canonicalKey":"repo"}""", shell.projects.single().repositoryIdentity)
    }

    @Test
    fun unknownItemsAreIgnored() {
        val shell = reduced(base)!!
        assertSame(shell, apply(shell, JSONObject().put("kind", "synchronized")))
        assertSame(shell, apply(shell, JSONObject().put("kind", "thread.whatever").put("sequence", 99)))
    }
}
