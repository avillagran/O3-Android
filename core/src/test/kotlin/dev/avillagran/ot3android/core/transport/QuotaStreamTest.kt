package dev.avillagran.ot3android.core.transport

import dev.avillagran.ot3android.core.usage.QuotaReader
import dev.avillagran.t3.T3Batch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class QuotaStreamTest {
    @Test
    fun nativeReducerFoldsActualConfigProviderAndHubItemsAndSanitizes() = runBlocking {
        assumeHostJni()
        val fake = FakeT3Transport()
        fake.connect(ConnectRoute.Bearer("http://fixture", "fixture"))
        val limits = JSONObject("""{"checkedAt":"2026-10-26T12:00:00Z","secret":"PRIVATE","windows":[{"id":"weekly","kind":"weekly","label":"Weekly","usedPercent":30}]}""")
        fun provider(id: String) = item("instanceId" to id, "driver" to "codex", "usageLimits" to limits,
            "auth" to item("status" to "authenticated", "accessToken" to "PRIVATE"))
        fun event(type: String, payload: JSONObject) = item("version" to 1, "type" to type, "payload" to payload)
        val snapshot = item("version" to 1, "type" to "snapshot", "config" to item("providers" to JSONArray().put(provider("first"))))
        val providers = event("providerStatuses", item("providers" to JSONArray().put(provider("second"))))
        val hub = event("usageLimitSourcesUpdated", item("sources" to JSONArray().put(item(
            "id" to "hub", "managementKey" to "PRIVATE", "error" to "PRIVATE",
            "accounts" to JSONArray().put(item("id" to "account", "driver" to "codex", "accessToken" to "PRIVATE", "usageLimits" to limits)),
        ))))
        fake.onServerConfigStream { flowOf(
            T3Batch(JSONArray().put(snapshot).put(providers).put(hub), false, null),
            T3Batch(JSONArray(), true, "disconnected"),
        ) }
        val updates = QuotaReader().stream(fake).toList()
        assertEquals(2, updates.size)
        val state = updates.first().state!!
        assertEquals("second", state.getJSONArray("providers").getJSONObject(0).getString("instanceId"))
        assertEquals("account", state.getJSONArray("usageLimitSources").getJSONObject(0).getJSONArray("accounts").getJSONObject(0).getString("id"))
        assertTrue(state.getBoolean("connected"))
        assertFalse(state.toString().contains("PRIVATE"))
        assertEquals("disconnected", updates.last().error)
        assertTrue(updates.last().closed)
        assertEquals(0, fake.openStreams.value)
    }

    @Test
    fun nativeTypedStreamClosesWithSocketFailure() = runBlocking {
        assumeHostJni()
        val transport = NativeT3Transport()
        try {
            FakeWebSocketServer { request, ws ->
                assertEquals("subscribeServerConfig", request.getString("tag"))
                assertTrue(request.getJSONObject("payload").getBoolean("usageLimitSources"))
                ws.chunk(item("version" to 1, "type" to "snapshot", "config" to item("providers" to JSONArray())))
                check(ws.awaitTag("Ack"))
            }.use { server ->
                transport.connect(ConnectRoute.Bearer(server.baseUrl, "fixture-token-123"))
                val batches = withTimeout(10_000) { transport.serverConfigStream().toList() }
                server.join()
                assertEquals("snapshot", batches.first().items.getJSONObject(0).getString("type"))
                assertTrue(batches.last().closed)
                assertNotNull(batches.last().error)
            }
        } finally { transport.shutdown() }
    }

    @Test
    fun cancellingNativeCollectorClosesSubscription() = runBlocking {
        assumeHostJni()
        val transport = NativeT3Transport()
        val interrupted = CountDownLatch(1)
        try {
            FakeWebSocketServer { _, ws ->
                ws.chunk(item("version" to 1, "type" to "snapshot", "config" to item("providers" to JSONArray())))
                check(ws.awaitTag("Ack"))
                if (ws.awaitTag("Interrupt")) interrupted.countDown()
            }.use { server ->
                transport.connect(ConnectRoute.Bearer(server.baseUrl, "fixture-token-123"))
                val received = CompletableDeferred<Unit>()
                val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                    transport.serverConfigStream().collect { received.complete(Unit) }
                }
                withTimeout(10_000) { received.await() }
                collector.cancelAndJoin()
                assertTrue(interrupted.await(5, TimeUnit.SECONDS))
                assertEquals(0, transport.wakeSubscriptions())
                server.join()
            }
        } finally { transport.shutdown() }
    }
}
