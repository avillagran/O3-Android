package dev.avillagran.ot3android.core.transport

import dev.avillagran.t3.T3Exception
import dev.avillagran.t3.ThreadUpdate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformWhile
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Scripted [T3Transport] for domain and UI tests; no native code. Answers are registered
 * per function or path and may throw [T3Exception] to script failures. Calls that need a
 * connection fail with `not_connected` before [connect], like the lib; unscripted ones fail
 * with `unknown_function`. Streams are hot [MutableSharedFlow]s per subscription target that
 * the test drives with [emitShell] and [emitThread] once [collectors] shows the subscriber;
 * a collector ends after a closed update.
 */
class FakeT3Transport(
    var capabilities: JSONObject = JSONObject().put("abiVersion", 3),
    var describe: JSONObject = JSONObject(),
) : T3Transport {

    /** Arguments of one stream collection, recorded when it starts. */
    data class StreamOpen(
        val threadId: String?,
        val afterSequence: Long?,
        val completionMarker: Boolean,
        val boundedSnapshot: Boolean = false,
        val compactTurnItems: Boolean = false,
    )

    data class Call(val fn: String, val args: JSONObject, val timeoutMs: Int?)

    val routes: MutableList<ConnectRoute> = CopyOnWriteArrayList()
    val calls: MutableList<Call> = CopyOnWriteArrayList()
    val streamOpens: MutableList<StreamOpen> = CopyOnWriteArrayList()

    /** Thrown by the next [connect] instead of connecting, then cleared. */
    @Volatile var connectFailure: T3Exception? = null

    @Volatile var connected: Boolean = false
        private set

    @Volatile var closed: Boolean = false
        private set

    @Volatile var wakes: Int = 0
        private set

    private val functions = ConcurrentHashMap<String, (JSONObject) -> Any?>()
    private val paths = ConcurrentHashMap<String, () -> Any?>()
    private val descriptors = ConcurrentHashMap<String, () -> JSONObject>()
    private val threads = ConcurrentHashMap<String, () -> JSONObject>()
    private val threadFlows = ConcurrentHashMap<String, MutableSharedFlow<ThreadUpdate>>()
    private val shellFlow = MutableSharedFlow<ThreadUpdate>(extraBufferCapacity = BUFFER)

    fun onCall(fn: String, answer: (args: JSONObject) -> Any?) { functions[fn] = answer }
    fun onRequest(path: String, answer: () -> Any?) { paths[path] = answer }
    fun onDescriptor(baseUrl: String, answer: () -> JSONObject) { descriptors[baseUrl] = answer }
    fun onBoundedThread(threadId: String, answer: () -> JSONObject) { threads[threadId] = answer }

    /** Collectors currently subscribed to the shell stream ([threadId] null) or a thread stream. */
    fun collectors(threadId: String? = null): StateFlow<Int> =
        (if (threadId == null) shellFlow else threadFlow(threadId)).subscriptionCount

    /** Emits to every current shell collector; suspends until they have room. */
    suspend fun emitShell(update: ThreadUpdate) = shellFlow.emit(update)

    suspend fun emitThread(threadId: String, update: ThreadUpdate) = threadFlow(threadId).emit(update)

    override suspend fun connect(route: ConnectRoute) {
        routes.add(route)
        connectFailure?.let { connectFailure = null; throw it }
        connected = true
    }

    override suspend fun disconnect() {
        connected = false
    }

    override suspend fun capabilities(): JSONObject = capabilities

    override suspend fun describe(): JSONObject = describe

    override suspend fun call(fn: String, args: JSONObject, timeoutMs: Int?): Any? {
        calls.add(Call(fn, args, timeoutMs))
        val answer = functions[fn] ?: throw T3Exception("unknown_function", "not scripted: $fn")
        requireConnected()
        return answer(args)
    }

    override suspend fun request(path: String, protocol: Boolean): Any? {
        requireConnected()
        return (paths[path] ?: throw T3Exception("http_404", "not scripted: $path"))()
    }

    override suspend fun fetchDescriptor(baseUrl: String, environmentId: String?): JSONObject {
        val descriptor = (descriptors[baseUrl] ?: throw T3Exception("rpc_connection_failed", "not scripted: $baseUrl"))()
        if (environmentId != null && descriptor.optString("environmentId") != environmentId) {
            throw T3Exception("t3_environment_mismatch")
        }
        return descriptor
    }

    override suspend fun boundedThread(threadId: String): JSONObject {
        requireConnected()
        return (threads[threadId] ?: throw T3Exception("http_404", "not scripted: $threadId"))()
    }

    override fun shellStream(afterSequence: Long?, completionMarker: Boolean): Flow<ThreadUpdate> =
        shellFlow.untilClosed(StreamOpen(null, afterSequence, completionMarker))

    override fun threadStream(
        threadId: String,
        afterSequence: Long?,
        completionMarker: Boolean,
        boundedSnapshot: Boolean,
        compactTurnItems: Boolean,
    ): Flow<ThreadUpdate> = threadFlow(threadId)
        .untilClosed(StreamOpen(threadId, afterSequence, completionMarker, boundedSnapshot, compactTurnItems))

    override suspend fun wakeSubscriptions(): Int {
        wakes++
        return shellFlow.subscriptionCount.value + threadFlows.values.sumOf { it.subscriptionCount.value }
    }

    override fun close() {
        closed = true
        connected = false
    }

    private fun requireConnected() {
        if (!connected) throw T3Exception("not_connected")
    }

    private fun threadFlow(threadId: String) =
        threadFlows.getOrPut(threadId) { MutableSharedFlow(extraBufferCapacity = BUFFER) }

    private fun Flow<ThreadUpdate>.untilClosed(open: StreamOpen): Flow<ThreadUpdate> =
        onStart { requireConnected(); streamOpens.add(open) }
            .transformWhile { emit(it); !it.closed }

    private companion object {
        const val BUFFER = 64
    }
}
