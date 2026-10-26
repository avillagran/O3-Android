package dev.avillagran.ot3android.debug.workspace

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
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
        enableEdgeToEdge()
        setContent {
            fixture = viewModel()
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
                                is HomeIntent.SetQuery -> vm.setQuery(intent.text)
                                HomeIntent.ClearSearch -> vm.clearSearch()
                                is HomeIntent.ToggleShelf -> vm.toggleShelf(intent.shelf)
                                else -> Unit
                            }
                        })
                    }
                    @Composable override fun Thread(route: Thread) {
                        val vm: ThreadViewModel = viewModel {
                            ThreadViewModel(route.environmentId, route.threadId, fixture.connections,
                                fixture.drafts, fixture.outbox, fixture.sender, fixture.viewModelScope)
                        }
                        ThreadScreen(vm, { navigator.back() })
                    }
                }
            }
            Ot3Theme { Ot3NavHost(nav, screens) }
        }
    }
}

class WorkspaceFixture(application: Application) : AndroidViewModel(application) {
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
    val transport = FakeT3Transport().apply {
        onFetchDescriptor("http://fixture.invalid") { JSONObject().put("environmentId", "node") }
        onShell { fixtureShell() }
        onBoundedThread("chat") { bounded() }
        onDomain("timeline.view") { DomainEngine.shared.domain("timeline.view", it) }
        onDomain("projection.apply") { DomainEngine.shared.domain("projection.apply", it) }
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
            .put("createdAt", "2026-10-25T10:00:00Z").put("status", "idle")))
    private fun bounded(): JSONObject {
        val messages = JSONArray()
        repeat(60) { i -> messages.put(JSONObject().put("id", "m$i").put("role", if (i % 2 == 0) "user" else "assistant")
            .put("text", "Mensaje $i: posición de lectura conservada al girar y contraer el panel.")
            .put("createdAt", "2026-10-25T10:${i.toString().padStart(2, '0')}:00Z").put("attachments", JSONArray())) }
        return JSONObject().put("snapshotSequence", 1).put("projection", JSONObject()
            .put("thread", JSONObject().put("id", "chat").put("title", "Navegación y metadatos"))
            .put("messages", messages).put("runs", JSONArray()).put("runtimeRequests", JSONArray()).put("turnItems", JSONArray()))
    }
}
