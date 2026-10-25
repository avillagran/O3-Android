package dev.avillagran.ot3android.core.transport

import dev.avillagran.t3.T3Exception
import dev.avillagran.t3.ThreadUpdate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Scripted [T3Transport] for domain and ViewModel tests; no native code. Answers are
 * registered per function (or for the shell) as `suspend` lambdas and may throw
 * [T3Exception] to script failures. Calls that need a connection fail with `not_connected`
 * before [connect], like the lib; [domain] needs none. Unscripted calls and domain functions
 * fail with `unknown_function`. Every method call is recorded in [log].
 *
 * Streams: each target (the shell, or one thread) has a hot flow that the test drives with
 * [emitShell], [emitThread] and [emitError] once [collectors] shows the subscriber; an `emit*`
 * without a collector is dropped. [onShellStream] and [onThreadStream] add a cold source per
 * collection on top of it. A collector ends after a closed update; [connect], [disconnect] and
 * [close] end every open stream with `closed = true, error = null`, as the lib does.
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

    /** One method call, in call order: the method's name and its arguments. */
    data class Invocation(val method: String, val args: List<Any?> = emptyList())

    val log: MutableList<Invocation> = CopyOnWriteArrayList()
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

    private val functions = ConcurrentHashMap<String, suspend (JSONObject) -> Any?>()
    private val domainFunctions = ConcurrentHashMap<String, suspend (JSONObject) -> Any?>()
    private val descriptors = ConcurrentHashMap<String, suspend () -> JSONObject>()
    private val threads = ConcurrentHashMap<String, suspend () -> JSONObject>()
    @Volatile private var shell: (suspend () -> JSONObject)? = null
    @Volatile private var shellSource: ((afterSequence: Long?) -> Flow<ThreadUpdate>)? = null
    @Volatile private var threadSource: ((threadId: String, afterSequence: Long?) -> Flow<ThreadUpdate>)? = null
    @Volatile private var streamFailure: T3Exception? = null

    private val threadFlows = ConcurrentHashMap<String, MutableSharedFlow<ThreadUpdate>>()
    private val shellFlow = MutableSharedFlow<ThreadUpdate>(extraBufferCapacity = BUFFER)

    /** Bumped by connect, disconnect and close; a stream ends when it moves past its start. */
    private val session = MutableStateFlow(0)
    private val open = MutableStateFlow(0)

    /** Streams being collected right now, on any target. */
    val openStreams: StateFlow<Int> = open.asStateFlow()

    fun onCall(fn: String, answer: suspend (args: JSONObject) -> Any?) { functions[fn] = answer }

    /** Scripts the domain function [fn]; [answer] may delegate to [DomainEngine] to run the real lib. */
    fun onDomain(fn: String, answer: suspend (args: JSONObject) -> Any?) { domainFunctions[fn] = answer }
    fun onShell(answer: suspend () -> JSONObject) { shell = answer }
    fun onFetchDescriptor(baseUrl: String, answer: suspend () -> JSONObject) { descriptors[baseUrl] = answer }
    fun onBoundedThread(threadId: String, answer: suspend () -> JSONObject) { threads[threadId] = answer }

    @Deprecated("Renamed", ReplaceWith("onFetchDescriptor(baseUrl, answer)"))
    fun onDescriptor(baseUrl: String, answer: suspend () -> JSONObject) = onFetchDescriptor(baseUrl, answer)

    /** Each shell collection also emits [source]`(afterSequence)`; null removes the hook. */
    fun onShellStream(source: ((afterSequence: Long?) -> Flow<ThreadUpdate>)?) { shellSource = source }

    /** Each thread collection also emits [source]`(threadId, afterSequence)`; null removes the hook. */
    fun onThreadStream(source: ((threadId: String, afterSequence: Long?) -> Flow<ThreadUpdate>)?) {
        threadSource = source
    }

    /** The next stream collection (any target) fails with [error] when it starts, then cleared. */
    fun failNextStream(error: T3Exception) { streamFailure = error }

    /** Collectors currently subscribed to the shell stream ([threadId] null) or a thread stream. */
    fun collectors(threadId: String? = null): StateFlow<Int> = target(threadId).subscriptionCount

    /** Emits to every current shell collector; suspends until they have room. Dropped without one. */
    suspend fun emitShell(update: ThreadUpdate) = shellFlow.emit(update)

    /** Emits to every current collector of [threadId]'s stream. Dropped without one. */
    suspend fun emitThread(threadId: String, update: ThreadUpdate) = threadFlow(threadId).emit(update)

    /**
     * Ends the shell stream ([threadId] null) or a thread stream with `closed = true` and
     * [error]'s code, as the lib reports a stream failure. Dropped without a collector.
     */
    suspend fun emitError(error: T3Exception, threadId: String? = null) =
        target(threadId).emit(ThreadUpdates.closed(error.code, errorMessage = error.reason))

    override suspend fun connect(route: ConnectRoute) {
        record("connect", route)
        routes.add(route)
        connectFailure?.let { connectFailure = null; throw it }
        connected = true
        session.update { it + 1 }
    }

    override suspend fun disconnect() {
        record("disconnect")
        connected = false
        session.update { it + 1 }
    }

    override suspend fun capabilities(): JSONObject {
        record("capabilities")
        return capabilities
    }

    override suspend fun describe(): JSONObject {
        record("describe")
        return describe
    }

    override suspend fun call(fn: String, args: JSONObject, timeoutMs: Int?): Any? {
        record("call", fn, args, timeoutMs)
        calls.add(Call(fn, args, timeoutMs))
        val answer = functions[fn] ?: throw T3Exception("unknown_function", "not scripted: $fn")
        requireConnected()
        return answer(args)
    }

    override suspend fun domain(fn: String, args: JSONObject): Any? {
        record("domain", fn, args)
        val answer = domainFunctions[fn] ?: throw T3Exception("unknown_function", "not scripted: $fn")
        return answer(args)
    }

    override suspend fun shell(): JSONObject {
        record("shell")
        requireConnected()
        return (shell ?: throw T3Exception("http_404", "not scripted: shell"))()
    }

    override suspend fun fetchDescriptor(baseUrl: String, environmentId: String?): JSONObject {
        record("fetchDescriptor", baseUrl, environmentId)
        val descriptor = (descriptors[baseUrl] ?: throw T3Exception("rpc_connection_failed", "not scripted: $baseUrl"))()
        if (environmentId != null && descriptor.optString("environmentId") != environmentId) {
            throw T3Exception("t3_environment_mismatch")
        }
        return descriptor
    }

    override suspend fun boundedThread(threadId: String): JSONObject {
        record("boundedThread", threadId)
        requireConnected()
        return (threads[threadId] ?: throw T3Exception("http_404", "not scripted: $threadId"))()
    }

    override fun shellStream(afterSequence: Long?, completionMarker: Boolean): Flow<ThreadUpdate> {
        record("shellStream", afterSequence, completionMarker)
        return stream(StreamOpen(null, afterSequence, completionMarker), shellFlow) {
            shellSource?.invoke(afterSequence)
        }
    }

    override fun threadStream(
        threadId: String,
        afterSequence: Long?,
        completionMarker: Boolean,
        boundedSnapshot: Boolean,
        compactTurnItems: Boolean,
    ): Flow<ThreadUpdate> {
        record("threadStream", threadId, afterSequence, completionMarker, boundedSnapshot, compactTurnItems)
        val open = StreamOpen(threadId, afterSequence, completionMarker, boundedSnapshot, compactTurnItems)
        return stream(open, threadFlow(threadId)) { threadSource?.invoke(threadId, afterSequence) }
    }

    override suspend fun wakeSubscriptions(): Int {
        record("wakeSubscriptions")
        wakes++
        return open.value
    }

    override fun close() {
        record("close")
        closed = true
        connected = false
        session.update { it + 1 }
    }

    private fun record(method: String, vararg args: Any?) {
        log.add(Invocation(method, args.toList()))
    }

    private fun requireConnected() {
        if (!connected) throw T3Exception("not_connected")
    }

    private fun target(threadId: String?) = if (threadId == null) shellFlow else threadFlow(threadId)

    private fun threadFlow(threadId: String) =
        threadFlows.getOrPut(threadId) { MutableSharedFlow(extraBufferCapacity = BUFFER) }

    /**
     * One collection: checks the connection and [failNextStream] like opening a lib stream,
     * then merges the target's hot flow, the hook's cold [source] and the end of the session,
     * and completes after the first closed update.
     */
    private fun stream(
        opened: StreamOpen,
        hot: MutableSharedFlow<ThreadUpdate>,
        source: () -> Flow<ThreadUpdate>?,
    ): Flow<ThreadUpdate> = flow {
        requireConnected()
        streamFailure?.let { streamFailure = null; throw it }
        streamOpens.add(opened)
        val started = session.value
        val ended = flow {
            session.first { it != started }
            emit(ThreadUpdates.closed(null))
        }
        open.update { it + 1 }
        try {
            emitAll(merge(hot, source() ?: emptyFlow(), ended).transformWhile { emit(it); !it.closed })
        } finally {
            open.update { it - 1 }
        }
    }

    private companion object {
        const val BUFFER = 64
    }
}

/** Hands out [FakeT3Transport]s built by [make] and keeps them, in order, in [created]. */
class FakeT3TransportFactory(
    private val make: () -> FakeT3Transport = { FakeT3Transport() },
) : T3TransportFactory {

    val created: MutableList<FakeT3Transport> = CopyOnWriteArrayList()

    override fun create(): FakeT3Transport = make().also { created.add(it) }
}
