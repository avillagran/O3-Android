package dev.avillagran.ot3android.ui.settings.general

import dev.avillagran.ot3android.core.settings.ScopedSettings
import dev.avillagran.ot3android.core.settings.SettingsConnections
import dev.avillagran.ot3android.core.transport.DomainCalls
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GeneralSettingsControllerTest {
    private fun scope(id: String) = JSONObject().put("targets", JSONArray().put(JSONObject().put("environment",id).put("projectId",JSONObject.NULL)))
    @Test fun staleResponseCannotRestorePreviousScope() = runTest {
        val release = CompletableDeferred<Unit>()
        val domain = DomainCalls { fn, args -> when(fn) {
            "scopedSettings.validatedTargets" -> args.getJSONObject("scope").getJSONArray("targets")
            "scopedSettings.page" -> { if(args.getJSONObject("scope").toString().contains("old")) release.await(); JSONObject().put("scope",args.getJSONObject("scope")) }
            else -> error(fn)
        } }
        val controller = GeneralSettingsController(ScopedSettings(SettingsConnections { null },domain))
        val old = launch { controller.load(scope("old")) }; runCurrent()
        val next = launch { controller.load(scope("new")) }; runCurrent()
        assertEquals(scope("new").toString(),controller.state.value.scope)
        assertEquals("{}",controller.state.value.page)
        release.complete(Unit); old.join(); next.join()
        assertTrue(controller.state.value.page.contains("new")); assertFalse(controller.state.value.busy)
    }
    @Test fun invalidScopeFailureClearsStaleFieldsAndDisablesWrites() = runTest {
        val domain = DomainCalls { _, _ -> error("settings_scope_unavailable") }
        val controller = GeneralSettingsController(ScopedSettings(SettingsConnections { null },domain))
        controller.load(scope("gone"))
        assertTrue(controller.state.value.failed); assertEquals("{}",controller.state.value.page)
        controller.change("defaultRuntimeMode","full-access")
        assertTrue(controller.state.value.failed)
    }
    @Test fun routeContributionIsScopedAndHasStableIdentity() {
        assertEquals("general",GeneralSettingsRoute.id); assertTrue(GeneralSettingsRoute.scoped)
        assertTrue(GeneralSettingsRoute.titleResource != 0)
    }
}
