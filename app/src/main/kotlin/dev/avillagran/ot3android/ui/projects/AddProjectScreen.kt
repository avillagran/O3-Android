package dev.avillagran.ot3android.ui.projects

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.ui.newchat.NewChatProjectOption

data class ProjectEnvironment(val id: String, val name: String, val connected: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddProjectScreen(environments: List<ProjectEnvironment>, selected: String?, projects: List<NewChatProjectOption>,
    state: ProjectCreationState, onEnvironment: (String) -> Unit, onBrowse: (String) -> Unit,
    onCreate: (String, String, String) -> Unit, onOpen: (NewChatProjectOption) -> Unit,
    onRetry: () -> Unit, onRefresh: () -> Unit, onBack: () -> Unit, onReviewed: () -> Unit = {},
) {
    var step by rememberSaveable(selected) { mutableIntStateOf(0) }
    var source by rememberSaveable(selected) { mutableStateOf("local") }
    var query by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable(selected) { mutableStateOf("") }
    var repository by rememberSaveable(selected) { mutableStateOf("") }
    var path by rememberSaveable(selected) { mutableStateOf("") }
    LaunchedEffect(state.parentPath) { if (state.parentPath.isNotBlank()) path = state.parentPath }
    val locked = state.busy || state.pending
    val back = {
        if (!state.busy) {
            when { step == 2 && source != "local" -> step = 1; step > 0 -> step = 0; else -> onBack() }
        }
    }
    BackHandler(enabled = step > 0 || state.busy) { back() }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.project_add)) },
        navigationIcon = { TextButton(onClick = back, enabled = !state.busy) { Text(stringResource(R.string.action_back)) } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.error != null) Text(state.error, color = MaterialTheme.colorScheme.error)
            if (state.pending) {
                Text(stringResource(R.string.project_pending))
                TextButton(onClick = onRefresh, enabled = !state.busy) { Text(stringResource(R.string.thread_menus_refresh)) }
                if (state.retryable) TextButton(onClick = onRetry, enabled = !state.busy) { Text(stringResource(R.string.thread_resend)) }
                else TextButton(onClick = onReviewed, enabled = !state.busy) { Text(stringResource(R.string.project_reviewed)) }
            }
            if (step == 0) {
                Text(stringResource(R.string.project_environment), style = MaterialTheme.typography.titleMedium)
                environments.forEach { environment ->
                    FilterChip(selected = environment.id == selected, onClick = { onEnvironment(environment.id) }, enabled = environment.connected && !state.busy,
                        label = { dev.avillagran.ot3android.ui.kit.HostBadge(environment.name, environment.id) }, modifier = Modifier.testTag("project_environment_${environment.id}"))
                }
                OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.project_search)) }, modifier = Modifier.fillMaxWidth().testTag("project_search"))
                val tokens = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
                val existing = projects.filter { project -> (selected == null || project.environmentId == selected) &&
                    tokens.all { token -> listOf(project.projectTitle, project.workspaceRoot.orEmpty(), project.environmentLabel).joinToString(" ").lowercase().contains(token) } }
                existing.forEach { project ->
                    ListItem(headlineContent = { dev.avillagran.ot3android.ui.kit.HostProjectTitle(project.projectTitle, project.environmentId) }, supportingContent = { Text(project.workspaceRoot.orEmpty()) },
                        trailingContent = { dev.avillagran.ot3android.ui.kit.HostBadge(project.environmentLabel, project.environmentId) }, modifier = Modifier.fillMaxWidth().clickable(enabled = project.connected && project.writable && !state.busy) { onOpen(project) }
                            .testTag("project_existing_${project.environmentId}/${project.projectId}"))
                }
                if (existing.isEmpty()) Text(stringResource(R.string.project_none))
                if (selected != null) {
                    HorizontalDivider()
                    state.sources.forEach { option ->
                        TextButton(enabled = option.ready && !locked, modifier = Modifier.fillMaxWidth().testTag("project_source_${option.id}"), onClick = {
                            source = option.id
                            when (source) {
                                "new" -> step = 3
                                "local" -> { step = 2; path = state.baseDirectory; onBrowse(path) }
                                else -> step = 1
                            }
                        }) {
                            Text(projectSourceLabel(option.id) + if (!option.ready) " · " + stringResource(R.string.project_setup) else "")
                        }
                    }
                }
            } else {
                Text(environments.firstOrNull { it.id == selected }?.name.orEmpty(), style = MaterialTheme.typography.labelLarge)
                Text(projectSourceLabel(source), style = MaterialTheme.typography.titleMedium)
                when (step) {
                    3 -> {
                        OutlinedTextField(name, { name = it.take(200) }, label = { Text(stringResource(R.string.project_name)) }, enabled = !locked, modifier = Modifier.fillMaxWidth().testTag("project_name"))
                        Text(state.newProjectsRoot)
                        Button(onClick = { onCreate(source, name, "") }, enabled = name.isNotBlank() && !locked, modifier = Modifier.testTag("project_create")) { Text(stringResource(R.string.project_new)) }
                    }
                    1 -> {
                        OutlinedTextField(repository, { repository = it }, label = { Text(if (source == "url") "https://host/owner/repository.git" else "owner/repository") }, enabled = !locked, modifier = Modifier.fillMaxWidth().testTag("project_repository"))
                        Button(onClick = { step = 2; path = state.baseDirectory; onBrowse(path) }, enabled = repository.isNotBlank() && !locked) { Text(stringResource(R.string.project_choose_folder)) }
                    }
                    2 -> {
                        Row {
                            TextButton(onClick = { path = state.parentPath.trimEnd('/').substringBeforeLast('/', "").ifBlank { "/" }; onBrowse(path) }, enabled = state.parentPath.isNotBlank() && state.parentPath != "/" && !locked) { Text("↑") }
                            OutlinedTextField(path, { path = it }, enabled = !locked, label = { Text(stringResource(R.string.project_folder)) }, modifier = Modifier.weight(1f).testTag("project_path"))
                            TextButton(onClick = { onBrowse(path) }, enabled = path.isNotBlank() && !locked) { Text(stringResource(R.string.project_browse)) }
                        }
                        Text(state.parentPath)
                        state.folders.forEach { folder ->
                            TextButton(onClick = { path = folder.path; onBrowse(path) }, enabled = !locked, modifier = Modifier.fillMaxWidth()) { Text(folder.name) }
                        }
                        if (source != "local") Text(state.parentPath.trimEnd('/') + "/" + runCatching { ProjectCreation.cloneName(repository) }.getOrDefault(""))
                        val samePath = path.trim().trimEnd('/') == state.parentPath.trimEnd('/')
                        Button(onClick = { onCreate(source, repository, state.parentPath) }, enabled = state.parentPath.isNotBlank() && samePath && !locked,
                            modifier = Modifier.testTag("project_open_folder")) { Text(stringResource(if (source == "local") R.string.project_open else R.string.project_clone)) }
                    }
                }
            }
            if (state.error != null && !state.pending) TextButton(onClick = onRefresh, enabled = !state.busy) { Text(stringResource(R.string.thread_menus_refresh)) }
        }
    }
}

@Composable
private fun projectSourceLabel(id: String): String = when (id) {
    "new" -> stringResource(R.string.project_new)
    "local" -> stringResource(R.string.project_local)
    "url" -> "Git URL"
    else -> stringResource(R.string.project_provider, when (id) {
        "github" -> "GitHub"; "gitlab" -> "GitLab"; "gitcafe" -> "GitCafe"; "forgejo" -> "Forgejo"; "bitbucket" -> "Bitbucket"; else -> "Azure DevOps"
    })
}
