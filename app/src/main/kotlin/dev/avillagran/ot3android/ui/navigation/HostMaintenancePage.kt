package dev.avillagran.ot3android.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.appGraph
import dev.avillagran.ot3android.core.maintenance.*
import dev.avillagran.ot3android.core.settings.*
import dev.avillagran.ot3android.ui.settings.*
import dev.avillagran.ot3android.ui.settings.general.*
import dev.avillagran.ot3android.ui.settings.maintenance.MaintenanceScreen
import dev.avillagran.ot3android.ui.settings.storage.StoragePolicyScreen
import kotlinx.coroutines.flow.flowOf

/** No selected-host fallback: destination state belongs to one credential and connection. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HostMaintenancePage(navigator: Ot3Navigator, route: HostMaintenance,
    repository: dev.avillagran.ot3android.core.data.EnvironmentRepository = LocalContext.current.appGraph.environmentRepository,
    connections: dev.avillagran.ot3android.core.connection.ConnectionManager = LocalContext.current.appGraph.connectionManager,
    receipts: MaintenanceReceipts = LocalContext.current.appGraph.maintenanceReceipts,
) {
    val page = MaintenancePage.valueOf(route.page)
    val environments by repository.environments.collectAsStateWithLifecycle(initialValue = emptyList())
    var environment by rememberSaveable { mutableStateOf(route.environmentId) }
    var project by rememberSaveable { mutableStateOf(route.projectId) }
    var policy by rememberSaveable { mutableStateOf(false) }
    val selected = environments.find { it.id == environment }
    val supervisor by key(environment) {
        produceState<dev.avillagran.ot3android.core.connection.ConnectionSupervisor?>(null) {
            value = environment?.let { connections.supervisor(it)?.also(connections::startIfCurrent) }
        }
    }
    val connection by key(environment, supervisor) {
        (supervisor?.connection ?: flowOf(null)).collectAsStateWithLifecycle(initialValue = null)
    }
    val shell by key(environment, supervisor) {
        (supervisor?.shell ?: flowOf(null)).collectAsStateWithLifecycle(initialValue = null)
    }
    val authority by key(environment) {
        (environment?.let(repository::cacheAuthorities) ?: flowOf(null)).collectAsStateWithLifecycle(initialValue = null)
    }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(dev.avillagran.ot3android.ui.settings.maintenance.title(page))) },
        navigationIcon = { TextButton(onClick = { navigator.back() }) { Text(stringResource(R.string.action_back)) } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ScopeChoice(stringResource(R.string.preferences_all_environments), environment,
                environments.map { PreferencesScopeOption(it.id, it.label) }) { environment = it; project = null }
            val current = connection
            val identity = authority
            if (selected == null || current == null || identity == null) {
                Text(stringResource(if (selected == null) R.string.preferences_all_environments else R.string.ot3_git_offline))
            } else key(selected.id, selected.baseUrl, identity, current, page, project, policy) {
                val host = MaintenanceHost(selected.id, selected.baseUrl, identity)
                Text(selected.label)
                if (page == MaintenancePage.STORAGE) {
                    Row {
                        TextButton(onClick = { policy = false }) { Text(stringResource(R.string.ot3_inspector_details)) }
                        TextButton(onClick = { policy = true }) { Text(stringResource(R.string.preferences_storage)) }
                    }
                    if (policy) {
                        ScopeChoice(stringResource(R.string.preferences_all_projects), project,
                            shell?.projects.orEmpty().map { PreferencesScopeOption(it.id, it.title ?: it.id) }) { project = it }
                        val settings = remember { ScopedSettings(connections = SettingsConnections { id ->
                            current.transport.takeIf { id == selected.id && supervisor?.connection?.value === current }
                        }) }
                        val controller = remember { StoragePolicyController(host, settings, receipts, project) }
                        StoragePolicyScreen(controller, Modifier.weight(1f))
                    } else {
                        val controller = remember { MaintenanceController(host, page, current.transport, receipts) }
                        MaintenanceScreen(controller, Modifier.weight(1f))
                    }
                } else {
                    val controller = remember { MaintenanceController(host, page, current.transport, receipts) }
                    MaintenanceScreen(controller, Modifier.weight(1f))
                }
            }
        }
    }
}
