package dev.avillagran.ot3android.core.git

import dev.avillagran.t3.T3Client
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** Uses the pinned AAR's stream, never call/rpc or a shell command. P20 provides the
 * authenticated handle for this exact environment, pinned for this operation's lifetime.
 * Opening is an effect: cancellation or timeout must leave the journal unresolved.
 */
class NativeGitStackedAction(
    private val resolve: (String) -> T3Client?,
    private val onPhase: (environment: String, actionId: String, phase: String) -> Unit = { _, _, _ -> },
) : GitStackedAction {
    override suspend fun execute(environment: String, payload: JSONObject): JSONObject = withTimeout(120_000) {
        withContext(Dispatchers.IO) {
            val captured = JSONObject(payload.toString())
            val client = resolve(environment) ?: error("git_scope_unavailable")
            client.subscribeFn("git.runStackedAction", captured).use { subscription ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val batch = subscription.poll(1000)
                    for (i in 0 until batch.items.length()) {
                        val event = batch.items.getJSONObject(i)
                        check(event.optString("actionId") == captured.getString("actionId") &&
                            event.optString("cwd") == captured.getString("cwd") &&
                            event.optString("action") == captured.getString("action")) { "git_scope_changed" }
                        when (event.optString("kind")) {
                            "phase_started" -> onPhase(environment, captured.getString("actionId"), event.optString("phase"))
                            "action_finished" -> {
                                val result = event.getJSONObject("result")
                                check(result.optString("action") == captured.getString("action")) { "invalid_git_result" }
                                return@withContext JSONObject().put("state", "complete").put("result", result)
                            }
                            "action_failed" -> return@withContext JSONObject().put("state", "failed")
                        }
                    }
                    check(!batch.closed && batch.error == null) { "git_stream_interrupted" }
                }
                @Suppress("UNREACHABLE_CODE")
                error("git_stream_interrupted")
            }
        }
    }
}
