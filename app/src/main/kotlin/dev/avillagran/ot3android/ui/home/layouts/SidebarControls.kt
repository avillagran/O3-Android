package dev.avillagran.ot3android.ui.home.layouts

import androidx.core.graphics.toColorInt
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.core.data.prefs.*
import dev.avillagran.ot3android.core.home.*

internal sealed interface SidebarEntry {
    data class Header(val key: String, val title: String, val count: Int) : SidebarEntry
    data class Item(val value: HomeItem, val section: String = "") : SidebarEntry
}
internal fun matchesChip(row: HomeRow, chip: String?, metadata: SidebarMetadata): Boolean = when {
    chip == null -> true
    chip.startsWith("group:") -> metadata.group(row) == chip.removePrefix("group:")
    else -> (row.projectKey ?: "host:${row.environmentId}") == chip
}
internal fun sidebarEntries(items: List<HomeItem>, settings: AppearanceSettings, grouped: Boolean,
    metadata: SidebarMetadata, chip: String?): List<SidebarEntry> {
    val shelfStart = items.indexOfFirst { it is HomeItem.ShelfHeader }.let { if (it < 0) items.size else it }
    val source = if (settings.interfaceLayout == "projects") items else items.take(shelfStart)
    val rows = source.filterIsInstance<HomeItem.Row>().map { it.row }.filter { matchesChip(it, chip, metadata) }
    val sections = sidebarSections(rows, settings.interfaceLayout, grouped, metadata,
        settings.localValue(LocalAppearancePreference.THREAD_ORDER) == "reply")
    return sections.flatMap { section ->
        (if (section.title.isNotEmpty()) listOf(SidebarEntry.Header(section.key, section.title, section.rows.size)) else emptyList()) +
            section.rows.map { SidebarEntry.Item(HomeItem.Row(it), section.key) }
    } + (if (settings.interfaceLayout == "projects") items.filterNot { it is HomeItem.Row || it is HomeItem.ShelfHeader }
        else items.drop(shelfStart)).filter { it !is HomeItem.Row || matchesChip(it.row, chip, metadata) }.map { SidebarEntry.Item(it) }
}

@Composable
internal fun SidebarControls(state: HomeUiState, settings: AppearanceSettings, metadata: SidebarMetadata,
    selected: String?, onLayout: (String) -> Unit, onHostStyle: (String, HostStyle) -> Unit, onChip: (String?) -> Unit) {
    var styling by rememberSaveable { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Box {
            TextButton(onClick = { menu = true }) { Text(sidebarLabel(settings.interfaceLayout)) }
            DropdownMenu(menu, { menu = false }) {
                listOf("classic", "focus", "projects").forEach { layout ->
                    DropdownMenuItem(text = { Text(sidebarLabel(layout)) }, onClick = { menu = false; onLayout(layout) })
                }
            }
        }
            val hosts = state.header.environments.map { it.id to it.label }.ifEmpty {
                state.rows.values.map { it.environmentId to (it.environmentLabel ?: it.environmentId) }.distinctBy { it.first }
            }
            hosts.forEach { (id, label) ->
                TextButton(onClick = { styling = id }) { Text(label, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            }
        }
        styling?.let { id ->
            val style = metadata.hostStyles[id] ?: HostStyle()
            AlertDialog(onDismissRequest = { styling = null }, title = { Text(id) }, confirmButton = {
                TextButton(onClick = { styling = null }) { Text(stringResource(R.string.sidebar_close)) }
            }, text = { Column {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    listOf("server", "cloud", "linux", "monitor", "laptop", "mac-mini", "mac-studio").forEach { icon ->
                        dev.avillagran.ot3android.ui.kit.Ot3IconButton(HostStyle(icon).displayIcon, icon, { onHostStyle(id, style.copy(icon = icon)) })
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    listOf("", "#e06c75", "#e5a050", "#d8c35a", "#8fc46a", "#4fb8a8", "#5aa2e6", "#9a7be6", "#d877b8").forEach { color ->
                        TextButton(onClick = { onHostStyle(id, style.copy(color = color)) }) {
                            Text(if (color.isEmpty()) stringResource(R.string.sidebar_no_color) else "●",
                                color = if (color.isEmpty()) MaterialTheme.colorScheme.onSurface else androidx.compose.ui.graphics.Color(color.toColorInt()))
                        }
                    }
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(style.showName, { onHostStyle(id, style.copy(showName = it)) })
                    Text(stringResource(R.string.sidebar_show_host_name))
                }
            } })
        }
        if (settings.localValue(LocalAppearancePreference.PROJECT_CHIPS) == "true") {
            val rows = state.rows.values.filter { state.filter.environmentId == null || it.environmentId == state.filter.environmentId }
            val chips = sidebarChips(rows.toList(), metadata)
            if (chips.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected == null, { onChip(null) }, label = { Text(stringResource(R.string.ot3_filter_all_projects)) })
                chips.forEach { chip -> FilterChip(selected == chip.key, {
                    onChip(if (selected == chip.key) null else chip.key)
                }, label = { Text("${chip.title} (${chip.count})") }) }
            }
        }
    }
}

@Composable
internal fun sidebarLabel(key: String): String = when (key) {
    "classic" -> stringResource(R.string.sidebar_layout_classic)
    "focus" -> stringResource(R.string.sidebar_layout_focus)
    "projects" -> stringResource(R.string.sidebar_layout_projects)
    "pinned" -> stringResource(R.string.sidebar_section_pinned)
    "waiting" -> stringResource(R.string.sidebar_section_waiting)
    "working" -> stringResource(R.string.sidebar_section_working)
    "done" -> stringResource(R.string.sidebar_section_done)
    "failed" -> stringResource(R.string.sidebar_section_failed)
    "snoozed" -> stringResource(R.string.sidebar_section_snoozed)
    "settled" -> stringResource(R.string.sidebar_section_settled)
    "idle", "pending" -> stringResource(R.string.sidebar_section_pending)
    else -> key
}

@Composable
internal fun SidebarSectionHeader(title: String, count: Int) {
    Text("${sidebarLabel(title)}${if (count > 0) " ($count)" else ""}",
        style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}
