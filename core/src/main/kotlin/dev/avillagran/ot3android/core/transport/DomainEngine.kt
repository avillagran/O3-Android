package dev.avillagran.ot3android.core.transport

import dev.avillagran.t3.T3Client
import dev.avillagran.t3.T3Exception
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * lib-t3-rust's pure domain functions (`{"op":"domain"}`, `describe().domain`): projection,
 * sidebar, timeline, Markdown, queue, approvals, ... They need no connection and no network,
 * so they work before `connect` and while offline. Arguments are validated in Rust; failures
 * are [T3Exception] (`unknown_function`, `invalid_<field>`, then the function's own codes).
 *
 * The result is the lib's JSON as `org.json` (`JSONObject`, `JSONArray`, a string, a number,
 * a boolean or null). A call is CPU work over JSON that can be a few MiB (a bounded thread
 * snapshot): implementations never run it on the caller's thread.
 */
fun interface DomainCalls {
    suspend fun domain(fn: String, args: JSONObject): Any?
}

/**
 * [DomainCalls] on one lib handle that is never connected, so UI state builders (the sidebar,
 * the thread timeline) can derive views without an environment's [T3Transport]. The handle is
 * created on first use, on [io], and lives as long as the engine; [shared] is the process-wide
 * one. `T3Client` takes concurrent calls, so callers need no lock.
 */
class DomainEngine(private val io: CoroutineDispatcher = Dispatchers.IO) : DomainCalls {

    @Volatile private var client: T3Client? = null

    override suspend fun domain(fn: String, args: JSONObject): Any? = withContext(io) { handle().domain(fn, args) }

    private fun handle(): T3Client = client ?: synchronized(this) { client ?: T3Client().also { client = it } }

    companion object {
        /** The process-wide engine: one handle for every domain call of the app. */
        val shared: DomainEngine by lazy { DomainEngine() }
    }
}
