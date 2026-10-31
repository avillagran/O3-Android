package dev.avillagran.ot3android.core.git

import dev.avillagran.ot3android.core.transport.DomainCalls
import dev.avillagran.ot3android.core.transport.DomainEngine
import dev.avillagran.ot3android.core.transport.T3Transport
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID

/** P20 must preflight attachment availability and atomically restore the draft with this id.
 * Returning false means a definitive non-application; throwing means unknown and no retry.
 * Native appendPrompt supplies text policy; recovery never dispatches rollback again.
 */
fun interface CheckpointDraftRecovery {
    suspend fun restoreOnce(id: String, message: JSONObject): Boolean
}

class CheckpointRollback(
    private val journal: GitJournal,
    private val domain: DomainCalls = DomainEngine.shared,
) {
    private val mutex = Mutex()

    suspend fun preview(projection: JSONObject, messageId: String, safety: JSONObject): JSONObject {
        val captured = JSONObject(projection.toString())
        val reference = domain.domain("checkpointRevert.reference", JSONObject().put("projection", captured).put("messageId", messageId)) as JSONObject
        val message = domain.domain("checkpointRevert.recoveryMessage", JSONObject().put("projection", captured).put("reference", reference))
        val gate = domain.domain("checkpointRevert.safety", JSONObject().put("safety", safety))
        return JSONObject().put("requestId", UUID.randomUUID().toString()).put("reference", reference).put("message", message).put("gate", gate)
    }

    /** Inputs must be freshly read under P20's global thread/composer gate. Native checks stale references. */
    suspend fun confirm(transport: T3Transport, target: JSONObject, preview: JSONObject, projection: JSONObject,
        safety: JSONObject, restoreFiles: Boolean, dirtyFilesConfirmed: Boolean, recoveryPrepared: Boolean): JSONObject = mutex.withLock {
        val saved = JSONObject(preview.toString())
        val fresh = JSONObject(projection.toString())
        check(recoveryPrepared) { "revert_recovery_not_prepared" }
        // Restoring any files always requires explicit acknowledgement, even if status was clean.
        check(!restoreFiles || dirtyFilesConfirmed) { "revert_dirty_confirmation_required" }
        GitOperations.authorize(transport, "orchestration:operate")
        val reference = saved.getJSONObject("reference")
        val scope = domain.domain("gitControls.scope", JSONObject(target.toString()).put("shell", transport.shell()).put("requestId", saved.getString("requestId"))) as JSONObject
        val environment = scope.getString("environment")
        check(scope.getString("thread") == reference.getString("threadId")) { "revert_scope_mismatch" }
        val id = saved.getString("requestId")
        val input = JSONObject().put("environment", environment).put("thread", reference.getString("threadId"))
            .put("reference", reference).put("requestId", id).put("confirmed", true).put("restoreFiles", restoreFiles)
        val command = domain.domain("checkpointRevert.command", JSONObject().put("input", input).put("projection", fresh).put("safety", safety)) as JSONObject
        val message = domain.domain("checkpointRevert.recoveryMessage", JSONObject().put("projection", fresh).put("reference", reference)) as JSONObject
        val cwd = scope.getString("cwd")
        check(journal.reserve(id, GitJournal.checkout(environment, cwd), JSONObject().put("kind", "rollback").put("input", input).put("command", command).put("message", message))) { "revert_already_pending" }
        transport.call("orchestration.dispatchCommand", command)
        // Dispatch receipt is not proof the conversation has rewound.
        journal.transition(id, "pending")
        journal.find(id)!!
    }

    /** Read-only on reconnect. A missing message alone is never interpreted as completion. */
    suspend fun observe(id: String, environment: String, projection: JSONObject): JSONObject = mutex.withLock {
        val record = journal.find(id) ?: error("revert_record_missing")
        val input = record.getJSONObject("input")
        check(input.getString("environment") == environment) { "revert_scope_mismatch" }
        if (record.getString("state") !in setOf("unknown", "pending", "superseded")) return@withLock record
        val observation = domain.domain("checkpointRevert.observation", JSONObject().put("input", input).put("projection", projection)) as JSONObject
        val state = observation.getString("state")
        journal.transition(id, if (state == "complete") "recovery_ready" else state, observation)
        journal.find(id)!!
    }

    suspend fun recover(id: String, recovery: CheckpointDraftRecovery): JSONObject = mutex.withLock {
        val record = journal.claimRecovery(id) ?: error("revert_recovery_not_ready")
        val restored = recovery.restoreOnce(id, record.getJSONObject("message"))
        journal.transition(id, if (restored) "complete" else "recovery_failed")
        journal.find(id)!!
    }

    suspend fun appendPrompt(current: String, restored: String): String = domain.domain("checkpointRevert.appendPrompt", JSONObject().put("current", current).put("restored", restored)) as String
}
