package dev.avillagran.ot3android.ui.usage

import dev.avillagran.ot3android.core.connection.Connection
import dev.avillagran.ot3android.core.transport.FakeT3Transport
import dev.avillagran.ot3android.core.usage.QuotaUpdate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class SharedQuotaSourceTest {
    @Test fun overlappingSurfacesShareThenReleaseReconnectAndReacquireFresh() = runTest {
        val connections = MutableStateFlow<Connection?>(Connection(FakeT3Transport(), 1))
        var active = 0
        var peak = 0
        var opens = 0
        val source = SharedQuotaSource(backgroundScope, { connections }, { _, _ -> }, { current -> flow {
            active++; opens++; peak = maxOf(peak, active)
            try { emit(QuotaUpdate(JSONObject().put("epoch", current.epoch))); awaitCancellation() }
            finally { active-- }
        } })
        var first = QuotaState()
        var second = QuotaState()
        val home = backgroundScope.launch { source.observe("a").collect { first = it } }
        runCurrent()
        val thread = backgroundScope.launch { source.observe("a").collect { second = it } }
        runCurrent()
        assertEquals(1, opens); assertEquals(1, active); assertSame(first.limits, second.limits)
        home.cancel(); runCurrent(); assertEquals(1, active)
        connections.value = null; runCurrent()
        assertEquals(0, active); assertTrue(second.offline); assertNull(second.limits)
        connections.value = Connection(FakeT3Transport(), 2); runCurrent()
        assertEquals(2, second.limits!!.getInt("epoch")); assertEquals(1, peak)
        thread.cancel(); runCurrent(); assertEquals(0, active)
        val limits = backgroundScope.launch { source.observe("a").collect { first = it } }
        runCurrent(); assertEquals(3, opens); assertEquals(2, first.limits!!.getInt("epoch"))
        limits.cancel(); runCurrent(); assertEquals(0, active)
    }

    @Test fun distinctEnvironmentsRetryIndependentlyAndStopDuringBackoff() = runTest {
        var a = 0
        var b = 0
        val source = SharedQuotaSource(backgroundScope, { flowOf(Connection(FakeT3Transport(), if (it == "a") 1 else 2)) }, { _, _ -> }, { current -> flow {
            if (current.epoch == 1) { a++; emit(QuotaUpdate(null, closed = true)) }
            else { b++; emit(QuotaUpdate(JSONObject())); awaitCancellation() }
        } })
        val first = backgroundScope.launch { source.observe("a").collect() }
        val second = backgroundScope.launch { source.observe("b").collect() }
        runCurrent(); assertEquals(1, a); assertEquals(1, b)
        advanceTimeBy(1000); runCurrent(); assertEquals(2, a); assertEquals(1, b)
        first.cancel(); second.cancel(); runCurrent()
        advanceTimeBy(300_000); runCurrent(); assertEquals(2, a); assertEquals(1, b)
    }
}
