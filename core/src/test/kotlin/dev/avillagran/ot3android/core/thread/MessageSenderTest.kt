package dev.avillagran.ot3android.core.thread

import dev.avillagran.ot3android.core.data.local.OutboxDao
import dev.avillagran.ot3android.core.data.local.OutboxEntity
import dev.avillagran.ot3android.core.transport.ConnectRoute
import dev.avillagran.ot3android.core.transport.FakeT3Transport
import dev.avillagran.t3.T3Exception
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageSenderTest {

    private class MemoryOutboxDao : OutboxDao {
        val rows = MutableStateFlow<Map<String, OutboxEntity>>(emptyMap())
        override suspend fun insert(entry: OutboxEntity) {
            check(entry.id !in rows.value) { "duplicate ${entry.id}" }
            rows.update { it + (entry.id to entry) }
        }
        override suspend fun findById(id: String) = rows.value[id]
        override fun observeThread(environmentId: String, threadId: String): Flow<List<OutboxEntity>> =
            rows.map { all -> all.values.filter { it.environmentId == environmentId && it.threadId == threadId } }
        override suspend fun setState(id: String, state: String) =
            rows.update { all -> all[id]?.let { all + (id to it.copy(state = state)) } ?: all }
        override suspend fun deleteById(id: String) = rows.update { it - id }
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
        assertEquals("mobile", args.getString("creationSource"))
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
        assertEquals("mobile", sender.creationSource)
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
    fun aServerThatRejectsMobileGetsWebFromThenOn() = runTest {
        val sender = MessageSender(OutboxRepository(dao), this, ids)
        fake.onCall(MessageSender.DISPATCH) { args ->
            if (args.getString("creationSource") == "mobile") {
                throw T3Exception("rpc_rejected", "Expected \"web\" | \"mcp\" at [\"creationSource\"], actual \"mobile\"")
            }
            JSONObject().put("sequence", 3)
        }
        val transport = connected()

        val result = sender.send(transport, "env-1", "t1", "Hello", null)
        assertEquals(SendResult.Accepted("ot3a-3", 3), result)
        assertEquals(listOf("mobile", "web"), fake.calls.map { it.args.getString("creationSource") })
        assertEquals("fresh ids for the corrected command", "ot3a-4", fake.calls[1].args.getString("messageId"))
        assertEquals("web", sender.creationSource)
        assertTrue(dao.rows.value.isEmpty())

        sender.send(transport, "env-1", "t1", "Again", null)
        assertEquals("web", fake.calls.last().args.getString("creationSource"))
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
