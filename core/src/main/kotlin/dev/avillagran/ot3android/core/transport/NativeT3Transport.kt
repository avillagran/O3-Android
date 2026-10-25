package dev.avillagran.ot3android.core.transport

import dev.avillagran.t3.T3Client
import dev.avillagran.t3.ThreadStream
import dev.avillagran.t3.ThreadUpdate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
 * [close] cancels the native streams, waits for in-flight calls and frees the handle; it is
 * idempotent and may block, so call it from a worker.
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

    override suspend fun capabilities(): JSONObject = native { client.execute(op("capabilities")) as JSONObject }

    override suspend fun describe(): JSONObject = native { client.describe() }

    override suspend fun call(fn: String, args: JSONObject, timeoutMs: Int?): Any? =
        native { client.call(fn, args, timeoutMs) }

    override suspend fun request(path: String, protocol: Boolean): Any? = native { client.request(path, protocol) }

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

    override fun close() = client.close()

    private suspend fun <T> native(block: () -> T): T = withContext(io) { block() }

    /**
     * Polls [open]'s stream on [io] until it closes or the collector goes away. Closing the
     * stream on cancellation wakes a poll blocked in native code at once. No buffer: the
     * lib's bounded queue is the buffer, and a collector that falls behind ends the stream
     * with `stream_backpressure` instead of holding snapshots in memory here.
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
                    if (isActive) throw error else break
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

        fun op(name: String): JSONObject = JSONObject().put("op", name)

        /** A poll that timed out with nothing new: no items, no chunk end, same flags. */
        fun ThreadUpdate.isNews(previous: ThreadUpdate?): Boolean =
            closed || batch.items.length() > 0 || batch.boundaries.isNotEmpty() ||
                idle != (previous?.idle ?: false) || completionMarker != (previous?.completionMarker ?: false)
    }
}
