package dev.avillagran.ot3android.core.home

import dev.avillagran.ot3android.core.connection.ConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformLatest

/**
 * The header's connection text, T3 Mobile's workspace status
 * (`t3m:src/features/home/workspace-connection-status.ts:19-35`) over every environment.
 * [Hidden] when everything is connected and synced.
 */
sealed interface HeaderStatus {
    /** Whether a spinner goes with the text. */
    val showsProgress: Boolean get() = false

    data object Hidden : HeaderStatus

    /** "You are offline": the device has no network. */
    data object Offline : HeaderStatus

    /** "Syncing threads...": connected, a newer snapshot is on its way. */
    data object Syncing : HeaderStatus {
        override val showsProgress: Boolean get() = true
    }

    /** "Loading threads...": connected, no snapshot yet. */
    data object Loading : HeaderStatus {
        override val showsProgress: Boolean get() = true
    }

    /** "Reconnecting to {environment}". */
    data class Reconnecting(val environmentLabel: String, override val showsProgress: Boolean) : HeaderStatus

    /** "Reconnecting {count} environments". */
    data class ReconnectingMany(val count: Int, override val showsProgress: Boolean) : HeaderStatus

    /** The lib's error code of an environment that will not retry on its own (or `unauthorized`). */
    data class Error(val environmentId: String, val code: String) : HeaderStatus

    /** "Not connected". */
    data object NotConnected : HeaderStatus
}

object HeaderStatusDisplay {

    /** T3 Mobile's `STATUS_SHOW_DELAY_MS`: brief reconnects never show. */
    const val SHOW_DELAY_MS = 800L

    /**
     * The undelayed status of [environments]. [offline] is the device's network state; nothing
     * reports it yet, so the app passes false (no Kotlin guess from error codes).
     */
    fun status(environments: List<HomeEnvironment>, offline: Boolean = false): HeaderStatus {
        if (environments.isEmpty()) return HeaderStatus.Hidden
        if (offline) return HeaderStatus.Offline
        val connected = environments.filter { it.connection is ConnectionState.Connected }
        val ready = connected.any { it.shell != null }
        val pending = connected.any { it.shell == null }
        val loaded = environments.any { it.shell != null }
        val connecting = environments.filter { it.connection.reconnecting() }
        val error = environments.firstNotNullOfOrNull { env -> env.connection.errorCode()?.let { HeaderStatus.Error(env.id, it) } }
        val shows = if (ready) pending else error != null || connecting.isNotEmpty() || pending || (loaded && !ready)
        if (!shows) return HeaderStatus.Hidden
        val progress = (ready || error == null) && (connecting.isNotEmpty() || pending)
        return when {
            ready && pending -> if (loaded) HeaderStatus.Syncing else HeaderStatus.Loading
            connecting.size == 1 -> HeaderStatus.Reconnecting(connecting.single().label, progress)
            connecting.size > 1 -> HeaderStatus.ReconnectingMany(connecting.size, progress)
            error != null -> error
            pending -> if (loaded) HeaderStatus.Syncing else HeaderStatus.Loading
            else -> HeaderStatus.NotConnected
        }
    }

    /** ot3's dot: live when every environment is connected, stale otherwise. */
    fun dot(environments: List<HomeEnvironment>): HeaderDot = when {
        environments.isEmpty() -> HeaderDot.None
        environments.all { it.connection is ConnectionState.Connected } -> HeaderDot.Live
        else -> HeaderDot.Stale
    }

    /**
     * T3 Mobile's delay (`t3m:src/features/home/WorkspaceConnectionTitle.tsx:20,42-52`): a
     * status appears [delayMs] after the header first has one, and from then on follows
     * changes at once; [HeaderStatus.Hidden] shows at once and restarts the wait.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun delayed(statuses: Flow<HeaderStatus>, delayMs: Long = SHOW_DELAY_MS): Flow<HeaderStatus> {
        val visible = statuses.map { it != HeaderStatus.Hidden }.distinctUntilChanged().transformLatest { has ->
            if (has) delay(delayMs)
            emit(has)
        }
        return combine(statuses, visible.onStart { emit(false) }) { status, shown -> if (shown) status else HeaderStatus.Hidden }.distinctUntilChanged()
    }

    /** Connecting, or backing off before an automatic retry. */
    private fun ConnectionState.reconnecting(): Boolean =
        this == ConnectionState.Connecting || (this is ConnectionState.Unreachable && nextRetryAt != null)

    /** An error only the user can fix. */
    private fun ConnectionState.errorCode(): String? = when (this) {
        ConnectionState.Unauthorized -> "unauthorized"
        is ConnectionState.Unreachable -> error.takeIf { nextRetryAt == null }
        else -> null
    }
}
