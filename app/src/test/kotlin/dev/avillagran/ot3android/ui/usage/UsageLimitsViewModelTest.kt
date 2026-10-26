package dev.avillagran.ot3android.ui.usage

import androidx.lifecycle.SavedStateHandle
import dev.avillagran.ot3android.core.connection.Connection
import dev.avillagran.ot3android.core.domain.Environment
import dev.avillagran.ot3android.core.transport.FakeT3Transport
import dev.avillagran.ot3android.core.usage.QuotaUpdate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class UsageLimitsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun teardown() = Dispatchers.resetMain()
    private val environments = MutableStateFlow(listOf(
        Environment("a", "NODE", "http://node.test", createdAt = 0),
        Environment("b", "VQ", "http://vq.test", createdAt = 0),
    ))

    @Test fun limitsCancelOnEnvironmentTabDisconnectAndCollectorStop() = runTest(dispatcher) {
        val a = MutableStateFlow<Connection?>(Connection(FakeT3Transport(), 1))
        val b = MutableStateFlow<Connection?>(Connection(FakeT3Transport(), 2))
        var active = 0
        var maximum = 0
        var summaries = 0
        val saved = SavedStateHandle(mapOf("tab" to "limits"))
        val model = UsageViewModel(environments, { if (it == "a") a else b }, saved,
            refreshProviders = { _, _ -> },
            readLimits = { current -> flow {
                active++; maximum = maxOf(maximum, active)
                try { emit(QuotaUpdate(JSONObject().put("epoch", current.epoch))); awaitCancellation() }
                finally { active-- }
            } }, read = { _, _ -> summaries++; JSONObject() })
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
        runCurrent()
        assertEquals(1, model.state.value.limits!!.getInt("epoch"))
        assertEquals(0, summaries)
        model.selectEnvironment("b"); runCurrent()
        assertEquals(2, model.state.value.limits!!.getInt("epoch"))
        assertEquals(1, maximum)
        b.value = null; runCurrent()
        assertNull(model.state.value.limits); assertTrue(model.state.value.offline); assertEquals(0, active)
        b.value = Connection(FakeT3Transport(), 3); runCurrent()
        assertEquals(3, model.state.value.limits!!.getInt("epoch"))
        model.selectTab("usage"); runCurrent()
        assertEquals(0, active); assertEquals(1, summaries)
        model.selectTab("limits"); runCurrent()
        assertEquals("limits", saved.get<String>("tab")); assertEquals(1, active)
        collector.cancel(); runCurrent(); assertEquals(0, active)
    }

    @Test fun closedQuotaStreamClearsOldDataAndRetriesWithoutParallelSubscription() = runTest(dispatcher) {
        var opens = 0
        val model = UsageViewModel(environments, { flowOf(Connection(FakeT3Transport(), 1)) }, SavedStateHandle(mapOf("tab" to "limits")),
            refreshProviders = { _, _ -> }, readLimits = { flow {
                opens++
                emit(QuotaUpdate(JSONObject().put("generation", opens)))
                if (opens == 1) emit(QuotaUpdate(null, closed = true, error = "stream_timeout")) else awaitCancellation()
            } })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
        runCurrent(); assertTrue(model.state.value.failed); assertNull(model.state.value.limits)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(2, opens); assertEquals(2, model.state.value.limits!!.getInt("generation"))
    }

    @Test fun providerProbeGateCountsFailuresAndIsPerEnvironment() = runTest {
        var now = 0L
        val gate = ProviderRefreshGate { now }
        val fake = FakeT3Transport()
        var calls = 0
        fake.onCall("server.refreshProviders") { calls++; error("probe_failed") }
        // Fake requires a connection before calls, like JNI.
        fake.connect(dev.avillagran.ot3android.core.transport.ConnectRoute.Bearer("http://example.test", "fixture"))
        val current = Connection(fake, 1)
        suspend fun probe(id: String) { runCatching { gate.refresh(id, current) } }
        probe("a"); probe("a"); assertEquals(1, calls)
        probe("b"); assertEquals(2, calls)
        now = 299_999; probe("a"); assertEquals(2, calls)
        now = 300_000; probe("a"); assertEquals(3, calls)
        fake.close()
    }
}
