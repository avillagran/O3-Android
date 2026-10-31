package dev.avillagran.ot3android.core.maintenance

import dev.avillagran.ot3android.core.transport.T3Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.lang.reflect.Proxy

class MaintenanceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val host = MaintenanceHost("environment", "https://host", "viewer")
    private class Fixture {
        var authenticated = true
        var scopes = listOf("orchestration:read", "orchestration:operate")
        var functions = listOf("scheduledTasks.list", "scheduledTasks.runNow", "scheduledTasks.delete")
        var fail = false
        var cancel = false
        var writes = 0
        var readGate: CompletableDeferred<Unit>? = null
        var response = JSONObject("""{"tasks":[{"id":"one","title":"Task"}]}""")
        val transport = Proxy.newProxyInstance(T3Transport::class.java.classLoader, arrayOf(T3Transport::class.java)) { _, method, args ->
            when (method.name) {
                "authSession" -> JSONObject().put("authenticated", authenticated).put("scopes", JSONArray(scopes))
                "describe" -> JSONObject().put("functions", JSONArray(functions.map { fn -> JSONObject().put("fn", fn)
                    .put("scope", if (fn.endsWith("list")) "orchestration:read" else "orchestration:operate") }))
                "call" -> {
                    val fn = args!![0] as String
                    if (!fn.endsWith("list")) { writes++; if (cancel) throw CancellationException(); if (fail) error("timeout") }
                    if (fn.endsWith("list") && fail) error("offline")
                    JSONObject(response.toString())
                }
                else -> error(method.name)
            }
        } as T3Transport
    }
    private fun controller(f: Fixture, receipts: MaintenanceReceipts = FileMaintenanceReceipts(temporary.newFolder())) =
        MaintenanceController(host, MaintenancePage.SCHEDULES, f.transport, receipts)

    @Test fun registryAndPermissionBothGateMutations() = runTest {
        val f = Fixture(); f.scopes = listOf("orchestration:read")
        val c = controller(f); c.refresh()
        assertTrue(c.state.value.readable); assertTrue(c.state.value.writable.isEmpty())
        f.scopes += "orchestration:operate"; f.functions = listOf("scheduledTasks.list")
        c.refresh(); assertTrue(c.state.value.writable.isEmpty())
    }
    @Test fun unauthenticatedCannotReadOrWrite() = runTest {
        val f = Fixture(); f.authenticated = false
        val c = controller(f); c.refresh(); assertFalse(c.state.value.readable)
    }
    @Test fun staleReportCannotDispatch() = runTest {
        val f = Fixture(); val c = controller(f)
        assertTrue(runCatching { c.mutate("scheduledTasks.runNow", JSONObject(), true) }.isFailure)
        assertEquals(0, f.writes)
    }
    @Test fun failureMakesOldReportStale() = runTest {
        val f = Fixture(); val c = controller(f); c.refresh(); f.fail = true; c.refresh()
        assertTrue(c.state.value.stale); assertTrue(c.state.value.failed); assertTrue(c.state.value.writable.isEmpty())
    }
    @Test fun unknownSurvivesRefreshAndControllerRecreation() = runTest {
        val f = Fixture(); val receipts = FileMaintenanceReceipts(temporary.newFolder())
        val c = controller(f, receipts); c.refresh(); f.fail = true
        c.mutate("scheduledTasks.runNow", JSONObject().put("id", "one"), true)
        assertTrue(c.state.value.unknown); f.fail = false; c.refresh(); assertTrue(c.state.value.unknown)
        val reopened = controller(f, receipts); reopened.refresh()
        assertTrue(runCatching { reopened.mutate("scheduledTasks.runNow", JSONObject(), true) }.isFailure)
        assertEquals(1, f.writes)
    }
    @Test fun cancellationKeepsReceipt() = runTest {
        val f = Fixture(); val receipts = FileMaintenanceReceipts(temporary.newFolder()); val c = controller(f, receipts)
        c.refresh(); f.cancel = true
        assertTrue(runCatching { c.mutate("scheduledTasks.runNow", JSONObject(), true) }.exceptionOrNull() is CancellationException)
        assertTrue(receipts.pending(host, MaintenancePage.SCHEDULES))
    }
    @Test fun successfulMutationRequiresFreshRead() = runTest {
        val f = Fixture(); val c = controller(f); c.refresh(); c.mutate("scheduledTasks.delete", JSONObject().put("id", "one"), true)
        assertTrue(c.state.value.stale); assertFalse(c.state.value.unknown)
        c.refresh(); assertFalse(c.state.value.stale)
    }
    @Test fun confirmationAndFreshAuthorizationRequired() = runTest {
        val f = Fixture(); val c = controller(f); c.refresh()
        assertTrue(runCatching { c.mutate("scheduledTasks.delete", JSONObject(), false) }.isFailure)
        f.authenticated = false; c.mutate("scheduledTasks.delete", JSONObject(), true)
        assertEquals(0, f.writes)
    }
    @Test fun closedControllerCannotBeReused() = runTest {
        val f = Fixture(); val c = controller(f); c.refresh(); c.close(); c.refresh()
        assertTrue(runCatching { c.mutate("scheduledTasks.delete", JSONObject(), true) }.isFailure)
        assertEquals(0, f.writes)
    }
    @Test fun receiptIsolationIncludesOriginViewerAndPage() {
        val r = FileMaintenanceReceipts(temporary.newFolder()); r.reserve(host, MaintenancePage.STORAGE, "cleanup", JSONObject())
        assertFalse(r.pending(host.copy(viewer = "other"), MaintenancePage.STORAGE))
        assertFalse(r.pending(host.copy(origin = "https://other"), MaintenancePage.STORAGE))
        assertFalse(r.pending(host, MaintenancePage.SCHEDULES))
        assertTrue(runCatching { r.reserve(host, MaintenancePage.STORAGE, "cleanup", JSONObject()) }.isFailure)
    }
    @Test fun localSchedulersAndVaultMutationsHaveNoActions() {
        listOf(MaintenancePage.MEMORY, MaintenancePage.VAULT, MaintenancePage.HOST_SYNC).forEach {
            assertNull(it.read); assertTrue(MaintenanceController.actions(it).isEmpty())
        }
    }

    @Test fun completionAfterCloseIsDiscarded() = runTest {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val transport = object : T3Transport by f.transport {
            override suspend fun call(fn: String, args: JSONObject, timeoutMs: Int?): Any? {
                entered.complete(Unit); finish.await(); return JSONObject("{\"tasks\":[]}")
            }
        }
        val c = MaintenanceController(host, MaintenancePage.SCHEDULES, transport, FileMaintenanceReceipts(temporary.newFolder()))
        val job = launch { c.refresh() }; entered.await(); c.close(); finish.complete(Unit); job.join()
        assertFalse(c.state.value.readable); assertTrue(c.state.value.stale)
    }

    @Test fun archiveRejectsChangedRowBeforeDispatch() = runTest {
        var reads = 0; var writes = 0
        val f = Fixture()
        f.functions = listOf("orchestration.getArchivedShellSnapshot", "orchestration.dispatchCommand")
        val transport = object : T3Transport by f.transport {
            override suspend fun describe() = JSONObject().put("functions", JSONArray(f.functions.map {
                JSONObject().put("fn", it).put("scope", if (it.endsWith("dispatchCommand")) "dynamic" else "orchestration:read")
            }))
            override suspend fun call(fn: String, args: JSONObject, timeoutMs: Int?): Any? {
                if (fn.endsWith("dispatchCommand")) { writes++; return JSONObject() }
                reads++
                return JSONObject().put("snapshotSequence", reads).put("threads", JSONArray().put(JSONObject()
                    .put("id", "one").put("projectId", "p").put("archivedAt", "date")
                    .put("title", if (reads == 1) "Old" else "Changed")))
            }
        }
        val c = MaintenanceController(host, MaintenancePage.ARCHIVE, transport, FileMaintenanceReceipts(temporary.newFolder()))
        c.refresh()
        c.mutate("orchestration.dispatchCommand", JSONObject().put("command", JSONObject().put("type", "thread.unarchive").put("threadId", "one")), true)
        assertEquals(0, writes); assertTrue(c.state.value.stale); assertFalse(c.state.value.unknown)
    }

    @Test fun archiveSnapshotsBeforeAcknowledgementDoNotConfirmSuccess() = runTest {
        val f = Fixture(); var dispatched = false
        val original = JSONObject("""{"snapshotSequence":1,"threads":[{"id":"one","projectId":"p","title":"A","archivedAt":"date"}]}""")
        val transport = object : T3Transport by f.transport {
            override suspend fun describe() = JSONObject().put("functions", JSONArray().put(JSONObject()
                .put("fn", "orchestration.getArchivedShellSnapshot").put("scope", "orchestration:read"))
                .put(JSONObject().put("fn", "orchestration.dispatchCommand").put("scope", "dynamic")))
            override suspend fun call(fn: String, args: JSONObject, timeoutMs: Int?): Any? {
                if (fn.endsWith("dispatchCommand")) { dispatched = true; return JSONObject().put("sequence", 10) }
                return if (dispatched) JSONObject("""{"snapshotSequence":2,"threads":[]}""") else JSONObject(original.toString())
            }
            override suspend fun shell() = JSONObject("""{"snapshotSequence":2,"threads":[{"id":"one","projectId":"p"}]}""")
        }
        val c = MaintenanceController(host, MaintenancePage.ARCHIVE, transport, FileMaintenanceReceipts(temporary.newFolder()))
        c.refresh(); c.mutate("orchestration.dispatchCommand", JSONObject().put("command", JSONObject()
            .put("type", "thread.unarchive").put("threadId", "one")), true)
        assertTrue(dispatched); assertTrue(c.state.value.unknown)
    }

    @Test fun unavailableTelemetryDoesNotReplaceProcessReport() = runTest {
        val f = Fixture()
        val transport = object : T3Transport by f.transport {
            override suspend fun authSession() = JSONObject("""{"authenticated":true,"scopes":["diagnostics:read"]}""")
            override suspend fun describe() = JSONObject().put("functions", JSONArray(listOf("server.getProcessDiagnostics", "server.getResourceTelemetryHistory").map {
                JSONObject().put("fn", it).put("scope", "diagnostics:read")
            }))
            override suspend fun call(fn: String, args: JSONObject, timeoutMs: Int?): Any? {
                if (fn.endsWith("History")) error("unsupported")
                return JSONObject("""{"processCount":2,"processes":[]}""")
            }
        }
        val c = MaintenanceController(host, MaintenancePage.DIAGNOSTICS, transport, FileMaintenanceReceipts(temporary.newFolder()))
        c.refresh(); assertFalse(c.state.value.failed)
        val data = JSONObject(c.state.value.data)
        assertEquals(2, data.getInt("processCount")); assertTrue(data.getBoolean("telemetryUnavailable"))
    }
}
