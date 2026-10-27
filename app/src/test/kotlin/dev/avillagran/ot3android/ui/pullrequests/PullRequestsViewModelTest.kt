package dev.avillagran.ot3android.ui.pullrequests

import androidx.lifecycle.SavedStateHandle
import dev.avillagran.ot3android.core.connection.Connection
import dev.avillagran.ot3android.core.domain.Environment
import dev.avillagran.ot3android.core.shell.ShellProject
import dev.avillagran.ot3android.core.transport.FakeT3Transport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PullRequestsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun teardown() = Dispatchers.resetMain()
    private val environments = MutableStateFlow(listOf(Environment("a", "NODE", "http://node.test", createdAt = 0), Environment("b", "VQ", "http://vq.test", createdAt = 0)))
    @Test fun debounceCancellationReconnectAndOfflineDoNotLeakOldScope() = runTest(dispatcher) {
        val connection = MutableStateFlow<Connection?>(Connection(FakeT3Transport(), 1))
        val saved = SavedStateHandle()
        var cancelled = false
        val requests = mutableListOf<Pair<Int, String>>()
        val model = PullRequestsViewModel(environments, { connection }, saved = saved, read = { current, _, _, query, _, _ ->
            requests += current.epoch to query
            if (query.isEmpty()) try { awaitCancellation() } finally { cancelled = true }
            JSONObject().put("entries", JSONArray().put(entry(current.epoch)))
        })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
        advanceTimeBy(301); runCurrent()
        assertTrue(model.state.value.busy)
        model.setQuery("fix"); advanceTimeBy(299); runCurrent(); assertTrue(cancelled); assertTrue(model.state.value.entries.isEmpty())
        advanceTimeBy(2); runCurrent(); assertTrue(cancelled)
        assertEquals("fix", saved.get<String>("query"))
        assertEquals(1, model.state.value.entries.single().getInt("number"))
        connection.value = Connection(FakeT3Transport(), 2); runCurrent()
        assertEquals(2, model.state.value.entries.single().getInt("number"))
        connection.value = null; runCurrent()
        assertTrue(model.state.value.offline); assertTrue(model.state.value.entries.isEmpty())
        assertEquals(listOf(1 to "", 1 to "fix", 2 to "fix"), requests)
    }
    @Test fun paginationScopeFailureRetryAndSavedFilters() = runTest(dispatcher) {
        val connection = MutableStateFlow<Connection?>(Connection(FakeT3Transport(), 1))
        val saved = SavedStateHandle(mapOf("environment" to "b", "project" to "p", "state" to "merged", "involvement" to "authored", "query" to "test"))
        val cursors = mutableListOf<Boolean>()
        var fail = false
        val model = PullRequestsViewModel(environments, { connection }, { flowOf(listOf(ShellProject("p", "Project", null, null, JSONObject()))) }, saved) { _, state, involvement, query, project, cursor ->
            assertEquals("merged", state); assertEquals("authored", involvement); assertEquals("test", query); assertEquals("p", project)
            if (fail) error("test")
            cursors += (cursor != null)
            JSONObject().put("entries", if (cursor == null) JSONArray().put(entry(8)).put(entry(3)) else JSONArray().put(entry(8).put("title", "Updated")).put(entry(9)))
                .put("truncated", cursor == null).put("nextCursors", JSONObject().put("p", "opaque"))
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
        advanceTimeBy(301); runCurrent()
        assertEquals("b", model.state.value.environmentId)
        model.loadMore(); runCurrent()
        assertEquals(listOf(8, 3, 8, 9), model.state.value.entries.map { it.getInt("number") })
        assertEquals("Updated", model.state.value.entries[2].getString("title"))
        assertEquals(listOf(false, true), cursors)
        fail = true; model.refresh(); runCurrent(); assertTrue(model.state.value.failed)
        fail = false; model.refresh(); runCurrent(); assertFalse(model.state.value.failed)
    }
    @Test fun missingMetadataNeverBroadensExplicitProjectOrSwitchesEnvironment() = runTest(dispatcher) {
        val connection = MutableStateFlow<Connection?>(Connection(FakeT3Transport(), 1))
        val options = MutableStateFlow<List<ShellProject>>(emptyList())
        val saved = SavedStateHandle(mapOf("environment" to "b", "project" to "removed-project"))
        val reads = mutableListOf<String?>()
        val model = PullRequestsViewModel(environments, { connection }, { options }, saved) { _, _, _, _, project, _ ->
            reads += project; JSONObject().put("entries", JSONArray())
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
        advanceTimeBy(301); runCurrent()
        assertEquals(listOf("removed-project"), reads)
        assertEquals("removed-project", model.state.value.projectId)
        environments.value = environments.value.filter { it.id != "b" }; runCurrent()
        assertTrue(model.state.value.scopeUnavailable)
        assertEquals("b", model.state.value.environmentId)
        assertEquals(1, reads.size)
        model.selectEnvironment("a"); runCurrent()
        assertEquals("a", model.state.value.environmentId)
        assertNull(model.state.value.projectId)
        assertNull(reads.last())
    }
    @Test fun pageTwoSurvivesInactiveReturnWithoutReadAndOffscreenMoreIsIgnored() = runTest(dispatcher) {
        val connection = MutableStateFlow<Connection?>(Connection(FakeT3Transport(), 1))
        val pages = mutableListOf<Boolean>()
        var collectors = 0
        val model = PullRequestsViewModel(environments, { connection.onStart { collectors++ }.onCompletion { collectors-- } }, read = { _, _, _, _, _, cursor ->
            pages += (cursor != null)
            JSONObject().put("entries", JSONArray().put(entry(if (cursor == null) 42 else 36)))
                .put("truncated", true).put("nextCursors", JSONObject().put("p", if (cursor == null) "page2" else "page3"))
        })
        var collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
        runCurrent(); model.loadMore(); runCurrent()
        assertEquals(listOf(false, true), pages)
        collector.cancel(); runCurrent()
        assertEquals(0, collectors)
        assertTrue(model.state.value.awaitingConnection)
        assertNull(model.state.value.result)
        model.loadMore(); runCurrent() // No active consumer: cannot queue a stale page action.
        collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
        runCurrent()
        assertEquals(1, collectors)
        assertEquals(listOf(false, true), pages)
        assertEquals(listOf(42, 36), model.state.value.entries.map { it.getInt("number") })
        assertEquals("page3", model.state.value.result!!.getJSONObject("nextCursors").getString("p"))
        collector.cancel()
    }
    @Test fun moreBetweenPublicationAndCollectorRegistrationIsBufferedOnce() = runTest(dispatcher) {
        val connection = MutableStateFlow<Connection?>(Connection(FakeT3Transport(), 1))
        var reads = 0
        val model = PullRequestsViewModel(environments, { connection }, read = { _, _, _, _, _, cursor ->
            reads++
            JSONObject().put("entries", JSONArray().put(entry(if (cursor == null) 42 else 36)))
                .put("truncated", cursor == null).put("nextCursors", JSONObject().put("p", "page2"))
        })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect { state ->
            if (!state.busy && state.entries.size == 1 && state.result?.optBoolean("truncated") == true) model.loadMore()
        } }
        runCurrent()
        assertEquals(2, reads)
        assertEquals(listOf(42, 36), model.state.value.entries.map { it.getInt("number") })
    }
    @Test fun retainedRowsArePrivateUntilConnectionValidatedAndReconnectInvalidates() = runTest(dispatcher) {
        val connection = MutableStateFlow<Connection?>(Connection(FakeT3Transport(), 1))
        var hold = false
        val release = CompletableDeferred<Unit>()
        var reads = 0
        val model = PullRequestsViewModel(environments, { flow {
            if (hold) release.await()
            emitAll(connection)
        } }, read = { current, _, _, _, _, _ ->
            reads++; JSONObject().put("entries", JSONArray().put(entry(current.epoch)))
        })
        var collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
        runCurrent(); assertEquals(1, reads)
        model.listState.requestScrollToItem(14, 35)
        assertEquals(14, model.listState.firstVisibleItemIndex)
        collector.cancel(); runCurrent()
        hold = true
        connection.value = Connection(FakeT3Transport(), 2)
        collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
        runCurrent()
        assertTrue(model.state.value.awaitingConnection); assertTrue(model.state.value.entries.isEmpty())
        release.complete(Unit); runCurrent()
        assertEquals(0, model.listState.firstVisibleItemIndex)
        assertEquals(0, model.listState.firstVisibleItemScrollOffset)
        assertEquals(2, reads); assertEquals(2, model.state.value.entries.single().getInt("number"))
        collector.cancel(); runCurrent()
        connection.value = Connection(FakeT3Transport(), 2) // Same epoch, replaced transport.
        collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
        runCurrent(); assertEquals(3, reads)
        collector.cancel(); runCurrent(); connection.value = null
        collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
        runCurrent(); assertTrue(model.state.value.offline); assertTrue(model.state.value.entries.isEmpty())
        assertEquals(3, reads)
        collector.cancel(); runCurrent()
        connection.value = Connection(FakeT3Transport(), 3) // Reconnect while unobserved.
        collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
        runCurrent(); assertEquals(4, reads); assertEquals(3, model.state.value.entries.single().getInt("number"))
        collector.cancel()
    }
    @Test fun refreshAndFilterChangesOffscreenDiscardCacheAndDefaultEnvironmentCannotFallback() = runTest(dispatcher) {
        val current = Connection(FakeT3Transport(), 1)
        val requests = mutableListOf<String>()
        val model = PullRequestsViewModel(environments, { flowOf(current) }, read = { _, state, _, query, _, _ ->
            requests += "$state:$query"; JSONObject().put("entries", JSONArray().put(entry(requests.size)))
        })
        var collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
        runCurrent(); assertEquals(listOf("open:"), requests)
        collector.cancel(); runCurrent()
        model.refresh()
        collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
        runCurrent(); assertEquals(2, requests.size)
        collector.cancel(); runCurrent()
        model.selectState("merged"); model.setQuery("updated"); advanceTimeBy(301); runCurrent()
        collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
        runCurrent(); assertEquals("merged:updated", requests.last())
        collector.cancel(); runCurrent()
        environments.value = environments.value.filter { it.id != "a" }
        collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
        runCurrent(); assertTrue(model.state.value.scopeUnavailable); assertEquals("a", model.state.value.environmentId)
        assertTrue(model.state.value.entries.isEmpty()); assertEquals(3, requests.size)
        collector.cancel()
    }
    @Test fun detailTabsCancelAndNewEpochDropsPreviousOverview() = runTest(dispatcher) {
        val connection = MutableStateFlow<Connection?>(Connection(FakeT3Transport(), 1))
        var checksCancelled = false
        val saved = SavedStateHandle()
        val model = PullRequestViewModel("a", JSONObject(), { connection }, saved) { current, action, _ ->
            if (action == "checks") try { awaitCancellation() } finally { checksCancelled = true }
            JSONObject().put("epoch", current.epoch).put("action", action)
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
        runCurrent(); assertEquals(1, model.state.value.detail!!.getInt("epoch"))
        model.selectTab("checks"); runCurrent(); assertTrue(model.state.value.busy)
        model.selectTab("activity"); runCurrent(); assertTrue(checksCancelled)
        assertEquals("activity", model.state.value.content!!.getString("action"))
        connection.value = Connection(FakeT3Transport(), 2); runCurrent()
        assertEquals(2, model.state.value.detail!!.getInt("epoch"))
        assertEquals("activity", saved.get<String>("tab"))
        connection.value = null; runCurrent(); assertNull(model.state.value.detail); assertTrue(model.state.value.offline)
    }
    private fun entry(number: Int) = JSONObject().put("host", "git.example").put("projectId", "p").put("repository", "o/r").put("number", number)
}
