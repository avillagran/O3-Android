package dev.avillagran.ot3android.core.thread

import dev.avillagran.ot3android.core.data.local.OutboxEntity
import dev.avillagran.ot3android.core.transport.T3Transport
import dev.avillagran.ot3android.core.transport.DomainCalls
import dev.avillagran.ot3android.core.transport.DomainEngine
import dev.avillagran.t3.T3Exception
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
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
 * again before any socket. Temporary: v0.3.4's `domain` still has no message or
 * interrupt builder (docs/LIB_REQUESTS.md, "2026-10-25: v0.3.3", finding 2).
 */
class MessageSender(
    private val outbox: OutboxRepository,
    private val scope: CoroutineScope,
    private val newId: () -> String = { ID_PREFIX + UUID.randomUUID() },
) {
    private var admission: dev.avillagran.ot3android.core.git.CheckoutAdmission? = null
    constructor(outbox: OutboxRepository, scope: CoroutineScope, admission: dev.avillagran.ot3android.core.git.CheckoutAdmission) : this(outbox, scope) {
        this.admission = admission
    }
    /** Rust builds queue mutations; the host only assigns and durably stores the retry id. */
    suspend fun queue(
        transport: T3Transport?,
        environmentId: String,
        threadId: String,
        arguments: JSONObject,
        domain: DomainCalls = DomainEngine.shared,
    ): SendResult = nativeMutation(transport, environmentId, threadId, "queue.command",
        JSONObject(arguments.toString()).put("thread", threadId), domain)

    /** Rust rechecks that this request is still pending, live, and offers this decision. */
    suspend fun approve(
        transport: T3Transport?, environmentId: String, threadId: String,
        requestId: String, decision: String, projection: JSONObject,
        domain: DomainCalls = DomainEngine.shared,
    ): SendResult = nativeMutation(transport, environmentId, threadId, "approvals.command",
        JSONObject().put("thread", threadId).put("runtimeRequestId", requestId)
            .put("decision", decision).put("projection", projection), domain)

    /** Answers are explicit form selections; Rust owns the response/dismiss command shape. */
    suspend fun answer(
        transport: T3Transport?, environmentId: String, threadId: String,
        requestId: String, action: String, answers: JSONObject,
        domain: DomainCalls = DomainEngine.shared,
    ): SendResult = nativeMutation(transport, environmentId, threadId, "questions.command",
        JSONObject().put("thread", threadId).put("runtimeRequestId", requestId)
            .put("action", action).put("answers", answers), domain)

    /** The provider advertises choices; Rust validates/builds the wire command. */
    suspend fun runtimeMode(
        transport: T3Transport?, environmentId: String, threadId: String, mode: String,
        domain: DomainCalls = DomainEngine.shared,
    ): SendResult = nativeMutation(transport, environmentId, threadId, "models.runtimeModeCommand",
        JSONObject().put("threadId", threadId).put("commandId", "ot3a-validation")
            .put("runtimeMode", mode), domain)

    /** Home lifecycle commands share the composer's durable command identity and recovery. */
    suspend fun sidebar(transport: T3Transport?, environmentId: String, threadId: String,
        fn: String, arguments: JSONObject, domain: DomainCalls = DomainEngine.shared,
    ): SendResult {
        require(fn == "sidebar.command" || fn == "sidebar.threadAction")
        return nativeMutation(transport, environmentId, threadId, fn, arguments, domain)
    }

    private suspend fun nativeMutation(
        transport: T3Transport?, environmentId: String, threadId: String,
        fn: String, arguments: JSONObject, domain: DomainCalls,
    ): SendResult = detached {
        if (transport == null) return@detached SendResult.Failed(NOT_CONNECTED)
        // Keep the same retry id even before the composer's Flow has observed an unknown result.
        val pending = outbox.observeThread(environmentId, threadId).first().firstOrNull()
        if (pending != null) return@detached SendResult.Failed(COMMAND_PENDING)
        val command = try {
            domain.domain(fn, arguments) as JSONObject
        } catch (error: T3Exception) {
            return@detached error.toFailed()
        }
        val commandId = newId()
        command.put("commandId", commandId)
        val bytes = command.toString()
        outbox.add(environmentId, threadId, commandId, bytes)
        dispatch(transport, commandId, bytes)
    }

    suspend fun send(
        transport: T3Transport?,
        environmentId: String,
        threadId: String,
        text: String,
        modelSelection: String? = null,
        /** Runs once the command is in the outbox, before it is sent. */
        onPersisted: suspend () -> Unit = {},
    ): SendResult = detached {
        if (!isSendable(text)) return@detached SendResult.Failed(INVALID_INPUT, "text")
        if (transport == null) return@detached SendResult.Failed(NOT_CONNECTED)
        val commandId = newId()
        val args = messageCommand(commandId, newId(), threadId, text, modelSelection).toString()
        outbox.add(environmentId, threadId, commandId, args)
        try {
            onPersisted()
        } catch (error: Exception) {
            // Only bookkeeping (the caller's draft): the command is persisted, so send it.
            if (error is CancellationException) throw error
        }
        dispatch(transport, commandId, args)
    }

    /** Atomic creation + first message. The envelope records which typed RPC owns retries. */
    suspend fun launchThread(transport: T3Transport?, environmentId: String, threadId: String,
        arguments: JSONObject,
    ): SendResult = detached {
        if (transport == null) return@detached SendResult.Failed(NOT_CONNECTED)
        val pending = outbox.observeThread(environmentId, threadId).first().firstOrNull()
        val pendingLaunch = outbox.observeLaunch(environmentId, arguments.optString("projectId")).first()
        if (pending != null || pendingLaunch != null) return@detached SendResult.Failed(COMMAND_PENDING)
        val commandId = newId()
        val args = JSONObject(arguments.toString()).put("commandId", commandId).put("threadId", threadId)
        val bytes = JSONObject().put(LAUNCH_ENVELOPE, args).toString()
        outbox.add(environmentId, threadId, commandId, bytes)
        dispatch(transport, commandId, bytes)
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

    private suspend fun dispatch(transport: T3Transport, commandId: String, commandJson: String): SendResult {
        val gate = admission ?: return dispatchAdmitted(transport, commandId, commandJson)
        val entry = outbox.find(commandId) ?: return SendResult.Failed(NOT_IN_OUTBOX)
        return try { gate.command(entry.environmentId) { dispatchAdmitted(transport, commandId, commandJson) } }
        catch (e: CancellationException) { throw e }
        catch (_: IllegalStateException) { outbox.setState(commandId, OutboxEntity.STATE_UNKNOWN); SendResult.Failed(COMMAND_PENDING) }
    }

    private suspend fun dispatchAdmitted(transport: T3Transport, commandId: String, commandJson: String): SendResult = try {
        val stored = JSONObject(commandJson)
        val launch = stored.optJSONObject(LAUNCH_ENVELOPE)
        val response = if (launch == null) transport.call(DISPATCH, stored)
            else transport.call("orchestration.launchThread", launch)
        if (launch != null) {
            check((response as? JSONObject)?.optString("threadId") == launch.getString("threadId")) { "launch_identity_mismatch" }
        }
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
    } catch (error: Exception) {
        // Legacy JSON, stale attachments and unexpected native failures must remain actionable.
        if (error is CancellationException) throw error
        outbox.setState(commandId, OutboxEntity.STATE_UNKNOWN)
        SendResult.OutcomeUnknown(commandId)
    }

    /** Runs [block] in the app scope, so the caller going away does not cancel it. */
    private suspend fun <T> detached(block: suspend () -> T): T = scope.async(NonCancellable) { block() }.await()

    companion object {
        const val LAUNCH_ENVELOPE = "_ot3aLaunchThread"
        const val DISPATCH = "orchestration.dispatchCommand"
        const val ID_PREFIX = "ot3a-"

        /**
         * What ot3 sends for a message typed by the user. The lib also accepts `mobile`, but no
         * live server has been seen accepting it (docs/PROTOCOL_NOTES.md).
         */
        const val CREATION_SOURCE = "web"
        const val OUTCOME_UNKNOWN = "rpc_outcome_unknown"
        const val NOT_CONNECTED = "not_connected"

        /** Not a lib code: the shared handle was closed under the call. */
        const val CLIENT_CLOSED = "client_closed"

        /** Not a lib code: the command is no longer in the outbox. */
        const val NOT_IN_OUTBOX = "not_in_outbox"

        /** Host bookkeeping: wait for the current durable mutation before starting another. */
        const val COMMAND_PENDING = "command_pending"

        /** The lib's code for arguments its validator refuses. */
        const val INVALID_INPUT = "invalid_input"

        /** The lib's `message.dispatch` limit on `text`, in characters (`src/api/orchestration.rs`). */
        const val TEXT_MAX = 120_000

        /**
         * What the lib's validator accepts as message text with no attachments: not blank, no
         * NUL, at most [TEXT_MAX] characters (Unicode scalar values, as Rust counts them).
         * Checked before anything is persisted, so a refused text never reaches the outbox.
         */
        fun isSendable(text: String): Boolean =
            text.isNotBlank() && '\u0000' !in text && text.codePointCount(0, text.length) <= TEXT_MAX

        /** `message.dispatch` with every member the lib's validator requires, and no other. */
        fun messageCommand(
            commandId: String,
            messageId: String,
            threadId: String,
            text: String,
            modelSelection: String?,
        ): JSONObject = JSONObject()
            .put("type", "message.dispatch")
            .put("commandId", commandId)
            .put("threadId", threadId)
            .put("messageId", messageId)
            .put("text", text)
            .put("attachments", JSONArray())
            .put("createdBy", "user")
            .put("creationSource", CREATION_SOURCE)
            .put("dispatchMode", JSONObject().put("type", "start_immediately"))
            .put("deliveryIntent", "auto")
            .apply { if (modelSelection != null) put("modelSelection", JSONObject(modelSelection)) }

        private fun sequenceOf(response: Any?): Long? =
            ((response as? JSONObject)?.opt("sequence") as? Number)?.toLong()

        private fun T3Exception.toFailed() = SendResult.Failed(code, reason, requiredScope)
    }
}
