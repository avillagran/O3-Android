package dev.avillagran.ot3android.ui.usage

import androidx.lifecycle.SavedStateHandle
import dev.avillagran.ot3android.core.connection.Connection
import dev.avillagran.ot3android.core.domain.Environment
import dev.avillagran.ot3android.core.transport.FakeT3Transport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UsageViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun teardown() = Dispatchers.resetMain()
    private val environments = MutableStateFlow(listOf(
        Environment("a", "NODE", "http://node.test", createdAt = 0),
        Environment("b", "VQ", "http://vq.test", createdAt = 0),
    ))

    @Test fun periodSwitchCancelsOldReadAndOfflineClearsData() = runTest(dispatcher) {
        val connection = MutableStateFlow<Connection?>(Connection(FakeT3Transport(), 1))
        var cancelled = false
        val model = UsageViewModel(environments, { connection }, read = { _, period ->
            if (period == "week") try { awaitCancellation() } finally { cancelled = true }
            JSONObject().put("period", period)
        })
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
        runCurrent()
        assertTrue(model.state.value.busy)
        model.selectPeriod("day")
        runCurrent()
        assertTrue(cancelled)
        assertEquals("day", model.state.value.summary!!.getString("period"))
        connection.value = null
        runCurrent()
        assertTrue(model.state.value.offline)
        assertNull(model.state.value.summary)
        collector.cancel()
    }

    @Test fun savedSelectionRefreshAndReconnectionUseCurrentEnvironment() = runTest(dispatcher) {
        val a = MutableStateFlow<Connection?>(Connection(FakeT3Transport(), 1))
        val b = MutableStateFlow<Connection?>(Connection(FakeT3Transport(), 2))
        val reads = mutableListOf<Pair<Int, String>>()
        val saved = SavedStateHandle(mapOf("environment" to "b", "period" to "month"))
        val model = UsageViewModel(environments, { if (it == "a") a else b }, saved) { current, period ->
            reads += current.epoch to period
            JSONObject().put("epoch", current.epoch)
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
        runCurrent()
        assertEquals("b", model.state.value.environmentId)
        assertEquals(listOf(2 to "month"), reads)
        model.refresh()
        runCurrent()
        assertEquals(2, reads.size)
        b.value = Connection(FakeT3Transport(), 3)
        runCurrent()
        assertEquals(3, model.state.value.summary!!.getInt("epoch"))
        model.selectEnvironment("a")
        runCurrent()
        assertEquals(1, model.state.value.summary!!.getInt("epoch"))
        assertEquals("a", saved.get<String>("environment"))
    }
    @Test fun explicitPriceRefreshRunsOnceAndThenReadsSummary() = runTest(dispatcher) {
        val connection = MutableStateFlow<Connection?>(Connection(FakeT3Transport(), 1))
        val events = mutableListOf<String>()
        val model = UsageViewModel(environments, { connection },
            refreshRates = { events += "prices:${it.epoch}" },
            read = { current, _ -> events += "summary:${current.epoch}"; JSONObject() })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
        runCurrent()
        model.refreshPrices()
        runCurrent()
        assertEquals(listOf("summary:1", "prices:1", "summary:1"), events)
        connection.value = Connection(FakeT3Transport(), 2)
        runCurrent()
        assertEquals("summary:2", events.last())
        assertEquals(1, events.count { it.startsWith("prices:") })
    }

    @Test fun changingEnvironmentCancelsPriceRefreshWithoutReplayingIt() = runTest(dispatcher) {
        val connections = mapOf("a" to MutableStateFlow<Connection?>(Connection(FakeT3Transport(), 1)),
            "b" to MutableStateFlow<Connection?>(Connection(FakeT3Transport(), 2)))
        var cancelled = false
        var priceCalls = 0
        val model = UsageViewModel(environments, { connections.getValue(it) },
            refreshRates = { priceCalls++; try { awaitCancellation() } finally { cancelled = true } },
            read = { current, _ -> JSONObject().put("epoch", current.epoch) })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
        runCurrent()
        model.refreshPrices()
        runCurrent()
        assertTrue(model.state.value.busy)
        model.selectEnvironment("b")
        runCurrent()
        assertTrue(cancelled)
        assertEquals(2, model.state.value.summary!!.getInt("epoch"))
        model.selectEnvironment("a")
        runCurrent()
        assertEquals(1, priceCalls)
        assertEquals(1, model.state.value.summary!!.getInt("epoch"))
    }

}
