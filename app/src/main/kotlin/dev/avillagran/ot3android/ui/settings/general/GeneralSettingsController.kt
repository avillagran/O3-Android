package dev.avillagran.ot3android.ui.settings.general

import dev.avillagran.ot3android.core.settings.ScopedSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

data class GeneralSettingsState(val scope: String = "", val page: String = "{}", val busy: Boolean = false, val failed: Boolean = false)

/** One controller per displayed page. P02 changes scope by calling load, never by mutating JSON. */
class GeneralSettingsController(private val settings: ScopedSettings) {
    private val mutable = MutableStateFlow(GeneralSettingsState())
    val state = mutable.asStateFlow()
    private val writes = Mutex()
    private var generation = 0L
    suspend fun load(scope: JSONObject) {
        val selected = scope.toString()
        val version = ++generation
        mutable.value = GeneralSettingsState(scope = selected, busy = true)
        try {
            val page = settings.read(JSONObject(selected))
            if (version == generation) mutable.value = GeneralSettingsState(selected, page.toString())
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { if (version == generation) mutable.value = GeneralSettingsState(selected, failed = true) }
    }
    suspend fun change(key: String, value: Any?, inherit: Boolean = false) {
        if (mutable.value.busy) return
        writes.withLock {
        val before = mutable.value
        if (before.busy || before.failed || !JSONObject(before.page).optBoolean("writable")) return@withLock
        val version = ++generation
        mutable.value = before.copy(busy = true)
        try {
            val page = settings.write(JSONObject(before.scope), JSONObject().put("key", key)
                .put("action", if (inherit) "clear" else "update").put("value", value ?: JSONObject.NULL))
            if (version == generation) mutable.value = before.copy(page = page.toString(), busy = false)
        } catch (e: CancellationException) {
            if (version == generation) mutable.value = before.copy(page = "{}", failed = true, busy = false)
            throw e
        } catch (_: Exception) {
            if (version == generation) mutable.value = before.copy(page = "{}", failed = true, busy = false)
        }
        }
    }
}
