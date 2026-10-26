package dev.avillagran.ot3android.core.awareness

import dev.avillagran.ot3android.core.transport.assumeHostJni
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ThreadAwarenessTest {
    private val now = 1792036800000L
    private fun env(kind: String): JSONArray {
        val thread = JSONObject().put("id", "thread").put("title", "Native awareness fixture")
            .put("projectId", "project").put("status", "running").put("latestRunId", "run")
            .put("activityRunStartedAt", Instant.ofEpochMilli(now - 60000).toString())
            .put("updatedAt", Instant.ofEpochMilli(now).toString()).put("lineage", JSONObject())
        if (kind == "approval") thread.put("pendingRuntimeRequest", JSONObject().put("id", "request")
            .put("kind", "command_approval").put("createdAt", Instant.ofEpochMilli(now).toString()))
        val shell = JSONObject().put("projects", JSONArray().put(JSONObject().put("id", "project").put("title", "OT3")))
            .put("threads", JSONArray().put(thread))
        return JSONArray().put(JSONObject().put("id", "node").put("name", "NODE").put("shell", shell))
    }
    @Test fun nativeColdBaselineAlertsOnceAndSuppliesRealPresentationObjects() = runTest {
        assumeHostJni()
        val engine = ThreadAwareness()
        val running = engine.evaluate(env("running"), now)
        assertEquals(0, running.getJSONArray("alerts").length())
        assertTrue(running.getJSONObject("card").getBoolean("active"))
        val approval = engine.evaluate(env("approval"), now)
        assertEquals(1, approval.getJSONArray("alerts").length())
        assertEquals("waiting_for_approval", approval.getJSONObject("card").getString("phase"))
        assertEquals("approve", approval.getJSONObject("card").getJSONObject("chip").getString("kind"))
        assertEquals(0, engine.evaluate(env("approval"), now + 1).getJSONArray("alerts").length())
        System.getenv("OT3A_AWARENESS_CAPTURE")?.let { path ->
            java.io.File(path).writeText(JSONObject().put("running", running).put("approval", approval).toString(2))
        }
    }
    @Test fun onScreenTransitionIsConsumedAndNeverReplayedAfterLeaving() = runTest {
        assumeHostJni()
        val engine = ThreadAwareness()
        engine.evaluate(env("running"), now)
        val route = JSONObject().put("environmentId", "node").put("threadId", "thread")
        assertEquals(0, engine.evaluate(env("approval"), now, onScreen = route).getJSONArray("alerts").length())
        assertEquals(0, engine.evaluate(env("approval"), now + 1).getJSONArray("alerts").length())
    }
}
