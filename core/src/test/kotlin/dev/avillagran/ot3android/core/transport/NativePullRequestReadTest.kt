package dev.avillagran.ot3android.core.transport

import dev.avillagran.ot3android.core.pullrequests.PullRequestReader
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Actual JNI typed API and WebSocket, with a local synthetic RPC server. */
class NativePullRequestReadTest {
    @Test fun projectFiltersAndOpaqueCursorReachNativeWire() = runBlocking {
        assumeHostJni()
        val transport = NativeT3Transport()
        try {
            FakeWebSocketServer { request, ws ->
                assertEquals("pullRequests.list", request.getString("tag"))
                val args = request.getJSONObject("payload")
                assertEquals("project", args.getString("projectId"))
                assertEquals("merged", args.getString("state"))
                assertEquals("authored", args.getString("involvement"))
                assertEquals("scope", args.getString("query"))
                assertEquals("opaque-cursor", args.getJSONObject("cursors").getString("project"))
                ws.send(item("_tag" to "Exit", "requestId" to request.get("id"), "exit" to item("_tag" to "Success", "value" to item("entries" to org.json.JSONArray(), "truncated" to false))))
            }.use { server ->
                transport.connect(ConnectRoute.Bearer(server.baseUrl, "fixture-token-123"))
                val result = PullRequestReader().list(transport, "merged", "authored", "scope", "project", JSONObject().put("project", "opaque-cursor"))
                assertEquals(0, result.getJSONArray("entries").length())
                server.join()
            }
        } finally { transport.shutdown() }
    }
}
