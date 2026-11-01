package dev.avillagran.ot3android.core.terminal

import dev.avillagran.ot3android.core.transport.T3Transport
import dev.avillagran.t3.T3Batch
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalSessionTest {
    private var operate = true
    private var reconnect = false
    private var denied = false
    private var exit = false
    private var beforeSnapshot: suspend () -> Unit = {}
    private val subscriptions = mutableListOf<Pair<Boolean, JSONObject>>()
    private val writes = mutableListOf<JSONObject>()
    private val resizes = mutableListOf<JSONObject>()
    private val domains = mutableListOf<String>()
    private fun snapshot(text: String) = JSONObject().put("type", "snapshot").put("snapshot", JSONObject().put("history", text).put("sequence", 1).put("cwd", "/host-a/worktree").put("cols", 90).put("rows", 12))
    private val host = Proxy.newProxyInstance(T3Transport::class.java.classLoader, arrayOf(T3Transport::class.java)) { _, method, args ->
        when (method.name) {
            "authSession" -> JSONObject().put("authenticated", true).put("scopes", JSONArray(listOf(if (operate) "terminal:operate" else "terminal:read")))
            "shell" -> { check(operate) { "terminal:read does not grant shell read" }; JSONObject().put("selectedHost", "host-a") }
            "domain" -> {
                val fn = args!![0] as String; val a = args[1] as JSONObject; domains += fn
                when (fn) {
                    "terminal.context" -> { assertEquals("host-a", a.getJSONObject("shell").getString("selectedHost")); assertEquals("thread-a", a.getString("threadId")); JSONObject().put("cwd", "/host-a/worktree") }
                    "terminal.attach" -> JSONObject(a.toString()).put("restartIfNotRunning", !a.getBoolean("resume"))
                    "terminal.output" -> {
                        val e = a.getJSONObject("event"); val replace = e.getString("type") == "snapshot"
                        JSONObject().put("state", JSONObject()).put("replace", replace).put("text", if (replace) e.getJSONObject("snapshot").getString("history") else JSONObject.NULL)
                            .put("finished", e.getString("type") == "exited")
                    }
                    else -> error(fn)
                }
            }
            "terminalStream" -> {
                subscriptions += (args!![0] as Boolean to args[1] as JSONObject)
                val attempt = subscriptions.size
                flow {
                    if (denied) throw dev.avillagran.t3.T3Exception("unknown_method")
                    beforeSnapshot()
                    emit(T3Batch(JSONArray().put(snapshot(if (attempt == 1) "old\u001b[31" else "fresh café 東京")), false, null))
                    if (reconnect && attempt == 1) throw IOException("disconnected")
                    if (exit) emit(T3Batch(JSONArray().put(JSONObject().put("type", "exited")), true, null))
                    else awaitCancellation()
                }
            }
            "call" -> {
                when (args!![0]) {
                    "terminal.write" -> { writes += args[1] as JSONObject; throw IOException("unknown delivery") }
                    "terminal.resize" -> { resizes += args[1] as JSONObject; null }
                    else -> error("unexpected call")
                }
            }
            else -> error("unexpected ${method.name}")
        }
    } as T3Transport
    @Test fun readOnlyUsesObserveWithoutAttachResizeOrWrites() = runTest {
        operate = false
        val session = TerminalSession("thread-a")
        val job = launch { session.collect(host) }; runCurrent()
        assertTrue(subscriptions.single().first)
        assertFalse(domains.contains("terminal.attach"))
        assertFalse(domains.contains("terminal.context"))
        assertEquals(setOf("threadId", "terminalId"), subscriptions.single().second.keys().asSequence().toSet())
        session.resize(90, 10); session.write("blocked")
        assertTrue(writes.isEmpty()); assertTrue(resizes.isEmpty())
        assertTrue(session.state.value.frame.text().startsWith("old"))
        assertEquals("/host-a/worktree", session.state.value.cwd)
        assertEquals(90, session.state.value.frame.cols)
        assertEquals(12, session.state.value.frame.rows)
        job.cancelAndJoin()
    }
    @Test fun reconnectResumesWithoutRestartAndReplacesHistoryAndParser() = runTest {
        reconnect = true
        val session = TerminalSession("thread-a")
        val job = launch { session.collect(host) }; runCurrent(); advanceTimeBy(500); runCurrent()
        assertEquals(2, subscriptions.size)
        assertFalse(subscriptions[0].second.getBoolean("resume"))
        assertTrue(subscriptions[1].second.getBoolean("resume"))
        assertFalse(subscriptions[1].second.getBoolean("restartIfNotRunning"))
        assertFalse(subscriptions[1].second.has("afterSequence"))
        assertEquals("fresh café 東京", session.state.value.frame.text().trim())
        assertEquals(7, session.state.value.frame.lines[0][0].fg)
        assertFalse(session.state.value.finished)
        assertEquals("/host-a/worktree", session.state.value.cwd)
        job.cancelAndJoin()
    }
    @Test fun resizeUsesViewportAndUncertainInputIsNeverRetried() = runTest {
        val session = TerminalSession("thread-a")
        val job = launch { session.collect(host) }; runCurrent()
        session.resize(100, 12); session.write("id\r"); runCurrent()
        assertEquals(100, resizes.single().getInt("cols")); assertEquals(12, resizes.single().getInt("rows"))
        assertEquals("thread-a", writes.single().getString("threadId"))
        assertTrue(session.state.value.uncertainWrite)
        advanceTimeBy(10000); runCurrent(); assertEquals(1, writes.size)
        job.cancelAndJoin()
    }
    @Test fun viewportDuringAttachAndKeyboardChangesReachHost() = runTest {
        val session = TerminalSession("thread-a")
        session.resize(41, 32)
        beforeSnapshot = { session.resize(47, 22) }
        val job = launch { session.collect(host) }; runCurrent()
        assertEquals(41, subscriptions.single().second.getInt("cols"))
        session.resize(41, 14)
        session.resize(41, 32)
        assertEquals(listOf(22, 14, 32), resizes.map { it.getInt("rows") })
        assertEquals(41, session.state.value.frame.cols)
        job.cancelAndJoin()
    }
    @Test fun lifecycleReentryResumesAndStaleQueuedInputIsDropped() = runTest {
        val session = TerminalSession("thread-a")
        val first = launch { session.collect(host) }; runCurrent()
        val epoch = session.inputEpoch
        first.cancelAndJoin()
        val second = launch { session.collect(host) }; runCurrent()
        assertTrue(subscriptions.last().second.getBoolean("resume"))
        session.write("stale", epoch); assertTrue(writes.isEmpty())
        second.cancelAndJoin()
    }
    @Test fun burstAt150msRttCoalesces200KeysAndEnterWithOrderedOutput() = runTest {
        val output = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val batches = mutableListOf<String>()
        val echoedAt = mutableListOf<Long>()
        var authReads = 0
        var active = 0
        var maxActive = 0
        val delayed = object : T3Transport by host {
            override suspend fun authSession(): JSONObject {
                authReads++
                delay(150)
                return host.authSession()
            }
            override suspend fun call(fn: String, args: JSONObject, timeoutMs: Int?): Any? {
                if (fn != "terminal.write") return host.call(fn, args, timeoutMs)
                active++; maxActive = maxOf(maxActive, active)
                batches += args.getString("data")
                delay(150)
                output.send(args.getString("data"))
                active--
                return null
            }
            override fun terminalStream(observe: Boolean, args: JSONObject) = flow {
                emit(T3Batch(JSONArray().put(snapshot("")), false, null))
                for (text in output) {
                    echoedAt += currentTime
                    emit(T3Batch(JSONArray().put(snapshot(batches.take(echoedAt.size).joinToString(""))), false, null))
                }
            }
        }
        val session = TerminalSession("thread-a")
        val job = launch { session.collect(delayed) }
        advanceTimeBy(150); runCurrent()
        val started = currentTime
        val keys = (0 until 200).map { ('a'.code + it % 26).toChar() }.joinToString("")
        session.write(keys.take(1)); runCurrent() // First RPC is now in flight.
        keys.drop(1).forEach { session.write(it.toString()) }
        session.write("\r")
        advanceTimeBy(300); runCurrent()
        assertEquals(listOf(keys.take(1), keys.drop(1) + "\r"), batches)
        assertEquals(keys + "\r", batches.joinToString(""))
        assertEquals(listOf(150L, 300L), echoedAt.map { it - started })
        assertEquals(1, maxActive)
        assertEquals(1, authReads) // Attach only; no auth GET for each key.
        assertEquals(keys, session.state.value.frame.text().replace("\n", "").trim())
        println("TERMINAL_RTT150: 200 keys + Enter, 2 writes, first output 150ms, final output 300ms, max concurrent 1, auth GETs 1")
        job.cancelAndJoin()
    }

    @Test fun uncertainWriteStopsAllQueuedAndFutureInputEvenAfterReconnect() = runTest {
        val delayed = object : T3Transport by host {
            override suspend fun call(fn: String, args: JSONObject, timeoutMs: Int?): Any? {
                if (fn == "terminal.write") delay(150)
                return host.call(fn, args, timeoutMs)
            }
        }
        val session = TerminalSession("thread-a")
        val job = launch { session.collect(delayed) }; runCurrent()
        session.write("first"); runCurrent()
        repeat(200) { session.write("x") }; session.write("\r")
        advanceTimeBy(150); runCurrent()
        assertTrue(session.state.value.uncertainWrite)
        session.write("later")
        advanceTimeBy(10000); runCurrent()
        assertEquals(listOf("first"), writes.map { it.getString("data") })
        job.cancelAndJoin()
        val resumed = launch { session.collect(delayed) }; runCurrent()
        session.write("after resume"); runCurrent()
        assertEquals(1, writes.size)
        resumed.cancelAndJoin()
    }

    @Test fun coalescingHonorsUtf8ByteLimitWithoutSplittingOrReorderingPackets() = runTest {
        val batches = mutableListOf<String>()
        val delayed = object : T3Transport by host {
            override suspend fun call(fn: String, args: JSONObject, timeoutMs: Int?): Any? {
                if (fn != "terminal.write") return host.call(fn, args, timeoutMs)
                batches += args.getString("data")
                delay(150)
                return null
            }
        }
        val session = TerminalSession("thread-a")
        val job = launch { session.collect(delayed) }; runCurrent()
        session.write("a"); runCurrent()
        session.write("é".repeat(32768)); session.write("😀"); session.write("\r")
        advanceTimeBy(450); runCurrent()
        assertEquals(listOf("a", "é".repeat(32768), "😀\r"), batches)
        assertTrue(batches.all { it.toByteArray(Charsets.UTF_8).size <= 65536 })
        job.cancelAndJoin()
    }

    @Test fun cancellingAnInflightWriteLatchesVisibleFailureAndNeverReplays() = runTest {
        val batches = mutableListOf<String>()
        val blocked = object : T3Transport by host {
            override suspend fun call(fn: String, args: JSONObject, timeoutMs: Int?): Any? {
                if (fn != "terminal.write") return host.call(fn, args, timeoutMs)
                batches += args.getString("data")
                awaitCancellation()
            }
        }
        val session = TerminalSession("thread-a")
        val first = launch { session.collect(blocked) }; runCurrent()
        session.write("uncertain"); runCurrent(); session.write("queued")
        first.cancelAndJoin()
        assertTrue(session.state.value.uncertainWrite)
        val resumed = launch { session.collect(blocked) }; runCurrent()
        session.write("later"); advanceTimeBy(1000); runCurrent()
        assertEquals(listOf("uncertain"), batches)
        resumed.cancelAndJoin()
    }

    @Test fun pasteAndArrowFollowTerminalModes() {
        val session = TerminalSession("thread-a")
        assertEquals("\u001b[A", session.arrow('A'))
        session.emulator.feed("\u001b[?1h\u001b[?2004h")
        assertEquals("\u001bOA", session.arrow('A'))
        assertEquals("\u001b[200~日本\u001b[201~", session.paste("日本"))
    }
    @Test fun unsupportedObserveIsExplicitAndNeverFallsBackToAttach() = runTest {
        operate = false; denied = true
        val session = TerminalSession("thread-a")
        session.collect(host)
        assertTrue(session.state.value.error)
        assertFalse(session.state.value.connected)
        assertEquals(1, subscriptions.size); assertTrue(subscriptions.single().first)
        assertFalse(domains.contains("terminal.attach")); assertTrue(writes.isEmpty())
    }
    @Test fun ptyExitStopsReconnectionAndDisablesInput() = runTest {
        exit = true
        val session = TerminalSession("thread-a")
        session.collect(host)
        assertTrue(session.state.value.finished)
        session.write("blocked")
        assertTrue(writes.isEmpty()); assertEquals(1, subscriptions.size)
    }
    @Test fun restoredPageNeverRestartsAnExitedShell() = runTest {
        val session = TerminalSession("thread-a", resumeInitially = true)
        val job = launch { session.collect(host) }; runCurrent()
        assertTrue(subscriptions.single().second.getBoolean("resume"))
        assertFalse(subscriptions.single().second.getBoolean("restartIfNotRunning"))
        job.cancelAndJoin()
    }
}
