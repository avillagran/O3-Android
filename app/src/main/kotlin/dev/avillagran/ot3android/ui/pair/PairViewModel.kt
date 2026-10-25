package dev.avillagran.ot3android.ui.pair

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.avillagran.ot3android.core.pairing.PairingService
import dev.avillagran.ot3android.core.pairing.PairingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Pairing screen state. [link] holds a one-time code: it is never shown back in clear, logged
 * or put in [toString].
 */
data class PairUiState(val link: String = "", val pairing: PairingState = PairingState.Idle) {
    val busy: Boolean get() = pairing is PairingState.Inspecting || pairing is PairingState.Pairing

    override fun toString() = "PairUiState(link=<redacted>, pairing=$pairing)"
}

/** Paste or receive a pairing link, read the server, then pair on the user's confirmation. */
class PairViewModel(private val service: PairingService) : ViewModel() {

    private val link = MutableStateFlow("")

    val state: StateFlow<PairUiState> = combine(link, service.state) { link, pairing -> PairUiState(link, pairing) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PairUiState())

    fun setLink(value: String) {
        if (state.value.busy) return
        link.value = value.trim()
        if (service.state.value !is PairingState.Idle) viewModelScope.launch { service.reset() }
    }

    /** A link from the scanner or a deep link: read its server straight away. */
    fun open(value: String) {
        if (state.value.busy) return
        link.value = value.trim()
        inspect()
    }

    fun inspect() {
        if (state.value.busy || link.value.isEmpty()) return
        val current = link.value
        viewModelScope.launch { service.inspect(current) }
    }

    /** Pairs; [replace] moves a saved environment at another address instead of adding one. */
    fun confirm(replace: Boolean = false) {
        viewModelScope.launch {
            if (service.confirm(replace) is PairingState.Paired) link.value = ""
        }
    }

    /** The screen calls this once it has navigated away from [PairingState.Paired]. */
    fun pairedHandled() {
        viewModelScope.launch { service.reset() }
    }
}
