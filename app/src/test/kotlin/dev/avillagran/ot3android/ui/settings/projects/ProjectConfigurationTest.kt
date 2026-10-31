package dev.avillagran.ot3android.ui.settings.projects

import dev.avillagran.ot3android.core.transport.*
import dev.avillagran.t3.T3Exception
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProjectConfigurationTest {
    private suspend fun fixture(write: Boolean = true): Pair<ProjectConfiguration, FakeT3Transport> {
        val fake = FakeT3Transport()
        fake.connect(ConnectRoute.Bearer("http://fixture", "fixture"))
        fake.onShell { JSONObject("""{"projects":[{"id":"p"}]}""") }
        fake.onCall("server.getConfig") { JSONObject() }
        fake.onCall("server.getSettings") { JSONObject() }
        fake.onCall("server.updateSettings") { JSONObject() }
        fake.onDomain("scopedSettings.targetView") { args ->
            assertEquals(write, args.getBoolean("writeAllowed"))
            JSONObject("""{"writeAllowed":$write,"fields":[{"key":"defaultThreadEnvMode","kind":"choice","scopeWritable":$write}]}""")
        }
        fake.onDomain("scopedSettings.writePatch") { JSONObject("""{"patch":{"projectSettingsOverrides":{"p":{"defaultThreadEnvMode":"worktree"}}}}""") }
        val transport = object : T3Transport by fake {
            override suspend fun authSession() = JSONObject("""{"authenticated":true,"permissions":["${if (write) "settings:write" else "orchestration:operate"}"]}""")
        }
        return ProjectConfiguration(ProjectSettingsRoute("e", "p"), transport) to fake
    }

    @Test fun settingsWriteScopeAndNativeWritableFieldAreBothRequired() = runBlocking {
        val (controller, fake) = fixture(false)
        controller.refresh(); controller.update("defaultThreadEnvMode", "worktree")
        assertEquals("settings_unavailable", controller.state.value.error)
        assertTrue(fake.calls.none { it.fn == "server.updateSettings" })
    }

    @Test fun acceptedWriteRequiresReadBackBeforeAnotherEdit() = runBlocking {
        val (controller, fake) = fixture()
        controller.refresh(); controller.update("defaultThreadEnvMode", "worktree")
        assertTrue(controller.state.value.needsRefresh)
        assertNull(controller.state.value.view)
        controller.update("defaultThreadEnvMode", "local")
        assertEquals(1, fake.calls.count { it.fn == "server.updateSettings" })
        controller.refresh()
        assertFalse(controller.state.value.needsRefresh)
    }

    @Test fun unknownWriteNeverAutomaticallyReplays() = runBlocking {
        val (controller, fake) = fixture()
        fake.onCall("server.updateSettings") { throw T3Exception("rpc_outcome_unknown", "unknown") }
        controller.refresh(); controller.update("defaultThreadEnvMode", "worktree")
        assertEquals("rpc_outcome_unknown", controller.state.value.error)
        assertTrue(controller.state.value.needsRefresh)
        controller.update("defaultThreadEnvMode", "worktree")
        assertEquals(1, fake.calls.count { it.fn == "server.updateSettings" })
        controller.refresh()
        assertFalse(controller.state.value.needsRefresh)
    }

    @Test fun unadvertisedFieldNeverReachesWriteBuilder() = runBlocking {
        val (controller, fake) = fixture()
        controller.refresh(); controller.update("invented", true)
        assertTrue(fake.calls.none { it.fn == "server.updateSettings" })
        assertNotNull(controller.state.value.error)
    }
}
