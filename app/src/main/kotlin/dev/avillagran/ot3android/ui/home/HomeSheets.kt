package dev.avillagran.ot3android.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.core.home.FilterOptions
import dev.avillagran.ot3android.core.home.HomeFilter
import dev.avillagran.ot3android.core.home.HomeRow
import dev.avillagran.ot3android.core.home.MenuEntry
import dev.avillagran.ot3android.core.home.ProjectAction
import dev.avillagran.ot3android.core.home.ProjectMenuEntry
import dev.avillagran.ot3android.core.home.SnoozePreset
import dev.avillagran.ot3android.core.home.StatusFilter
import dev.avillagran.ot3android.core.home.ThreadAction

/**
 * The Thread actions sheet: ot3's hover-card header, then the menu's usable items in ot3's
 * order (interim: Copy ▸ Path / Branch / Thread ID and Filter by project; ot3 also lists the
 * disabled ones, greyed out). A submenu is shown as "Copy ▸ Path".
 */
@Composable
fun ThreadActionsContent(row: HomeRow, menu: List<MenuEntry>, onAction: (ThreadAction) -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag(SheetTags.THREAD_ACTIONS)) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(row.title ?: stringResource(R.string.ot3_row_untitled), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val details = listOfNotNull(row.projectTitle, row.environmentLabel, row.branch).joinToString(" · ")
            if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.labelSmall)
        }
        HorizontalDivider()
        var group: Int? = null
        menu.filter { it.available }.forEach { entry ->
            if (group != null && entry.group != group) HorizontalDivider(Modifier.padding(vertical = 4.dp))
            group = entry.group
            if (entry.children.isEmpty()) {
                SheetItem(threadActionText(entry.action, row), SheetTags.action(entry.action)) { onAction(entry.action) }
            } else {
                val parent = threadActionText(entry.action, row)
                entry.children.filter { it.available }.forEach { child ->
                    SheetItem("$parent ▸ ${threadActionText(child.action, row)}", SheetTags.action(child.action)) { onAction(child.action) }
                }
            }
        }
    }
}

/** The Project actions sheet: ot3's project menu, usable items only (interim: Copy Path). */
@Composable
fun ProjectActionsContent(title: String?, menu: List<ProjectMenuEntry>, onAction: (ProjectAction) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag(SheetTags.PROJECT_ACTIONS)) {
        title?.let { Text(it, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
        menu.filter { it.enabled }.forEach { entry ->
            SheetItem(projectActionText(entry.action), SheetTags.project(entry.action)) { onAction(entry.action) }
        }
    }
}

/** Thread list options: Status with counts, Project, and Environment when more than one is saved. */
@Composable
fun ThreadListOptionsContent(
    options: FilterOptions,
    filter: HomeFilter,
    onStatus: (StatusFilter) -> Unit,
    onProject: (String?) -> Unit,
    onEnvironment: (String?) -> Unit,
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag(SheetTags.LIST_OPTIONS)) {
        SectionTitle(stringResource(R.string.ot3_filter_status))
        options.statuses.forEach { (status, count) ->
            Choice("${statusFilterText(status)}  $count", filter.status == status, SheetTags.status(status)) { onStatus(status) }
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        SectionTitle(stringResource(R.string.ot3_filter_project))
        Choice(stringResource(R.string.ot3_filter_all_projects), filter.projectKey == null, SheetTags.project(null)) { onProject(null) }
        options.projects.forEach { project ->
            val label = listOfNotNull(project.title, project.environmentLabel).joinToString(" · ")
            Choice(label, filter.projectKey == project.key, SheetTags.project(project.key)) { onProject(project.key) }
        }
        if (options.environments.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            SectionTitle(stringResource(R.string.mobile_filter_environment))
            Choice(stringResource(R.string.ot3_filter_all_environments), filter.environmentId == null, SheetTags.environment(null)) { onEnvironment(null) }
            options.environments.forEach { env ->
                Choice(env.label, filter.environmentId == env.id, SheetTags.environment(env.id)) { onEnvironment(env.id) }
            }
        }
    }
}

/** Snooze until: ot3's presets. Snoozing mutates, so every choice is disabled until the builders exist. */
@Composable
fun SnoozeUntilContent(presets: List<SnoozePreset>, enabled: Boolean, onChoose: (SnoozePreset) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag(SheetTags.SNOOZE)) {
        SectionTitle(stringResource(R.string.mobile_snooze_until))
        presets.forEach { preset ->
            SheetItem(snoozeText(preset.option), "snooze_${preset.option.name}", enabled) { onChoose(preset) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
}

@Composable
private fun SheetItem(text: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp).testTag(tag),
    )
}

@Composable
private fun Choice(text: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(horizontal = 12.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
    }
}

object SheetTags {
    const val THREAD_ACTIONS = "sheet_thread_actions"
    const val PROJECT_ACTIONS = "sheet_project_actions"
    const val LIST_OPTIONS = "sheet_list_options"
    const val SNOOZE = "sheet_snooze"

    fun action(action: ThreadAction) = "thread_action_${action.name}"

    fun project(action: ProjectAction) = "project_action_${action.name}"

    fun status(status: StatusFilter) = "filter_status_${status.name}"

    fun project(key: String?) = "filter_project_${key ?: "all"}"

    fun environment(id: String?) = "filter_environment_${id ?: "all"}"
}
