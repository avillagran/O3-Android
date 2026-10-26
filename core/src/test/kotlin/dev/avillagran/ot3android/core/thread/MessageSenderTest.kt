package dev.avillagran.ot3android.core.thread

import dev.avillagran.ot3android.core.data.local.OutboxEntity
import dev.avillagran.ot3android.core.transport.ConnectRoute
import dev.avillagran.ot3android.core.transport.FakeT3Transport
import dev.avillagran.ot3android.core.transport.assumeHostJni
import dev.avillagran.t3.T3Exception
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageSenderTest {

    private fun approvalProjection(live: Boolean = true, options: String = """[{"decision":"accept","label":"Approve"}]""") = JSONObject("""{
      "thread":{"id":"t1"},
      "runtimeRequests":[{"id":"request","kind":"command","status":"pending","responseCapability":{"type":"${if (live) "live" else "not_resumable"}"}}],
      "turnItems":[{"type":"approval_request","requestId":"request","prompt":"Read the requested file","options":$options}]
    }""")

    @Test
    fun approvalUnknownOutcomeUsesNativeDecisionAndTheSameDurableRetry() = runTest {
        assumeHostJni()
        val sender = MessageSender(OutboxRepository(dao), this, ids)
        var responses = 0
        fake.onCall(MessageSender.DISPATCH) {
            if (responses++ == 0) throw T3Exception("rpc_outcome_unknown")
            JSONObject().put("sequence", 12)
        }
        val result = sender.approve(connected(), "env-1", "t1", "request", "accept", approvalProjection())
        assertEquals(SendResult.OutcomeUnknown("ot3a-1"), result)
        val args = fake.calls.single().args
        assertEquals("runtime-request.respond", args.getString("type"))
        assertEquals("request", args.getString("requestId"))
        assertEquals("accept", args.getString("decision"))
        assertEquals(SendResult.Accepted("ot3a-1", 12), sender.retry(fake, "ot3a-1"))
        assertEquals(fake.calls[0].args.toString(), fake.calls[1].args.toString())
        assertEquals(1, counter)
    }

    @Test
    fun nativeApprovalRejectsGoneUnavailableAndUnofferedChoicesBeforeAnyDispatch() = runTest {
        assumeHostJni()
        val sender = MessageSender(OutboxRepository(dao), this, ids)
        val transport = connected()
        val gone = sender.approve(transport, "env-1", "t1", "gone", "accept", approvalProjection())
        val notLive = sender.approve(transport, "env-1", "t1", "request", "accept", approvalProjection(live = false))
        val empty = sender.approve(transport, "env-1", "t1", "request", "accept", approvalProjection(options = "[]"))
        assertEquals(SendResult.Failed("approval_no_longer_pending"), gone)
        assertEquals(SendResult.Failed("approval_not_resumable"), notLive)
        assertEquals(SendResult.Failed("invalid_approval_decision"), empty)
        assertTrue(fake.calls.isEmpty())
        assertTrue(dao.rows.value.isEmpty())
        assertEquals(0, counter)
    }

    @Test
    fun queueEditUsesRustAndUnknownOutcomeRetainsTheExactAttachmentCommand() = runTest {
        assumeHostJni()
        val sender = MessageSender(OutboxRepository(dao), this, ids)
        var responses = 0
        fake.onCall(MessageSender.DISPATCH) {
            if (responses++ == 0) throw T3Exception("rpc_outcome_unknown")
            JSONObject().put("sequence", 8)
        }
        val arguments = JSONObject("""{"action":"edit","runId":"queued","text":"Revised","attachments":[{"type":"file","name":"notes.txt","url":"https://t3.test/a"}],"context":{"filePaths":["/repo/file"]}}""")
        val result = sender.queue(connected(), "env-1", "t1", arguments)
        assertEquals(SendResult.OutcomeUnknown("ot3a-1"), result)
        val stored = dao.rows.value.getValue("ot3a-1")
        val command = JSONObject(stored.commandJson)
        assertEquals("queued-run.edit", command.getString("type"))
        assertEquals("t1", command.getString("threadId"))
        assertEquals("notes.txt", command.getJSONArray("attachments").getJSONObject(0).getString("name"))
        assertEquals("/repo/file", command.getJSONObject("context").getJSONArray("filePaths").getString(0))
        // A second tap before UI flow delivery cannot mint a different mutation id.
        assertEquals(SendResult.Failed(MessageSender.COMMAND_PENDING), sender.queue(fake, "env-1", "t1", arguments))
        assertEquals(1, fake.calls.size)
        assertEquals(1, counter)
        assertEquals(SendResult.Accepted("ot3a-1", 8), sender.retry(fake, "ot3a-1"))
        assertEquals(fake.calls[0].args.toString(), fake.calls[1].args.toString())
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun queueRejectsInvalidRustCommandBeforePersistingOrDispatching() = runTest {
        assumeHostJni()
        val sender = MessageSender(OutboxRepository(dao), this, ids)
        val result = sender.queue(connected(), "env-1", "t1", JSONObject().put("action", "steer").put("runId", "queued"))
        assertTrue(result is SendResult.Failed)
        assertTrue(dao.rows.value.isEmpty())
        assertTrue(fake.calls.isEmpty())
        assertEquals(0, counter)
    }

    private val dao = MemoryOutboxDao()
    private val fake = FakeT3Transport()
    private var counter = 0
    private val ids = { "ot3a-${++counter}" }

    private suspend fun connected() = fake.also { it.connect(ConnectRoute.Bearer("http://t3.test", "token-12345678")) }

    private val model = JSONObject().put("instanceId", "codex").put("model", "gpt-5").toString()

    @Test
    fun theCommandIsPersistedBeforeItIsSentAndDeletedOnSuccess() = runTest {
        val sender = MessageSender(OutboxRepository(dao) { 1_000 }, this, ids)
        val seenInOutbox = mutableListOf<OutboxEntity?>()
        fake.onCall(MessageSender.DISPATCH) { args ->
            seenInOutbox += dao.rows.value[args.getString("commandId")]
            JSONObject().put("sequence", 77)
        }

        val result = sender.send(connected(), "env-1", "t1", "Hello", model)

        assertEquals(SendResult.Accepted("ot3a-1", 77), result)
        val persisted = seenInOutbox.single()!!
        assertEquals(OutboxEntity.STATE_SENDING, persisted.state)
        assertEquals("env-1", persisted.environmentId)
        assertTrue(sameJson(JSONObject(persisted.commandJson), fake.calls.single().args))
        assertTrue("deleted after success", dao.rows.value.isEmpty())

        val args = fake.calls.single().args
        assertEquals(
            setOf(
                "type", "commandId", "threadId", "messageId", "text", "attachments", "createdBy", "creationSource",
                "dispatchMode", "deliveryIntent", "modelSelection",
            ),
            args.keyNames(),
        )
        assertEquals("message.dispatch", args.getString("type"))
        assertEquals("ot3a-1", args.getString("commandId"))
        assertEquals("ot3a-2", args.getString("messageId"))
        assertEquals("t1", args.getString("threadId"))
        assertEquals("Hello", args.getString("text"))
        assertEquals(0, args.getJSONArray("attachments").length())
        assertEquals("user", args.getString("createdBy"))
        assertEquals("what ot3 sends", "web", args.getString("creationSource"))
        assertEquals("start_immediately", args.getJSONObject("dispatchMode").getString("type"))
        assertEquals("auto", args.getString("deliveryIntent"))
        assertEquals("gpt-5", args.getJSONObject("modelSelection").getString("model"))
    }

    @Test
    fun anUnknownOutcomeKeepsTheCommandAndRetryResendsTheSameBytes() = runTest {
        val sender = MessageSender(OutboxRepository(dao), this, ids)
        var answers = 0
        fake.onCall(MessageSender.DISPATCH) {
            if (answers++ == 0) throw T3Exception("rpc_outcome_unknown") else JSONObject().put("sequence", 9)
        }
        val transport = connected()

        val first = sender.send(transport, "env-1", "t1", "Hello", null)
        assertEquals(SendResult.OutcomeUnknown("ot3a-1"), first)
        val kept = dao.rows.value.getValue("ot3a-1")
        assertEquals(OutboxEntity.STATE_UNKNOWN, kept.state)
        assertFalse("no modelSelection when the thread has none", fake.calls[0].args.has("modelSelection"))

        val retried = sender.retry(transport, "ot3a-1")
        assertEquals(SendResult.Accepted("ot3a-1", 9), retried)
        assertEquals(2, fake.calls.size)
        assertEquals("byte-identical", fake.calls[0].args.toString(), fake.calls[1].args.toString())
        assertTrue(sameJson(JSONObject(kept.commandJson), fake.calls[1].args))
        assertTrue(dao.rows.value.isEmpty())
        assertEquals("no new ids for a retry", 2, counter)

        assertEquals(SendResult.Failed(MessageSender.NOT_IN_OUTBOX), sender.retry(transport, "ot3a-1"))
    }

    @Test
    fun aRejectionIsReportedAndLeavesNothingInTheOutbox() = runTest {
        val sender = MessageSender(OutboxRepository(dao), this, ids)
        fake.onCall(MessageSender.DISPATCH) { throw T3Exception("rpc_rejected", "Thread is archived") }

        val result = sender.send(connected(), "env-1", "t1", "Hello", null)

        assertEquals(SendResult.Failed("rpc_rejected", "Thread is archived"), result)
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun aMissingScopeNamesIt() = runTest {
        val sender = MessageSender(OutboxRepository(dao), this, ids)
        fake.onCall(MessageSender.DISPATCH) {
            throw T3Exception("write_not_authorized", "missing scope", requiredScope = "orchestration:operate")
        }
        val result = sender.send(connected(), "env-1", "t1", "Hello", null) as SendResult.Failed
        assertEquals("orchestration:operate", result.requiredScope)
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun aRejectionNamingCreationSourceIsNotRetriedWithAnotherValue() = runTest {
        val sender = MessageSender(OutboxRepository(dao), this, ids)
        fake.onCall(MessageSender.DISPATCH) { throw T3Exception("rpc_rejected", "Expected \"mcp\" at [\"creationSource\"]") }

        val result = sender.send(connected(), "env-1", "t1", "Hello", null)

        assertEquals("rpc_rejected", (result as SendResult.Failed).code)
        assertEquals("sent once", 1, fake.calls.size)
        assertEquals("web", fake.calls.single().args.getString("creationSource"))
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun textTheLibWouldRefuseIsNeverPersisted() = runTest {
        val sender = MessageSender(OutboxRepository(dao), this, ids)
        fake.onCall(MessageSender.DISPATCH) { JSONObject() }
        val transport = connected()
        for (text in listOf("", "  \n ", "a\u0000b", "x".repeat(MessageSender.TEXT_MAX + 1))) {
            assertEquals(SendResult.Failed(MessageSender.INVALID_INPUT, "text"), sender.send(transport, "env-1", "t1", text, null))
        }
        assertTrue(dao.rows.value.isEmpty())
        assertTrue(fake.calls.isEmpty())
        // The limit counts characters, not UTF-16 units.
        assertTrue(MessageSender.isSendable("😀".repeat(MessageSender.TEXT_MAX)))
        assertFalse(MessageSender.isSendable("😀".repeat(MessageSender.TEXT_MAX + 1)))
    }

    @Test
    fun notConnectedPersistsNothing() = runTest {
        val sender = MessageSender(OutboxRepository(dao), this, ids)
        assertEquals(SendResult.Failed(MessageSender.NOT_CONNECTED), sender.send(null, "env-1", "t1", "Hello", null))
        assertTrue(dao.rows.value.isEmpty())
        assertEquals(0, counter)
    }

    @Test
    fun interruptTargetsTheRunAndHoldsTheQueue() = runTest {
        val sender = MessageSender(OutboxRepository(dao), this, ids)
        fake.onCall(MessageSender.DISPATCH) { JSONObject().put("sequence", 5) }

        val result = sender.interrupt(connected(), "t1", "run-7")

        assertEquals(SendResult.Accepted("ot3a-1", 5), result)
        val args = fake.calls.single().args
        assertEquals(setOf("type", "commandId", "threadId", "runId", "holdQueue"), args.keyNames())
        assertEquals("run.interrupt", args.getString("type"))
        assertEquals("run-7", args.getString("runId"))
        assertTrue(args.getBoolean("holdQueue"))
        assertTrue("an interrupt is not persisted", dao.rows.value.isEmpty())
    }

    @Test
    fun discardForgetsAnUnconfirmedCommand() = runTest {
        val sender = MessageSender(OutboxRepository(dao), this, ids)
        fake.onCall(MessageSender.DISPATCH) { throw T3Exception("rpc_outcome_unknown") }
        sender.send(connected(), "env-1", "t1", "Hello", null)
        assertNotNull(dao.rows.value["ot3a-1"])

        sender.discard("ot3a-1")
        assertNull(dao.rows.value["ot3a-1"])
    }

    @Test
    fun aHandleClosedUnderTheCallKeepsTheCommandForAResend() = runTest {
        val sender = MessageSender(OutboxRepository(dao), this, ids)
        fake.onCall(MessageSender.DISPATCH) { throw IllegalStateException("client_closed") }

        val result = sender.send(connected(), "env-1", "t1", "Hello", null)

        assertEquals(SendResult.OutcomeUnknown("ot3a-1"), result)
        assertEquals(OutboxEntity.STATE_UNKNOWN, dao.rows.value.getValue("ot3a-1").state)
    }
}
