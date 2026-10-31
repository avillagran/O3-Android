package dev.avillagran.ot3android.core.transport

import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConsumerWritesTest {
    @get:Rule val directory = TemporaryFolder()
    @Test fun restartRetriesOnlyUnacknowledgedSuffixWithOriginalIds() = runTest {
        val disk = directory.newFolder()
        var calls = 0
        val received = mutableListOf<String>()
        val transport = object : T3Transport by FakeT3Transport() {
            override suspend fun call(fn: String, args: JSONObject, timeoutMs: Int?): Any? {
                received += args.toString(); calls++
                if (calls == 2) error("connection_lost")
                return JSONObject()
            }
        }
        val commands = JSONArray().put(JSONObject().put("commandId", "first")).put(JSONObject().put("commandId", "second"))
        try { ConsumerWrites(disk).submit("env", transport, "orchestration.dispatchCommand", commands); fail() }
        catch (_: IllegalStateException) { }
        val restarted = ConsumerWrites(disk)
        assertEquals(1, restarted.pending("env")!!.getInt("next"))
        restarted.retry("env", transport)
        assertEquals(listOf("first", "second", "second"), received.map { JSONObject(it).getString("commandId") })
        assertEquals(received[1], received[2])
        assertNull(restarted.pending("env"))
    }
}
