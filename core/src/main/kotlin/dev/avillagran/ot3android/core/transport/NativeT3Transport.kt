package dev.avillagran.ot3android.core.transport

import dev.avillagran.t3.T3Client
import dev.avillagran.t3.T3Exception
import dev.avillagran.t3.ThreadStream
import dev.avillagran.t3.ThreadUpdate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * [T3Transport] over lib-t3-rust's [T3Client] (JNI). Every native call runs on [io]; the
 * client blocks, so nothing here may run on the main thread.
 *
 * [close] returns at once: the native close (cancel the streams, wait for in-flight calls up to
 * their native deadline, free the handle) runs on an internal IO scope. [shutdown] waits for it.
 */
class NativeT3Transport(
    private val client: T3Client = T3Client(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : T3Transport {

    override suspend fun connect(route: ConnectRoute) = native {
        when (route) {
            is ConnectRoute.Bearer -> client.connectBearer(route.baseUrl, route.token, route.options)
            is ConnectRoute.Relay ->
                client.connectRelay(route.relayUrl, route.cloudJwt, route.environmentId, route.clientId, route.options)
        }
    }

    override suspend fun disconnect() {
        native { client.execute(op("disconnect")) }
    }

    override suspend fun capabilities(): JSONObject = native { client.execute(op("capabilities")) as JSONObject }

    override suspend fun describe(): JSONObject = native { client.describe() }

    override suspend fun call(fn: String, args: JSONObject, timeoutMs: Int?): Any? =
        native { client.call(fn, args, timeoutMs) }

    // T3Client has no shell wrapper; this is T3's endpoint, read through the lib's GET.
    override suspend fun shell(): JSONObject = request(SHELL_PATH) as JSONObject

    /** A GET on the connected environment, as `T3Client.request`; tests only, the UI uses [shell]. */
    internal suspend fun request(path: String, protocol: Boolean = true): Any? = native { client.request(path, protocol) }

    // fetchDescriptor and boundedThread have no T3Client wrapper yet (docs/LIB_REQUESTS.md,
    // phase 1 item 8); these are the lib's documented operations, unchanged.
    override suspend fun fetchDescriptor(baseUrl: String, environmentId: String?): JSONObject = native {
        client.execute(op("fetchDescriptor").put("httpBaseUrl", baseUrl).put("environmentId", environmentId)) as JSONObject
    }

    override suspend fun boundedThread(threadId: String): JSONObject = native {
        client.execute(op("boundedThread").put("threadId", threadId)) as JSONObject
    }

    override fun shellStream(afterSequence: Long?, completionMarker: Boolean): Flow<ThreadUpdate> =
        updates { ThreadStream.shell(client, afterSequence, completionMarker) }

    override fun threadStream(
        threadId: String,
        afterSequence: Long?,
        completionMarker: Boolean,
        boundedSnapshot: Boolean,
        compactTurnItems: Boolean,
    ): Flow<ThreadUpdate> = updates {
        ThreadStream.thread(client, threadId, afterSequence, completionMarker, boundedSnapshot, compactTurnItems)
    }

    override suspend fun wakeSubscriptions(): Int = native { client.wakeSubscriptions() }

    private val closer = CoroutineScope(io + SupervisorJob())
    private val closing: Deferred<Unit> by lazy { closer.async { client.close() } }

    /** Starts the native close in the background (fire and forget); idempotent. */
    override fun close() {
        closing.start()
    }

    /** [close], then waits until the handle is freed; rethrows what the native close threw. */
    suspend fun shutdown() = closing.await()

    private suspend fun <T> native(block: () -> T): T = withContext(io) { block() }

    /**
     * Polls [open]'s stream on [io] until it closes or the collector goes away. Closing the
     * stream on cancellation wakes a poll blocked in native code at once. No buffer: the
     * lib's bounded queue is the buffer, and a collector that falls behind ends the stream
     * with `stream_backpressure` instead of holding snapshots in memory here. A poll that
     * fails ends the stream with a closed update instead of an exception (see [endedBy]).
     */
    private fun updates(open: () -> ThreadStream): Flow<ThreadUpdate> = channelFlow {
        val stream = open()
        launch {
            var previous: ThreadUpdate? = null
            while (isActive) {
                val update = try {
                    stream.poll(POLL_TIMEOUT_MS)
                } catch (error: Exception) {
                    // A poll cut short by cancellation (stream closed under it) is not a failure.
                    if (!isActive) break
                    stream.endedBy(error) ?: throw error
                }
                if (!update.isNews(previous)) continue
                send(update)
                previous = update
                if (update.closed) break
            }
            channel.close()
        }
        awaitClose { stream.close() }
    }.buffer(0).flowOn(io)

    private companion object {
        const val POLL_TIMEOUT_MS = 1000
        const val SHELL_PATH = "/api/orchestration/shell"

        fun op(name: String): JSONObject = JSONObject().put("op", name)

        /**
         * The closed update for a poll that threw. A successful connect or disconnect drops the
         * subscription from the handle, so a poll after it fails with `unknown_subscription`, and
         * one after [close] with `client_closed`: both are a cancel (`error = null`), like the
         * closed batch an in-flight poll gets. Any other [T3Exception] keeps its code.
         */
        fun ThreadStream.endedBy(error: Exception): ThreadUpdate? {
            val (code, reason) = when {
                error is T3Exception && error.code == "unknown_subscription" -> null to null
                error is T3Exception -> error.code to error.reason
                error is IllegalStateException && error.message == "client_closed" -> null to null
                else -> return null
            }
            return ThreadUpdates.closed(code, cursor = cursor, errorMessage = reason, start = start)
        }

        /** A poll that timed out with nothing new: no items, no chunk end, same flags. */
        fun ThreadUpdate.isNews(previous: ThreadUpdate?): Boolean =
            closed || batch.items.length() > 0 || batch.boundaries.isNotEmpty() ||
                idle != (previous?.idle ?: false) || completionMarker != (previous?.completionMarker ?: false)
    }
}
