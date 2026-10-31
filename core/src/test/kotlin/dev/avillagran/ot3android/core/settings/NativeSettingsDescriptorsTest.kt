package dev.avillagran.ot3android.core.settings

import dev.avillagran.ot3android.core.transport.DomainEngine
import dev.avillagran.ot3android.core.transport.assumeHostJni
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Pinned AAR policy fixtures, required in CI with ot3a.requireHostJni. */
class NativeSettingsDescriptorsTest {
    @Before fun nativeAvailable() = assumeHostJni()
    private val native = DomainEngine()
    private val target = JSONObject("""{"environment":"host","projectId":"p"}""")
    private val project = JSONObject("""{"id":"p","workspaceRoot":"/repo"}""")
    private val config = JSONObject("""{"environment":{"capabilities":{"projectSettingsOverrides":true}},"providers":[]}""")
    private suspend fun view(raw: JSONObject, configuration: JSONObject = config, p: JSONObject? = project): JSONObject =
        native.domain("scopedSettings.targetView",JSONObject().put("target",target).put("config",configuration).put("settings",raw)
            .put("project",p ?: JSONObject.NULL).put("writeAllowed",true)) as JSONObject
    private fun fields(page: JSONObject) = page.getJSONArray("fields").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    @Test fun projectCapabilityAbsentDisablesScopedWrites() = runBlocking {
        val page = view(JSONObject("""{"defaultRuntimeMode":"full-access"}"""),JSONObject())
        assertFalse(fields(page).first { it.getString("key") == "defaultRuntimeMode" }.getBoolean("scopeWritable"))
    }
    @Test fun missingSettingsDoNotInventTelemetryOrUpdateControls() = runBlocking {
        val keys = fields(view(JSONObject())).map { it.getString("key") }
        assertFalse("observability.otlpTracesUrl" in keys)
        assertFalse("enableProviderUpdateChecks" in keys)
    }
    @Test fun mixedValuesAndSourcesArePreserved() = runBlocking {
        val a = view(JSONObject("""{"defaultRuntimeMode":"full-access"}"""))
        val b = view(JSONObject("""{"defaultRuntimeMode":"full-access","projectSettingsOverrides":{"p":{"defaultRuntimeMode":"approval-required"}}}"""))
        val aggregate = native.domain("scopedSettings.aggregate",JSONObject().put("views",JSONArray().put(a).put(b))) as JSONObject
        val field = fields(aggregate).first { it.getString("key") == "defaultRuntimeMode" }
        assertTrue(field.getBoolean("mixed")); assertEquals("mixed",field.getString("source"))
    }
    @Test fun inheritPatchPreservesOtherProjectOverrides() = runBlocking {
        val raw = JSONObject("""{"defaultRuntimeMode":"full-access","projectSettingsOverrides":{"p":{"defaultRuntimeMode":"approval-required","threadTitleInstructions":"Keep"}}}""")
        val patch = native.domain("scopedSettings.writePatch",JSONObject().put("input",JSONObject("""{"key":"defaultRuntimeMode","action":"clear"}"""))
            .put("settings",raw).put("config",config).put("project",project).put("writeAllowed",true)) as JSONObject
        val entry = patch.getJSONObject("patch").getJSONObject("projectSettingsOverrides").getJSONObject("p")
        assertFalse(entry.has("defaultRuntimeMode")); assertEquals("Keep",entry.getString("threadTitleInstructions"))
    }
    @Test fun resetAndReadbackUseNativeRules() = runBlocking {
        val patch = native.domain("nativeSettings.patch",JSONObject().put("input",JSONObject("""{"key":"defaultModelSelection","value":null}"""))) as JSONObject
        assertTrue(patch.getJSONObject("patch").isNull("defaultModelSelection"))
        val dropped = native.domain("scopedSettings.writeWasDropped",JSONObject().put("next",JSONObject("""{"responseStreamingMode":"paragraph"}"""))
            .put("before",JSONObject("""{"responseStreamingMode":"turn"}""" )).put("saved",JSONObject("""{"responseStreamingMode":"turn"}"""))) as Boolean
        assertTrue(dropped)
    }
    @Test fun partialPageRetainsErrorsAndDisablesWrites() = runBlocking {
        val v = view(JSONObject())
        val page = native.domain("scopedSettings.page",JSONObject().put("scope",JSONObject().put("targets",JSONArray().put(target)))
            .put("views",JSONArray().put(v)).put("errors",JSONArray("""[{"target":{"environment":"gone","projectId":"p"},"error":"unreachable","uncertain":true}]"""))
            .put("saved",JSONArray().put(target)).put("requested",2)) as JSONObject
        assertFalse(page.getBoolean("writable")); assertFalse(page.getBoolean("complete")); assertEquals(1,page.getJSONArray("savedTargets").length())
    }
}
