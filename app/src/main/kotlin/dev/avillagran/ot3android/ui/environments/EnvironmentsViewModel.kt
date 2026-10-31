package dev.avillagran.ot3android.ui.environments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.avillagran.ot3android.core.connection.ConnectionManager
import dev.avillagran.ot3android.core.connection.ConnectionState
import dev.avillagran.ot3android.core.data.EnvironmentRepository
import dev.avillagran.ot3android.core.domain.Environment
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class EnvironmentItem(val environment: Environment, val state: ConnectionState?, val bridgeStatus: String? = null)

/** Saved environments with the state of their supervisor (null until one is created). */
class EnvironmentsViewModel(
    private val repository: EnvironmentRepository,
    private val connections: ConnectionManager,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val ticks = kotlinx.coroutines.flow.flow {
        while (true) {
            emit(clock())
            kotlinx.coroutines.delay(1_000)
        }
    }

    val items: StateFlow<List<EnvironmentItem>?> =
        combine(repository.environments, connections.states, repository.bridgeStates, ticks) { environments, states, origins, now ->
            environments.map { environment -> EnvironmentItem(environment, states[environment.id], origins.find { it.id == environment.id }?.effectiveStatus(now)) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun delete(environmentId: String) {
        viewModelScope.launch {
            connections.forget(environmentId)
            repository.delete(environmentId)
        }
    }
}
