package dev.avillagran.ot3android.ui.thread

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.core.composer.SharedDraftRepository
import dev.avillagran.ot3android.core.composer.SharedDraftSynchronizer
import dev.avillagran.ot3android.core.composer.UploadStatus
import dev.avillagran.ot3android.core.connection.*
import dev.avillagran.ot3android.core.data.EnvironmentRepository
import dev.avillagran.ot3android.core.data.local.Ot3Database
import dev.avillagran.ot3android.core.security.TokenStore
import dev.avillagran.ot3android.core.settings.GeneralClientPreferences
import dev.avillagran.ot3android.core.thread.*
import dev.avillagran.ot3android.core.transport.*
import dev.avillagran.ot3android.testing.assumeHostJni
import dev.avillagran.ot3android.ui.thread.composer.ComposerBinaryUpload
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

    @Test fun aServerRefusalFreesTheComposerAndKeepsTextAndChips() = runBlocking {
        fixture("default") { f ->
            val chip = f.readyAttachment("notes.md")
            f.fake.onCall(MessageSender.DISPATCH) { throw T3Exception("rpc_rejected", "Model is not available") }
            f.vm.onDraftChange("Please review")
            f.await { f.vm.state.value.draft == "Please review" }
            f.vm.dispatchAttachments()
            f.await { f.vm.state.value.notice != null && !f.vm.state.value.sending }
            assertEquals(ThreadNotice.Failed("rpc_rejected", "Model is not available", rejected = true), f.vm.state.value.notice)
            assertNull(f.vm.attachmentPending.value)
            assertTrue(f.vm.state.value.unconfirmed.isEmpty())
            assertEquals("Please review", f.vm.state.value.draft)
            assertEquals(listOf(chip), f.vm.attachmentDraft.value.map { it.id })
            assertTrue(f.vm.state.value.canSend)
            assertEquals("", f.journal().getString("command"))
            // Resending is a new command; a success empties the editor and the notice.
            val first = f.fake.calls.single { it.fn == MessageSender.DISPATCH }.args.getString("commandId")
            f.fake.onCall(MessageSender.DISPATCH) { JSONObject().put("sequence", 2) }
            f.vm.dispatchAttachments()
            f.await { f.vm.state.value.draft.isEmpty() && !f.vm.state.value.sending }
            assertNull(f.vm.state.value.notice)
            assertNotEquals(first, f.fake.calls.last { it.fn == MessageSender.DISPATCH }.args.getString("commandId"))
            assertTrue(f.vm.attachmentDraft.value.isEmpty())
        }
    }

    @Test fun anAuthorizationRefusalAlsoFreesTheComposerWithItsScope() = runBlocking {
        fixture("default") { f ->
            f.fake.onCall(MessageSender.DISPATCH) { throw T3Exception("write_not_authorized", null, "orchestration:operate") }
            f.vm.onDraftChange("Hello")
            f.await { f.vm.state.value.draft == "Hello" }
            f.vm.dispatchAttachments()
            f.await { f.vm.state.value.notice != null && !f.vm.state.value.sending }
            assertEquals(ThreadNotice.Failed("write_not_authorized", null, "orchestration:operate", rejected = true), f.vm.state.value.notice)
            assertNull(f.vm.attachmentPending.value)
            assertEquals("Hello", f.vm.state.value.draft)
        }
    }

    @Test fun anUnknownOutcomeStaysPendingUntilAResendIsRefused() = runBlocking {
        fixture("default") { f ->
            f.fake.onCall(MessageSender.DISPATCH) { throw T3Exception("rpc_outcome_unknown") }
            f.vm.onDraftChange("Hello")
            f.await { f.vm.state.value.draft == "Hello" }
            f.vm.dispatchAttachments()
            f.await { f.vm.attachmentPending.value != null && f.vm.state.value.unconfirmed.isNotEmpty() && !f.vm.state.value.sending }
            val id = f.vm.attachmentPending.value!!
            assertEquals(ThreadNotice.Unconfirmed(id), f.vm.state.value.notice)
            assertFalse(f.vm.state.value.canSend)
            f.fake.onCall(MessageSender.DISPATCH) { throw T3Exception("rpc_rejected", "Thread is archived") }
            f.vm.retry(id)
            f.await { f.vm.attachmentPending.value == null && !f.vm.state.value.sending }
            assertEquals("Hello", f.vm.state.value.draft)
            assertTrue(f.vm.state.value.unconfirmed.isEmpty())
            assertEquals(ThreadNotice.Failed("rpc_rejected", "Thread is archived", rejected = true), f.vm.state.value.notice)
        }
    }

    @Test fun aRowResentAndRefusedReturnsItsTextToTheEditor() = runBlocking {
        fixture("default") { f ->
            f.fake.onCall(MessageSender.DISPATCH) { throw T3Exception("rpc_outcome_unknown") }
            f.vm.onDraftChange("Moved to the row")
            f.await { f.vm.state.value.draft == "Moved to the row" }
            f.vm.send()
            f.await { f.vm.state.value.unconfirmed.isNotEmpty() && !f.vm.state.value.sending }
            assertEquals("", f.vm.state.value.draft)
            f.fake.onCall(MessageSender.DISPATCH) { throw T3Exception("rpc_rejected", "Thread is archived") }
            f.vm.retry(f.vm.state.value.unconfirmed.single().commandId)
            f.await { f.vm.state.value.draft == "Moved to the row" && f.vm.state.value.unconfirmed.isEmpty() }
            assertEquals(true, (f.vm.state.value.notice as ThreadNotice.Failed).rejected)
        }
    }

    @Test fun aFilePickedOfflineStaysAsAChipAndUploadsOnReconnect() = runBlocking {
        fixture("default") { f ->
            f.offline()
            f.vm.importAttachment(f.context, f.content("notes.md", "offline bytes"))
            // The chip is immediate; the background copy may already have queued its bytes.
            assertEquals(1, f.vm.attachmentDraft.value.size)
            f.await { f.vm.attachmentDraft.value.single().status == UploadStatus.Queued }
            val chip = f.vm.attachmentDraft.value.single()
            assertEquals("notes.md", chip.name)
            assertEquals(0, f.uploads.size)
            assertEquals("offline bytes", f.spool(chip.id).readText())
            f.online()
            f.await { f.vm.attachmentDraft.value.single().status == UploadStatus.Ready }
            assertEquals(listOf("notes.md" to "offline bytes"), f.uploads)
            assertFalse(f.spool(chip.id).exists())
            assertEquals(1, f.journal().getJSONArray("attachments").length())
        }
    }

    @Test fun aCameraCaptureOutlivesTheConnectionAndOnlyItsSpoolReachesTheHost() = runBlocking {
        fixture("default") { f ->
            f.offline()
            val capture = f.capture("offline photo")
            f.vm.importAttachment(f.context, capture.first)
            f.await { f.vm.attachmentDraft.value.singleOrNull()?.status == UploadStatus.Queued }
            val chip = f.vm.attachmentDraft.value.single()
            assertEquals("offline photo", f.spool(chip.id).readText())
            assertFalse("the spool now owns the bytes", capture.second.exists())
            f.vm.removeAttachment(chip.id)
            assertFalse(f.spool(chip.id).exists())
            assertTrue(f.vm.attachmentDraft.value.isEmpty())
            f.online()
            delay(50)
            assertTrue(f.uploads.isEmpty())
        }
    }

    @Test fun aRefusedCopyKeepsTheCaptureUntilTheChipIsRemoved() = runBlocking {
        fixture("default") { f ->
            val capture = f.capture("")
            f.vm.importAttachment(f.context, capture.first)
            f.await { f.vm.attachmentDraft.value.singleOrNull()?.status == UploadStatus.Failed }
            assertTrue(capture.second.exists())
            f.vm.removeAttachment(f.vm.attachmentDraft.value.single().id)
            assertFalse(capture.second.exists())
        }
    }

    @Test fun aSpooledChipSurvivesAProcessDeathAndUploadsFromTheJournal() = runBlocking {
        fixture("default") { f ->
            f.offline()
            f.vm.importAttachment(f.context, f.content("notes.md", "durable bytes"))
            f.await { f.vm.attachmentDraft.value.singleOrNull()?.status == UploadStatus.Queued }
            val orphan = java.io.File(f.spool("x").parentFile, "orphan").apply { writeText("no chip") }
            // A new owner restarts the connection, so the journal's chip goes straight out.
            val restored = f.reopen()
            f.await { restored.attachmentDraft.value.singleOrNull()?.status == UploadStatus.Ready }
            assertEquals("notes.md", restored.attachmentDraft.value.single().name)
            assertFalse(orphan.exists())
            assertEquals(listOf("notes.md" to "durable bytes"), f.uploads)
        }
    }

    @Test fun aFailedUploadKeepsItsBytesForARetryAndTheNextConnection() = runBlocking {
        fixture("default") { f ->
            f.failUploads = true
            f.vm.importAttachment(f.context, f.content("notes.md", "bytes"))
            f.await { f.vm.attachmentDraft.value.singleOrNull()?.status == UploadStatus.Failed }
            val chip = f.vm.attachmentDraft.value.single()
            assertTrue(f.spool(chip.id).exists())
            f.vm.retryAttachment(chip.id)
            f.await { f.attempts == 2 && f.vm.attachmentDraft.value.single().status == UploadStatus.Failed }
            f.failUploads = false
            f.offline(); f.online()
            f.await { f.vm.attachmentDraft.value.single().status == UploadStatus.Ready }
            assertEquals(listOf("notes.md" to "bytes"), f.uploads)
        }
    }

    @Test fun retryWhileTheFailedUploadIsFinishingIsNotLost() = runBlocking {
        fixture("default") { f ->
            f.failUploads = true
            var retried = false
            val retry = launch(main, start = CoroutineStart.UNDISPATCHED) {
                f.vm.attachmentDraft.collect { files ->
                    val failed = files.singleOrNull()?.takeIf { it.status == UploadStatus.Failed }
                    if (failed != null && !retried) {
                        retried = true
                        f.vm.retryAttachment(failed.id)
                    }
                }
            }
            try {
                f.vm.importAttachment(f.context, f.content("notes.md", "bytes"))
                f.await { f.attempts == 2 && f.vm.attachmentDraft.value.single().status == UploadStatus.Failed }
                assertTrue(f.spool(f.vm.attachmentDraft.value.single().id).exists())
            } finally { retry.cancelAndJoin() }
        }
    }

    @Test fun aFileThatArrivesDuringASendJoinsTheDraftNotTheCommand() = runBlocking {
        fixture("default") { f ->
            val sent = f.readyAttachment("sent.md")
            val gate = CompletableDeferred<Unit>()
            f.fake.onCall(MessageSender.DISPATCH) { gate.await(); JSONObject().put("sequence", 2) }
            f.vm.onDraftChange("Hello")
            f.await { f.vm.state.value.draft == "Hello" }
            f.vm.dispatchAttachments()
            f.await { f.fake.calls.any { it.fn == MessageSender.DISPATCH } }
            f.vm.importAttachment(f.context, f.content("late.md", "late"))
            f.await { f.vm.attachmentDraft.value.any { it.name == "late.md" && it.status == UploadStatus.Ready } }
            gate.complete(Unit)
            f.await { f.vm.state.value.draft.isEmpty() && !f.vm.state.value.sending }
            assertEquals(listOf("late.md"), f.vm.attachmentDraft.value.map { it.name })
            assertFalse(f.vm.attachmentDraft.value.any { it.id == sent })
            assertEquals(1, f.fake.calls.single { it.fn == MessageSender.DISPATCH }.args.getJSONArray("attachments").length())
        }
    }

    @Test fun aFileThatArrivesWhileACommandIsPreparedStaysOutOfIt() = runBlocking {
        fixture { f ->
            f.vm.chosenInteraction.value = "plan"
            val sent = f.readyAttachment("sent.md")
            val gate = CompletableDeferred<Unit>()
            val policy = CompletableDeferred<Unit>()
            f.fake.onCall("server.getConfig") {
                policy.complete(Unit); gate.await()
                JSONObject("""{"providers":[{"instanceId":"provider","enabled":true,"showInteractionModeToggle":true,"models":[{"slug":"model","name":"Model"}]}]}""")
            }
            f.fake.onCall(MessageSender.DISPATCH) { JSONObject().put("sequence", 2) }
            f.vm.onDraftChange("Hello")
            f.await { f.vm.state.value.draft == "Hello" }
            f.vm.dispatchAttachments()
            policy.await()
            f.vm.importAttachment(f.context, f.content("late.md", "late"))
            f.await { f.vm.attachmentDraft.value.any { it.name == "late.md" && it.status == UploadStatus.Ready } }
            gate.complete(Unit)
            f.await { f.vm.state.value.draft.isEmpty() && !f.vm.state.value.sending }
            assertEquals(listOf("late.md"), f.vm.attachmentDraft.value.map { it.name })
            assertFalse(f.vm.attachmentDraft.value.any { it.id == sent })
            val message = f.fake.calls.last { it.fn == MessageSender.DISPATCH }.args
            assertEquals(1, message.getJSONArray("attachments").length())
        }
    }

    @Test fun aSocketThatNeverOpenedLeavesNoPendingCommand() = runBlocking {
        fixture("default") { f ->
            f.fake.onCall(MessageSender.DISPATCH) { throw T3Exception("disconnected") }
            f.vm.onDraftChange("Hello")
            f.await { f.vm.state.value.draft == "Hello" }
            f.vm.dispatchAttachments()
            f.await { f.vm.state.value.notice != null && !f.vm.state.value.sending }
            assertNull(f.vm.attachmentPending.value)
            assertTrue(f.vm.state.value.unconfirmed.isEmpty())
            assertEquals("Hello", f.vm.state.value.draft)
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
        val context = ApplicationProvider.getApplicationContext<Application>()
        val uploads = mutableListOf<Pair<String, String>>()
        var failUploads = false
        var attempts = 0
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
        private val live = object : T3Transport by fake, ComposerBinaryUpload {
            override suspend fun domain(fn: String, args: JSONObject) = DomainEngine.shared.domain(fn, args)
            override suspend fun authSession() = session
            override suspend fun upload(fd: Int, name: String, mimeType: String, sizeBytes: Long): JSONObject {
                attempts++
                check(!failUploads) { "upload_failed" }
                uploads += name to java.io.File("/proc/self/fd/$fd").readText()
                return JSONObject().put("id", "host-" + uploads.size).put("name", name).put("mimeType", mimeType)
            }
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
            vm = newViewModel()
            store.put("chat", vm)
            scope.launch { vm.state.collect() }
            await { vm.state.value.connected && vm.state.value.view.loaded && vm.state.value.composer.loaded }
            assertTrue(vm.refreshPlanControls().getBoolean("enabled"))
        }
        private fun newViewModel() = ThreadViewModel("node", "chat", connections, DraftRepository(db.draftDao()), outbox,
            MessageSender(outbox, scope), scope, attachmentDirectory = directory,
            cacheAuthority = { credential }, clientPreferences = prefs)
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
        private var contents = 0
        private val captures = java.io.File(context.cacheDir, "phone-captures").apply { mkdirs() }
        private val threadKey = java.security.MessageDigest.getInstance("SHA-256").digest("node/chat".toByteArray()).joinToString("") { "%02x".format(it) }
        fun spool(id: String) = java.io.File(directory, "$threadKey.spool/$id")
        fun journal() = JSONObject(java.io.File(directory, "$threadKey.json").readText())
        /** A one-off grant, as the system picker gives. */
        fun content(name: String, text: String): String {
            val authority = "picker.test.${contents++}"
            val file = java.io.File.createTempFile("picked-", ".data", context.cacheDir).apply { writeText(text) }
            val provider = object : android.content.ContentProvider() {
                override fun onCreate() = true
                override fun query(uri: android.net.Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) =
                    android.database.MatrixCursor(arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(name)) }
                override fun getType(uri: android.net.Uri) = "text/plain"
                override fun openFile(uri: android.net.Uri, mode: String) = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri? = null
                override fun delete(uri: android.net.Uri, selection: String?, selectionArgs: Array<out String>?) = 0
                override fun update(uri: android.net.Uri, values: android.content.ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
            }
            provider.attachInfo(context, android.content.pm.ProviderInfo().apply { this.authority = authority; exported = false })
            org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(authority, provider)
            return "content://$authority/file"
        }
        /** The same provider shape, but under the camera's authority, which owns its file. */
        fun capture(text: String): Pair<String, java.io.File> {
            val authority = context.packageName + ".phone-capture"
            val file = java.io.File.createTempFile("photo-", ".jpg", captures).apply { writeText(text) }
            val provider = object : android.content.ContentProvider() {
                override fun onCreate() = true
                override fun query(uri: android.net.Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) =
                    android.database.MatrixCursor(arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(file.name)) }
                override fun getType(uri: android.net.Uri) = "image/jpeg"
                override fun openFile(uri: android.net.Uri, mode: String) = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri? = null
                override fun delete(uri: android.net.Uri, selection: String?, selectionArgs: Array<out String>?) = if (file.delete()) 1 else 0
                override fun update(uri: android.net.Uri, values: android.content.ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
            }
            provider.attachInfo(context, android.content.pm.ProviderInfo().apply { this.authority = authority; exported = false })
            org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(authority, provider)
            return "content://$authority/capture/${file.name}" to file
        }
        suspend fun readyAttachment(name: String): String {
            vm.importAttachment(context, content(name, "bytes of $name"))
            await { vm.attachmentDraft.value.any { it.name == name && it.status == UploadStatus.Ready } }
            return vm.attachmentDraft.value.first { it.name == name }.id
        }
        suspend fun offline() {
            connections.supervisor("node")!!.stopAndJoin()
            await { !vm.state.value.connected }
        }
        suspend fun online() = connections.startIfCurrent(connections.supervisor("node"))
        /** The same files and database under a new owner, as after the process died. */
        suspend fun reopen(): ThreadViewModel {
            vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            vm = newViewModel()
            store.put("chat-reopened", vm)
            scope.launch { vm.state.collect() }
            return vm
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
