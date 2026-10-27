package dev.avillagran.ot3android.core.home

import dev.avillagran.ot3android.core.shell.RowTime
import dev.avillagran.ot3android.core.transport.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import dev.avillagran.ot3android.core.thread.*
import kotlinx.coroutines.flow.first
import dev.avillagran.t3.T3Exception
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ThreadManagementTest {
    private val row = HomeRow(
        key = "e1/t1", environmentId = "e1", threadId = "t1", title = "Fix", projectId = "p1", projectKey = "e1/p1",
        projectTitle = "Alpha", environmentLabel = null, branch = "main", path = "/w/alpha", worktree = false, detail = "", state = "idle",
        statusLabel = "", statusRole = "muted", time = RowTime("age", 0, "—"), pinned = false, settled = false, snoozed = false,
        working = false, depth = 0, subagent = false, identity = null, canSnooze = false, autoSettleDisabled = false,
        style = RowStyle.Card, swipe = SwipeActions.None,
    )


    private val outbox = OutboxRepository(MemoryOutboxDao())
    private val sender = MessageSender(outbox, CoroutineScope(Dispatchers.Unconfined))
    private suspend fun execute(transport: T3Transport, row: HomeRow, action: ThreadAction,
        title: String? = null, until: String? = null, confirmed: Boolean = false) =
        ThreadManagement.execute(transport, row, action, title, until, confirmed, sender)

    private val config = JSONObject("""{"environment":{"capabilities":{"threadPinning":true,"threadSettlement":true,"threadSnooze":true}}}""")
    private val write = JSONObject("""{"authenticated":true,"permissions":["orchestration:operate"]}""")

    @Test fun capabilityAndPermissionAreBothRequiredAndUnknownFailsClosed() {
        val catalog = JSONObject("""{"capabilities":{"threadPinning":true,"threadSnooze":false}}""")
        assertTrue(ThreadManagement.access(catalog, write).canOperate)
        assertEquals(setOf(ServerCapability.ThreadPinning), ThreadManagement.access(catalog, write).capabilities)
        assertFalse(ThreadManagement.access(catalog, JSONObject()).canOperate)
        assertFalse(ThreadManagement.access(catalog, JSONObject("""{"authenticated":true,"permissions":["orchestration:read"]}""")).canOperate)
        assertFalse(ThreadManagement.access(catalog, JSONObject("""{"authenticated":"true","permissions":["orchestration:operate"]}""")).canOperate)
    }

    private suspend fun transport(session: JSONObject = write): Pair<T3Transport, FakeT3Transport> {
        assumeHostJni()
        val fake = FakeT3Transport()
        fake.connect(ConnectRoute.Bearer("http://fixture", "fixture"))
        fake.onCall("server.getConfig") { config }
        fake.onCall("orchestration.dispatchCommand") { JSONObject().put("sequence", 1) }
        return object : T3Transport by fake {
            override suspend fun authSession() = session
            override suspend fun domain(fn: String, args: JSONObject): Any? = DomainEngine.shared.domain(fn, args)
        } to fake
    }

    @Test fun nativeBuildersDispatchPinUnpinAndLifecycleWithCommandIds() = runBlocking {
        val (transport, fake) = transport()
        execute(transport, row, ThreadAction.Pin)
        execute(transport, row.copy(pinned = true), ThreadAction.Unpin)
        execute(transport, row, ThreadAction.MarkSettled)
        execute(transport, row.copy(settled = true), ThreadAction.MoveToActive)
        execute(transport, row.copy(snoozed = true), ThreadAction.WakeNow)
        execute(transport, row.copy(canSnooze = true), ThreadAction.Snooze, until = "2099-01-01T09:00:00Z")
        val commands = fake.calls.filter { it.fn == "orchestration.dispatchCommand" }.map { it.args }
        assertEquals(listOf("thread.pin", "thread.unpin", "thread.settle", "thread.unsettle", "thread.unsnooze", "thread.snooze"), commands.map { it.getString("type") })
        assertEquals(6, commands.map { it.getString("commandId") }.toSet().size)
        assertEquals("user", commands[3].getString("reason"))
        assertEquals("user", commands[4].getString("reason"))
    }

    @Test fun readOnlyAndWorkingArchiveAndUnconfirmedDeleteNeverDispatch() = runBlocking {
        val (readOnly, readonlyFake) = transport(JSONObject("""{"authenticated":true,"scopes":["orchestration:read"]}"""))
        assertTrue(runCatching { execute(readOnly, row, ThreadAction.Pin) }.isFailure)
        assertTrue(readonlyFake.calls.none { it.fn == "orchestration.dispatchCommand" })
        val (transport, fake) = transport()
        assertTrue(runCatching { execute(transport, row.copy(working = true), ThreadAction.Archive, confirmed = true) }.isFailure)
        assertTrue(runCatching { execute(transport, row, ThreadAction.Delete) }.isFailure)
        assertTrue(fake.calls.none { it.fn == "orchestration.dispatchCommand" })
        execute(transport, row, ThreadAction.Delete, confirmed = true)
        assertEquals("thread.delete", fake.calls.last().args.getString("type"))
    }

    @Test fun rejectedAndUnknownOutcomesAreNotRetried() = runBlocking {
        val (transport, fake) = transport()
        fake.onCall("orchestration.dispatchCommand") { throw T3Exception("rpc_outcome_unknown") }
        val result = execute(transport, row, ThreadAction.Pin)
        assertTrue(result is SendResult.OutcomeUnknown)
        val saved = outbox.observeThread(row.environmentId, row.threadId).first().single()
        assertEquals(saved.commandJson, fake.calls.last().args.toString())
        assertEquals(1, fake.calls.count { it.fn == "orchestration.dispatchCommand" })
        fake.onCall("orchestration.dispatchCommand") { JSONObject().put("sequence", 2) }
        sender.retry(transport, saved.commandId)
        assertEquals(saved.commandJson, fake.calls.last().args.toString())
        assertTrue(outbox.observeThread(row.environmentId, row.threadId).first().isEmpty())
    }
}
