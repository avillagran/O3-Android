package dev.avillagran.ot3android.core.thread

import dev.avillagran.ot3android.core.transport.ConnectRoute
import dev.avillagran.ot3android.core.transport.FakeT3Transport
import dev.avillagran.t3.T3Exception
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NewThreadLaunchTest {
    private val dao = MemoryOutboxDao()
    private val outbox = OutboxRepository(dao)
    private val fake = FakeT3Transport()
    private fun arguments() = JSONObject().put("projectId", "project-other")
        .put("title", "New task").put("modelSelection", JSONObject().put("instanceId", "provider").put("model", "model"))
        .put("runtimeMode", "approval-required").put("interactionMode", "default")
        .put("workspaceStrategy", JSONObject().put("type", "root")).put("creationSource", "web")
        .put("initialMessage", JSONObject().put("text", "A new task").put("attachments", org.json.JSONArray()))

    @Test fun launchPersistsBeforeRpcAndUsesTheExplicitProjectAndThread() = runTest {
        fake.connect(ConnectRoute.Bearer("http://fixture", "fixture"))
        val sender = MessageSender(outbox, this) { "new-command" }
        fake.onCall("orchestration.launchThread") { args ->
            val saved = outbox.find("new-command")!!
            assertEquals("new-thread", saved.threadId)
            assertEquals("project-other", args.getString("projectId"))
            assertEquals("new-thread", args.getString("threadId"))
            assertEquals(args.toString(), JSONObject(saved.commandJson).getJSONObject(MessageSender.LAUNCH_ENVELOPE).toString())
            JSONObject().put("threadId", "new-thread")
        }
        assertTrue(sender.launchThread(fake, "env-other", "new-thread", arguments()) is SendResult.Accepted)
        assertTrue(dao.rows.value.isEmpty())
        assertTrue(fake.calls.none { it.fn == MessageSender.DISPATCH })
    }

    @Test fun lostAcknowledgementSurvivesSenderRecreationAndRetriesIdenticalLaunch() = runTest {
        fake.connect(ConnectRoute.Bearer("http://fixture", "fixture"))
        fake.onCall("orchestration.launchThread") { throw T3Exception("rpc_outcome_unknown") }
        val sender = MessageSender(outbox, this) { "new-command" }
        assertEquals(SendResult.OutcomeUnknown("new-command"), sender.launchThread(fake, "env", "new-thread", arguments()))
        val original = fake.calls.single().args.toString()
        val replacement = MessageSender(outbox, this) { "must-not-be-used" }
        assertEquals(SendResult.Failed(MessageSender.COMMAND_PENDING), replacement.launchThread(fake, "env", "new-thread", arguments()))
        assertEquals(SendResult.Failed(MessageSender.COMMAND_PENDING), replacement.launchThread(fake, "env", "fresh-draft-thread", arguments()))
        val recovered = outbox.observeLaunch("env", "project-other").first()!!
        assertEquals("new-thread", recovered.threadId)
        assertNull(outbox.observeLaunch("different-env", "project-other").first())
        fake.onCall("orchestration.launchThread") { JSONObject().put("threadId", "new-thread") }
        assertTrue(replacement.retry(fake, "new-command") is SendResult.Accepted)
        assertEquals(original, fake.calls.last().args.toString())
        assertEquals(2, fake.calls.size)
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test fun rejectionDoesNotLeaveARequestAndOfflineDoesNotPersist() = runTest {
        val sender = MessageSender(outbox, this) { "new-command" }
        assertEquals(SendResult.Failed(MessageSender.NOT_CONNECTED), sender.launchThread(null, "env", "new-thread", arguments()))
        assertTrue(dao.rows.value.isEmpty())
        fake.connect(ConnectRoute.Bearer("http://fixture", "fixture"))
        fake.onCall("orchestration.launchThread") { throw T3Exception("write_not_authorized") }
        assertEquals(SendResult.Failed("write_not_authorized"), sender.launchThread(fake, "env", "new-thread", arguments()))
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test fun unexpectedReturnedIdentityRetainsTheOriginalRequestWithoutSecondCreation() = runTest {
        fake.connect(ConnectRoute.Bearer("http://fixture", "fixture"))
        fake.onCall("orchestration.launchThread") { JSONObject().put("threadId", "different-thread") }
        val sender = MessageSender(outbox, this) { "new-command" }
        assertEquals(SendResult.OutcomeUnknown("new-command"), sender.launchThread(fake, "env", "new-thread", arguments()))
        assertNotNull(outbox.find("new-command"))
        assertEquals(1, fake.calls.size)
    }
}
