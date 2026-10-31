package dev.avillagran.ot3android.core.thread

import dev.avillagran.ot3android.core.thread.ThreadFixtures.event
import dev.avillagran.ot3android.core.thread.ThreadFixtures.message
import dev.avillagran.ot3android.core.thread.ThreadFixtures.projection
import dev.avillagran.ot3android.core.thread.ThreadFixtures.run
import dev.avillagran.ot3android.core.transport.assumeHostJni
import dev.avillagran.t3.T3Exception
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [ThreadTimelines] over the real lib (`projection.apply`, `timeline.view`, `markdown.blocks`
 * through the host JNI library). The named projections are lib-t3-rust's own
 * `tests/fixtures/timeline-projections.json` (v0.3.3).
 */
class ThreadTimelineTest {

    @Test fun markdownImagesParseInsideListsTablesAndReferenceSyntax() = runBlocking {
        val source = "Before\n\n![Controlando el otro equipo](/home/avillagran/Work/gliff-fork.VaT72V/.github/assets/screenshots/control-driving.png)\n\n- ![List](assets/list.png)\n\n| Image |\n| --- |\n| ![Table](table.png) |\n\n![Again][ref]\n\n[ref]: file:///tmp/second.png"
        val blocks = domain.domain("markdown.blocks", JSONObject().put("markdown", source)) as JSONArray
        val image = blocks.getJSONObject(1)
        assertEquals("image", image.getString("type"))
        assertEquals("Controlando el otro equipo", image.getString("alt"))
        assertTrue(image.getString("id").startsWith("markdown-"))
        assertEquals("assets/list.png", blocks.getJSONObject(2).getJSONArray("items").getJSONObject(0)
            .getJSONArray("blocks").getJSONObject(0).getString("url"))
        assertEquals("table.png", blocks.getJSONObject(3).getJSONArray("rows").getJSONArray(0)
            .getJSONArray(0).getJSONObject(0).getString("url"))
        assertEquals("file:///tmp/second.png", blocks.getJSONObject(4).getString("url"))
    }

    private val domain = TestDomain

    @Before
    fun setUp() = assumeHostJni()

    private fun libFixture(name: String): JSONObject {
        val all = JSONArray(javaClass.getResource("/lib-t3-rust/timeline-projections.json")!!.readText())
        return (0 until all.length()).map { all.getJSONObject(it) }.first { it.getString("name") == name }
    }

    private fun events(vararg items: JSONObject) = items.map { it.getJSONObject("event") }

    @Test
    fun applyUpsertsMessagesAndKeepsTheActiveRun() = runBlocking {
        val start = projection(messages = listOf(message("m1", "hello", T1, role = "user")))

        val result = ThreadTimelines.apply(
            domain,
            start,
            events(
                event(11, "message.updated", message("m2", "Hi", T2, streaming = true)),
                event(12, "message.updated", message("m2", "Hi there", T2)),
                event(13, "run.updated", run("r1", "running")),
            ),
        )

        assertEquals(3, result.applied)
        val view = ThreadTimelines.view(domain, result.projection)
        assertEquals(listOf("m1", "m2"), view.messages.map { it.id })
        assertEquals(listOf("user", "assistant"), view.messages.map { it.role })
        assertEquals("Hi there", view.messages.last().text)
        assertFalse(view.messages.last().streaming)
        assertTrue(view.working)
        assertEquals("r1", view.activeRunId)
        assertTrue("every row has a rev", view.messages.all { !it.rev.isNullOrEmpty() })

        val done = ThreadTimelines.apply(domain, result.projection, events(event(14, "run.updated", run("r1", "completed"))))
        val after = ThreadTimelines.view(domain, done.projection)
        assertFalse(after.working)
        assertNull(after.activeRunId)
    }

    @Test
    fun typesTheLibDoesNotKnowChangeNothing() = runBlocking {
        val start = projection(messages = listOf(message("m1", "hello", T1, role = "user")))

        val result = ThreadTimelines.apply(
            domain,
            start,
            events(
                event(11, "thread.visited", JSONObject().put("threadId", "t1")),
                event(12, "future.event", JSONObject().put("id", "x")),
            ),
        )

        assertEquals(0, result.applied)
        assertEquals(listOf("m1"), ThreadTimelines.view(domain, result.projection).messages.map { it.id })
    }

    @Test
    fun applyNeedsAtLeastOneEvent() {
        val error = assertThrows(T3Exception::class.java) {
            runBlocking { ThreadTimelines.apply(domain, projection(), emptyList()) }
        }
        assertEquals("invalid_event", error.code)
    }

    @Test
    fun anUnreadableProjectionIsMalformed() {
        val broken = projection(messages = listOf(JSONObject().put("id", "m1").put("role", "user").put("text", 7)))
        val error = assertThrows(T3Exception::class.java) { runBlocking { ThreadTimelines.view(domain, broken) } }
        assertEquals("malformed", error.code)
    }

    @Test
    fun approvalsAndQuestionsBecomeBanners() = runBlocking {
        val view = ThreadTimelines.view(domain, libFixture("approvals-and-questions").getJSONObject("projection"))

        assertEquals(
            listOf(RuntimeRequest("r", "file-read", "/etc/hosts"), RuntimeRequest("q", "user_input", "When?")),
            view.pendingRequests,
        )
        assertEquals(1, view.raw.getJSONArray("approvals").length())
        assertEquals(1, view.raw.getJSONArray("questions").length())
    }

    @Test
    fun theKitchenSinkKeepsMessagesAsBubblesAndTheRestForUi2() = runBlocking {
        val fixture = libFixture("kitchen-sink")
        val history = ThreadTimelines.History.of(fixture)

        val view = ThreadTimelines.view(domain, fixture.getJSONObject("projection"), history)

        // HTML and MCP app rows (assistant rows with only an attachment) wait for UI-2.
        assertEquals(listOf("m-1", "m-2", "m-3", "m-auto", "m-agent", "m-4"), view.messages.map { it.id })
        assertTrue(view.messages.last().streaming)
        assertTrue(view.working)
        assertEquals("run-3", view.activeRunId)
        val roles = (0 until view.raw.getJSONArray("timeline").length())
            .map { view.raw.getJSONArray("timeline").getJSONObject(it).getString("role") }.toSet()
        assertEquals(setOf("user", "assistant", "activity", "changes", "context", "plan", "secret", "subagents", "error"), roles)
        assertEquals(fixture.getBoolean("hasMoreHistory"), view.raw.getBoolean("hasMoreHistory"))
        assertEquals(fixture.get("historyCursor"), view.raw.get("historyCursor"))
    }

    @Test
    fun blocksFlattenToPlainText() = runBlocking {
        val markdown = "Hello **world** and `code`\n\n- a\n- b\n\n> quoted\n\n```kt\nval x = 1\n```\n\n---"
        val blocks = domain.domain("markdown.blocks", JSONObject().put("markdown", markdown)) as JSONArray

        assertEquals("Hello world and code\na\nb\nquoted\nval x = 1", ThreadTimelines.plainText(blocks))
    }

    private companion object {
        const val T1 = "2026-10-25T01:00:01.000Z"
        const val T2 = "2026-10-25T01:00:02.000Z"
    }
}
