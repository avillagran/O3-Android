package dev.avillagran.ot3android.core.transport

import dev.avillagran.t3.T3Exception
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeT3TransportTest {

    private val fake = FakeT3Transport()
    private val route = ConnectRoute.Bearer("http://t3.test", "token-12345678")

    @Test
    fun scriptedCallNeedsAConnection() = runTest {
        fake.onCall("vcs.refreshStatus") { args -> JSONObject().put("cwd", args.getString("cwd")) }

        val before = runCatching { fake.call("vcs.refreshStatus", JSONObject().put("cwd", "/repo")) }
        fake.connect(route)
        val after = fake.call("vcs.refreshStatus", JSONObject().put("cwd", "/repo")) as JSONObject

        assertEquals("not_connected", (before.exceptionOrNull() as T3Exception).code)
        assertEquals("/repo", after.getString("cwd"))
        assertEquals(2, fake.calls.size)
    }

    @Test
    fun answersMaySuspend() = runTest {
        fake.onShell { delay(1_000); JSONObject().put("snapshotSequence", 4) }
        fake.onBoundedThread("t-1") { delay(10); JSONObject().put("id", "t-1") }
        fake.connect(route)

        assertEquals(4, fake.shell().getInt("snapshotSequence"))
        assertEquals("t-1", fake.boundedThread("t-1").getString("id"))
    }

    @Test
    fun unscriptedCallIsUnknownFunction() {
        val error = assertThrows(T3Exception::class.java) {
            runBlocking { fake.call("vcs.nope") }
        }
        assertEquals("unknown_function", error.code)
    }

    @Test
    fun everyMethodIsLogged() = runTest {
        fake.onFetchDescriptor("http://t3.test") { JSONObject().put("environmentId", "env-1") }

        fake.fetchDescriptor("http://t3.test", "env-1")
        fake.connect(route)
        fake.capabilities()
        fake.wakeSubscriptions()
        fake.disconnect()
        fake.close()

        assertEquals(
            listOf("fetchDescriptor", "connect", "capabilities", "wakeSubscriptions", "disconnect", "close"),
            fake.log.map { it.method },
        )
        assertEquals(listOf("http://t3.test", "env-1"), fake.log.first().args)
    }

    @Test
    fun routeNeverPrintsItsCredential() {
        val route = ConnectRoute.Bearer("http://t3.test", "secret-token-123")

        assertTrue(route.toString().contains("<redacted>"))
        assertTrue(!route.toString().contains("secret-token-123"))
    }

    @Test
    fun threadStreamEndsAfterTheClosedUpdate() = runTest {
        fake.connect(route)
        val collected = async { fake.threadStream("t-1", afterSequence = 5).toList() }
        fake.collectors("t-1").first { it > 0 }

        fake.emitThread("t-1", ThreadUpdates.idle(cursor = 6))
        fake.emitThread("t-1", ThreadUpdates.closed("stream_timeout", cursor = 6))

        val updates = collected.await()
        assertEquals(listOf(false, true), updates.map { it.closed })
        assertEquals(FakeT3Transport.StreamOpen("t-1", 5, false, true, false), fake.streamOpens.single())
    }

    @Test
    fun emitErrorEndsTheStreamWithItsCode() = runTest {
        fake.connect(route)
        val collected = async { fake.shellStream().toList() }
        fake.collectors().first { it > 0 }

        fake.emitError(T3Exception("stream_backpressure", "slow collector"))

        val last = collected.await().single()
        assertTrue(last.closed)
        assertEquals("stream_backpressure", last.error)
        assertEquals("slow collector", last.errorMessage)
    }

    @Test
    fun failNextStreamFailsOnlyTheNextCollection() = runTest {
        fake.connect(route)
        fake.failNextStream(T3Exception("subscription_limit"))
        fake.onShellStream { flowOf(ThreadUpdates.closed(null)) }

        val error = runCatching { fake.shellStream().toList() }.exceptionOrNull() as T3Exception
        val next = fake.shellStream().toList()

        assertEquals("subscription_limit", error.code)
        assertEquals(1, next.size)
    }

    @Test
    fun streamHooksReceiveTheirArguments() = runTest {
        fake.connect(route)
        fake.onThreadStream { threadId, afterSequence ->
            flowOf(
                ThreadUpdates.snapshot(JSONObject().put("id", threadId), cursor = afterSequence),
                ThreadUpdates.event(JSONObject().put("kind", "event").put("sequence", 8), cursor = 8),
                ThreadUpdates.closed("disconnected", cursor = 8),
            )
        }

        val updates = fake.threadStream("t-2", afterSequence = 7).toList()

        assertEquals("t-2", updates[0].snapshot!!.getString("id"))
        assertEquals(7L, updates[0].cursor)
        assertEquals(8, updates[1].events.single().getInt("sequence"))
        assertEquals("disconnected", updates.last().error)
    }

    @Test
    fun connectDisconnectAndCloseEndOpenStreamsLikeACancel() = runTest {
        fake.connect(route)
        fake.onShellStream { flow { emit(ThreadUpdates.idle()); delay(Long.MAX_VALUE) } }
        for (end in listOf<suspend () -> Unit>({ fake.connect(route) }, { fake.disconnect() }, { fake.close() })) {
            if (!fake.connected) fake.connect(route)
            val collected = async { fake.shellStream().toList() }
            fake.openStreams.first { it == 1 }

            end()

            val last = collected.await().last()
            assertTrue(last.closed)
            assertNull(last.error)
            assertEquals(0, fake.openStreams.value)
        }
    }

    @Test
    fun emitWithoutACollectorIsDropped() = runTest {
        fake.connect(route)
        fake.emitShell(ThreadUpdates.idle())
        fake.onShellStream { flowOf(ThreadUpdates.closed(null)) }

        val updates = fake.shellStream().toList()

        assertEquals(listOf(true), updates.map { it.closed })
    }

    @Test
    fun factoryKeepsEveryTransportItCreates() {
        val factory = FakeT3TransportFactory()

        val first = factory.create()
        val second = factory.create()

        assertEquals(listOf(first, second), factory.created)
        assertTrue(first !== second)
    }
}
