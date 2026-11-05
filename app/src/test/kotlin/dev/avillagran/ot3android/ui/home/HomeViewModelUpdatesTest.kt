package dev.avillagran.ot3android.ui.home

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.core.connection.*
import dev.avillagran.ot3android.core.data.EnvironmentRepository
import dev.avillagran.ot3android.core.data.local.Ot3Database
import dev.avillagran.ot3android.core.data.prefs.ShelfStore
import dev.avillagran.ot3android.core.home.ShelfState
import dev.avillagran.ot3android.core.security.TokenStore
import dev.avillagran.ot3android.core.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelUpdatesTest {
    @Test fun regeneratedTitleReachesHomeWhileShellUpdatesKeepArriving() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), Ot3Database::class.java)
            .allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = ViewModelStore()
        try {
            val cursors = object : ShellCursorStore {
                override suspend fun cursor(environmentId: String): Long? = null
                override suspend fun setCursor(environmentId: String, cursor: Long?) = Unit
            }
            val tokens = object : TokenStore {
                override suspend fun token(environmentId: String) = "fixture-token"
                override suspend fun setToken(environmentId: String, token: String) = Unit
                override suspend fun delete(environmentId: String) = Unit
            }
            val repository = EnvironmentRepository(database.environmentDao(), { tokens }, cursors)
            fun thread(title: String) = JSONObject().put("id", "thread").put("projectId", "project").put("title", title).put("status", "idle")
            val fake = FakeT3Transport().apply {
                onFetchDescriptor("http://fixture.invalid") { JSONObject().put("environmentId", "node") }
                onShell { JSONObject().put("snapshotSequence", 1).put("projects", JSONArray())
                    .put("threads", JSONArray().put(thread("Original"))) }
                onCall("server.getConfig") { JSONObject() }
                onDomain("models.catalog") { JSONObject() }
            }
            val live = object : T3Transport by fake {
                override suspend fun authSession() = JSONObject().put("authenticated", false)
            }
            val connections = ConnectionManager(repository, cursors, object : T3TransportFactory {
                override fun create() = live
            }, scope, io = Dispatchers.Unconfined, computation = Dispatchers.Unconfined)
            val shelves = object : ShelfStore {
                override val shelves = MutableStateFlow(ShelfState())
                override suspend fun update(transform: (ShelfState) -> ShelfState) { shelves.value = transform(shelves.value) }
            }
            val cancelled = AtomicInteger()
            val domain = DomainCalls { _, args ->
                val environments = args.getJSONArray("environments")
                val rows = JSONArray()
                for (i in 0 until environments.length()) {
                    val environment = environments.getJSONObject(i)
                    val threads = environment.optJSONObject("shell")?.optJSONArray("threads") ?: continue
                    for (index in 0 until threads.length()) {
                        val source = threads.getJSONObject(index)
                        rows.put(JSONObject(source.toString()).put("key", "node/thread").put("environmentId", "node")
                            .put("state", "idle").put("time", JSONObject().put("kind", "elapsed")))
                    }
                }
                // Una proyección tarda más que las actualizaciones: igual que en el teléfono.
                try { delay(250) } catch (error: CancellationException) { cancelled.incrementAndGet(); throw error }
                JSONObject().put("threads", rows).put("sections", JSONObject().put("active", rows))
                    .put("filterCounts", JSONObject().put("all", rows.length()))
            }
            val vm = HomeViewModel(repository, connections, shelves, domain = domain)
            store.put("home", vm)
            val collector = scope.launch { vm.state.collect() }
            repository.save("node", "NODE", "http://fixture.invalid", "node", null)
            withTimeout(5_000) { vm.state.filterNotNull().first { it.rows["node/thread"]?.title == "Original" } }
            withTimeout(5_000) { fake.collectors().first { it > 0 } }
            val updates = scope.launch {
                for (sequence in 2L..41L) {
                    fake.emitShell(ThreadUpdates.batch(JSONObject().put("kind", "thread.updated").put("sequence", sequence)
                        .put("thread", thread("Regenerated"))))
                    delay(40)
                }
            }
            // Debe publicar durante la ráfaga, sin esperar a que el shell deje de cambiar.
            withTimeout(1_200) { vm.state.filterNotNull().first { it.rows["node/thread"]?.title == "Regenerated" } }
            assertTrue(updates.isActive)
            assertEquals(0, cancelled.get())
            updates.cancelAndJoin()
            collector.cancelAndJoin()
        } finally { store.clear(); scope.cancel(); database.close(); Dispatchers.resetMain() }
    }
}
