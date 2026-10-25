package dev.avillagran.ot3android.core.thread

import dev.avillagran.ot3android.core.thread.ThreadFixtures.event
import dev.avillagran.ot3android.core.thread.ThreadFixtures.message
import dev.avillagran.ot3android.core.thread.ThreadFixtures.projection
import dev.avillagran.ot3android.core.thread.ThreadFixtures.run
import dev.avillagran.ot3android.core.thread.ThreadFixtures.snapshotItem
import dev.avillagran.ot3android.core.thread.ThreadFixtures.unknownEvent
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadProjectionShimTest {

    private val shim = ThreadProjectionShim()

    private fun messages() = ThreadProjectionShim.messages(shim.projection)

    @Test
    fun aSnapshotIsHeldWithItsSequence() {
        assertTrue(
            shim.replace(
                ThreadFixtures.bounded(
                    10,
                    projection(messages = listOf(message("m1", "hello", "2026-10-25T01:00:00.000Z", role = "user"))),
                ),
            ),
        )
        assertEquals(10L, shim.sequence)
        assertEquals(listOf(ThreadMessage("m1", "user", "hello", "2026-10-25T01:00:00.000Z", false, "run-1")), messages())
        assertEquals("Thread t1", ThreadProjectionShim.title(shim.projection))
        assertEquals("gpt-5", ThreadProjectionShim.modelSelection(shim.projection)?.getString("model"))
        assertFalse(shim.replace(JSONObject().put("snapshotSequence", 3)))
    }

    @Test
    fun messagesUpsertByIdAndOrderByCreatedAtThenArrayOrder() {
        shim.replace(ThreadFixtures.bounded(1, projection(messages = listOf(message("b", "B", "2026-10-25T02:00:00.000Z")))))

        assertEquals(ApplyResult.APPLIED, shim.apply(event(2, "message.updated", message("a", "A", "2026-10-25T01:00:00.000Z"))))
        assertEquals(ApplyResult.APPLIED, shim.apply(event(3, "message.updated", message("c", "C", "2026-10-25T02:00:00.000Z"))))
        assertEquals(ApplyResult.APPLIED, shim.apply(event(4, "message.updated", message("b", "B2", "2026-10-25T02:00:00.000Z"))))

        assertEquals(listOf("a" to "A", "b" to "B2", "c" to "C"), messages().map { it.id to it.text })
        assertEquals(4L, shim.sequence)
        assertEquals("a row without an id is skipped", ApplyResult.UNKNOWN, shim.apply(event(5, "message.updated", JSONObject().put("text", "x"))))
        assertEquals(3, messages().size)
    }

    @Test
    fun messageCreatedAndDeletedAreNotGuessedAt() {
        shim.replace(ThreadFixtures.bounded(1, projection(messages = listOf(message("a", "A", "1")))))
        // Not in the lib's apply table: only the cursor moves.
        assertEquals(ApplyResult.UNKNOWN, shim.apply(event(2, "message.created", message("b", "B", "2"))))
        assertEquals(ApplyResult.UNKNOWN, shim.apply(event(3, "message.deleted", JSONObject().put("id", "a"))))
        assertEquals(listOf("a"), messages().map { it.id })
        assertEquals(3L, shim.sequence)
    }

    @Test
    fun aStreamingRowGrowsWithWholeRowUpdates() {
        shim.replace(ThreadFixtures.bounded(1, projection()))
        val at = "2026-10-25T01:00:00.000Z"
        shim.apply(event(2, "message.updated", message("m", "Hel", at, streaming = true)))
        assertEquals(listOf("Hel" to true), messages().map { it.text to it.streaming })
        shim.apply(event(3, "message.updated", message("m", "Hello wor", at, streaming = true)))
        shim.apply(event(4, "message.updated", message("m", "Hello world", at, streaming = false)))
        assertEquals(listOf("Hello world" to false), messages().map { it.text to it.streaming })
    }

    @Test
    fun eventsAtOrBelowTheHeldSequenceAreIgnored() {
        shim.replace(ThreadFixtures.bounded(10, projection()))
        assertEquals(ApplyResult.IGNORED, shim.apply(event(10, "message.updated", message("m", "x", "1"))))
        assertEquals(ApplyResult.IGNORED, shim.apply(event(9, "run.updated", run("r", "running"))))
        assertTrue(messages().isEmpty())
        assertEquals(10L, shim.sequence)
    }

    @Test
    fun runsDriveWorkingAndTheActiveRun() {
        shim.replace(ThreadFixtures.bounded(1, projection()))
        assertFalse(ThreadProjectionShim.working(shim.projection))

        shim.apply(event(2, "run.created", run("r1", "preparing")))
        assertTrue(ThreadProjectionShim.working(shim.projection))
        assertEquals("r1", ThreadProjectionShim.activeRunId(shim.projection))

        shim.apply(event(3, "run.updated", run("r1", "running")))
        assertEquals(1, shim.projection!!.getJSONArray("runs").length())

        shim.apply(event(4, "run.updated", run("r1", "completed")))
        assertFalse(ThreadProjectionShim.working(shim.projection))
        assertNull(ThreadProjectionShim.activeRunId(shim.projection))

        // A waiting run with no activeRunId still counts as working.
        shim.replace(ThreadFixtures.bounded(5, projection(runs = listOf(run("r2", "waiting")))))
        assertTrue(ThreadProjectionShim.working(shim.projection))
        assertEquals("r2", ThreadProjectionShim.interruptibleRunId(shim.projection))
    }

    @Test
    fun anyThreadEventNamingThisThreadReplacesIt() {
        shim.replace(ThreadFixtures.bounded(1, projection()))
        val renamed = JSONObject().put("id", "t1").put("title", "Renamed").put("activeRunId", "r9")
        assertEquals(ApplyResult.APPLIED, shim.apply(event(2, "thread.updated", renamed)))
        assertEquals("Renamed", ThreadProjectionShim.title(shim.projection))
        assertTrue(ThreadProjectionShim.working(shim.projection))
        assertEquals(ApplyResult.APPLIED, shim.apply(event(3, "thread.archived", JSONObject().put("id", "t1").put("title", "Archived"))))
        assertEquals("Archived", ThreadProjectionShim.title(shim.projection))

        assertEquals(ApplyResult.UNKNOWN, shim.apply(event(4, "thread.updated", JSONObject().put("id", "other").put("title", "x"))))
        assertEquals("Archived", ThreadProjectionShim.title(shim.projection))
    }

    @Test
    fun aPullRequestLinkUpdatePatchesItsLinkOnly() {
        val links = JSONArray().put(JSONObject().put("host", "github.com").put("repository", "a/b").put("number", 3).put("url", "u"))
        val p = projection()
        p.getJSONObject("thread").put("pullRequests", links)
        shim.replace(ThreadFixtures.bounded(1, p))

        val update = JSONObject().put("host", "github.com").put("repository", "a/b").put("number", 3).put("url", "u2")
        assertEquals(ApplyResult.APPLIED, shim.apply(event(2, "thread.pull-request-link-updated", update)))
        val thread = shim.projection!!.getJSONObject("thread")
        assertEquals("u2", thread.getJSONArray("pullRequests").getJSONObject(0).getString("url"))
        assertEquals("the thread row stays", "t1", thread.getString("id"))
    }

    @Test
    fun runtimeRequestsUpsertWholeRows() {
        shim.replace(ThreadFixtures.bounded(1, projection()))
        val pending = JSONObject().put("id", "q1").put("kind", "command").put("status", "pending").put("prompt", "ls")
        assertEquals(ApplyResult.APPLIED, shim.apply(event(2, "runtime-request.updated", pending)))
        assertEquals(listOf(RuntimeRequest("q1", "command", "ls")), ThreadProjectionShim.pendingRequests(shim.projection))

        assertEquals(ApplyResult.APPLIED, shim.apply(event(3, "runtime-request.updated", JSONObject(pending.toString()).put("status", "resolved"))))
        assertTrue(ThreadProjectionShim.pendingRequests(shim.projection).isEmpty())
        assertEquals(1, shim.projection!!.getJSONArray("runtimeRequests").length())
    }

    @Test
    fun cancelledBackgroundWorkIsMirroredOnItsRun() {
        shim.replace(ThreadFixtures.bounded(1, projection(runs = listOf(run("r", "completed")))))
        val payload = JSONObject().put("runId", "r").put("restartCancelledBackgroundWork", JSONArray().put(JSONObject().put("k", 1)))
        assertEquals(ApplyResult.APPLIED, shim.apply(event(2, "run.background-work-cancelled", payload)))
        val run = shim.projection!!.getJSONArray("runs").getJSONObject(0)
        assertEquals(1, run.getJSONArray("restartCancelledBackgroundWork").getJSONObject(0).getInt("k"))
    }

    @Test
    fun eventsTheLibReducesIntoOtherRowsAreIgnoredAndMoveTheCursor() {
        shim.replace(ThreadFixtures.bounded(1, projection()))
        val before = shim.projection.toString()
        var seq = 1L
        for (type in ThreadProjectionShim.IRRELEVANT + ThreadProjectionShim.IRRELEVANT_PARTIAL) {
            assertEquals(type, ApplyResult.IGNORED, shim.apply(event(++seq, type, JSONObject().put("id", "x").put("providerSessionId", "s"))))
        }
        assertEquals(seq, shim.sequence)
        assertEquals("nothing this view reads changed", before, shim.projection.toString())
    }

    @Test
    fun eventsTheLibDoesNotKnowOnlyMoveTheCursor() {
        shim.replace(ThreadFixtures.bounded(1, projection()))
        val before = shim.projection.toString()
        // Observed live right after subscribing: not in the lib's apply table.
        assertEquals(ApplyResult.UNKNOWN, shim.apply(event(2, "thread.visited", JSONObject().put("threadId", "t1").put("visitedAt", "x"))))
        assertEquals(ApplyResult.UNKNOWN, shim.apply(event(3, "future.event", JSONObject().put("id", "f"))))
        assertEquals(3L, shim.sequence)
        assertEquals(before, shim.projection.toString())
        // Cursor-only and marker items change nothing either.
        assertEquals(ApplyResult.IGNORED, shim.apply(unknownEvent(7)))
        assertEquals(7L, shim.sequence)
        assertEquals(ApplyResult.IGNORED, shim.apply(JSONObject().put("kind", "synchronized")))
        assertEquals(ApplyResult.IGNORED, shim.apply(JSONObject().put("kind", "something-new")))
        assertEquals(7L, shim.sequence)
    }

    @Test
    fun aSnapshotWithoutProjectionOrSequenceIsMalformedAndKeepsTheHeldOne() {
        shim.replace(ThreadFixtures.bounded(1, projection(messages = listOf(message("a", "A", "1")))))
        assertEquals(ApplyResult.MALFORMED, shim.apply(JSONObject().put("kind", "snapshot").put("snapshotSequence", 9)))
        assertEquals(ApplyResult.MALFORMED, shim.apply(JSONObject().put("kind", "snapshot").put("projection", projection())))
        assertFalse(shim.replace(JSONObject().put("projection", projection())))
        assertEquals(1L, shim.sequence)
        assertEquals(listOf("a"), messages().map { it.id })
        assertEquals("no snapshot to apply to", ApplyResult.MALFORMED, ThreadProjectionShim().apply(event(2, "message.updated", message("b", "B", "2"))))
    }

    @Test
    fun aSnapshotOnAResumedStreamReplacesInsteadOfMerging() {
        shim.replace(ThreadFixtures.bounded(1, projection(messages = listOf(message("old", "old", "1")))))
        shim.apply(event(2, "message.updated", message("live", "live", "2")))

        val fresh = projection(messages = listOf(message("new", "new", "3")))
        assertEquals(ApplyResult.APPLIED, shim.apply(snapshotItem(40, fresh)))

        assertEquals(listOf("new"), messages().map { it.id })
        assertEquals(40L, shim.sequence)
    }

    @Test
    fun messagesStayBoundedLikeTheLib() {
        shim.replace(ThreadFixtures.bounded(0, projection()))
        for (i in 1..200) shim.apply(event(i.toLong(), "message.updated", message("m$i", "x", "%04d".format(i))))
        val ids = messages().map { it.id }
        assertEquals(ThreadProjectionShim.MESSAGES_CAP, ids.size)
        assertEquals("m41", ids.first())
        assertEquals("m200", ids.last())
    }

    @Test
    fun pendingRequestsCarryTheirKindAndPrompt() {
        shim.replace(
            ThreadFixtures.bounded(
                1,
                projection(
                    runtimeRequests = listOf(
                        JSONObject().put("id", "q1").put("kind", "command").put("status", "pending"),
                        JSONObject().put("id", "q2").put("kind", "user_input").put("status", "resolved"),
                    ),
                    turnItems = listOf(
                        JSONObject().put("type", "approval_request").put("requestId", "q1").put("prompt", "old"),
                        JSONObject().put("type", "approval_request").put("requestId", "q1").put("prompt", "rm -rf ./build"),
                    ),
                ),
            ),
        )
        assertEquals(listOf(RuntimeRequest("q1", "command", "rm -rf ./build")), ThreadProjectionShim.pendingRequests(shim.projection))
    }
}
