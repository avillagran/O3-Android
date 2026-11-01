package dev.avillagran.ot3android.ui.thread

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.core.composer.SharedDraftRepository
import dev.avillagran.ot3android.core.composer.SharedDraftSynchronizer
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
            f.await { f.vm.attachmentPending.value == null && !f.vm.state.value.sending && f.vm.state.value.draft.isEmpty() }
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
                assertEquals("full-access", args.getString("runtimeMode"))
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
            f.await { f.vm.chatNavigation.value != null && f.vm.state.value.draft.isEmpty() }
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

    @Test fun ordinarySendAndNewUsesTheNativeAtomicInitialMessage() = runBlocking {
        fixture { f ->
            f.vm.onDraftChange("Start another task")
            f.await { f.vm.state.value.draft == "Start another task" }
            f.fake.onCall("orchestration.launchThread") { args ->
                assertEquals("Start another task", args.getJSONObject("initialMessage").getString("text"))
                assertEquals(0, args.getJSONObject("initialMessage").getJSONArray("attachments").length())
                assertNull(f.vm.chatNavigation.value)
                JSONObject().put("threadId", args.getString("threadId"))
            }
            f.vm.dispatchAttachments(newThread = true)
            f.await { f.vm.chatNavigation.value != null && f.vm.state.value.draft.isEmpty() }
            assertFalse(f.fake.calls.any { it.fn == MessageSender.DISPATCH })
            assertEquals("", f.vm.state.value.draft)
        }
    }

    @Test fun forkUsesTheNativeResponseRunAndRetainsTargetAcrossUnknownRecovery() = runBlocking {
        fixture { f ->
            var unknown = true
            f.fake.onCall(MessageSender.DISPATCH) {
                if (unknown) throw T3Exception("rpc_outcome_unknown")
                JSONObject().put("sequence", 4)
            }
            f.vm.forkFromResponse("run")
            f.await { f.vm.state.value.unconfirmed.isNotEmpty() && !f.vm.state.value.sending }
            val command = f.fake.calls.single { it.fn == MessageSender.DISPATCH }.args
            assertEquals("thread.fork", command.getString("type"))
            assertEquals("chat", command.getString("sourceThreadId"))
            assertEquals("run", command.getJSONObject("sourcePoint").getString("runId"))
            assertNull(f.vm.chatNavigation.value)
            unknown = false
            f.vm.retry(f.vm.state.value.unconfirmed.single().commandId)
            f.await { f.vm.chatNavigation.value != null }
            assertEquals(command.getString("targetThreadId"), f.vm.chatNavigation.value)
            assertEquals(command.toString(), f.fake.calls.last { it.fn == MessageSender.DISPATCH }.args.toString())
        }
    }

    @Test fun mergeBackUsesOnlyTheNativeWorkflowTargetAndCompletedRun() = runBlocking {
        fixture { f ->
            f.fake.onCall(MessageSender.DISPATCH) { JSONObject().put("sequence", 5) }
            f.vm.mergeBack()
            f.await { f.vm.chatNavigation.value != null }
            val command = f.fake.calls.single { it.fn == MessageSender.DISPATCH }.args
            assertEquals("thread.merge_back", command.getString("type"))
            assertEquals("origin-thread", command.getString("targetThreadId"))
            assertEquals("run", command.getJSONObject("sourcePoint").getString("runId"))
            assertEquals("origin-thread", f.vm.chatNavigation.value)
        }
    }

    @Test fun plainSendDoesNotDependOnAnUnreachableSharedDraftAuthority() = runBlocking {
        fixture("default") { f ->
            val text = "Hello from the phone"
            f.sharedDrafts { error("route unavailable") }.edit(draftPayload(text))
            assertPlainMessage(f.plainSend(text), text)
        }
    }

    @Test fun plainSendDoesNotDependOnAMatchingSharedDraftDocument() = runBlocking {
        fixture("default") { f ->
            // The authority never saw this text, so no clear command can be captured.
            f.sharedDrafts()
            assertPlainMessage(f.plainSend("Typed but not synchronized"), "Typed but not synchronized")
        }
    }

    @Test fun plainSendBeyondTheSharedDraftLimitStillDispatches() = runBlocking {
        fixture("default") { f ->
            val text = "x".repeat(9 * 1024)
            f.sharedDrafts().edit(draftPayload(text))
            assertPlainMessage(f.plainSend(text), text)
        }
    }

    @Test fun plainSendDoesNotWaitForABlockedSharedDraftCall() = runBlocking {
        fixture("default") { f ->
            val release = java.util.concurrent.CountDownLatch(1)
            try {
                f.sharedDrafts { withContext(Dispatchers.IO) { release.await() }; error("route unavailable") }.edit(draftPayload("Hello"))
                // Main is a virtual-time test dispatcher: move past the capture window.
                assertPlainMessage(f.plainSend("Hello") { main.scheduler.advanceTimeBy(3_001) }, "Hello")
            } finally { release.countDown() }
        }
    }

    @Test fun plainSendWithUnreadableCredentialStillDispatches() = runBlocking {
        fixture("default") { f ->
            f.credential = null
            assertPlainMessage(f.plainSend("Hello"), "Hello")
        }
    }

    @Test fun plainSendInPlanThreadDegradesWhenPolicyCannotBeRead() = runBlocking {
        fixture { f ->
            f.fake.onCall("server.getConfig") { throw T3Exception("rpc_failed") }
            assertPlainMessage(f.plainSend("Keep going"), "Keep going")
        }
    }

    @Test fun plainSendDegradesWhenPolicyAnswerIsMalformed() = runBlocking {
        fixture { f ->
            f.fake.onCall("server.getConfig") { JSONArray() }
            assertPlainMessage(f.plainSend("Keep going"), "Keep going")
        }
    }

    @Test fun plainSendWithChosenModeDegradesWhenSessionHidesTheOperateScope() = runBlocking {
        fixture("default") { f ->
            f.vm.chosenInteraction.value = "plan"
            f.session = JSONObject().put("authenticated", true)
            assertPlainMessage(f.plainSend("Hello"), "Hello")
        }
    }

    @Test fun plainSendWithUnmatchedProviderLeavesThreadModeAlone() = runBlocking {
        fixture { f ->
            f.vm.selectModel(JSONObject().put("available", true).put("instanceId", "other").put("model", "model"))
            f.await { f.vm.state.value.modelSelection?.contains("other") == true }
            assertPlainMessage(f.plainSend("Hello"), "Hello")
            assertFalse(f.fake.calls.any { it.fn == MessageSender.DISPATCH && it.args.optString("type") == "thread.interaction-mode.set" })
        }
    }

    @Test fun plainSendWithEmptyHostConfigStaysPlain() = runBlocking {
        fixture { f ->
            f.vm.chosenInteraction.value = "default"
            f.fake.onCall("server.getConfig") { JSONObject() }
            assertPlainMessage(f.plainSend("Hello"), "Hello")
        }
    }

    @Test fun chosenPlanStillSetsTheModeBeforeAPlainMessage() = runBlocking {
        fixture("default") { f ->
            f.vm.chosenInteraction.value = "plan"
            f.fake.onCall(MessageSender.DISPATCH) { JSONObject().put("sequence", 2) }
            f.vm.onDraftChange("Plan this")
            f.await { f.vm.state.value.draft == "Plan this" }
            f.vm.dispatchAttachments()
            f.await { f.fake.calls.count { it.fn == MessageSender.DISPATCH } == 2 && f.vm.state.value.draft.isEmpty() }
            val calls = f.fake.calls.filter { it.fn == MessageSender.DISPATCH }
            assertEquals("thread.interaction-mode.set", calls[0].args.getString("type"))
            assertEquals("plan", calls[0].args.getString("interactionMode"))
            assertEquals("message.dispatch", calls[1].args.getString("type"))
        }
    }

    @Test fun planImplementationStillRequiresReadablePolicy() = runBlocking {
        fixture { f ->
            val ready = f.nativePlan()
            f.fake.onCall("server.getConfig") { JSONArray() }
            f.vm.followUpPlan(ready, false)
            f.await { f.vm.state.value.notice != null }
            assertFalse(f.fake.calls.any { it.fn == MessageSender.DISPATCH })
        }
    }

    @Test fun chatActionKeepsTheSpecificErrorCode() = runBlocking {
        fixture { f ->
            f.vm.forkFromResponse("missing-run")
            f.await { f.vm.state.value.notice != null }
            assertEquals(ThreadNotice.Failed("run_unavailable"), f.vm.state.value.notice)
        }
    }

    private fun draftPayload(text: String) = JSONObject().put("schema", 1).put("text", text).put("attachments", JSONArray())

    private fun assertPlainMessage(command: JSONObject, text: String) {
        assertEquals("message.dispatch", command.getString("type"))
        assertEquals(text, command.getString("text"))
    }

    private val main = UnconfinedTestDispatcher()

    private suspend fun fixture(interactionMode: String = "plan", block: suspend (Fixture) -> Unit) {
        assumeHostJni()
        Dispatchers.setMain(main)
        val f = Fixture(interactionMode)
        try { f.start(); block(f) }
        finally { f.close(); Dispatchers.resetMain() }
    }

    private class Fixture(interactionMode: String) {
        private val context = ApplicationProvider.getApplicationContext<Application>()
        private val db = Room.inMemoryDatabaseBuilder(context, Ot3Database::class.java).allowMainThreadQueries()
            .setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        private val directory = Files.createTempDirectory("chat-actions-test").toFile()
        private val store = ViewModelStore()
        private val selection = JSONObject("""{"instanceId":"provider","model":"model","options":[]}""")
        val projection = JSONObject("""{"thread":{"id":"chat","projectId":"project","title":"Chat","interactionMode":"plan","runtimeMode":"approval-required","lineage":{"relationshipToParent":"fork","parentThreadId":"origin-thread"}},"runs":[{"id":"run","status":"completed","createdAt":"2026-11-01T00:00:00Z"}],"messages":[{"id":"answer","role":"assistant","runId":"run","text":"Plan ready","createdAt":"2026-11-01T00:00:01Z"}],"runtimeRequests":[],"turnItems":[],"plans":[{"id":"plan","threadId":"chat","runId":"run","kind":"proposed_plan","status":"active","markdown":"# Chat implementation\n\nBuild the actions."}]}""").apply {
            getJSONObject("thread").put("modelSelection", selection).put("interactionMode", interactionMode)
        }
        val fake = FakeT3Transport().apply {
            onFetchDescriptor("http://fixture.invalid") { JSONObject().put("environmentId", "node") }
            onShell { JSONObject().put("snapshotSequence", 1).put("projects", JSONArray().put(JSONObject().put("id", "project").put("title", "Project").put("workspaceRoot", "/fixture")))
                .put("threads", JSONArray().put(JSONObject(projection.getJSONObject("thread").toString()))) }
            onBoundedThread("chat") { JSONObject().put("snapshotSequence", 1).put("projection", JSONObject(projection.toString())) }
            onCall("server.getConfig") { JSONObject("""{"providers":[{"instanceId":"provider","enabled":true,"showInteractionModeToggle":true,"models":[{"slug":"model","name":"Model"}]}]}""") }
        }
        var session: JSONObject = JSONObject().put("authenticated", true).put("scopes", JSONArray().put("orchestration:operate"))
        private val live = object : T3Transport by fake {
            override suspend fun domain(fn: String, args: JSONObject) = DomainEngine.shared.domain(fn, args)
            override suspend fun authSession() = session
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
        var credential: String? = "node"
        lateinit var vm: ThreadViewModel
        suspend fun start() {
            prefs.update("planModeEnabled", true)
            repository.save("node", "Fixture", "http://fixture.invalid", "node", null)
            vm = ThreadViewModel("node", "chat", connections, DraftRepository(db.draftDao()), outbox,
                MessageSender(outbox, scope), scope, attachmentDirectory = directory,
                cacheAuthority = { credential }, clientPreferences = prefs)
            store.put("chat", vm)
            scope.launch { vm.state.collect() }
            await { vm.state.value.connected && vm.state.value.view.loaded && vm.state.value.composer.loaded }
            assertTrue(vm.refreshPlanControls().getBoolean("enabled"))
        }
        /** Selects an authority backed by [remote]; the default answers like one holding no documents. */
        fun sharedDrafts(remote: suspend (JSONObject) -> JSONObject = { JSONObject().put("cursor", "a:1").put("items", JSONArray()).put("nextOffset", JSONObject.NULL) }): SharedDraftSynchronizer {
            var disk: String? = null
            val repository = SharedDraftRepository("authority", { disk }, { disk = it }, remote)
            vm.sharedDraftRepository = repository
            return SharedDraftSynchronizer(repository, vm.state.value.composerScope!!, scope, syncRemotely = false)
                .also { vm.sharedDraftSynchronizer = it }
        }
        /** Sends through the screen's path and returns the single dispatched command. */
        suspend fun plainSend(text: String, afterTap: () -> Unit = {}): JSONObject {
            fake.onCall(MessageSender.DISPATCH) { JSONObject().put("sequence", 2) }
            vm.onDraftChange(text)
            await { vm.state.value.draft == text }
            vm.dispatchAttachments()
            afterTap()
            await(8_000) { fake.calls.any { it.fn == MessageSender.DISPATCH } || vm.state.value.notice != null }
            assertNull(vm.state.value.notice)
            await { vm.state.value.draft.isEmpty() && vm.attachmentPending.value == null && !vm.state.value.sending }
            return fake.calls.single { it.fn == MessageSender.DISPATCH }.args
        }
        suspend fun nativePlan() = dev.avillagran.ot3android.core.composer.NativeComposer(live).planView(projection).getJSONObject("ready")
        suspend fun await(timeoutMs: Long = 5_000, condition: () -> Boolean) { withTimeout(timeoutMs) { while (!condition()) delay(10) } }
        suspend fun close() {
            store.clear()
            if (::vm.isInitialized) vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            scope.coroutineContext[Job]?.cancelAndJoin()
            db.close(); directory.deleteRecursively()
        }
    }
}
