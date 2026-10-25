package dev.avillagran.ot3android.core.transport

import dev.avillagran.t3.T3Batch
import dev.avillagran.t3.T3Exception
import dev.avillagran.t3.ThreadUpdate
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeT3TransportTest {

    private val fake = FakeT3Transport()

    @Test
    fun scriptedCallNeedsAConnection() = runTest {
        fake.onCall("vcs.refreshStatus") { args -> JSONObject().put("cwd", args.getString("cwd")) }

        val before = runCatching { fake.call("vcs.refreshStatus", JSONObject().put("cwd", "/repo")) }
        fake.connect(ConnectRoute.Bearer("http://t3.test", "token-12345678"))
        val after = fake.call("vcs.refreshStatus", JSONObject().put("cwd", "/repo")) as JSONObject

        assertEquals("not_connected", (before.exceptionOrNull() as T3Exception).code)
        assertEquals("/repo", after.getString("cwd"))
        assertEquals(2, fake.calls.size)
    }

    @Test
    fun unscriptedCallIsUnknownFunction() {
        val error = assertThrows(T3Exception::class.java) {
            runBlocking { fake.call("vcs.nope") }
        }
        assertEquals("unknown_function", error.code)
    }

    @Test
    fun routeNeverPrintsItsCredential() {
        val route = ConnectRoute.Bearer("http://t3.test", "secret-token-123")

        assertTrue(route.toString().contains("<redacted>"))
        assertTrue(!route.toString().contains("secret-token-123"))
    }

    @Test
    fun threadStreamEndsAfterTheClosedUpdate() = runTest {
        fake.connect(ConnectRoute.Bearer("http://t3.test", "token-12345678"))
        val collected = async { fake.threadStream("t-1", afterSequence = 5).toList() }
        fake.collectors("t-1").first { it > 0 }

        fake.emitThread("t-1", update(cursor = 6, closed = false))
        fake.emitThread("t-1", update(cursor = 6, closed = true, error = "stream_timeout"))

        val updates = collected.await()
        assertEquals(listOf(false, true), updates.map { it.closed })
        assertEquals(FakeT3Transport.StreamOpen("t-1", 5, false, true, false), fake.streamOpens.single())
    }

    @Test
    fun factoryKeepsEveryTransportItCreates() {
        val factory = FakeT3TransportFactory()

        val first = factory.create()
        val second = factory.create()

        assertEquals(listOf(first, second), factory.created)
        assertTrue(first !== second)
    }

    private fun update(cursor: Long, closed: Boolean, error: String? = null): ThreadUpdate {
        val batch = T3Batch(items = JSONArray(), closed = closed, error = error, cursor = cursor)
        return ThreadUpdate(
            snapshot = null, events = emptyList(), repaint = false, synchronized = false, cursor = cursor,
            start = null, idle = false, completionMarker = false, closed = closed, error = error,
            errorMessage = null, batch = batch,
        )
    }
}
