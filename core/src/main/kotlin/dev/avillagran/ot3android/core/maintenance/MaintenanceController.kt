package dev.avillagran.ot3android.core.maintenance

import dev.avillagran.ot3android.core.transport.T3Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** One immutable authenticated host binding per controller; P20 replaces it on route/auth changes. */
data class MaintenanceHost(val environment: String, val origin: String, val viewer: String) {
    init { require(environment.isNotBlank() && origin.isNotBlank() && viewer.isNotBlank()) }
}
enum class MaintenancePage(val read: String?) {
    MEMORY(null), STORAGE("diagnostics.storageCleanupReport"), ARCHIVE("orchestration.getArchivedShellSnapshot"),
    SCHEDULES("scheduledTasks.list"), SESSIONS("agentSessions.scan"), DIAGNOSTICS("server.getProcessDiagnostics"), HOST_SYNC(null), VAULT(null),
}
data class MaintenanceState(
    val busy: Boolean = false, val failed: Boolean = false, val stale: Boolean = true,
    val data: String = "{}", val readable: Boolean = false, val writable: Set<String> = emptySet(),
    val unknown: Boolean = false,
)

/** No shell, local cleanup, background scheduler or automatic mutation retry. */
class MaintenanceController(
    val host: MaintenanceHost,
    val page: MaintenancePage,
    private val transport: T3Transport,
    private val receipts: MaintenanceReceipts,
) {
    private val mutable = MutableStateFlow(MaintenanceState(unknown = receipts.pending(host, page)))
    val state = mutable.asStateFlow()
    private val gate = Mutex()
    private var active = true
    fun close() { active = false; mutable.value = MaintenanceState(unknown = receipts.pending(host, page)) }

    suspend fun refresh() = gate.withLock {
        if (!active) return@withLock
        mutable.value = mutable.value.copy(busy = true, stale = true, writable = emptySet())
        try {
            val access = access()
            val read = page.read
            if (read == null || read !in access) {
                if (active) mutable.value = MaintenanceState(unknown = receipts.pending(host, page))
                return@withLock
            }
            val raw = transport.call(read)
            val result = if (raw == null || raw == JSONObject.NULL) JSONObject() else raw as? JSONObject ?: error("invalid_report")
            if (page == MaintenancePage.DIAGNOSTICS && "server.getResourceTelemetryHistory" in access) {
                try {
                    result.put("telemetry", transport.call("server.getResourceTelemetryHistory",
                        JSONObject().put("windowMs", 1800000).put("bucketMs", 60000)))
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { result.put("telemetryUnavailable", true) }
            }
            if (active) mutable.value = MaintenanceState(data = result.toString(), stale = false, readable = true,
                writable = actions(page).intersect(access), unknown = receipts.pending(host, page))
        } catch (e: CancellationException) {
            if (active) mutable.value = mutable.value.copy(busy = false, failed = true)
            throw e
        } catch (_: Exception) {
            if (active) mutable.value = mutable.value.copy(busy = false, failed = true)
        }
    }

    /** A successful refresh never resolves an uncertain mutation: reports are not command receipts. */
    suspend fun mutate(fn: String, args: JSONObject, confirmed: Boolean) = gate.withLock {
        val before = mutable.value
        check(active && confirmed && !before.busy && !before.stale && !before.unknown && fn in before.writable)
        val payload = JSONObject(args.toString())
        val archiveCommand = if (page == MaintenancePage.ARCHIVE) payload.getJSONObject("command") else null
        if (archiveCommand != null) {
            check(archiveCommand.getString("type") in setOf("thread.unarchive", "thread.delete"))
            archiveCommand.put("commandId", "maintenance-${java.util.UUID.randomUUID()}")
        }
        mutable.value = before.copy(busy = true, writable = emptySet())
        var dispatched = false
        try {
            check(fn in access() && active)
            if (archiveCommand != null) {
                val previous = JSONObject(before.data)
                val fresh = transport.call(page.read!!) as JSONObject
                val id = archiveCommand.getString("threadId")
                val expected = row(previous, id) ?: error("archive_changed")
                val observed = row(fresh, id) ?: error("archive_changed")
                check(fresh.getLong("snapshotSequence") >= previous.getLong("snapshotSequence"))
                check(listOf("projectId", "title", "archivedAt", "createdAt").all { expected.opt(it) == observed.opt(it) })
                check(!observed.isNull("archivedAt") && observed.isNull("deletedAt"))
            }
            check(active)
            // Persist before dispatch. An interrupted or timed-out call must survive process death.
            receipts.reserve(host, page, fn, payload)
            dispatched = true
            val acknowledgement = transport.call(fn, payload)
            if (archiveCommand != null) {
                val fresh = transport.call(page.read!!) as JSONObject
                val main = transport.shell()
                val id = archiveCommand.getString("threadId")
                val expected = row(JSONObject(before.data), id)!!
                val minimum = maxOf(JSONObject(before.data).getLong("snapshotSequence"),
                    (acknowledgement as JSONObject).getLong("sequence"))
                check(fresh.getLong("snapshotSequence") >= minimum)
                check(main.getLong("snapshotSequence") >= minimum)
                check(row(fresh, id) == null)
                val restored = row(main, id)
                if (archiveCommand.getString("type") == "thread.unarchive") {
                    check(restored != null && restored.isNull("archivedAt") && restored.opt("projectId") == expected.opt("projectId"))
                } else {
                    check(restored == null)
                    val archived = main.optJSONArray("archivedThreads")
                    check(archived != null && (0 until archived.length()).none {
                        val item = archived.opt(it)
                        item == id || (item is JSONObject && item.optString("id") == id)
                    })
                }
            }
            receipts.complete(host, page)
            if (active) mutable.value = before.copy(busy = false, stale = true, writable = emptySet(), data = "{}")
        } catch (e: CancellationException) {
            if (active) mutable.value = before.copy(busy = false, stale = true, unknown = dispatched, writable = emptySet())
            throw e
        } catch (_: Exception) {
            if (active) mutable.value = before.copy(busy = false, stale = true, failed = true,
                unknown = dispatched || receipts.pending(host, page), writable = emptySet())
        }
    }

    private suspend fun access(): Set<String> {
        val session = transport.authSession()
        if (session.opt("authenticated") != true) return emptySet()
        val permissions = session.optJSONArray("permissions") ?: session.optJSONArray("scopes") ?: return emptySet()
        val scopes = (0 until permissions.length()).map { permissions.optString(it) }.toSet()
        val functions = transport.describe().optJSONArray("functions") ?: return emptySet()
        val supported = (0 until functions.length()).map { functions.getJSONObject(it) }
            .filter { (it.optString("scope") in scopes ||
                (it.optString("fn") == "orchestration.dispatchCommand" && "orchestration:operate" in scopes)) && !it.optBoolean("stream") }
            .map { it.getString("fn") }.toSet()
        if (page != MaintenancePage.STORAGE || "server.runStorageCleanup" !in supported) return supported
        val config = transport.call("server.getConfig") as? JSONObject
        return if (config?.optJSONObject("environment")?.optJSONObject("capabilities")?.opt("storageCleanupRun") == true)
            supported else supported - "server.runStorageCleanup"
    }
    companion object {
        private fun row(snapshot: JSONObject, id: String): JSONObject? {
            val rows = snapshot.optJSONArray("threads") ?: return null
            return (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.find { it.optString("id") == id }
        }
        fun actions(page: MaintenancePage): Set<String> = when (page) {
            MaintenancePage.STORAGE -> setOf("server.runStorageCleanup")
            MaintenancePage.SCHEDULES -> setOf("scheduledTasks.setEnabled", "scheduledTasks.delete", "scheduledTasks.runNow", "scheduledTasks.upsert")
            MaintenancePage.SESSIONS -> setOf("agentSessions.import")
            MaintenancePage.ARCHIVE -> setOf("orchestration.dispatchCommand")
            MaintenancePage.DIAGNOSTICS -> setOf("server.signalProcess")
            else -> emptySet()
        }
    }
}
