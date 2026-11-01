package dev.avillagran.ot3android.ui.thread

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.core.connection.*
import dev.avillagran.ot3android.core.data.EnvironmentRepository
import dev.avillagran.ot3android.core.data.local.Ot3Database
import dev.avillagran.ot3android.core.security.TokenStore
import dev.avillagran.ot3android.core.settings.GeneralClientPreferences
import dev.avillagran.ot3android.core.thread.*
import dev.avillagran.ot3android.core.transport.*
import dev.avillagran.ot3android.testing.assumeHostJni
import dev.avillagran.t3.T3Exception
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    @Test fun implementationRevalidatesPlanAndUnknownMessageNeverNavigatesOrClearsDraft() = runBlocking {
        fixture { f ->
            f.vm.onDraftChange("Refine validation")
            f.await { f.vm.state.value.draft == "Refine validation" }
            var unknown = true
            f.fake.onCall(MessageSender.DISPATCH) {
                if (unknown) throw T3Exception("rpc_outcome_unknown")
                JSONObject().put("sequence", 2)
            }
            val ready = f.nativePlan()
            f.vm.followUpPlan(ready, false)
            f.await { f.vm.attachmentPending.value != null && !f.vm.state.value.sending }
            val message = f.fake.calls.single { it.fn == MessageSender.DISPATCH }.args
            assertEquals("Refine validation", message.getString("text"))
            assertFalse(message.has("sourcePlanRef"))
            assertEquals("Refine validation", f.vm.state.value.draft)
            assertNull(f.vm.chatNavigation.value)
            val calls = f.fake.calls.count { it.fn == MessageSender.DISPATCH }
            delay(50)
            assertEquals(calls, f.fake.calls.count { it.fn == MessageSender.DISPATCH })
            unknown = false
            f.vm.retry(f.vm.attachmentPending.value!!)
            f.await { f.vm.attachmentPending.value == null && !f.vm.state.value.sending }
            assertEquals("", f.vm.state.value.draft)
            assertEquals(message.toString(), f.fake.calls.last { it.fn == MessageSender.DISPATCH }.args.toString())
        }
    }

    @Test fun implementInNewThreadRetainsSourceReferenceAndNavigatesOnlyAfterMessageAcceptance() = runBlocking {
        fixture { f ->
            f.vm.onDraftChange("Keep this feedback out of the new implementation")
            f.await { f.vm.state.value.draft.isNotBlank() }
            f.fake.onCall("orchestration.launchThread") { args ->
                assertNull(f.vm.chatNavigation.value)
                assertEquals("default", args.getString("interactionMode"))
                assertEquals("root", args.getJSONObject("workspaceStrategy").getString("type"))
                JSONObject().put("threadId", args.getString("threadId"))
            }
            f.fake.onCall(MessageSender.DISPATCH) { args ->
                assertNull(f.vm.chatNavigation.value)
                assertEquals("chat", args.getJSONObject("sourcePlanRef").getString("threadId"))
                assertEquals("plan", args.getJSONObject("sourcePlanRef").getString("planId"))
                assertFalse(args.getString("text").contains("Keep this feedback"))
                JSONObject().put("sequence", 3)
            }
            f.vm.followUpPlan(f.nativePlan(), true)
            f.await { f.vm.chatNavigation.value != null }
            val launch = f.fake.calls.single { it.fn == "orchestration.launchThread" }.args
            val send = f.fake.calls.single { it.fn == MessageSender.DISPATCH }.args
            assertEquals(launch.getString("threadId"), send.getString("threadId"))
            assertEquals(send.getString("threadId"), f.vm.chatNavigation.value)
            assertEquals("", f.vm.state.value.draft)
        }
    }

    @Test fun staleReadyCardIsRejectedAfterAuthoritativeRefresh() = runBlocking {
        fixture { f ->
            val old = f.nativePlan()
            f.projection.getJSONArray("plans").getJSONObject(0).put("status", "superseded")
            f.vm.followUpPlan(old, false)
            f.await { !f.vm.state.value.sending && f.vm.state.value.notice != null }
            assertFalse(f.fake.calls.any { it.fn == MessageSender.DISPATCH || it.fn == "orchestration.launchThread" })
            assertNull(f.vm.attachmentPending.value)
        }
    }

    private suspend fun fixture(block: suspend (Fixture) -> Unit) {
        assumeHostJni()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = Fixture()
        try { f.start(); block(f) }
        finally { f.close(); Dispatchers.resetMain() }
    }

    private class Fixture {
        private val context = ApplicationProvider.getApplicationContext<Application>()
        private val db = Room.inMemoryDatabaseBuilder(context, Ot3Database::class.java).allowMainThreadQueries()
            .setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        private val directory = Files.createTempDirectory("chat-actions-test").toFile()
        private val store = ViewModelStore()
        private val selection = JSONObject("""{"instanceId":"provider","model":"model","options":[]}""")
        val projection = JSONObject("""{"thread":{"id":"chat","projectId":"project","title":"Chat","interactionMode":"plan","runtimeMode":"approval-required"},"runs":[{"id":"run","status":"completed","createdAt":"2026-11-01T00:00:00Z"}],"messages":[],"runtimeRequests":[],"turnItems":[],"plans":[{"id":"plan","threadId":"chat","runId":"run","kind":"proposed_plan","status":"active","markdown":"# Chat implementation\n\nBuild the actions."}]}""").apply {
            getJSONObject("thread").put("modelSelection", selection)
        }
        val fake = FakeT3Transport().apply {
            onFetchDescriptor("http://fixture.invalid") { JSONObject().put("environmentId", "node") }
            onShell { JSONObject().put("snapshotSequence", 1).put("projects", JSONArray().put(JSONObject().put("id", "project").put("title", "Project").put("workspaceRoot", "/fixture")))
                .put("threads", JSONArray().put(JSONObject(projection.getJSONObject("thread").toString()))) }
            onBoundedThread("chat") { JSONObject().put("snapshotSequence", 1).put("projection", JSONObject(projection.toString())) }
            onCall("server.getConfig") { JSONObject("""{"providers":[{"instanceId":"provider","enabled":true,"showInteractionModeToggle":true,"models":[{"slug":"model","name":"Model"}]}]}""") }
        }
        private val live = object : T3Transport by fake {
            override suspend fun domain(fn: String, args: JSONObject) = DomainEngine.shared.domain(fn, args)
            override suspend fun authSession() = JSONObject().put("authenticated", true).put("scopes", JSONArray().put("orchestration:operate"))
        }
        private val cursors = object : ShellCursorStore {
            override suspend fun cursor(environmentId: String): Long? = null
            override suspend fun setCursor(environmentId: String, cursor: Long?) = Unit
        }
        private val tokens = object : TokenStore {
            override suspend fun token(environmentId: String) = "fixture-token"
            override suspend fun setToken(environmentId: String, token: String) = Unit
            override suspend fun delete(environmentId: String) = Unit
        }
        private val repository = EnvironmentRepository(db.environmentDao(), { tokens }, cursors)
        private val connections = ConnectionManager(repository, cursors, object : T3TransportFactory {
            override fun create() = live
        }, scope, io = Dispatchers.Unconfined, computation = Dispatchers.Unconfined)
        private val outbox = OutboxRepository(db.outboxDao())
        private val prefs = GeneralClientPreferences(context)
        lateinit var vm: ThreadViewModel
        suspend fun start() {
            prefs.update("planModeEnabled", true)
            repository.save("node", "Fixture", "http://fixture.invalid", "node", null)
            vm = ThreadViewModel("node", "chat", connections, DraftRepository(db.draftDao()), outbox,
                MessageSender(outbox, scope), scope, attachmentDirectory = directory,
                cacheAuthority = { "node" }, clientPreferences = prefs)
            store.put("chat", vm)
            scope.launch { vm.state.collect() }
            await { vm.state.value.connected && vm.state.value.view.loaded && vm.state.value.composer.loaded }
            assertTrue(vm.refreshPlanControls().getBoolean("enabled"))
        }
        suspend fun nativePlan() = dev.avillagran.ot3android.core.composer.NativeComposer(live).planView(projection).getJSONObject("ready")
        suspend fun await(condition: () -> Boolean) { withTimeout(5_000) { while (!condition()) delay(10) } }
        fun close() { store.clear(); scope.cancel(); db.close(); directory.deleteRecursively() }
    }
}
