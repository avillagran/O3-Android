package dev.avillagran.ot3android.ui.usage

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.avillagran.ot3android.core.connection.Connection
import dev.avillagran.ot3android.core.domain.Environment
import dev.avillagran.ot3android.core.usage.UsageReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class UsageState(
    val environments: List<Environment> = emptyList(),
    val environmentId: String? = null,
    val period: String = "week",
    val busy: Boolean = false,
    val offline: Boolean = false,
    val failed: Boolean = false,
    val summary: JSONObject? = null,
)

/** A period/environment switch cancels the old read; reconnect replaces its generation. */
@OptIn(ExperimentalCoroutinesApi::class)
class UsageViewModel(
    environments: Flow<List<Environment>>,
    private val connection: (String) -> Flow<Connection?>,
    private val saved: SavedStateHandle = SavedStateHandle(),
    private val refreshRates: suspend (Connection) -> Unit = { it.transport.call("server.refreshUsageRates", JSONObject()); Unit },
    private val read: suspend (Connection, String) -> JSONObject = { current, period ->
        val zone = TimeZone.getDefault()
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = zone }.format(Date())
        UsageReader.read(current.transport, period, today, zone.id)
    },
) : ViewModel() {
    private val selected = saved.getStateFlow<String?>("environment", null)
    private val period = saved.getStateFlow("period", "week")
    private data class ReadRequest(val sequence: Int = 0, val pricesFor: String? = null)
    private val refresh = MutableStateFlow(ReadRequest())
    private var consumedPriceRequest = -1
    val state = combine(environments, selected, period, refresh) { all, id, window, request ->
        UsageState(all, all.firstOrNull { it.id == id }?.id ?: all.firstOrNull()?.id, window) to request
    }.flatMapLatest { (base, request) ->
        val id = base.environmentId
        if (id == null) flowOf(base) else connection(id).flatMapLatest { current ->
            if (current == null) flowOf(base.copy(offline = true)) else flow {
                emit(base.copy(busy = true))
                try {
                    // Consume this explicit action once. Reconnect/resume must not replay it.
                    if (request.pricesFor == id && request.sequence != consumedPriceRequest) {
                        consumedPriceRequest = request.sequence
                        refreshRates(current)
                    }
                    emit(base.copy(summary = read(current, base.period)))
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { emit(base.copy(failed = true)) }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), UsageState())

    fun selectEnvironment(id: String) { saved["environment"] = id }
    fun selectPeriod(value: String) { saved["period"] = value }
    fun refresh() { refresh.update { ReadRequest(it.sequence + 1) } }
    fun refreshPrices() {
        val current = state.value
        if (current.busy || current.offline) return
        val id = current.environmentId ?: return
        refresh.update { ReadRequest(it.sequence + 1, id) }
    }
}
