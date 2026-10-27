package dev.avillagran.ot3android.core.transport

import dev.avillagran.ot3android.core.thread.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Actual JNI typed launch and WebSocket framing; no productive server or agent is started. */
class NativeNewThreadLaunchTest {
    @Test fun selectedProjectModelAndAtomicFirstMessageReachNativeWire() = runBlocking {
        assumeHostJni()
        val transport = NativeT3Transport()
        val outbox = OutboxRepository(MemoryOutboxDao())
        val sender = MessageSender(outbox, this) { "launch-command" }
        try {
            FakeWebSocketServer { request, ws ->
                assertEquals("orchestration.launchThread", request.getString("tag"))
                val args = request.getJSONObject("payload")
                assertEquals("project-selected", args.getString("projectId"))
                assertEquals("launch-thread", args.getString("threadId"))
                assertEquals("launch-command", args.getString("commandId"))
                assertEquals("provider", args.getJSONObject("modelSelection").getString("instanceId"))
                assertEquals("root", args.getJSONObject("workspaceStrategy").getString("type"))
                assertEquals("Task from picker", args.getJSONObject("initialMessage").getString("text"))
                ws.send(item("_tag" to "Exit", "requestId" to request.get("id"), "exit" to
                    item("_tag" to "Success", "value" to item("threadId" to "launch-thread", "resumed" to false))))
            }.use { server ->
                transport.connect(ConnectRoute.Bearer(server.baseUrl, "fixture-token-123"))
                val args = JSONObject().put("projectId", "project-selected").put("title", "Task")
                    .put("modelSelection", JSONObject().put("instanceId", "provider").put("model", "model"))
                    .put("runtimeMode", "approval-required").put("interactionMode", "default")
                    .put("workspaceStrategy", JSONObject().put("type", "root")).put("creationSource", "web")
                    .put("initialMessage", JSONObject().put("text", "Task from picker").put("attachments", JSONArray()))
                assertTrue(sender.launchThread(transport, "local-env", "launch-thread", args) is SendResult.Accepted)
                assertNull(outbox.find("launch-command"))
                server.join()
            }
        } finally { transport.shutdown() }
    }
}
