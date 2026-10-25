package dev.avillagran.ot3android.ui.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.avillagran.ot3android.core.connection.ConnectionSupervisor
import dev.avillagran.ot3android.core.connection.ServerDescriptor
import dev.avillagran.ot3android.core.connection.ServerProbe
import dev.avillagran.ot3android.core.connection.normalizeOrigin
import dev.avillagran.ot3android.core.data.EnvironmentRepository
import dev.avillagran.t3.T3Exception
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why the form cannot go on: a missing field, or the lib's error code. */
sealed interface ConnectError {
    data object UrlRequired : ConnectError
    data object TokenRequired : ConnectError
    data class Failed(val code: String) : ConnectError
}

enum class ConnectBusy { Checking, Saving }

/**
 * Connect form state. [token] is held only in memory while the screen lives and is never
 * shown back, logged or put in a [toString].
 */
data class ConnectUiState(
    val editingId: String? = null,
    val url: String = "",
    val token: String = "",
    val label: String = "",
    val hasSavedToken: Boolean = false,
    val busy: ConnectBusy? = null,
    val server: ServerDescriptor? = null,
    val error: ConnectError? = null,
    val savedEnvironmentId: String? = null,
) {
    override fun toString() =
        "ConnectUiState(editingId=$editingId, url=$url, token=<redacted>, label=$label, busy=$busy, error=$error)"
}

/**
 * URL + token entry. "Check server" fetches the descriptor (no credential); "Save and connect"
 * verifies the token with the server (descriptor, connect, shell GET), then stores the
 * environment and its token. [onSaved] lets the connection layer drop a supervisor built
 * from older settings.
 */
class ConnectViewModel(
    private val probe: ServerProbe,
    private val repository: EnvironmentRepository,
    private val editingId: String?,
    private val onSaved: suspend (environmentId: String) -> Unit,
) : ViewModel() {

    private val _state = MutableStateFlow(ConnectUiState(editingId = editingId))
    val state: StateFlow<ConnectUiState> = _state.asStateFlow()

    init {
        if (editingId != null) {
            viewModelScope.launch {
                val environment = repository.find(editingId) ?: return@launch
                val hasToken = repository.token(editingId) != null
                _state.update { it.copy(url = environment.baseUrl, label = environment.label, hasSavedToken = hasToken) }
            }
        }
    }

    fun setUrl(value: String) = _state.update { it.copy(url = value, server = null, error = null) }

    fun setToken(value: String) = _state.update { it.copy(token = value.trim(), error = null) }

    fun setLabel(value: String) = _state.update { it.copy(label = value) }

    fun check() {
        val origin = normalizeOrigin(_state.value.url) ?: return fail(ConnectError.UrlRequired)
        run(ConnectBusy.Checking) {
            val server = probe.check(origin)
            _state.update { it.copy(url = origin, server = server) }
        }
    }

    fun save() {
        val current = _state.value
        val origin = normalizeOrigin(current.url) ?: return fail(ConnectError.UrlRequired)
        if (current.token.isEmpty() && !current.hasSavedToken) return fail(ConnectError.TokenRequired)
        run(ConnectBusy.Saving) {
            val token = current.token.ifEmpty { editingId?.let { repository.token(it) } }
                ?: return@run fail(ConnectError.TokenRequired)
            val server = probe.verify(origin, token)
            val label = current.label.trim().ifEmpty { server.label ?: origin.substringAfter("://") }
            val saved = repository.save(
                id = editingId,
                label = label,
                baseUrl = origin,
                environmentId = server.environmentId,
                token = current.token.ifEmpty { null },
            )
            onSaved(saved.id)
            _state.update { it.copy(url = origin, server = server, token = "", savedEnvironmentId = saved.id) }
        }
    }

    private fun fail(error: ConnectError) = _state.update { it.copy(error = error) }

    private fun run(busy: ConnectBusy, block: suspend () -> Unit) {
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = busy, error = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (error: T3Exception) {
                fail(ConnectError.Failed(error.code))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Not a lib failure (no stable code): say so without the message, which may
                // carry what the user typed.
                fail(ConnectError.Failed(ConnectionSupervisor.INTERNAL_ERROR))
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }
}
