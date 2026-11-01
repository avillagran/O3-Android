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

    @Test fun acceptedChatStepsAreCheckpointedAndUnknownSuffixWaitsForExplicitRetry() = runTest {
        val outbox = OutboxRepository(dao)
        val sender = MessageSender(outbox, this, ids)
        val mode = dev.avillagran.ot3android.core.composer.ChatCommands.interaction("mode-id", "t1", "default")
        val message = MessageSender.messageCommand("message-id", "m1", "t1", "Implement", model)
        val envelope = dev.avillagran.ot3android.core.composer.ChatCommands.envelope("receipt", "Original draft",
            org.json.JSONArray().put(dev.avillagran.ot3android.core.composer.ChatCommands.step(mode))
                .put(dev.avillagran.ot3android.core.composer.ChatCommands.step(message)), "target")
        outbox.add("env-1", "t1", "receipt", envelope.toString())
        var fail = true
        fake.onCall(MessageSender.DISPATCH) { args ->
            val persisted = JSONObject(outbox.find("receipt")!!.commandJson)
            if (args.getString("commandId") == "message-id") {
                assertEquals(1, persisted.getInt("nextStep"))
                if (fail) throw T3Exception("rpc_outcome_unknown")
            }
            JSONObject().put("sequence", 42)
        }
        assertEquals(SendResult.OutcomeUnknown("receipt"), sender.retry(connected(), "receipt"))
        assertEquals(2, fake.calls.size)
        val saved = outbox.find("receipt")!!
        assertEquals(OutboxEntity.STATE_UNKNOWN, saved.state)
        assertEquals("Original draft", saved.text)
        assertEquals("target", JSONObject(saved.commandJson).getString("navigateThread"))
        // A replacement owner does no work until the user explicitly requests the stored receipt.
        val restored = MessageSender(outbox, this, ids)
        assertEquals(2, fake.calls.size)
        fail = false
        assertEquals(SendResult.Accepted("receipt", 42), restored.retry(fake, "receipt"))
        assertEquals(listOf("mode-id", "message-id", "message-id"), fake.calls.map { it.args.getString("commandId") })
        assertEquals(fake.calls[1].args.toString(), fake.calls[2].args.toString())
        assertNull(outbox.find("receipt"))
    }

    @Test fun unknownLaunchDoesNotDispatchThePlanOrNavigateAndRetainsStableTarget() = runTest {
        val outbox = OutboxRepository(dao)
        val launch = JSONObject().put("fn", "orchestration.launchThread").put("args",
            JSONObject().put("commandId", "launch-id").put("threadId", "new-thread"))
        val envelope = dev.avillagran.ot3android.core.composer.ChatCommands.envelope("receipt", "draft",
            org.json.JSONArray().put(launch).put(dev.avillagran.ot3android.core.composer.ChatCommands.step(
                MessageSender.messageCommand("message-id", "m1", "new-thread", "Implement", model))), "new-thread")
        outbox.add("env-1", "t1", "receipt", envelope.toString())
        fake.onCall("orchestration.launchThread") { throw T3Exception("rpc_outcome_unknown") }
        val sender = MessageSender(outbox, this, ids)
        assertEquals(SendResult.OutcomeUnknown("receipt"), sender.retry(connected(), "receipt"))
        assertEquals(1, fake.calls.size)
        assertEquals(0, JSONObject(outbox.find("receipt")!!.commandJson).getInt("nextStep"))
        fake.onCall("orchestration.launchThread") { JSONObject().put("threadId", "new-thread") }
        fake.onCall(MessageSender.DISPATCH) { JSONObject().put("sequence", 1) }
        assertTrue(sender.retry(fake, "receipt") is SendResult.Accepted)
        assertEquals(fake.calls[0].args.toString(), fake.calls[1].args.toString())
        assertEquals("new-thread", fake.calls.last().args.getString("threadId"))
    }

    @Test
    fun runtimeModeUsesNativeValidationAndExactDurableRetry() = runTest {
        assumeHostJni()
        val sender = MessageSender(OutboxRepository(dao), this, ids)
        val transport = connected()
        assertEquals(SendResult.Failed("invalid_runtime_mode"), sender.runtimeMode(transport, "env-1", "t1", "unsupported"))
        assertTrue(fake.calls.isEmpty())
        var calls = 0
        fake.onCall(MessageSender.DISPATCH) {
            if (calls++ == 0) throw T3Exception("rpc_outcome_unknown")
            JSONObject().put("sequence", 20)
        }
        assertEquals(SendResult.OutcomeUnknown("ot3a-1"), sender.runtimeMode(transport, "env-1", "t1", "auto-accept-edits"))
        val command = fake.calls.single().args
        assertEquals("thread.runtime-mode.set", command.getString("type"))
        assertEquals("auto-accept-edits", command.getString("runtimeMode"))
        assertEquals("ot3a-1", command.getString("commandId"))
        assertEquals(SendResult.Accepted("ot3a-1", 20), sender.retry(fake, "ot3a-1"))
        assertEquals(command.toString(), fake.calls.last().args.toString())
    }

    @Test
    fun questionResponsesAndDismissalsUseRustCommandsAndStableUnknownRetry() = runTest {
        assumeHostJni()
        val sender = MessageSender(OutboxRepository(dao), this, ids)
        var count = 0
        fake.onCall(MessageSender.DISPATCH) {
            if (count++ == 0) throw T3Exception("rpc_outcome_unknown")
            JSONObject().put("sequence", 19)
        }
        val answers = JSONObject("""{"first":"a","second":["b","c"]}""")
        val result = sender.answer(connected(), "env-1", "t1", "input", "respond", answers)
        assertEquals(SendResult.OutcomeUnknown("ot3a-1"), result)
        assertEquals("runtime-request.respond", fake.calls[0].args.getString("type"))
        assertEquals("b", fake.calls[0].args.getJSONObject("answers").getJSONArray("second").getString(0))
        assertEquals(SendResult.Accepted("ot3a-1", 19), sender.retry(fake, "ot3a-1"))
        assertEquals(fake.calls[0].args.toString(), fake.calls[1].args.toString())
        sender.answer(fake, "env-1", "t1", "input", "dismiss", JSONObject())
        assertEquals("thread.user-input.dismiss", fake.calls.last().args.getString("type"))
        assertFalse(fake.calls.last().args.has("answers"))
    }

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
    fun queueEndReorderUsesNativeNullTargetAndRetriesTheSameDurableId() = runTest {
        assumeHostJni()
        val sender = MessageSender(OutboxRepository(dao), this, ids)
        var attempt = 0
        fake.onCall(MessageSender.DISPATCH) {
            if (attempt++ == 0) throw T3Exception("rpc_outcome_unknown")
            JSONObject().put("sequence", 9)
        }
        assertEquals(SendResult.OutcomeUnknown("ot3a-1"), sender.queue(connected(), "env-1", "t1",
            JSONObject().put("action", "reorder").put("runId", "queued").put("beforeRunId", JSONObject.NULL)))
        val first = fake.calls.single().args
        assertEquals("queued-run.reorder", first.getString("type"))
        assertTrue(first.isNull("beforeRunId"))
        assertEquals(SendResult.Accepted("ot3a-1", 9), sender.retry(fake, "ot3a-1"))
        assertEquals(first.toString(), fake.calls.last().args.toString())
        assertEquals(1, counter)
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

        assertEquals(SendResult.Failed("rpc_rejected", "Thread is archived", rejected = true), result)
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun onlyADefiniteAnswerCountsAsRejected() = runTest {
        val sender = MessageSender(OutboxRepository(dao), this, ids)
        for (code in listOf("write_not_authorized", "invalid_input", "rpc_connection_failed", "disconnected")) {
            fake.onCall(MessageSender.DISPATCH) { throw T3Exception(code) }
            val result = sender.send(connected(), "env-1", "t1", "Hello", null)
            assertEquals(SendResult.Failed(code, rejected = true), result)
            assertTrue(dao.rows.value.isEmpty())
        }
        fake.onCall(MessageSender.DISPATCH) { throw T3Exception("rpc_outcome_unknown") }
        assertTrue(sender.send(connected(), "env-1", "t1", "Hello", null) is SendResult.OutcomeUnknown)
        assertEquals(1, dao.rows.value.size)
        assertEquals(SendResult.Failed(MessageSender.NOT_CONNECTED), sender.retry(null, dao.rows.value.keys.single()))
        assertEquals(1, dao.rows.value.size)
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
