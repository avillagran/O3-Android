package dev.avillagran.ot3android.core.git

import dev.avillagran.ot3android.core.transport.DomainCalls
import dev.avillagran.ot3android.core.transport.DomainEngine
import dev.avillagran.ot3android.core.transport.T3Transport
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID

/** The stream is available in the pinned AAR, but absent from T3Transport's facade.
 * P20 supplies a connection-bound adapter which opens ONCE and consumes native events.
 * A returned acknowledgement is not completion. Throw/cancel/disconnect leaves unknown.
 */
fun interface GitStackedAction {
    suspend fun execute(environment: String, payload: JSONObject): JSONObject
}

class GitOperations(
    private val journal: GitJournal,
    private val domain: DomainCalls = DomainEngine.shared,
    private val stackedAction: GitStackedAction? = null,
) {
    private val mutex = Mutex()
    val supportsStackedActions: Boolean get() = stackedAction != null

    suspend fun read(transport: T3Transport, target: JSONObject): JSONObject {
        val input = JSONObject(target.toString()).put("requestId", UUID.randomUUID().toString())
        val shell = transport.shell()
        val scope = domain.domain("gitControls.scope", input.put("shell", shell)) as JSONObject
        val projects = shell.getJSONArray("projects")
        val project = (0 until projects.length()).map { projects.getJSONObject(it) }.first { it.getString("id") == scope.getString("projectId") }
        val scripts = domain.domain("gitControls.resolveScripts", JSONObject().put("settings", transport.call("server.getSettings")).put("project", project))
        val status = transport.call("vcs.refreshStatus", JSONObject().put("cwd", scope.getString("cwd")))
        return domain.domain("gitControls.present", JSONObject().put("scope", scope).put("status", status).put("scripts", scripts)) as JSONObject
    }

    /** Call only for the transport resolved by snapshot.scope.environment. No outbox replay. */
    suspend fun confirm(transport: T3Transport, snapshot: JSONObject, choice: JSONObject): JSONObject {
        val current = JSONObject(snapshot.toString())
        val intent = JSONObject(choice.toString())
        return mutex.withLock {
            val scope = current.getJSONObject("scope")
            val operation = intent.getString("operation")
            authorize(transport, if (operation == "script") "terminal:write" else "source-control:write")
            if (operation in setOf("create_pr", "commit_push_pr")) authorize(transport, "orchestration:read")
            val fresh = read(transport, scope)
            val plan = domain.domain("gitControls.buildVerifiedAction", JSONObject().put("current", current).put("fresh", fresh).put("intent", intent)) as JSONObject
            val steps = plan.getJSONArray("steps")
            require((0 until steps.length()).all { steps.getJSONObject(it).getString("tag") in SUPPORTED })
            check(stackedAction != null || (0 until steps.length()).none { steps.getJSONObject(it).getString("tag") == "git.runStackedAction" }) { "git_stream_adapter_required" }
            val id = plan.getString("actionId")
            val environment = scope.getString("environment")
            check(journal.reserve(id, GitJournal.checkout(environment, scope.getString("cwd")), JSONObject().put("kind", "git").put("plan", plan))) { "git_operation_already_recorded" }
            var result = JSONObject()
            // Persisted unknown remains on every exception, including cancellation. Never resume steps.
            for (i in 0 until steps.length()) {
                val step = steps.getJSONObject(i)
                val tag = step.getString("tag")
                val payload = step.getJSONObject("payload")
                result = if (tag == "git.runStackedAction") stackedAction!!.execute(environment, payload)
                    else {
                        val receipt = transport.call(tag, payload)
                        if (tag == "vcs.pull") check((receipt as? JSONObject)?.optString("status") in setOf("pulled", "skipped_up_to_date")) { "invalid_git_result" }
                        JSONObject().put("receipt", receipt ?: JSONObject.NULL)
                    }
            }
            // Stacked events need a native terminal result; an adapter must report one explicitly.
            val state = if (operation == "script") "pending" else if (operation == "pull") "complete"
                else when (result.optString("state")) { "complete", "failed" -> result.getString("state"); else -> "unknown" }
            journal.transition(id, state, result)
            journal.find(id)!!
        }
    }

    companion object {
        private val SUPPORTED = setOf("git.runStackedAction", "vcs.pull", "terminal.open", "terminal.write")
        suspend fun authorize(transport: T3Transport, permission: String) {
            val session = transport.authSession()
            val permissions = session.optJSONArray("permissions") ?: session.optJSONArray("scopes")
            check(session.optBoolean("authenticated") && permissions != null && (0 until permissions.length()).any { permissions.optString(it) == permission }) { "git_permission_required" }
        }
        fun intent(snapshot: JSONObject, operation: String): JSONObject = JSONObject().put("scope", JSONObject(snapshot.getJSONObject("scope").toString()))
            .put("revision", snapshot.get("revision")).put("operation", operation).put("actionId", UUID.randomUUID().toString()).put("confirmed", true)
    }
}
