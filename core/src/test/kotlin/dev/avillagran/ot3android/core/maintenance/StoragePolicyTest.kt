package dev.avillagran.ot3android.core.maintenance

import dev.avillagran.ot3android.core.settings.ScopedSettings
import dev.avillagran.ot3android.core.settings.SettingsConnections
import dev.avillagran.ot3android.core.transport.DomainCalls
import dev.avillagran.ot3android.core.transport.T3Transport
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.lang.reflect.Proxy

class StoragePolicyTest {
    @get:Rule val temporary = TemporaryFolder()
    private val host = MaintenanceHost("e", "https://host", "viewer")
    private class Fixture : DomainCalls {
        var writable = true
        var uncertain = false
        var invalidPolicy = false
        var updates = 0
        var selected = JSONObject()
        private val transport = Proxy.newProxyInstance(T3Transport::class.java.classLoader, arrayOf(T3Transport::class.java)) { _, method, args ->
            when(method.name) {
                "authSession" -> JSONObject("""{"authenticated":true,"scopes":["settings:write"]}""")
                "shell" -> JSONObject("""{"projects":[{"id":"p","workspaceRoot":"/repo"}]}""")
                "call" -> when(args!![0]) {
                    "server.getConfig", "server.getSettings" -> JSONObject()
                    "server.updateSettings" -> { updates++; if (uncertain) error("timeout"); JSONObject() }
                    else -> error("unexpected")
                }
                else -> error(method.name)
            }
        } as T3Transport
        val settings = ScopedSettings(SettingsConnections { check(it == "e"); transport }, this)
        override suspend fun domain(fn: String, args: JSONObject): Any = when(fn) {
            "scopedSettings.validatedTargets" -> args.getJSONObject("scope").getJSONArray("targets").also { selected = it.getJSONObject(0) }
            "scopedSettings.targetView" -> JSONObject()
            "scopedSettings.writePatch" -> if (invalidPolicy) error("invalid_settings") else JSONObject().put("patch", JSONObject().put("storageCleanup", JSONObject().put("logsAfterDays", 7)))
            "scopedSettings.writeWasDropped" -> false
            "scopedSettings.page" -> JSONObject().put("writable", writable).put("errors", args.getJSONArray("errors"))
                .put("fields", JSONArray().put(JSONObject().put("key", "storageCleanup.logsAfterDays").put("scopeWritable", writable)))
            else -> error(fn)
        }
    }
    @Test fun storageUsesNativeWritePatchAndRefreshesConfirmedPage() = runTest {
        val f = Fixture(); val c = StoragePolicyController(host, f.settings, FileMaintenanceReceipts(temporary.newFolder()))
        c.refresh(); c.change("storageCleanup.logsAfterDays", 7)
        assertEquals(1, f.updates); assertFalse(c.state.value.stale); assertFalse(c.state.value.unknown)
    }
    @Test fun uncertainPolicyCannotBeRetriedAfterRefresh() = runTest {
        val f = Fixture(); val c = StoragePolicyController(host, f.settings, FileMaintenanceReceipts(temporary.newFolder()))
        c.refresh(); f.uncertain = true; c.change("storageCleanup.logsAfterDays", 7)
        assertTrue(c.state.value.unknown); f.uncertain = false; c.refresh()
        assertTrue(c.state.value.unknown)
        assertTrue(runCatching { c.change("storageCleanup.logsAfterDays", 7) }.isFailure)
        assertEquals(1, f.updates)
    }
    @Test fun unadvertisedOrReadOnlyFieldsCannotWrite() = runTest {
        val f = Fixture(); f.writable = false
        val c = StoragePolicyController(host, f.settings, FileMaintenanceReceipts(temporary.newFolder())); c.refresh()
        assertTrue(runCatching { c.change("storageCleanup.logsAfterDays", 7) }.isFailure)
        assertTrue(runCatching { c.change("storageCleanup.madeUp", 7) }.isFailure)
        assertEquals(0, f.updates)
    }

    @Test fun explicitProjectScopeIsPassedToNativeSettings() = runTest {
        val f = Fixture()
        val c = StoragePolicyController(host, f.settings, FileMaintenanceReceipts(temporary.newFolder()), projectId = "p")
        c.refresh(); assertEquals("p", f.selected.getString("projectId")); assertEquals("e", f.selected.getString("environment"))
    }

    @Test fun nativeRejectionBeforeDispatchDoesNotLeaveUnknownReceipt() = runTest {
        val f = Fixture(); val c = StoragePolicyController(host, f.settings, FileMaintenanceReceipts(temporary.newFolder()))
        c.refresh(); f.invalidPolicy = true; c.change("storageCleanup.logsAfterDays", 7)
        assertTrue(c.state.value.failed); assertFalse(c.state.value.unknown); assertEquals(0, f.updates)
        f.invalidPolicy = false; c.refresh(); c.change("storageCleanup.logsAfterDays", 7)
        assertEquals(1, f.updates)
    }
}
