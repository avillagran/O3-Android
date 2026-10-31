package dev.avillagran.ot3android.ui.pair

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.avillagran.ot3android.core.pairing.PairingFailure
import dev.avillagran.ot3android.core.pairing.PairingService
import dev.avillagran.ot3android.core.pairing.PairingState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Pairing screen state. [link] holds a one-time code: it is never shown back in clear, logged
 * or put in [toString].
 */
data class PairUiState(
    val link: String = "",
    val pairing: PairingState = PairingState.Idle,
    /** The server [link] points at, read offline by the lib; null while it is not a readable link. */
    val linkOrigin: String? = null,
    val bridge: String? = null,
    val bridgeTarget: dev.avillagran.ot3android.core.bridge.BridgePairingReview.Target? = null,
) {
    val busy: Boolean get() = bridge == "connecting" || pairing is PairingState.Inspecting || pairing is PairingState.Pairing

    override fun toString() = "PairUiState(link=<redacted>, pairing=$pairing)"
}

/** Paste or receive a pairing link, read the server, then pair on the user's confirmation. */
@OptIn(ExperimentalCoroutinesApi::class)
class PairViewModel(
    private val service: PairingService,
    private val pairBridge: (suspend (org.json.JSONObject) -> Boolean)? = null,
    private val bridgeAvailable: () -> Boolean = { false },
) : ViewModel() {
    val bridgeSupported: Boolean get() = bridgeAvailable()
    private val bridge = MutableStateFlow<String?>(null)
    private val review = dev.avillagran.ot3android.core.bridge.BridgePairingReview()
    private val target = MutableStateFlow<dev.avillagran.ot3android.core.bridge.BridgePairingReview.Target?>(null)
    private var bridgeJob: kotlinx.coroutines.Job? = null

    private val link = MutableStateFlow("")

    /** Each link with its origin, read by the lib off the main thread. */
    private val origins = link.mapLatest { value -> value to value.takeIf { it.isNotEmpty() && !BridgeInvitation.isLink(it) }?.let { service.origin(it) } }

    val state: StateFlow<PairUiState> = combine(link, service.state, origins, bridge, target) { link, pairing, source, bridge, target ->
        val (read, origin) = source
        // An origin read for an older link is not shown with a newer one.
        PairUiState(link, pairing, origin.takeIf { read == link }, bridge, target)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PairUiState())

    fun setLink(value: String) {
        if (state.value.busy) return
        review.clear()
        target.value = null
        link.value = value.trim()
        bridge.value = null
        if (service.state.value !is PairingState.Idle) viewModelScope.launch { service.reset() }
    }

    /**
     * A link the user scanned in the app: read its server straight away. A link from outside
     * (a deep link) only fills the field through [setLink] and waits for Continue.
     */
    fun open(value: String) {
        if (state.value.busy) return
        review.clear()
        target.value = null
        link.value = value.trim()
        bridge.value = null
        inspect()
    }

    fun inspect() {
        if (state.value.busy || link.value.isEmpty()) return
        val current = link.value
        if (BridgeInvitation.isLink(current)) {
            if (!bridgeAvailable() || pairBridge == null) { bridge.value = "sdk_unavailable"; return }
            try {
                target.value = review.inspect(BridgeInvitation.decode(current))
                bridge.value = "review"
            } catch (_: Exception) {
                target.value = null
                link.value = ""
                bridge.value = "failed"
            }
            return
        }
        viewModelScope.launch { forgetDeadLink(service.inspect(current)) }
    }

    /** Explicit confirmation of the full peer ID; all-host scope is decided by the issuer. */
    fun confirmBridge(exactPeerId: String, consent: Boolean) {
        if (state.value.busy || pairBridge == null) return
        val (ticket, invitation) = try { review.consume(exactPeerId, consent) } catch (_: Exception) {
            bridge.value = "failed"
            return
        }
        target.value = null
        link.value = ""
        bridge.value = "connecting"
        bridgeJob = viewModelScope.launch {
            try {
                if (!review.isCurrent(ticket)) return@launch
                val approved = pairBridge.invoke(invitation)
                if (review.isCurrent(ticket)) bridge.value = if (approved) "approved" else "pending"
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { if (review.isCurrent(ticket)) bridge.value = "failed" }
        }
    }

    /** Leaving the surface clears the one-use invitation and rejects late completions. */
    fun dismissBridge() {
        review.clear()
        bridgeJob?.cancel()
        bridgeJob = null
        target.value = null
        link.value = ""
        bridge.value = null
    }

    override fun onCleared() {
        review.close()
        bridgeJob?.cancel()
        super.onCleared()
    }

    /** Pairs; [replace] moves a saved environment at another address instead of adding one. */
    fun confirm(replace: Boolean = false) {
        viewModelScope.launch {
            val result = service.confirm(replace)
            if (result is PairingState.Paired) link.value = "" else forgetDeadLink(result)
        }
    }

    /** A link whose code was refused or cannot be used is not kept, not even masked in the field. */
    private fun forgetDeadLink(result: PairingState) {
        if (result is PairingState.Failed && result.failure in DEAD_CODE) link.value = ""
    }

    /** The screen calls this once it has navigated away from [PairingState.Paired]. */
    fun pairedHandled() {
        viewModelScope.launch { service.reset() }
    }

    private companion object {
        val DEAD_CODE = setOf(PairingFailure.Rejected, PairingFailure.InvalidCode)
    }
}
