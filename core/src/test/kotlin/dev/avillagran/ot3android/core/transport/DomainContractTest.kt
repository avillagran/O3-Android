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
 * (UI-2 to UI-7), through [DomainEngine] and the host JNI library. Inputs are lib-t3-rust's
 * `tests/fixtures/timeline-projections.json` (v0.3.3), minimal literals taken from the lib's own
 * tests (`tests/domain_*.rs`, `subagents.rs`, `timeline_minimap.rs`, `i18n_catalog.rs` at
 * v0.3.4) or Omarchy 4.0.2's `themes/<id>/colors.toml` (MIT, copied under `omarchy/`). Each
 * test names the LIB_REQUESTS row it closes.
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
    fun activeTaskProjectionCrossesTheNativeBoundaryWithoutClientDerivation() = runBlocking {
        val p = projection("kitchen-sink")
        val view = call("timeline.view", JSONObject().put("projection", p)) as JSONObject
        val tasks = view.getJSONObject("tasks")
        assertEquals("run-3", tasks.getString("runId"))
        assertEquals("Test", tasks.getString("current"))
        assertEquals(1, tasks.getInt("completed"))
        assertEquals(2, tasks.getInt("total"))
        assertEquals(3000, tasks.getJSONArray("items").getJSONObject(0).getInt("durationMs"))
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
            "/api/orchestration/threads/t1/history?cursor=c-1&compactIds=1",
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

    @Test
    fun u9DiffReviewPresent() = runBlocking {
        val patch = "diff --git a/src/a.rs b/src/a.rs\n--- a/src/a.rs\n+++ b/src/a.rs\n@@ -1,2 +1,2 @@\n keep\n-old\n+new\n"
        val result = JSONObject().put("diff", patch).put("fromTurnCount", 1).put("toTurnCount", 2)

        val view = call("diffReview.present", JSONObject().put("result", result)) as JSONObject

        assertEquals(setOf("additions", "deletions", "files", "fromTurnCount", "toTurnCount"), view.keyNames())
        val file = view.getJSONArray("files").getJSONObject(0)
        assertEquals("src/a.rs", file.getString("path"))
        assertTrue(file.has("rows") && file.has("splitRows"))
    }

    @Test
    fun u11ThemeTokensTokyoNight() = runBlocking {
        val tokens = call("theme.tokens", JSONObject().put("colors", omarchy("tokyo-night")).put("id", "tokyo-night")) as JSONObject

        assertEquals(THEME_KEYS, tokens.keyNames())
        assertEquals("Tokyo Night", tokens.getString("name"))
        assertEquals("dark", tokens.getString("appearance"))
        assertEquals("#6b718a", tokens.getJSONObject("roles").getString("dim"))
        assertEquals("#21222e", tokens.getJSONObject("roles").getString("surface"))
    }

    @Test
    fun u11ThemeTokensCatppuccinLatte() = runBlocking {
        val tokens = call("theme.tokens", JSONObject().put("colors", omarchy("catppuccin-latte")).put("id", "catppuccin-latte")) as JSONObject

        assertEquals(THEME_KEYS, tokens.keyNames())
        assertEquals("light", tokens.getString("appearance"))
        assertEquals("#9295a6", tokens.getJSONObject("roles").getString("dim"))
        assertEquals("#d20f39", tokens.getJSONObject("roles").getString("failed"))
    }

    @Test
    fun u12TimelineMinimap() = runBlocking {
        val rows = JSONArray()
            .put(JSONObject().put("id", "u1").put("role", "user").put("text", "Fix\nthe   build"))
            .put(JSONObject().put("id", "a1").put("role", "assistant").put("text", "Done."))
            .put(JSONObject().put("id", "u2").put("role", "user").put("text", "And tests?"))

        val map = call("timeline.minimap", JSONObject().put("rows", rows).put("atEnd", true)) as JSONObject

        assertEquals(setOf("items", "visible", "currentIndex", "visibleIds"), map.keyNames())
        assertEquals("Fix the build", map.getJSONArray("items").getJSONObject(0).getString("userPreview"))
        assertEquals(1, map.getInt("currentIndex"))
        assertEquals(2, call("timeline.minimapIndex", JSONObject().put("y", 75.0).put("height", 100.0).put("count", 3)))
    }

    @Test
    fun u13SubagentBuckets() = runBlocking {
        val subagents = JSONArray()
            .put(JSONObject().put("status", "waiting"))
            .put(JSONObject().put("status", "interrupted"))
            .put(JSONObject().put("status", "idle"))
            .put(JSONObject())

        val buckets = call("subagents.buckets", JSONObject().put("subagents", subagents)) as JSONObject

        assertEquals(setOf("working", "done", "failed", "stopped", "idle"), buckets.keyNames())
        assertEquals(listOf(1, 0, 0, 2, 1), listOf("working", "done", "failed", "stopped", "idle").map(buckets::getInt))
    }

    @Test
    fun u14UsageRequestAndQuotaLimits() = runBlocking {
        val request = call(
            "usageSummary.request",
            JSONObject().put("period", "week").put("today", "2026-10-20").put("timeZone", "America/Santiago"),
        ) as JSONObject
        val window = JSONObject().put("id", "5h").put("kind", "session").put("label", "5 hours").put("usedPercent", 42.5)
        val limits = call(
            "quotaSources.limits",
            JSONObject().put("usageLimits", JSONObject().put("checkedAt", "2026-10-18T12:00:00Z").put("windows", JSONArray().put(window))),
        ) as JSONObject

        assertEquals(setOf("sinceDay", "untilDay", "payload"), request.keyNames())
        assertEquals("2026-10-14", request.getString("sinceDay"))
        assertEquals("America/Santiago", request.getJSONObject("payload").getString("timeZone"))
        assertEquals(setOf("checkedAt", "windows"), limits.keyNames())
    }

    @Test
    fun u15PlanWorkflowView() = runBlocking {
        val plan = JSONObject().put("id", "plan-1").put("threadId", "thread-1").put("runId", "run-1").put("status", "active")
            .put("kind", "proposed_plan").put("markdown", "# Native Plan\n\n## Summary\nShip **it**")
        val projection = JSONObject()
            .put("thread", JSONObject().put("id", "thread-1").put("interactionMode", "plan"))
            .put("runs", JSONArray().put(JSONObject().put("id", "run-1").put("ordinal", 1).put("status", "completed")))
            .put("turnItems", JSONArray().put(JSONObject().put("item", JSONObject().put("type", "proposed_plan").put("planId", "plan-1"))))
            .put("messages", JSONArray())
            .put("plans", JSONArray().put(plan))

        val view = call("planWorkflow.view", JSONObject().put("projection", projection)) as JSONObject

        assertEquals(setOf("plans", "ready"), view.keyNames())
        val first = view.getJSONArray("plans").getJSONObject(0)
        assertEquals("Native Plan", first.getString("title"))
        assertTrue(first.get("blocks") is JSONArray)
        assertEquals("plan-1", view.getJSONObject("ready").getString("id"))
    }

    @Test
    fun u16StringsInSpanishAndArabic() = runBlocking {
        assertEquals("Cerrar", call("i18n.t", JSONObject().put("locale", "es").put("key", "Close")))
        assertEquals("إغلاق", call("i18n.t", JSONObject().put("locale", "ar").put("key", "Close")))
        val arabic = call("i18n.resolve", JSONObject().put("locale", "ar")) as JSONObject
        assertEquals(setOf("locale", "language", "rtl"), arabic.keyNames())
        assertTrue(arabic.getBoolean("rtl"))
        val catalog = call("i18n.catalog", JSONObject().put("locale", "es")) as JSONObject
        assertEquals(2027, catalog.getJSONObject("strings").length())
    }

    @Test
    fun u19PullRequestBadge() = runBlocking {
        val link = JSONObject().put("number", 7).put("url", "https://github.com/o/r/pull/7").put("repository", "o/r")
            .put("snapshot", JSONObject().put("state", "open"))
        val thread = JSONObject().put("projectId", "p").put("pullRequests", JSONArray().put(link))

        val badge = call("pullRequests.sidebarBadge", JSONObject().put("thread", thread)) as JSONObject

        assertEquals(setOf("current", "kind", "links", "state", "text"), badge.keyNames())
        assertEquals(listOf("single", "7", "open"), listOf("kind", "text", "state").map(badge::getString))
    }

    @Test
    fun u20BranchPickerSelectionTarget() = runBlocking {
        val target = call(
            "branchPicker.selectionTarget",
            JSONObject().put("projectCwd", "/p").put("ref", JSONObject().put("name", "feat").put("worktreePath", "/w")),
        ) as JSONObject

        assertEquals(setOf("checkoutCwd", "nextWorktreePath", "reuseExistingWorktree"), target.keyNames())
        assertEquals("/w", target.getString("checkoutCwd"))
        assertTrue(target.getBoolean("reuseExistingWorktree"))
    }

    @Test
    fun u22GitControlsPresentAndConfirmedAction() = runBlocking {
        val shell = JSONObject()
            .put("projects", JSONArray().put(JSONObject().put("id", "p").put("workspaceRoot", "/remote/project").put("scripts", JSONArray())))
            .put("threads", JSONArray().put(JSONObject().put("id", "t").put("projectId", "p").put("worktreePath", "/remote/tree")))
        val status = JSONObject().put("isRepo", true).put("hasPrimaryRemote", true).put("isDefaultRef", false).put("refName", "feature/x")
            .put("hasWorkingTreeChanges", true).put("hasUpstream", true).put("aheadCount", 1).put("behindCount", 0).put("pr", JSONObject.NULL)
            .put("workingTree", JSONObject().put("files", JSONArray()).put("insertions", 2).put("deletions", 1))
        val scope = call(
            "gitControls.scope",
            JSONObject().put("shell", shell).put("environment", "remote").put("thread", "t").put("requestId", "r1")
                .put("projectId", "p").put("cwd", "/remote/tree"),
        ) as JSONObject

        val sheet = call("gitControls.present", JSONObject().put("scope", scope).put("status", status).put("scripts", JSONArray())) as JSONObject
        val intent = JSONObject().put("scope", sheet.getJSONObject("scope")).put("revision", sheet.get("revision"))
            .put("operation", "commit_push").put("actionId", "dd38bad7-c625-438d-9f12-165f25521753").put("confirmed", true)
        val plan = call("gitControls.buildAction", JSONObject().put("current", sheet).put("intent", intent)) as JSONObject

        assertEquals(setOf("actions", "revision", "scope", "scripts", "status"), sheet.keyNames())
        assertEquals(setOf("actionId", "operation", "retryAutomatically", "revision", "scope", "steps"), plan.keyNames())
        val step = plan.getJSONArray("steps").getJSONObject(0)
        assertEquals(setOf("tag", "payload"), step.keyNames())
        assertEquals("git.runStackedAction", step.getString("tag"))
    }

    @Test
    fun u23NativeAndScopedSettings() = runBlocking {
        val settings = JSONObject().put("defaultRuntimeMode", "full-access").put("apiKey", "PRIVATE")
            .put("projectSettingsOverrides", JSONObject().put("p", JSONObject().put("defaultRuntimeMode", "approval-required")))

        val snapshot = call("nativeSettings.snapshot", JSONObject().put("settings", settings)) as JSONObject
        val effective = call(
            "scopedSettings.effective",
            JSONObject().put("settings", settings).put("project", JSONObject().put("id", "p").put("workspaceRoot", "/r")),
        ) as JSONObject

        assertEquals(setOf("fields"), snapshot.keyNames())
        assertTrue(!snapshot.toString().contains("PRIVATE"))
        assertEquals(setOf("overrides", "settings", "sources"), effective.keyNames())
        assertEquals("project", effective.getJSONObject("sources").getString("defaultRuntimeMode"))
    }

    @Test
    fun u24CheckpointRevertSafety() = runBlocking {
        val open = JSONObject().put("canOperate", true).put("connected", true).put("supportsConversationRollback", true)

        val gate = call("checkpointRevert.safety", JSONObject().put("safety", open)) as JSONObject
        val busy = call("checkpointRevert.safety", JSONObject().put("safety", JSONObject(open.toString()).put("working", true))) as JSONObject

        assertEquals(setOf("allowed", "error", "safety"), gate.keyNames())
        assertTrue(gate.getBoolean("allowed"))
        assertEquals("revert_interrupt_first", busy.getString("error"))
    }

    @Test
    fun u25ToolInspectorRequest() = runBlocking {
        val reference = JSONObject().put("sourceThreadId", "thread:source").put("sourceItemId", "item:1").put("type", "command_execution")
            .put("runId", "run:1").put("providerTurnId", "turn:1").put("revision", "2026-10-17T12:00:01.000Z")
        val viewer = JSONObject().put("reference", reference).put("environment", "laptop").put("threadId", "thread:viewer")

        val request = call("toolInspector.request", viewer) as JSONObject

        assertEquals(setOf("method", "payload"), request.keyNames())
        assertEquals("orchestration.getTurnItem", request.getString("method"))
        assertEquals(setOf("threadId", "itemId", "revision"), request.getJSONObject("payload").keyNames())
    }

    private fun omarchy(id: String): String = javaClass.getResource("/omarchy/$id/colors.toml")!!.readText()

    private fun JSONObject.keyNames(): Set<String> = keys().asSequence().toSet()

    private companion object {
        val THEME_KEYS = setOf(
            "id", "name", "appearance", "luminance", "mode", "base", "roles", "layers", "statusColors",
            "projectPalette", "projectFills", "sky", "document", "mcpTheme",
        )
    }
}
