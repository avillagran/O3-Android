package dev.avillagran.ot3android.core.providers

import dev.avillagran.ot3android.core.transport.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProviderAdministrationTest {
    private fun fixture(manage: Boolean = true): Pair<FakeT3Transport, ProviderAdministration> {
        val fake = FakeT3Transport()
        fake.onCall("server.getConfig") { JSONObject("""{"providers":[{"instanceId":"work","displayName":"Work","driver":"codex","enabled":true,"versionAdvisory":{"canUpdate":true},"setup":{"canAuthenticate":true},"environment":{"TOKEN":"never-export"},"config":{"secret":"never-export"}}]}""") }
        val transport = object : T3Transport by fake {
            override suspend fun authSession() = JSONObject().put("authenticated", true)
                .put("permissions", org.json.JSONArray(if (manage) listOf("providers:manage") else emptyList<String>()))
        }
        return fake to ProviderAdministration("host-a", transport)
    }
    @Test fun refreshRetainsOnlyDisplayAllowlistAndTargetsOneHost() = runBlocking {
        val (fake, controller) = fixture()
        fake.connect(ConnectRoute.Bearer("https://host.test", "token"))
        controller.refresh()
        assertEquals("host-a", controller.environmentId)
        assertTrue(controller.state.value.canManage)
        assertTrue(controller.state.value.providers.single().getBoolean("canUpdate"))
        assertTrue(controller.state.value.providers.single().getBoolean("canAuthenticate"))
        assertFalse(controller.state.value.providers.single().toString().contains("never-export"))
        assertEquals(listOf("server.getConfig"), fake.calls.map { it.fn })
    }
    @Test fun uncertainMutationIsNeverRetriedAndDoesNotChangeProviderState() = runBlocking {
        val (fake, controller) = fixture()
        fake.connect(ConnectRoute.Bearer("https://host.test", "token"))
        controller.refresh()
        fake.onCall("server.updateProvider") { error("sensitive error data") }
        controller.act("work", ProviderAdministration.Action.Update)
        assertTrue(controller.state.value.uncertain)
        assertEquals("provider_request_failed", controller.state.value.errorCode)
        assertFalse(controller.state.value.providers.single().has("currentVersion"))
        controller.act("work", ProviderAdministration.Action.Update)
        assertEquals(1, fake.calls.count { it.fn == "server.updateProvider" })
        assertEquals("codex", fake.calls.last().args.getString("provider"))
        assertEquals("work", fake.calls.last().args.getString("instanceId"))
    }
    @Test fun readOnlyCannotStartSetup() = runBlocking {
        val (fake, controller) = fixture(false)
        fake.connect(ConnectRoute.Bearer("https://host.test", "token"))
        controller.refresh()
        controller.act("work", ProviderAdministration.Action.SignIn)
        assertEquals(0, fake.calls.count { it.fn == "provider.auth.start" })
    }
    @Test fun duplicateTapWhileRequestIsPendingSendsOnce() = runBlocking {
        val (fake, controller) = fixture()
        fake.connect(ConnectRoute.Bearer("https://host.test", "token"))
        controller.refresh()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        fake.onCall("provider.auth.start") { started.complete(Unit); finish.await(); JSONObject().put("flowId", "flow") }
        val job = launch { controller.act("work", ProviderAdministration.Action.SignIn) }
        started.await()
        controller.act("work", ProviderAdministration.Action.SignIn)
        finish.complete(Unit); job.join()
        assertEquals(1, fake.calls.count { it.fn == "provider.auth.start" })
        assertEquals("flow", controller.state.value.setup?.getString("flowId"))
    }
}
