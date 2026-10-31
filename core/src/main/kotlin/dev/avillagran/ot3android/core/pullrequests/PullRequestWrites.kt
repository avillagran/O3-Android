package dev.avillagran.ot3android.core.pullrequests

import dev.avillagran.ot3android.core.transport.DomainCalls
import dev.avillagran.ot3android.core.transport.DomainEngine
import dev.avillagran.ot3android.core.transport.T3Transport
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** A dispatched write is never replayed after a timeout, disconnect or unrecognised receipt. */
sealed interface PullRequestWriteResult {
    data object Received : PullRequestWriteResult
    data object Unknown : PullRequestWriteResult
}

class PullRequestWrites(private val domain: DomainCalls = DomainEngine.shared) {
    suspend fun write(transport: T3Transport, reference: JSONObject, values: JSONObject): PullRequestWriteResult {
        // Validation/build errors precede dispatch and remain ordinary failures.
        val recipe = domain.domain("pullRequests.payload", JSONObject(values.toString())
            .put("reference", JSONObject(reference.toString()))) as JSONObject
        require(recipe.getString("method") in setOf("pullRequests.comment", "pullRequests.submitReview", "pullRequests.runAction"))
        return try {
            val receipt = transport.call(recipe.getString("method"), recipe.getJSONObject("payload"))
            // These three typed APIs acknowledge success with null, not an object receipt.
            if (receipt == null || receipt === JSONObject.NULL) PullRequestWriteResult.Received else PullRequestWriteResult.Unknown
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { PullRequestWriteResult.Unknown }
    }
}

/** Native badge projection owns single/stack selection; consumers must retain the full reference. */
object PullRequestBadgeAction {
    fun reference(badge: JSONObject): JSONObject? = badge.optJSONObject("current")?.let { JSONObject(it.toString()) }
    fun multiple(badge: JSONObject): Boolean = badge.optString("kind") != "single"
}

/** P20 dispatches this exact command with its existing command/receipt controller. */
class PullRequestThreadCommands(private val domain: DomainCalls = DomainEngine.shared) {
    suspend fun build(threadId: String, action: String, reference: JSONObject): JSONObject {
        require(threadId.isNotBlank())
        return domain.domain("pullRequests.threadCommand", JSONObject().put("thread", threadId)
            .put("link", action).put("reference", JSONObject(reference.toString()))) as JSONObject
    }
}
