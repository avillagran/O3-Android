package dev.avillagran.ot3android.core.awareness

import dev.avillagran.ot3android.core.transport.DomainCalls
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*

class ThreadAwarenessLifecycleTest {
    @Test fun lifecycleAndBaselineSurviveOfflineResumeAndFailedEvaluation() = runTest {
        val calls = mutableListOf<JSONObject>()
        var reject = false
        val awareness = ThreadAwareness(DomainCalls { fn, args ->
            assertEquals("awareness.view", fn)
            calls.add(args)
            if (reject) error("projection_failed")
            JSONObject().put("baseline", JSONObject().put("generation", calls.size))
        })
        val envs = JSONArray().put(JSONObject().put("id", "node").put("state", "ready"))
        awareness.evaluate(envs, 1)
        assertEquals("cold", calls.last().getString("lifecycle"))
        val route = JSONObject().put("environmentId", "node").put("threadId", "t")
        val prefs = JSONObject().put("completion", false)
        awareness.evaluate(envs, 2, onScreen = route, preferences = prefs)
        assertEquals("running", calls.last().getString("lifecycle"))
        assertSame(route, calls.last().getJSONObject("onScreen"))
        assertSame(prefs, calls.last().getJSONObject("preferences"))
        reject = true
        try { awareness.evaluate(JSONArray(), 3); fail("expected projection failure") } catch (_: IllegalStateException) { }
        reject = false
        awareness.evaluate(envs, 4, resumed = true)
        assertEquals("resumed", calls.last().getString("lifecycle"))
        assertEquals(2, calls.last().getJSONObject("baseline").getInt("generation"))
        assertFalse(calls.last().has("onScreen"))
    }
}
