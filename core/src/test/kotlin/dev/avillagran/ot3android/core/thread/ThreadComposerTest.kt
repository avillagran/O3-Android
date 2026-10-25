package dev.avillagran.ot3android.core.thread

import dev.avillagran.ot3android.core.data.local.OutboxEntity
import dev.avillagran.ot3android.core.transport.ConnectRoute
import dev.avillagran.ot3android.core.transport.FakeT3Transport
import dev.avillagran.t3.T3Exception
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadComposerTest {

    private val outboxDao = MemoryOutboxDao()
    private val draftDao = MemoryDraftDao()
    private val outbox = OutboxRepository(outboxDao)
    private val drafts = DraftRepository(draftDao)
    private val fake = FakeT3Transport()
    private val results = mutableListOf<SendResult>()
    private var counter = 0

    private suspend fun TestScope.composer(): ThreadComposer {
        fake.connect(ConnectRoute.Bearer("http://t3.test", "token-12345678"))
        val sender = MessageSender(outbox, backgroundScope) { "ot3a-${++counter}" }
        return ThreadComposer(
            "env-1", "t1", drafts, outbox, sender,
            scope = backgroundScope, appScope = backgroundScope,
            transport = { fake },
            onResult = { results += it },
        ).also { runCurrent() }
    }

    private fun persistedDraft() = draftDao.rows.value[DraftRepository.key("env-1", "t1")]?.text

    private fun dispatches() = fake.calls.count { it.fn == MessageSender.DISPATCH }

    @Test
    fun theDraftIsLockedWhileSendingAndClearedOnAccept() = runTest {
        val answer = CompletableDeferred<Any?>()
        fake.onCall(MessageSender.DISPATCH) { answer.await() }
        val composer = composer()
        composer.onDraftChange("Hello")
        advanceTimeBy(ThreadComposer.DRAFT_DEBOUNCE_MS + 1)
        assertEquals("Hello", persistedDraft())

        assertTrue(composer.send(null))
        runCurrent()
        assertTrue(composer.state.value.sending)
        assertNull("the outbox row owns the text now", persistedDraft())

        composer.onDraftChange("Hello again")
        assertEquals("locked", "Hello", composer.state.value.draft)
        assertFalse("no second send", composer.send(null))
        runCurrent()
        assertEquals(1, dispatches())

        answer.complete(JSONObject().put("sequence", 4))
        runCurrent()
        assertEquals(ComposerState(), composer.state.value)
        assertEquals(listOf<SendResult>(SendResult.Accepted("ot3a-1", 4)), results)
        advanceTimeBy(ThreadComposer.DRAFT_DEBOUNCE_MS + 1)
        assertNull(persistedDraft())
    }

    @Test
    fun anUnknownOutcomeMovesTheTextToTheOutboxRowAndBlocksSending() = runTest {
        fake.onCall(MessageSender.DISPATCH) { throw T3Exception("rpc_outcome_unknown") }
        val composer = composer()
        composer.onDraftChange("Hello")
        composer.send(null)
        runCurrent()

        val state = composer.state.value
        assertEquals("", state.draft)
        assertFalse(state.sending)
        assertEquals(listOf("Hello"), state.unconfirmed.map { it.text })
        assertNull(persistedDraft())

        composer.onDraftChange("Hello")
        assertFalse("nothing new while one is unconfirmed", composer.state.value.canSend)
        assertFalse(composer.send(null))
        runCurrent()
        assertEquals(1, dispatches())
    }

    @Test
    fun retryResendsTheRowAndNeverTouchesTheDraft() = runTest {
        var answers = 0
        fake.onCall(MessageSender.DISPATCH) {
            if (answers++ == 0) throw T3Exception("rpc_outcome_unknown") else JSONObject().put("sequence", 9)
        }
        val composer = composer()
        composer.onDraftChange("Hello")
        composer.send(null)
        runCurrent()
        composer.onDraftChange("Something else")

        composer.retry("ot3a-1")
        runCurrent()

        assertEquals("Something else", composer.state.value.draft)
        assertTrue(composer.state.value.unconfirmed.isEmpty())
        assertTrue(composer.state.value.canSend)
        assertEquals(SendResult.Accepted("ot3a-1", 9), results.last())
        assertEquals("byte-identical", fake.calls[0].args.toString(), fake.calls[1].args.toString())
    }

    @Test
    fun aRejectionKeepsTheDraftAndPersistsItAgain() = runTest {
        fake.onCall(MessageSender.DISPATCH) { throw T3Exception("rpc_rejected", "Thread is archived") }
        val composer = composer()
        composer.onDraftChange("Hello")
        composer.send(null)
        runCurrent()

        assertEquals(ComposerState(draft = "Hello"), composer.state.value)
        assertEquals("Hello", persistedDraft())
        assertTrue(outboxDao.rows.value.isEmpty())
        assertEquals(SendResult.Failed("rpc_rejected", "Thread is archived"), results.single())
    }

    @Test
    fun aCommandStillSendingIsNotShownAsUnconfirmed() = runTest {
        val answer = CompletableDeferred<Any?>()
        fake.onCall(MessageSender.DISPATCH) { answer.await() }
        val composer = composer()
        composer.onDraftChange("Hello")
        composer.send(null)
        runCurrent()

        assertEquals(OutboxEntity.STATE_SENDING, outboxDao.rows.value.values.single().state)
        assertTrue(composer.state.value.unconfirmed.isEmpty())
        answer.complete(JSONObject())
        runCurrent()
    }

    @Test
    fun aSendCutByACrashComesBackUnconfirmedAtTheNextStart() = runTest {
        // The previous process persisted the command, cleared the draft, and died mid-send.
        val before = OutboxEntity("ot3a-old", "env-1", "t1", """{"text":"Hello"}""", 1_000, OutboxEntity.STATE_SENDING)
        outboxDao.insert(before)
        // A send of this process that is still in flight is left alone.
        outboxDao.insert(before.copy(id = "ot3a-new", createdAt = 5_000))

        assertEquals(1, OutboxRepository(outboxDao).recoverInterrupted(startedAt = 2_000))
        val composer = composer()

        assertEquals(listOf("ot3a-old"), composer.state.value.unconfirmed.map { it.commandId })
        assertEquals(OutboxEntity.STATE_SENDING, outboxDao.rows.value.getValue("ot3a-new").state)
        assertEquals("", composer.state.value.draft)
        composer.onDraftChange("Hello")
        assertFalse("held until the old one is resolved", composer.send(null))

        composer.discard("ot3a-old")
        runCurrent()
        assertTrue(composer.state.value.canSend)
    }
}
