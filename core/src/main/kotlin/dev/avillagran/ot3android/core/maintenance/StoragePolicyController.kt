package dev.avillagran.ot3android.core.maintenance

import dev.avillagran.ot3android.core.settings.ScopedSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Native scopedSettings owns descriptors, capability checks, validation and environment patch folding. */
class StoragePolicyController(
    val host: MaintenanceHost,
    private val settings: ScopedSettings,
    private val receipts: MaintenanceReceipts,
    val projectId: String? = null,
) {
    private val mutable = MutableStateFlow(MaintenanceState(unknown = receipts.pending(host, MaintenancePage.STORAGE)))
    val state = mutable.asStateFlow()
    private val gate = Mutex()
    private var active = true
    private fun scope() = JSONObject().put("targets", JSONArray().put(JSONObject().put("environment", host.environment).put("projectId", projectId ?: JSONObject.NULL)))
    fun close() { active = false }
    suspend fun refresh() = gate.withLock {
        if (!active) return@withLock
        mutable.value = mutable.value.copy(busy = true, stale = true)
        try {
            val page = settings.read(scope())
            if (active) mutable.value = MaintenanceState(data = page.toString(), readable = true,
                stale = page.optJSONArray("errors")?.length() != 0, unknown = receipts.pending(host, MaintenancePage.STORAGE))
        } catch (e: CancellationException) { if (active) mutable.value = mutable.value.copy(busy = false, failed = true); throw e }
        catch (_: Exception) { if (active) mutable.value = mutable.value.copy(busy = false, failed = true) }
    }
    suspend fun change(key: String, value: Any?) = gate.withLock {
        val before = mutable.value
        check(active && !before.busy && !before.stale && !receipts.pending(host, MaintenancePage.STORAGE))
        check(key == "worktreeCleanup" || key == "worktreesDirectory" || key.startsWith("storageCleanup."))
        val fields = JSONObject(before.data).optJSONArray("fields") ?: error("storage_unavailable")
        check((0 until fields.length()).any { fields.getJSONObject(it).optString("key") == key && fields.getJSONObject(it).optBoolean("scopeWritable") })
        check(JSONObject(before.data).optBoolean("writable"))
        val input = JSONObject().put("key", key).put("action", "update").put("value", value ?: JSONObject.NULL)
        mutable.value = before.copy(busy = true)
        var reserved = false
        try {
            receipts.reserve(host, MaintenancePage.STORAGE, "server.updateSettings", input); reserved = true
            val page = settings.write(scope(), input)
            val errors = page.optJSONArray("errors")
            if (errors == null || errors.length() != 0) {
                if (errors != null && (0 until errors.length()).all { errors.getJSONObject(it).opt("uncertain") == false }) {
                    receipts.complete(host, MaintenancePage.STORAGE); reserved = false
                }
                error("storage_write_unconfirmed")
            }
            receipts.complete(host, MaintenancePage.STORAGE)
            if (active) mutable.value = MaintenanceState(data = page.toString(), readable = true, stale = false)
        } catch (e: CancellationException) {
            if (active) mutable.value = before.copy(busy = false, stale = true, unknown = reserved)
            throw e
        } catch (_: Exception) {
            if (active) mutable.value = before.copy(busy = false, failed = true, stale = true, unknown = reserved)
        }
    }
}
