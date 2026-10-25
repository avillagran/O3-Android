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
        assertSame("an identical snapshot changes nothing", replaced, apply(replaced, snapshot(9, listOf(project("p2")), listOf(thread("t9")))))
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
