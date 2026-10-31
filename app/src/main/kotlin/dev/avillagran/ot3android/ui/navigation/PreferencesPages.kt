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
import dev.avillagran.ot3android.ui.settings.*
import dev.avillagran.ot3android.ui.settings.general.*
import dev.avillagran.ot3android.ui.settings.projects.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScopedPreferencesPage(navigator: Ot3Navigator, projects: Boolean, initial: ProjectPreferences = ProjectPreferences()) {
    val graph = LocalContext.current.appGraph
    val environments by graph.environmentRepository.environments.collectAsStateWithLifecycle(initialValue = emptyList())
    var environment by rememberSaveable { mutableStateOf(initial.environmentId) }
    var project by rememberSaveable { mutableStateOf(initial.projectId) }
    val supervisor by key(environment) {
        produceState<dev.avillagran.ot3android.core.connection.ConnectionSupervisor?>(null) {
            value = environment?.let { graph.connectionManager.supervisor(it)?.also(graph.connectionManager::startIfCurrent) }
        }
    }
    val shell by (supervisor?.shell ?: kotlinx.coroutines.flow.flowOf(null)).collectAsStateWithLifecycle(initialValue = null)
    val connection by (supervisor?.connection ?: kotlinx.coroutines.flow.flowOf(null)).collectAsStateWithLifecycle(initialValue = null)
    val mathRendering by graph.mathRenderingEnabled.collectAsStateWithLifecycle()
    val controller = remember { GeneralSettingsController(graph.scopedSettings) }
    val targets = JSONArray()
    environments.filter { environment == null || environment == it.id }.forEach {
        targets.put(JSONObject().put("environment", it.id).put("projectId", project ?: JSONObject.NULL))
    }
    val scope = remember(targets.toString()) { JSONObject().put("targets", targets) }
    val coroutine = rememberCoroutineScope()
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(if (projects) R.string.preferences_projects else R.string.preferences_general)) },
        navigationIcon = { TextButton(onClick = { navigator.back() }) { Text(stringResource(R.string.action_back)) } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PreferencesScopePicker(PreferencesScope(environment, project), environments.map { PreferencesScopeOption(it.id, it.label) },
                shell?.projects.orEmpty().map { PreferencesScopeOption(it.id, it.title ?: it.id) }, emptyList()) {
                environment = it.environment; project = it.project
            }
            if (!projects) {
                ListItem(headlineContent = { Text(stringResource(R.string.parity_math_rendering)) }, trailingContent = {
                    Switch(mathRendering, graph::setMathRenderingEnabled)
                })
                GeneralSettingsScreen(controller, scope, graph.generalClientPreferences, Modifier.weight(1f))
            }
            else {
                val selectedEnvironment = environment
                val selectedProject = project
                val transport = connection?.transport
                if (selectedEnvironment != null && selectedProject != null && transport != null) {
                    key(selectedEnvironment, selectedProject, connection) {
                        val config = remember { ProjectConfiguration(ProjectSettingsRoute(selectedEnvironment, selectedProject), transport) }
                        val state by config.state.collectAsStateWithLifecycle()
                        LaunchedEffect(config) { config.refresh() }
                        ProjectSettingsScreen(state, { coroutine.launch { config.refresh() } }, { name, value, clear -> coroutine.launch { config.update(name, value, clear) } })
                    }
                } else Text(stringResource(if (transport == null && selectedEnvironment != null) R.string.ot3_git_offline else R.string.preferences_all_projects))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SourceControlPreferencesPage(navigator: Ot3Navigator) {
    val context = LocalContext.current
    val graph = context.appGraph
    val settings by graph.diffReviewSettings.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.preferences_source_control)) },
        navigationIcon = { TextButton(onClick = { navigator.back() }) { Text(stringResource(R.string.action_back)) } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ListItem(headlineContent = { Text(stringResource(R.string.ot3_diff_split)) }, trailingContent = {
                Switch(settings.split, { graph.setDiffReviewSettings(settings.copy(split = it)) })
            })
            ListItem(headlineContent = { Text(stringResource(R.string.ot3_diff_wrap)) }, trailingContent = {
                Switch(settings.wordWrap, { graph.setDiffReviewSettings(settings.copy(wordWrap = it)) })
            })
            ListItem(headlineContent = { Text(stringResource(R.string.ot3_git_show_context)) }, trailingContent = {
                Switch(settings.showContext, { graph.setDiffReviewSettings(settings.copy(showContext = it)) })
            })
            TextButton(onClick = { navigator.push(ProjectPreferences()) }) { Text(stringResource(R.string.ot3_action_project_settings)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProvidersPreferencesPage(navigator: Ot3Navigator) {
    val graph = LocalContext.current.appGraph
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val environments by graph.environmentRepository.environments.collectAsStateWithLifecycle(initialValue = emptyList())
    var environment by rememberSaveable { mutableStateOf<String?>(null) }
    val supervisor by key(environment) {
        produceState<dev.avillagran.ot3android.core.connection.ConnectionSupervisor?>(null) {
            value = environment?.let { graph.connectionManager.supervisor(it)?.also(graph.connectionManager::startIfCurrent) }
        }
    }
    val connection by key(environment, supervisor) {
        (supervisor?.connection ?: kotlinx.coroutines.flow.flowOf(null)).collectAsStateWithLifecycle(initialValue = null)
    }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.preferences_providers)) },
        navigationIcon = { TextButton(onClick = { navigator.back() }) { Text(stringResource(R.string.action_back)) } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ScopeChoice(stringResource(R.string.preferences_all_environments), environment, environments.map { PreferencesScopeOption(it.id, it.label) }) { environment = it }
            val selected = environment
            val transport = connection?.transport
            if (selected != null && transport != null) key(selected, connection) {
                val controller = remember { dev.avillagran.ot3android.core.providers.ProviderAdministration(selected, transport) }
                dev.avillagran.ot3android.ui.settings.providers.ProvidersRoute(controller, { navigator.back() }, uriHandler::openUri)
            } else Text(stringResource(if (selected != null) R.string.ot3_git_offline else R.string.preferences_all_environments))
        }
    }
}
