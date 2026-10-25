package dev.avillagran.ot3android.core.transport

import dev.avillagran.t3.T3Exception
import dev.avillagran.t3.ThreadUpdate
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import java.io.Closeable

/**
 * One connection to a T3 environment, mirroring lib-t3-rust's `T3Client`. Protocol logic
 * (validation, typed functions, streams, resume cursors) lives in the Rust library; this
 * layer only moves calls off the main thread and turns stream polls into flows.
 *
 * Failures with a stable code are [T3Exception] from the lib, passed through unchanged;
 * the UI maps [T3Exception.code]. Nothing here reconnects or retries: the host owns that.
 *
 * Results are the lib's own JSON (`org.json`); `describe()` documents every function.
 */
interface T3Transport : Closeable {

    /** Connects (or switches) this handle. A failed switch keeps the previous session. */
    suspend fun connect(route: ConnectRoute)

    /** ABI and library versions, operations and limits. Offline. */
    suspend fun capabilities(): JSONObject

    /** The typed API registry: functions, arguments, scopes, replay rules and streams. Offline. */
    suspend fun describe(): JSONObject

    /**
     * A typed function from [describe], validated in Rust before any socket and sent once.
     * [timeoutMs] (1..300000) overrides the function's deadline.
     */
    suspend fun call(fn: String, args: JSONObject = JSONObject(), timeoutMs: Int? = null): Any?

    /** A GET on the connected environment, as `T3Client.request`. */
    suspend fun request(path: String, protocol: Boolean = true): Any?

    /**
     * The environment descriptor at [baseUrl], with no connection and no credential. With
     * [environmentId], fails with `t3_environment_mismatch` unless the descriptor names it.
     */
    suspend fun fetchDescriptor(baseUrl: String, environmentId: String? = null): JSONObject

    /** The restored bounded window of a thread (up to 64 MiB), to paint before its stream. */
    suspend fun boundedThread(threadId: String): JSONObject

    /**
     * The environment's shell stream (projects and thread rows). Cold: each collection opens
     * one subscription and closes it when collection ends. See [threadStream] for the rules.
     */
    fun shellStream(afterSequence: Long? = null, completionMarker: Boolean = false): Flow<ThreadUpdate>

    /**
     * A thread stream. Cold: each collection opens one subscription and closes it when the
     * collector stops. Updates that carry nothing new are skipped. When the stream ends, the
     * closed update (with its `error`) is emitted last and the flow completes; resume by
     * collecting again with `afterSequence` = that update's `cursor`, after the host's backoff.
     *
     * [compactTurnItems] stays false: the lib does not restore compact stream snapshots yet
     * (docs/LIB_REQUESTS.md, phase 1 item 2).
     */
    fun threadStream(
        threadId: String,
        afterSequence: Long? = null,
        completionMarker: Boolean = false,
        boundedSnapshot: Boolean = true,
        compactTurnItems: Boolean = false,
    ): Flow<ThreadUpdate>

    /**
     * Probes every open stream, e.g. on resume or a network change: a dead socket then ends
     * with `stream_timeout` within about 3 s. Returns how many streams were probed.
     */
    suspend fun wakeSubscriptions(): Int
}

/** How [T3Transport.connect] reaches an environment. Credentials never appear in [toString]. */
sealed class ConnectRoute {

    /**
     * URL + bearer token. [options]: `requestTimeoutMs`, `connectTimeoutMs`,
     * `poolIdleTimeoutMs`, `maxBytes`; anything else fails with `invalid_connection_options`.
     */
    data class Bearer(
        val baseUrl: String,
        val token: String,
        val options: JSONObject? = null,
    ) : ConnectRoute() {
        override fun toString() = "Bearer(baseUrl=$baseUrl, token=<redacted>, options=$options)"
    }

    /** T3 Connect relay with a current cloud JWT; same [options] as [Bearer]. */
    data class Relay(
        val relayUrl: String,
        val cloudJwt: String,
        val environmentId: String,
        val clientId: String = "t3-mobile",
        val options: JSONObject? = null,
    ) : ConnectRoute() {
        override fun toString() =
            "Relay(relayUrl=$relayUrl, cloudJwt=<redacted>, environmentId=$environmentId, clientId=$clientId, " +
                "options=$options)"
    }
}
