package dev.avillagran.ot3android.core.thread

import dev.avillagran.ot3android.core.data.local.OutboxEntity
import dev.avillagran.ot3android.core.transport.T3Transport
import dev.avillagran.t3.T3Exception
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** How a command ended, as far as this device can know. */
sealed interface SendResult {
    /** The server accepted it at [sequence]; the result arrives on the thread stream. */
    data class Accepted(val commandId: String, val sequence: Long?) : SendResult

    /** The answer was lost (`rpc_outcome_unknown`); the command stays in the outbox for a retry. */
    data class OutcomeUnknown(val commandId: String) : SendResult

    /** Not sent or refused, with the lib's [code]; nothing is left in the outbox. */
    data class Failed(val code: String, val reason: String? = null, val requiredScope: String? = null) : SendResult
}

/**
 * Sends user commands through `orchestration.dispatchCommand` (scope `orchestration:operate`).
 *
 * A message's `commandId` and `messageId` are minted once and the command is persisted in the
 * outbox **before** it is sent. The send runs in [scope] (the app's) under [NonCancellable],
 * so leaving the screen never cuts it in half. On success the entry is deleted; on
 * `rpc_outcome_unknown` it stays and [retry] resends the stored bytes, which the server
 * deduplicates by `commandId`; on any other failure it is deleted and the caller keeps the
 * draft. The arguments follow the lib's `message.dispatch` validator; Rust validates them
 * again before any socket (docs/LIB_REQUESTS.md phase 1 item 4 would build them there).
 */
class MessageSender(
    private val outbox: OutboxRepository,
    private val scope: CoroutineScope,
    private val newId: () -> String = { ID_PREFIX + UUID.randomUUID() },
) {
    /**
     * `creationSource` of new messages: `mobile`, or `web` once a server rejected `mobile`
     * (the lib accepts both; older servers may not know `mobile`).
     */
    @Volatile var creationSource: String = MOBILE
        private set

    suspend fun send(
        transport: T3Transport?,
        environmentId: String,
        threadId: String,
        text: String,
        modelSelection: String? = null,
        /** Runs once the command is in the outbox, before it is sent. */
        onPersisted: suspend () -> Unit = {},
    ): SendResult = detached {
        if (transport == null) return@detached SendResult.Failed(NOT_CONNECTED)
        val source = creationSource
        val result = sendNew(transport, environmentId, threadId, text, modelSelection, source, onPersisted)
        if (source == MOBILE && result is SendResult.Failed && result.rejectsCreationSource()) {
            creationSource = WEB
            sendNew(transport, environmentId, threadId, text, modelSelection, WEB, onPersisted)
        } else {
            result
        }
    }

    /** Resends a stored command byte-identical; only for an entry still in the outbox. */
    suspend fun retry(transport: T3Transport?, commandId: String): SendResult = detached {
        val entry = outbox.find(commandId) ?: return@detached SendResult.Failed(NOT_IN_OUTBOX)
        if (transport == null) return@detached SendResult.Failed(NOT_CONNECTED)
        outbox.setState(commandId, OutboxEntity.STATE_SENDING)
        dispatch(transport, commandId, entry.commandJson)
    }

    /** Forgets a stored command; it may still have reached the server. */
    suspend fun discard(commandId: String) = detached { outbox.remove(commandId) }

    /** `run.interrupt` of [runId], holding the queue. Repeatable, so it is not persisted. */
    suspend fun interrupt(transport: T3Transport?, threadId: String, runId: String): SendResult = detached {
        if (transport == null) return@detached SendResult.Failed(NOT_CONNECTED)
        val commandId = newId()
        val args = JSONObject().put("type", "run.interrupt").put("commandId", commandId).put("threadId", threadId)
            .put("runId", runId).put("holdQueue", true)
        try {
            SendResult.Accepted(commandId, sequenceOf(transport.call(DISPATCH, args)))
        } catch (error: T3Exception) {
            if (error.code == OUTCOME_UNKNOWN) SendResult.OutcomeUnknown(commandId) else error.toFailed()
        } catch (error: IllegalStateException) {
            if (error is CancellationException) throw error
            SendResult.Failed(CLIENT_CLOSED)
        }
    }

    private suspend fun sendNew(
        transport: T3Transport,
        environmentId: String,
        threadId: String,
        text: String,
        modelSelection: String?,
        source: String,
        onPersisted: suspend () -> Unit,
    ): SendResult {
        val commandId = newId()
        val args = messageCommand(commandId, newId(), threadId, text, modelSelection, source).toString()
        outbox.add(environmentId, threadId, commandId, args)
        try {
            onPersisted()
        } catch (error: Exception) {
            // Only bookkeeping (the caller's draft): the command is persisted, so send it.
            if (error is CancellationException) throw error
        }
        return dispatch(transport, commandId, args)
    }

    private suspend fun dispatch(transport: T3Transport, commandId: String, commandJson: String): SendResult = try {
        val response = transport.call(DISPATCH, JSONObject(commandJson))
        outbox.remove(commandId)
        SendResult.Accepted(commandId, sequenceOf(response))
    } catch (error: T3Exception) {
        if (error.code == OUTCOME_UNKNOWN) {
            outbox.setState(commandId, OutboxEntity.STATE_UNKNOWN)
            SendResult.OutcomeUnknown(commandId)
        } else {
            outbox.remove(commandId)
            error.toFailed()
        }
    } catch (error: IllegalStateException) {
        // The supervisor closed the handle (`client_closed`) while reconnecting. Keep the entry:
        // a resend is harmless because the server deduplicates by commandId.
        if (error is CancellationException) throw error
        outbox.setState(commandId, OutboxEntity.STATE_UNKNOWN)
        SendResult.OutcomeUnknown(commandId)
    }

    /** Runs [block] in the app scope, so the caller going away does not cancel it. */
    private suspend fun <T> detached(block: suspend () -> T): T = scope.async(NonCancellable) { block() }.await()

    companion object {
        const val DISPATCH = "orchestration.dispatchCommand"
        const val ID_PREFIX = "ot3a-"
        const val MOBILE = "mobile"
        const val WEB = "web"
        const val OUTCOME_UNKNOWN = "rpc_outcome_unknown"
        const val NOT_CONNECTED = "not_connected"

        /** Not a lib code: the shared handle was closed under the call. */
        const val CLIENT_CLOSED = "client_closed"

        /** Not a lib code: the command is no longer in the outbox. */
        const val NOT_IN_OUTBOX = "not_in_outbox"

        /** `message.dispatch` with every member the lib's validator requires, and no other. */
        fun messageCommand(
            commandId: String,
            messageId: String,
            threadId: String,
            text: String,
            modelSelection: String?,
            creationSource: String,
        ): JSONObject = JSONObject()
            .put("type", "message.dispatch")
            .put("commandId", commandId)
            .put("threadId", threadId)
            .put("messageId", messageId)
            .put("text", text)
            .put("attachments", JSONArray())
            .put("createdBy", "user")
            .put("creationSource", creationSource)
            .put("dispatchMode", JSONObject().put("type", "start_immediately"))
            .put("deliveryIntent", "auto")
            .apply { if (modelSelection != null) put("modelSelection", JSONObject(modelSelection)) }

        private fun sequenceOf(response: Any?): Long? =
            ((response as? JSONObject)?.opt("sequence") as? Number)?.toLong()

        private fun T3Exception.toFailed() = SendResult.Failed(code, reason, requiredScope)

        private fun SendResult.Failed.rejectsCreationSource(): Boolean =
            code == "rpc_rejected" && reason.orEmpty().let { it.contains("creationSource") || it.contains("\"mobile\"") }
    }
}
