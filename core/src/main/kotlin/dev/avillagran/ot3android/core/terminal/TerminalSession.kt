package dev.avillagran.ot3android.core.terminal

import dev.avillagran.ot3android.core.transport.T3Transport
import dev.avillagran.t3.T3Exception
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** One owner per environment/thread/terminal. Transport changes never replay user input. */
class TerminalSession(
    val threadId: String, val terminalId: String = "term-1",
    resumeInitially: Boolean = false, private val onAttempt: () -> Unit = {},
) {
    data class View(
        val frame: TerminalEmulator.Frame, val cwd: String = "", val connected: Boolean = false,
        val operate: Boolean = false, val finished: Boolean = false, val exit: String = "",
        val error: Boolean = false, val uncertainWrite: Boolean = false, val applicationCursor: Boolean = false,
    )
    val emulator = TerminalEmulator()
    private val mutable = MutableStateFlow(View(emulator.frame()))
    val state = mutable.asStateFlow()
    private var transport: T3Transport? = null
    private var output = JSONObject()
    private var viewport: Pair<Int, Int>? = null
    private var remoteSize: Pair<Int, Int>? = null
    private var attempted = resumeInitially
    private var generation = 0
    var inputEpoch = 0L; private set
    private fun publish() { mutable.value = mutable.value.copy(frame = emulator.frame(), applicationCursor = emulator.applicationCursor) }

    /** Cancel collection when the page stops. Any later subscription is a resume, even after uncertain attach. */
    private data class Input(val epoch: Long, val data: String)
    private val input = Channel<Input>(Channel.UNLIMITED)
    private val collector = Mutex()

    suspend fun collect(host: T3Transport) = collector.withLock { coroutineScope {
        val writer = launch { writeLoop(host) }
        try { collectHost(host) } finally { writer.cancel(); writer.join() }
    } }

    private suspend fun collectHost(host: T3Transport) {
        val owner = ++generation
        transport = host
        var retry = 0
        try {
            while (currentCoroutineContext().isActive && owner == generation) {
                try {
                    val auth = host.authSession()
                    check(TerminalBindings.canRead(auth)) { "terminal_permission_required" }
                    val operate = TerminalBindings.canOperate(auth)
                    val context = if (operate) host.domain("terminal.context", JSONObject().put("shell", host.shell())
                        .put("threadId", threadId).put("fallback", "")) as JSONObject else JSONObject()
                    mutable.value = mutable.value.copy(cwd = context.optString("cwd", mutable.value.cwd), operate = operate, error = false)
                    val args = if (operate) host.domain("terminal.attach", JSONObject().put("threadId", threadId)
                        .put("terminalId", terminalId).put("context", context).put("cols", emulator.cols)
                        .put("rows", emulator.rows).put("resume", attempted)) as JSONObject
                    else JSONObject().put("threadId", threadId).put("terminalId", terminalId)
                    remoteSize = if (operate) args.getInt("cols") to args.getInt("rows") else null
                    attempted = true
                    onAttempt()
                    inputEpoch++
                    host.terminalStream(!operate, args).collect stream@{ batch ->
                        for (i in 0 until batch.items.length()) {
                            val event = batch.items.getJSONObject(i)
                            val reduced = host.domain("terminal.output", JSONObject().put("state", output).put("event", event)) as JSONObject
                            if (owner != generation) return@stream
                            output = reduced.getJSONObject("state")
                            val snapshot = event.optJSONObject("snapshot")
                            if (snapshot != null && !operate) emulator.resize(snapshot.optInt("cols", emulator.cols), snapshot.optInt("rows", emulator.rows))
                            if (reduced.optBoolean("replace")) emulator.reset()
                            if (!reduced.isNull("text")) {
                                val text = reduced.getString("text")
                                var offset = 0
                                while (offset < text.length) {
                                    val end = (offset + 8192).coerceAtMost(text.length)
                                    emulator.feed(text.substring(offset, end)); offset = end
                                    yield()
                                }
                            }
                            val exit = listOf("exitCode", "exitSignal").mapNotNull { key ->
                                reduced.opt(key)?.takeUnless { it == JSONObject.NULL }?.toString()
                            }.joinToString(" / ")
                            mutable.value = mutable.value.copy(cwd = snapshot?.optString("cwd", mutable.value.cwd) ?: mutable.value.cwd, connected = !batch.closed,
                                finished = reduced.optBoolean("finished"), exit = exit,
                                error = !reduced.isNull("error"))
                            publish(); retry = 0
                            if (operate) viewport?.let { (cols, rows) -> resize(cols, rows) }
                            val replies = emulator.drainReplies()
                            if (operate && !reduced.optBoolean("replace")) for (reply in replies) write(reply)
                        }
                        if (batch.closed) mutable.value = mutable.value.copy(connected = false)
                    }
                    mutable.value = mutable.value.copy(connected = false)
                    if (mutable.value.finished) break
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    mutable.value = mutable.value.copy(connected = false, error = true)
                    // Unsupported observe and authorization failures stay explicit; never fall back to attach.
                    if (e is IllegalStateException || e is T3Exception && e.code in setOf("forbidden", "unauthorized", "permission_denied", "unknown_function", "unknown_method", "method_not_found")) break
                }
                delay((500L shl retry.coerceAtMost(4)).coerceAtMost(8000)); retry++
            }
        } finally {
            if (owner == generation) { inputEpoch++; transport = null; mutable.value = mutable.value.copy(connected = false) }
        }
    }
    suspend fun resize(cols: Int, rows: Int) {
        // An observer keeps the actual host dimensions and never sends a mutation.
        if (!mutable.value.operate && attempted) return
        viewport = cols to rows
        if (cols != emulator.cols || rows != emulator.rows) { emulator.resize(cols, rows); publish() }
        val host = transport ?: return
        if (!mutable.value.connected || mutable.value.finished) return
        val size = emulator.cols to emulator.rows
        if (size == remoteSize) return
        remoteSize = size
        try { TerminalBindings.resize(host, threadId, terminalId, size.first, size.second) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutable.value = mutable.value.copy(error = true) }
    }
    /** Enqueue synchronously at the input boundary, before any coroutine can suspend. */
    fun write(data: String, epoch: Long = inputEpoch) {
        if (epoch != inputEpoch || data.isEmpty()) return
        val view = mutable.value
        if (transport == null || !view.operate || !view.connected || view.finished || view.uncertainWrite) return
        if (data.toByteArray(Charsets.UTF_8).size > 65536) {
            mutable.value = view.copy(error = true, uncertainWrite = true)
            return
        }
        input.trySend(Input(epoch, data))
    }

    private suspend fun writeLoop(host: T3Transport) {
        var pending: Input? = null
        while (currentCoroutineContext().isActive) {
            val first = pending ?: input.receive()
            pending = null
            if (first.epoch != inputEpoch || mutable.value.uncertainWrite || !mutable.value.connected) continue
            val bytes = StringBuilder(first.data)
            var size = first.data.toByteArray(Charsets.UTF_8).size
            while (true) {
                val next = input.tryReceive().getOrNull() ?: break
                if (next.epoch != inputEpoch) continue
                val count = next.data.toByteArray(Charsets.UTF_8).size
                if (size + count > 65536) { pending = next; break }
                bytes.append(next.data); size += count
            }
            try {
                // Authorization was checked at attach; the host still enforces every RPC.
                // No per-keystroke auth/session GET and no retry after unknown delivery.
                TerminalBindings.writeAuthenticated(host, threadId, terminalId, bytes.toString())
            } catch (e: CancellationException) {
                mutable.value = mutable.value.copy(uncertainWrite = true)
                throw e
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(uncertainWrite = true)
                return
            }
        }
    }
    fun paste(text: String): String = if (emulator.bracketedPaste) "\u001b[200~$text\u001b[201~" else text
    fun arrow(final: Char) = "\u001b" + (if (emulator.applicationCursor) "O" else "[") + final
}
