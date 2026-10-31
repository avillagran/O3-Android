package dev.avillagran.ot3android.ui.thread.reader

import dev.avillagran.ot3android.core.transport.FakeT3Transport
import dev.avillagran.ot3android.core.transport.T3Transport
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TranscriptConnectionTest {
    @Test fun privateAnswerRequiresPermissionAndCurrentThread() = runBlocking {
        for (allowed in listOf(false, true)) {
            var calls = 0
            val base = FakeT3Transport()
            val transport = object : T3Transport by base {
                override suspend fun authSession() = JSONObject().put("authenticated", true)
                    .put("permissions", org.json.JSONArray(if (allowed) listOf("orchestration:operate") else emptyList<String>()))
                override suspend fun call(fn: String, args: JSONObject, timeoutMs: Int?): Any? { calls++; return null }
            }
            val controller = scopedTranscriptSecrets(transport, "thread") { true }
            val value = "private".toCharArray()
            val result = runCatching { controller.answer("thread", "request", value) }
            assertEquals(allowed, result.isSuccess)
            assertEquals(if (allowed) 1 else 0, calls)
            assertTrue(value.all { it == '\u0000' })
            assertTrue(runCatching { controller.answer("another-thread", "request", null) }.isFailure)
            assertEquals(if (allowed) 1 else 0, calls)
            assertTrue(base.log.isEmpty())
        }
    }

    @Test fun connectionSwitchDuringAuthorizationCannotSendOrReplay() = runBlocking {
        var current = true
        var calls = 0
        val base = FakeT3Transport()
        val transport = object : T3Transport by base {
            override suspend fun authSession(): JSONObject {
                current = false
                return JSONObject("""{"authenticated":true,"permissions":["orchestration:operate"]}""")
            }
            override suspend fun call(fn: String, args: JSONObject, timeoutMs: Int?): Any? { calls++; return null }
        }
        val controller = scopedTranscriptSecrets(transport, "thread") { current }
        assertTrue(runCatching { controller.answer("thread", "request", "private".toCharArray()) }.isFailure)
        current = true
        controller.answer("thread", "request", null)
        assertEquals(0, calls)
        assertEquals(SecretDelivery.Uncertain, controller.deliveries.value["thread/request"])
        assertTrue(base.log.isEmpty())
    }
}
