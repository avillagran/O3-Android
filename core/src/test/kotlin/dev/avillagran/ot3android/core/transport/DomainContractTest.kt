package dev.avillagran.ot3android.core.transport

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The shapes ot3-Android will build on, for the domain functions the screens do not call yet
 * (UI-2 to UI-6), through [DomainEngine] and the host JNI library. Inputs are lib-t3-rust's
 * `tests/fixtures/timeline-projections.json` (v0.3.3) or minimal literals. Each test names
 * the LIB_REQUESTS row it closes.
 */
class DomainContractTest {

    private val domain = DomainEngine.shared

    @Before
    fun setUp() = assumeHostJni()

    private fun projection(name: String): JSONObject {
        val all = JSONArray(javaClass.getResource("/lib-t3-rust/timeline-projections.json")!!.readText())
        return (0 until all.length()).map { all.getJSONObject(it) }.first { it.getString("name") == name }.getJSONObject("projection")
    }

    private suspend fun call(fn: String, args: JSONObject): Any? = domain.domain(fn, args)

    @Test
    fun u3ActivityDescribe() = runBlocking {
        val item = projection("kitchen-sink").getJSONArray("turnItems").getJSONObject(0)

        val activity = call("activity.describe", JSONObject().put("row", item)) as JSONObject

        assertEquals("ti-1", activity.getString("id"))
        assertEquals("execute", activity.getString("category"))
        assertEquals("cargo", activity.getString("command"))
        assertEquals(setOf("id", "type", "category", "title", "command", "runId", "status", "failed", "active"), activity.keyNames())
    }

    @Test
    fun u0LiveActivity() = runBlocking {
        val live = call("timeline.liveActivity", JSONObject().put("projection", projection("kitchen-sink"))) as JSONArray

        assertTrue(live.length() in 1..3)
        assertTrue((0 until live.length()).all { live.getJSONObject(it).getBoolean("active") })
    }

    @Test
    fun u4ChangesSummaries() = runBlocking {
        val changes = call("changes.summaries", JSONObject().put("projection", projection("kitchen-sink"))) as JSONArray

        val first = changes.getJSONObject(0)
        assertEquals("cp-1", first.getString("id"))
        assertEquals(14, first.getInt("additions"))
        assertEquals("src/lib.rs", first.getJSONArray("files").getJSONObject(0).getString("path"))
    }

    @Test
    fun u5QueueDeriveAndCommand() = runBlocking {
        val queue = call("queue.derive", JSONObject().put("projection", projection("kitchen-sink"))) as JSONObject
        assertEquals("m-queued", queue.getJSONArray("items").getJSONObject(0).getString("messageId"))
        assertTrue(queue.getBoolean("canSteer"))

        val command = call("queue.command", JSONObject().put("thread", "t1").put("action", "resume")) as JSONObject
        assertEquals("queue.resume", command.getString("type"))
    }

    @Test
    fun u6ApprovalsAndQuestions() = runBlocking {
        val p = projection("approvals-and-questions")

        val approvals = call("approvals.pending", JSONObject().put("projection", p)) as JSONArray
        val questions = call("questions.pending", JSONObject().put("projection", p)) as JSONArray
        val approve = call(
            "approvals.command",
            JSONObject().put("thread", "t1").put("runtimeRequestId", "r").put("decision", "accept").put("projection", p),
        ) as JSONObject

        assertEquals("file-read", approvals.getJSONObject(0).getString("requestKind"))
        assertEquals("q", questions.getJSONObject(0).getString("requestId"))
        assertEquals("runtime-request.respond", approve.getString("type"))
    }

    @Test
    fun u7ModelsAndRuntimeMode() = runBlocking {
        val catalog = call("models.catalog", JSONObject().put("config", JSONObject())) as JSONObject
        val command = call(
            "models.runtimeModeCommand",
            JSONObject().put("threadId", "t1").put("commandId", "c1").put("runtimeMode", "full-access"),
        ) as JSONObject

        assertTrue(catalog.has("models") && catalog.has("providers") && catalog.has("capabilities"))
        assertEquals("thread.runtime-mode.set", command.getString("type"))
    }

    @Test
    fun u8ComposerCompletions() = runBlocking {
        val result = JSONObject().put("entries", JSONArray().put(JSONObject().put("path", "src/main.rs").put("kind", "file")))

        val paths = call("composer.paths", JSONObject().put("result", result)) as JSONArray

        assertEquals("src/main.rs", paths.getJSONObject(0).getString("label"))
        assertEquals("[main.rs](/repo/src/main.rs)", call("composer.fileLink", JSONObject().put("path", "/repo/src/main.rs")))
    }

    @Test
    fun u10MarkdownBlocksAndLinks() = runBlocking {
        val blocks = call("markdown.blocks", JSONObject().put("markdown", "# Title\n\n```bash\necho hi\n```")) as JSONArray
        val link = call("markdown.link", JSONObject().put("href", "https://github.com/a/b/pull/3")) as JSONObject

        assertEquals(listOf("heading", "code"), (0 until blocks.length()).map { blocks.getJSONObject(it).getString("type") })
        assertEquals("echo hi", blocks.getJSONObject(1).getString("command"))
        assertEquals("github", link.getString("kind"))
    }

    @Test
    fun u17PairingTarget() = runBlocking {
        val target = call("connectionCatalog.pairingTarget", JSONObject().put("link", "https://t3.example.com/pair#token=abc123")) as JSONObject

        assertEquals("https://t3.example.com", target.getString("httpBaseUrl"))
        assertEquals("abc123", target.getString("pairingCode"))
    }

    @Test
    fun u18HistoryPath() = runBlocking {
        assertEquals(
            "/api/orchestration/threads/t1/history?cursor=c-1",
            call("history.path", JSONObject().put("threadId", "t1").put("cursor", "c-1")),
        )
    }

    @Test
    fun u21ProjectIdentity() = runBlocking {
        val projects = JSONArray().put(JSONObject().put("key", "e/p1").put("title", "ot3-android"))

        val identity = (call("projects.identity", JSONObject().put("projects", projects)) as JSONArray).getJSONObject(0)

        assertEquals("O3", identity.getString("monogram"))
        assertEquals("e/p1", identity.getString("key"))
        assertTrue(identity.has("colorSlot") && identity.has("fill"))
    }

    private fun JSONObject.keyNames(): Set<String> = keys().asSequence().toSet()
}
