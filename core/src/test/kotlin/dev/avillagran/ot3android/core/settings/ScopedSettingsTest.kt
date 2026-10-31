package dev.avillagran.ot3android.core.settings

import dev.avillagran.ot3android.core.transport.DomainCalls
import dev.avillagran.ot3android.core.transport.T3Transport
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

/** Recorded descriptor boundaries; the fake domain records arguments, not a second policy engine. */
class ScopedSettingsTest {
    private class Fixture : DomainCalls {
        val seen = mutableListOf<Pair<String, JSONObject>>()
        var fields = JSONArray("""[{"key":"responseStreamingMode","kind":"choice","value":"turn","source":"environment","scopeWritable":true}]""")
        var dropped = false
        override suspend fun domain(fn: String, args: JSONObject): Any {
            seen += fn to JSONObject(args.toString())
            return when(fn) {
                "scopedSettings.validatedTargets" -> args.getJSONObject("scope").getJSONArray("targets")
                "scopedSettings.writePatch" -> JSONObject("""{"patch":{"responseStreamingMode":"paragraph"}}""")
                "scopedSettings.writeWasDropped" -> dropped
                "scopedSettings.projectFile" -> JSONObject("""{"defaultThreadEnvMode":"worktree"}""")
                "scopedSettings.targetView" -> JSONObject().put("target", args.getJSONObject("target")).put("fields", fields)
                "scopedSettings.page" -> JSONObject().put("views", args.getJSONArray("views")).put("errors", args.getJSONArray("errors"))
                    .put("savedTargets", args.getJSONArray("saved")).put("scope", args.getJSONObject("scope"))
                else -> error(fn)
            }
        }
    }
    private class Host {
        var raw = JSONObject("""{"responseStreamingMode":"turn"}""")
        var failAfterWrite = false
        var writeFails = false
        var writes = 0
        val transport = Proxy.newProxyInstance(T3Transport::class.java.classLoader, arrayOf(T3Transport::class.java)) { _, method, args ->
            when(method.name) {
                "authSession" -> JSONObject("""{"authenticated":true,"scopes":["settings:write"]}""")
                "shell" -> JSONObject("""{"projects":[{"id":"p","workspaceRoot":"/repo"}]}""")
                "call" -> when(args!![0]) {
                    "server.getConfig" -> JSONObject()
                    "server.getSettings" -> if (writes > 0 && failAfterWrite) error("timeout") else JSONObject(raw.toString())
                    "server.updateSettings" -> { writes++; if (writeFails) error("timeout"); raw = (args[1] as JSONObject).getJSONObject("patch"); JSONObject() }
                    "projects.readFile" -> JSONObject("""{"truncated":false,"contents":"{}"}""")
                    else -> error("unexpected call")
                }
                else -> error(method.name)
            }
        } as T3Transport
    }
    private fun scope(vararg env: String, project: String? = null) = JSONObject().put("targets", JSONArray(env.map {
        JSONObject().put("environment", it).put("projectId", project ?: JSONObject.NULL)
    }))
    private val edit get() = JSONObject("""{"key":"responseStreamingMode","action":"update","value":"paragraph"}""")

    @Test fun missingHostDoesNotFallBackOrWrite() = runTest {
        val f = Fixture(); val result = ScopedSettings(SettingsConnections { null }, f).write(scope("missing"), edit)
        assertEquals(1, result.getJSONArray("errors").length()); assertFalse(f.seen.any { it.first == "scopedSettings.writePatch" })
    }
    @Test fun partialFailureRetainsSavedTargetsAndNeverRetries() = runTest {
        val f = Fixture(); val good = Host(); val bad = Host().also { it.writeFails = true }
        val result = ScopedSettings(SettingsConnections { if(it == "good") good.transport else bad.transport }, f).write(scope("good", "bad"), edit)
        assertEquals(1, result.getJSONArray("savedTargets").length()); assertEquals(1, result.getJSONArray("errors").length())
        assertTrue(result.getJSONArray("errors").getJSONObject(0).getBoolean("uncertain"))
        assertEquals(1, good.writes); assertEquals(1, bad.writes)
    }
    @Test fun uncertainReadBackRequiresExplicitReload() = runTest {
        val f = Fixture(); val h = Host().also { it.failAfterWrite = true }; val api = ScopedSettings(SettingsConnections { h.transport }, f)
        val result = api.write(scope("host"), edit)
        assertTrue(result.getJSONArray("errors").getJSONObject(0).getBoolean("uncertain"))
        assertEquals(0, result.getJSONArray("savedTargets").length())
        h.failAfterWrite = false; api.read(scope("host"))
        assertEquals(1, h.writes)
        assertEquals("paragraph", f.seen.last { it.first == "scopedSettings.targetView" }.second.getJSONObject("settings").getString("responseStreamingMode"))
    }
    @Test fun droppedWriteIsNotReportedSaved() = runTest {
        val f = Fixture().also { it.dropped = true }; val h = Host()
        val result = ScopedSettings(SettingsConnections { h.transport }, f).write(scope("host"), edit)
        assertEquals("settings_write_dropped", result.getJSONArray("errors").getJSONObject(0).getString("error"))
        assertEquals(0, result.getJSONArray("savedTargets").length())
    }
    @Test fun projectInheritanceAndFileArePassedToNative() = runTest {
        val f = Fixture(); val h = Host(); val api = ScopedSettings(SettingsConnections { h.transport }, f)
        api.write(scope("host", project = "p"), JSONObject().put("key", "responseStreamingMode").put("action", "clear"))
        val input = f.seen.first { it.first == "scopedSettings.writePatch" }.second
        assertEquals("clear", input.getJSONObject("input").getString("action"))
        assertEquals("p", input.getJSONObject("project").getString("id"))
        assertTrue(input.getBoolean("writeAllowed"))
        assertEquals("worktree", f.seen.last { it.first == "scopedSettings.targetView" }.second.getJSONObject("file").getString("defaultThreadEnvMode"))
    }
    @Test fun descriptorsArePassedWithoutFillingMissingCapabilitiesOrMixedValues() = runTest {
        val f = Fixture().also { it.fields = JSONArray("""[{"key":"defaultRuntimeMode","mixed":true,"scopeWritable":false,"source":"mixed"}]""") }
        val result = ScopedSettings(SettingsConnections { Host().transport }, f).read(scope("host"))
        val fields = result.getJSONArray("views").getJSONObject(0).getJSONArray("fields")
        assertEquals(1, fields.length()); assertTrue(fields.getJSONObject(0).getBoolean("mixed"))
        assertFalse(fields.getJSONObject(0).getBoolean("scopeWritable"))
    }
    @Test fun resetValueIsSentUnchangedAndSurvivesReload() = runTest {
        val f = Fixture(); val h = Host(); val api = ScopedSettings(SettingsConnections { h.transport }, f)
        val reset = JSONObject("""{"key":"defaultModelSelection","action":"update","value":null}""")
        api.write(scope("host"), reset); api.read(scope("host"))
        assertTrue(f.seen.first { it.first == "scopedSettings.writePatch" }.second.getJSONObject("input").isNull("value"))
        assertEquals(1,h.writes)
    }
}
