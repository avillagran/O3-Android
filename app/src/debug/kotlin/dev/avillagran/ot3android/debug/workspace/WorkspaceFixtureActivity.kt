package dev.avillagran.ot3android.debug.workspace

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.room.Room
import dev.avillagran.ot3android.core.connection.*
import dev.avillagran.ot3android.core.data.EnvironmentRepository
import dev.avillagran.ot3android.core.data.local.Ot3Database
import dev.avillagran.ot3android.core.data.prefs.ShelfStore
import dev.avillagran.ot3android.core.home.ShelfState
import dev.avillagran.ot3android.core.security.TokenStore
import dev.avillagran.ot3android.core.thread.*
import dev.avillagran.ot3android.core.transport.*
import dev.avillagran.ot3android.ui.home.*
import dev.avillagran.ot3android.ui.navigation.*
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import dev.avillagran.ot3android.ui.thread.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Debug-only deterministic server. Real graph, screens, VMs, Room and Rust views; no network. */
class WorkspaceFixtureActivity : ComponentActivity() {
    lateinit var fixture: WorkspaceFixture
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("landscape", false)) requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        val bars = androidx.activity.SystemBarStyle.auto(android.graphics.Color.TRANSPARENT,
            android.graphics.Color.TRANSPARENT) { intent.getBooleanExtra("dark", false) }
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        setContent {
            fixture = viewModel { WorkspaceFixture(application, intent.getBooleanExtra("rich", false), intent.getStringExtra("request").orEmpty()) }
            val nav = rememberOt3NavController()
            val navigator = remember(nav) { Ot3Navigator(nav) }
            val screens = remember(navigator, fixture) {
                object : Ot3Screens by AppScreens(navigator) {
                    @Composable override fun Home(entry: NavBackStackEntry, isTop: Boolean) {
                        val owner = remember(entry) { nav.getBackStackEntry(ChatsGraph) }
                        val vm: HomeViewModel = viewModel(viewModelStoreOwner = owner) {
                            HomeViewModel(fixture.repository, fixture.connections, fixture.shelves)
                        }
                        val state by vm.state.collectAsStateWithLifecycle()
                        HomeScreen(state, isTop, { intent ->
                            when (intent) {
                                is HomeIntent.OpenRow -> navigator.openThread(intent.row.environmentId, intent.row.threadId)
                                is HomeIntent.OpenPullRequest -> intent.row.pullRequestBadge?.optJSONObject("current")?.let { pr ->
                                    navigator.push(PullRequest(intent.row.environmentId, pr.getString("projectId"), pr.getString("repository"), pr.getInt("number"), pr.getString("host")))
                                }
                                is HomeIntent.SetQuery -> vm.setQuery(intent.text)
                                HomeIntent.ClearSearch -> vm.clearSearch()
                                is HomeIntent.ToggleShelf -> vm.toggleShelf(intent.shelf)
                                else -> Unit
                            }
                        }, quotaStrip = {
                            dev.avillagran.ot3android.ui.usage.QuotaStripHost(source = fixture.quotas, environments = fixture.repository.environments,
                                onOpenLimits = { navigator.push(dev.avillagran.ot3android.ui.navigation.QuotaLimits()) })
                        })
                    }
                    // Route arguments are explicit, matching the production screen dispatch.
                    @Composable override fun Usage(initialTab: String, environmentId: String?) {
                        val model: dev.avillagran.ot3android.ui.usage.UsageViewModel = viewModel {
                            dev.avillagran.ot3android.ui.usage.UsageViewModel(
                                fixture.repository.environments, fixture.connections::connectionFlow,
                                createSavedStateHandle().apply { if (!contains("tab")) set("tab", initialTab); if (!contains("environment")) set("environment", environmentId) },
                                quotaSource = fixture.quotas,
                            ) { connection, period ->
                                dev.avillagran.ot3android.core.usage.UsageReader.read(
                                    connection.transport, period, "2026-10-26", "America/Santiago")
                            }
                        }
                        val state by model.state.collectAsStateWithLifecycle()
                        dev.avillagran.ot3android.ui.usage.UsageScreen(state,
                            model::selectEnvironment, model::selectPeriod, model::refresh,
                            onBack = { navigator.back() }, onRefreshPrices = model::refreshPrices, onTab = model::selectTab)
                    }
                    @Composable override fun PullRequests() {
                        val model: dev.avillagran.ot3android.ui.pullrequests.PullRequestsViewModel = viewModel {
                            dev.avillagran.ot3android.ui.pullrequests.PullRequestsViewModel(fixture.repository.environments,
                                fixture.connections::connectionFlow, projects = { id -> kotlinx.coroutines.flow.flow {
                                    fixture.connections.supervisor(id)?.shell?.collect { emit(it?.projects.orEmpty()) }
                                } }, saved = createSavedStateHandle())
                        }
                        fixture.prListState = model.listState
                        val state by model.state.collectAsStateWithLifecycle()
                        dev.avillagran.ot3android.ui.pullrequests.PullRequestsScreen(state, model::selectEnvironment,
                            model::selectProject, model::selectState, model::selectInvolvement, model::setQuery,
                            model::refresh, model::loadMore, { id, pr -> navigator.push(PullRequest(id, pr.getString("projectId"), pr.getString("repository"), pr.getInt("number"), pr.getString("host"))) }, listState = model.listState)
                    }
                    @Composable override fun PullRequest(route: PullRequest) {
                        val model: dev.avillagran.ot3android.ui.pullrequests.PullRequestViewModel = viewModel {
                            dev.avillagran.ot3android.ui.pullrequests.PullRequestViewModel(route.environmentId,
                                JSONObject().put("projectId", route.projectId).put("repository", route.repository).put("number", route.number).put("host", route.host),
                                fixture.connections::connectionFlow, createSavedStateHandle())
                        }
                        val state by model.state.collectAsStateWithLifecycle()
                        dev.avillagran.ot3android.ui.pullrequests.PullRequestScreen(route.number, route.repository, state,
                            model::selectTab, model::refresh, { navigator.back() })
                    }
                    @Composable override fun Thread(route: Thread) {
                        val vm: ThreadViewModel = viewModel {
                            ThreadViewModel(route.environmentId, route.threadId, fixture.connections,
                                fixture.drafts, fixture.outbox, fixture.sender, fixture.viewModelScope)
                        }
                        ThreadScreen(vm, { navigator.back() }, quotaStrip = {
                            dev.avillagran.ot3android.ui.usage.QuotaStripHost(route.environmentId, false, fixture.quotas, fixture.repository.environments,
                                onOpenLimits = { navigator.push(dev.avillagran.ot3android.ui.navigation.QuotaLimits(route.environmentId)) })
                        })
                    }
                }
            }
            val fixtureColors = remember { dev.avillagran.ot3android.ui.theme.OmarchyThemeCatalog.loadBlocking(application)
                .let { catalog -> dev.avillagran.ot3android.ui.theme.Ot3Colors.fromTheme(catalog[if (intent.getBooleanExtra("dark", false)) "tokyo-night" else "catppuccin-latte"]!!) } }
            Ot3Theme(colors = fixtureColors, motion = dev.avillagran.ot3android.ui.theme.rememberOt3Motion(
                dev.avillagran.ot3android.core.data.prefs.AnimationPreference.AUTO)) { Ot3NavHost(nav, screens) }
        }
    }
}

class WorkspaceFixture(application: Application, private val rich: Boolean = false, private val request: String = "") : AndroidViewModel(application) {
    var queueHold: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    var queueUnknownFirst = false
    var queueRejectNext = false
    private var queueDispatches = 0
    private var queueOrder = listOf("q1", "q2", "q3", "q4")
    private var queueSequence = 1L
    suspend fun publishQueueOrder(order: List<String>) {
        queueOrder = order
        val runs = bounded().getJSONObject("projection").getJSONArray("runs").objects().filter { it.optString("status") == "queued" }
        val events = runs.map { row -> JSONObject().put("kind", "event").put("event", JSONObject().put("type", "run.updated").put("payload", row)) }
        transport.emitThread("chat", ThreadUpdates.event(*events.toTypedArray(), cursor = ++queueSequence))
    }
    private fun addQueue(projection: JSONObject) {
        projection.put("providerThreads", JSONArray("""[{"id":"pt-queue","appThreadId":"chat","providerSessionId":"ps-queue"}]"""))
        projection.put("providerSessions", JSONArray("""[{"id":"ps-queue","status":"ready","capabilities":{"turns":{"supportsQueuedMessages":true,"supportsSteer":true}}}]"""))
        projection.put("providerTurns", JSONArray("""[{"id":"turn-active","runAttemptId":"attempt-active","status":"running"}]"""))
        val runs = JSONArray().put(JSONObject("""{"id":"active-run","status":"running","activeAttemptId":"attempt-active","providerThreadId":"pt-queue"}"""))
        queueOrder.forEachIndexed { index, run ->
            runs.put(JSONObject().put("id", run).put("status", "queued").put("userMessageId", "message-$run").put("queuePosition", index + 1).put("ordinal", listOf("q1", "q2", "q3", "q4").indexOf(run) + 2))
            projection.getJSONArray("messages").put(JSONObject().put("id", "message-$run").put("runId", run).put("role", "user").put("text", "Queued instruction $run")
                .put("createdAt", "2026-10-26T20:00:00Z").put("attachments", if (run == "q1") JSONArray("""[{"type":"file","name":"notes.txt","url":"https://fixture.invalid/notes"}]""") else JSONArray())
                .put("context", JSONObject().put("filePaths", JSONArray().put("/fixture/notes.txt"))))
        }
        projection.put("runs", runs)
    }
    var prListState: androidx.compose.foundation.lazy.LazyListState? = null
    private val database = Room.inMemoryDatabaseBuilder(application, Ot3Database::class.java).build()
    private val cursors = object : ShellCursorStore {
        override suspend fun cursor(environmentId: String): Long? = null
        override suspend fun setCursor(environmentId: String, cursor: Long?) = Unit
    }
    private val tokens = object : TokenStore {
        override suspend fun token(environmentId: String) = "offline-fixture"
        override suspend fun setToken(environmentId: String, token: String) = Unit
        override suspend fun delete(environmentId: String) = Unit
    }
    val repository = EnvironmentRepository(database.environmentDao(), { tokens }, cursors)
    val shelves = object : ShelfStore {
        override val shelves = MutableStateFlow(ShelfState())
        override suspend fun update(transform: (ShelfState) -> ShelfState) { shelves.value = transform(shelves.value) }
    }
    val refreshGate = dev.avillagran.ot3android.ui.usage.ProviderRefreshGate()
    val quotas by lazy { dev.avillagran.ot3android.ui.usage.SharedQuotaSource(viewModelScope, connections::connectionFlow, refreshGate::refresh) }
    private var pricesFresh = request != "prices"
    val transport = FakeT3Transport().apply {
        onImage = { _, _, _ -> application.assets.open("reader/preview.png").use { it.readBytes() } }
        onFetchDescriptor("http://fixture.invalid") { JSONObject().put("environmentId", "node") }
        onShell { fixtureShell() }
        listOf("list", "detail", "checks", "activity").forEach { action -> onCall("pullRequests.$action") { args ->
            val data = JSONObject(application.assets.open("pullrequests/$action.json").bufferedReader().use { it.readText() })
            if (action == "detail" && request == "pr-retain") data.put("number", args.getInt("number")).put("title", "Pull request ${args.getInt("number")}")
            if (action == "list" && request == "pr-retain") {
                val page = when (args.optJSONObject("cursors")?.optString("project")) {
                    "fixture-page-2" -> 2
                    "fixture-page-3" -> 3
                    else -> 1
                }
                val template = data.getJSONArray("entries").getJSONObject(0)
                val entries = JSONArray()
                repeat(12) { index ->
                    val number = page * 100 + index
                    entries.put(JSONObject(template.toString()).put("number", number).put("title", "Pull request $number")
                        .put("url", "https://github.com/example/ot3/pull/$number"))
                }
                data.put("entries", entries).put("truncated", page < 3)
                    .put("nextCursors", JSONObject().put("project", "fixture-page-${page + 1}"))
            } else if (action == "list" && args.has("cursors")) {
                data.put("truncated", false).put("entries", JSONArray().put(data.getJSONArray("entries").getJSONObject(0).put("number", 36).put("title", "Restore reading position")))
            } else data
        } }
        onCall("server.refreshProviders") { JSONObject() }
        onServerConfigStream { kotlinx.coroutines.flow.flow {
            val events = if (request == "quota-empty") JSONArray("""[{"version":1,"type":"snapshot","config":{"providers":[]}}]""") else JSONArray(application.assets.open("usage/limits.events.json").bufferedReader().use { it.readText() })
            emit(dev.avillagran.t3.T3Batch(events, false, null))
            kotlinx.coroutines.awaitCancellation()
        } }
        onCall("server.refreshUsageRates") { pricesFresh = true; JSONObject().put("status", "fresh") }
        onCall("server.getUsageSummary") { args ->
            val input = JSONObject(application.assets.open("usage/summary.input.json").bufferedReader().use { it.readText() })
            val summary = input.getJSONObject("summary")
            summary.put("pricing", JSONObject().put("status", if (pricesFresh) "fresh" else "unavailable"))
            // The fake server respects the requested window; aggregation remains native.
            val buckets = summary.getJSONArray("buckets").objects().filter {
                it.getString("day") >= args.getString("sinceDay") && it.getString("day") <= args.getString("untilDay")
            }
            summary.put("buckets", JSONArray(buckets))
        }
        onCall("server.getConfig") { JSONObject("""{"settings":{"favorites":[]},"providers":[{"enabled":true,"instanceId":"fixture-provider","displayName":"Fixture provider","status":"ready","supportedRuntimeModes":["approval-required","auto-accept-edits","auto","full-access"],"models":[{"slug":"fixture-model","name":"Fixture model","shortName":"Fixture","capabilities":{"optionDescriptors":[{"id":"effort","label":"Reasoning effort","type":"select","currentValue":"low","options":[{"id":"low","label":"Low"},{"id":"high","label":"High"}]}]}}]}]}""") }
        onCall("orchestration.dispatchCommand") {
            if (request in listOf("queue", "editor")) {
                queueDispatches++
                queueHold?.await()
                if (queueRejectNext) { queueRejectNext = false; throw dev.avillagran.t3.T3Exception("rpc_rejected") }
                if (queueUnknownFirst && queueDispatches == 1) throw dev.avillagran.t3.T3Exception("rpc_outcome_unknown")
            }
            JSONObject().put("sequence", 2)
        }
        onBoundedThread("chat") { bounded() }
        onDomain("timeline.view") { DomainEngine.shared.domain("timeline.view", it) }
        onDomain("projection.apply") { DomainEngine.shared.domain("projection.apply", it) }
        onCall("orchestration.getTurnDiff") { args ->
            JSONObject().put("threadId", args.getString("threadId"))
                .put("fromTurnCount", args.getInt("fromTurnCount")).put("toTurnCount", args.getInt("toTurnCount"))
                .put("diff", "diff --git a/ui/TimelineReader.kt b/ui/TimelineReader.kt\n--- a/ui/TimelineReader.kt\n+++ b/ui/TimelineReader.kt\n@@ -1 +1 @@\n-Text(markdown)\n+MarkdownBlocks(nativeBlocks)\ndiff --git a/ui/ThreadWorkspace.kt b/ui/ThreadWorkspace.kt\n--- a/ui/ThreadWorkspace.kt\n+++ b/ui/ThreadWorkspace.kt\n@@ -1 +1 @@\n-resetSelectedFile()\n+rememberSelectedFile(environmentId, threadId, selectedPath, preserveDraft = true, preserveReadingPosition = true)\n")
        }
        onCall("orchestration.getTurnItem") { args ->
            JSONObject().put("item", fixtureItems().objects().firstOrNull { it.optString("id") == args.optString("itemId") })
        }
    }
    val connections = ConnectionManager(repository, cursors, object : T3TransportFactory {
        override fun create(): T3Transport = transport
    }, viewModelScope)
    val drafts = DraftRepository(database.draftDao())
    val outbox = OutboxRepository(database.outboxDao())
    val sender = MessageSender(outbox, viewModelScope)
    init {
        viewModelScope.launch { repository.save("node", "node · Linux", "http://fixture.invalid", "node", null) }
    }
    private fun fixtureShell(): JSONObject = JSONObject().put("snapshotSequence", 1)
        .put("projects", JSONArray().put(JSONObject().put("id", "project").put("title", "ot3-Android").put("workspaceRoot", "/work/ot3-android")))
        .put("threads", JSONArray().put(JSONObject().put("id", "chat").put("projectId", "project")
            .put("title", "Navegación y metadatos").put("branch", "feat/ui-1h-navigation")
            .put("linkedPullRequest", JSONObject().put("number", 42).put("repository", "example/fixture")
                .put("url", "https://github.com/example/fixture/pull/42")
                .put("snapshot", JSONObject().put("state", "open").put("isDraft", true)))
            .put("createdAt", "2026-10-25T10:00:00Z").put("status", "idle")).also { threads ->
                if (rich) {
                    fun row(id: String, title: String) = JSONObject().put("id", id).put("projectId", "project")
                        .put("title", title).put("branch", "feat/ui-1i").put("createdAt", "2026-10-25T09:00:00Z")
                    threads.put(row("working", "Cielo, efectos y movimiento").put("status", "running")
                        .put("activeRunId", "r1").put("latestRunId", "r1").put("latestRunStartedAt", "2026-10-25T23:00:00Z"))
                    threads.put(row("approval", "Revisar cambios de navegación").put("pendingRuntimeRequest", JSONObject().put("kind", "approval").put("id", "q1")))
                    threads.put(row("failed", "Verificación del composer").put("status", "failed").put("latestRunId", "r2"))
                    threads.put(row("settled", "Tipografía y roles").put("settledAt", "2026-10-25T08:00:00Z"))
                }
            })
    private fun bounded(): JSONObject {
        val messages = JSONArray()
        repeat(60) { i -> messages.put(JSONObject().put("id", "m$i").put("role", if (i % 2 == 0) "user" else "assistant")
            .put("text", if (rich && i == 59) """# Reader implementation

The **native reader** keeps *your place* while the run streams. See [the reference](https://example.org/docs).

- Rust owns the ordered timeline
- Android renders the supplied blocks

```kotlin
val thread = session.view.value // a deliberately long line that scrolls horizontally without wrapping the code
println(thread.title)
```

The checks completed; review the activity and changed files above.

| Host | Branch | State | Runs |
| :--- | :---: | :--- | ---: |
| NODE | `feat/reader` | **Working** | 12 |
| VQ | main | Ready, awaiting review | 3 |
| Omarchy | [source](https://example.com) | A longer cell wraps without hiding the next row | 108 |
""" else "Mensaje $i: posición de lectura conservada al girar y contraer el panel.")
            .put("createdAt", "2026-10-25T10:${i.toString().padStart(2, '0')}:00Z").put("attachments", if (rich && i == 58) JSONArray("""[{"id":"reader-shot","type":"image","name":"Vista previa del lector.png","mimeType":"image/png"}]""") else JSONArray())) }
        return JSONObject().put("snapshotSequence", 1).put("projection", JSONObject()
            .put("thread", JSONObject().put("id", "chat").put("title", "Navegación y metadatos")
                .put("runtimeMode", "approval-required").put("modelSelection", JSONObject().put("instanceId", "fixture-provider").put("model", "fixture-model")))
            .put("messages", messages).put("runs", if (rich) JSONArray("""[{"id":"run-reader","status":"completed","createdAt":"2026-10-25T10:57:00Z"}]""") else JSONArray())
            .put("runtimeRequests", if (request !in listOf("approval", "question")) JSONArray() else JSONArray().put(JSONObject()
                .put("id", "request-fixture").put("kind", if (request == "question") "user_input" else "command")
                .put("status", "pending").put("createdAt", "2026-10-25T10:59:00Z")
                .put("responseCapability", JSONObject().put("type", "live"))))
            .put("turnItems", (if (rich) fixtureItems() else JSONArray()).also { items ->
                if (request == "approval") items.put(JSONObject("""{"id":"approval-fixture","type":"approval_request","requestId":"request-fixture","prompt":"./gradlew :app:testDebugUnitTest","options":[{"decision":"accept","label":"Approve"},{"decision":"decline","label":"Decline"},{"decision":"acceptForSession","label":"Always allow this session","warning":"Applies to later requests"}]}"""))
                if (request == "question") items.put(JSONObject("""{"id":"question-fixture","type":"user_input_request","requestId":"request-fixture","questions":[{"id":"theme","header":"Review theme","question":"Which **theme** should be checked next?","allowCustomAnswer":false,"options":[{"label":"Tokyo Night","value":"tokyo-night"},{"label":"Catppuccin Latte","value":"catppuccin-latte"}]}]}"""))
            })
            .put("checkpoints", if (rich) JSONArray("""[{"id":"cp-reader","runId":"run-reader","appRunOrdinal":1,"status":"ready","capturedAt":"2026-10-25T10:58:45Z","files":[{"path":"ui/TimelineReader.kt","kind":"modified","additions":42,"deletions":8},{"path":"ui/ThreadWorkspace.kt","kind":"modified","additions":18,"deletions":5}]}]""") else JSONArray())).also { if (request == "queue") addQueue(it.getJSONObject("projection")) }
    }
}

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull(::optJSONObject)
private fun fixtureItems() = JSONArray("""[
 {"id":"think-reader","threadId":"chat","runId":"run-reader","providerTurnId":null,"type":"reasoning","status":"completed","createdAt":"2026-10-25T10:58:10Z","updatedAt":"2026-10-25T10:58:15Z","text":"Compare the native reader with the reference, then verify draft and anchor preservation."},
 {"id":"test-reader","threadId":"chat","runId":"run-reader","providerTurnId":null,"type":"command_execution","status":"completed","createdAt":"2026-10-25T10:58:20Z","updatedAt":"2026-10-25T10:58:30Z","input":"./gradlew :app:testDebugUnitTest","output":"Reader checks passed. No network requests were sent.","exitCode":0}
]""")
