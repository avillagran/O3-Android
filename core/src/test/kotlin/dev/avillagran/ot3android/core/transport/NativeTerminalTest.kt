package dev.avillagran.ot3android.core.transport

import dev.avillagran.ot3android.core.terminal.TerminalEmulator
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class NativeTerminalTest {
    @Before fun nativeAvailable() = assumeHostJni()
    @Test fun readOnlyWireOperationAndSplitUtf8AnsiSurviveRealNativeStream() = runBlocking {
        val server = FakeWebSocketServer { request, ws ->
            assertEquals("terminal.observe", request.getString("tag"))
            val args = request.getJSONObject("payload")
            assertEquals(setOf("threadId", "terminalId"), args.keys().asSequence().toSet())
            ws.chunk(item("type" to "snapshot", "snapshot" to item("history" to "", "sequence" to 1, "status" to "running")))
            check(ws.awaitTag("Ack"))
            ws.chunkSplitUtf8(item("type" to "output", "sequence" to 2, "data" to "café 東京 🚀 \u001b[3"))
            check(ws.awaitTag("Ack"))
            ws.chunk(item("type" to "output", "sequence" to 3, "data" to "1mred"))
            check(ws.awaitTag("Ack"))
            ws.chunk(item("type" to "exited", "exitCode" to 7))
            check(ws.awaitTag("Ack"))
        }
        server.use {
            NativeT3Transport().use { host ->
                host.connect(ConnectRoute.Bearer(server.baseUrl, "fixture-token-123"))
                val emulator = TerminalEmulator()
                var output = JSONObject()
                var finished = false
                withTimeout(10000) {
                    host.terminalStream(true, item("threadId" to "thread-a", "terminalId" to "term-1")).collect { batch ->
                        for (i in 0 until batch.items.length()) {
                            val reduced = host.domain("terminal.output", item("state" to output, "event" to batch.items.getJSONObject(i))) as JSONObject
                            output = reduced.getJSONObject("state")
                            if (reduced.getBoolean("replace")) emulator.reset()
                            if (!reduced.isNull("text")) emulator.feed(reduced.getString("text"))
                            if (reduced.getBoolean("finished")) { finished = true; assertEquals(7, reduced.getInt("exitCode")) }
                        }
                    }
                }
                assertTrue(emulator.frame().text().startsWith("café 東京 🚀 red"))
                assertEquals(1, emulator.frame().lines[0].first { it.text == "r" }.fg)
                assertTrue(finished)
            }
            server.join()
        }
    }
    @Test fun unaryWritesPayForANewWebSocketUpgradeEveryTime() = runBlocking {
        val received = mutableListOf<String>()
        val elapsed = mutableListOf<Long>()
        val server = FakeWebSocketServer(connections = 3, handshakeDelayMs = 150) { request, ws ->
            assertEquals("terminal.write", request.getString("tag"))
            received += request.getJSONObject("payload").getString("data")
            Thread.sleep(150)
            ws.send(item("_tag" to "Exit", "requestId" to "1",
                "exit" to item("_tag" to "Success", "value" to JSONObject.NULL)))
        }
        server.use {
            NativeT3Transport().use { host ->
                host.connect(ConnectRoute.Bearer(server.baseUrl, "fixture-token-123"))
                for (text in listOf("a", "b", "\r")) {
                    val start = System.nanoTime()
                    host.call("terminal.write", item("threadId" to "thread-a", "terminalId" to "term-1", "data" to text))
                    elapsed += (System.nanoTime() - start) / 1_000_000
                }
            }
            server.join()
        }
        assertEquals(listOf("a", "b", "\r"), received)
        assertTrue(elapsed.all { it >= 300 })
        println("TERMINAL_NATIVE: 150ms upgrade + 150ms response, 3 writes, 3 TCP/WebSocket connections, elapsedMs=$elapsed")
    }
    @Test fun nativeContextResumeAndSnapshotReplacementAreAuthoritative() = runBlocking {
        NativeT3Transport().use { host ->
            val shell = JSONObject("""{"projects":[{"id":"p","workspaceRoot":"/remote/root"}],"threads":[{"id":"t","projectId":"p","worktreePath":"/remote/tree","modelSelection":{"instanceId":"provider-a"}}]}""")
            val context = host.domain("terminal.context", item("shell" to shell, "threadId" to "t", "fallback" to "")) as JSONObject
            assertEquals("/remote/tree", context.getString("cwd")); assertEquals("provider-a", context.getString("providerInstanceId"))
            val resume = host.domain("terminal.attach", item("threadId" to "t", "terminalId" to "term-1", "context" to context, "cols" to 99, "rows" to 12, "resume" to true)) as JSONObject
            assertFalse(resume.getBoolean("restartIfNotRunning")); assertTrue(resume.getBoolean("resume")); assertFalse(resume.has("afterSequence"))
            val snapshot = host.domain("terminal.output", item("event" to item("type" to "snapshot", "snapshot" to item("history" to "replacement", "sequence" to 9, "status" to "exited", "exitCode" to 0)))) as JSONObject
            assertTrue(snapshot.getBoolean("replace")); assertTrue(snapshot.getBoolean("finished"))
            val duplicate = host.domain("terminal.output", item("state" to snapshot.getJSONObject("state"), "event" to item("type" to "output", "sequence" to 9, "data" to "duplicate"))) as JSONObject
            assertTrue(duplicate.isNull("text"))
        }
    }
}
