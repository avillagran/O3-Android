package dev.avillagran.ot3android.ui.home.layouts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.core.home.HomeRow
import dev.avillagran.ot3android.ui.kit.Ot3Icon
import dev.avillagran.ot3android.ui.kit.Ot3IconButton
import dev.avillagran.ot3android.ui.theme.Ot3Theme

enum class TaskBoardStatus { Pending, Working, Waiting, Failed, Done, Snoozed, Closed }
data class TaskBoardColumn(val status: TaskBoardStatus, val rows: List<HomeRow>)

/** Solo presentación: el servidor decide la pertenencia al filtro y el estado de cada hilo. */
fun taskBoardColumns(rows: List<HomeRow>, metadata: SidebarMetadata, chip: String?): List<TaskBoardColumn> {
    val roots = rows.filter { !it.subagent && matchesChip(it, chip, metadata) }
    val sorted = if (roots.all { it.key in metadata.clocks }) roots.sortedWith(
        compareByDescending<HomeRow> { metadata.clocks.getValue(it.key).time(it) }.thenBy { it.key }) else roots
    val columns = TaskBoardStatus.entries.associateWith { mutableListOf<HomeRow>() }
    sorted.forEach { row ->
        val status = when {
            row.snoozed -> TaskBoardStatus.Snoozed
            row.settled -> TaskBoardStatus.Closed
            row.state == "failed" -> TaskBoardStatus.Failed
            row.state == "waiting" -> TaskBoardStatus.Waiting
            row.working || row.state == "working" -> TaskBoardStatus.Working
            row.detail == "done" -> TaskBoardStatus.Done
            else -> TaskBoardStatus.Pending
        }
        columns.getValue(status).add(row)
    }
    return columns.map { (status, members) -> TaskBoardColumn(status, members) }
}

@Composable
internal fun TaskBoard(columns: List<TaskBoardColumn>, collapsed: Set<String>, onToggle: (String) -> Unit,
    modifier: Modifier = Modifier, liveMode: Boolean = false, onLiveMode: (Boolean) -> Unit = {},
    liveContent: @Composable (HomeRow, Boolean, Boolean) -> Unit = { _, _, _ -> }, rowContent: @Composable (HomeRow) -> Unit,
) {
    var expandedKeys by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val horizontal = rememberLazyListState()
    val visibleRows = remember { mutableStateMapOf<String, List<String>>() }
    val visibleColumns by remember { derivedStateOf { horizontal.layoutInfo.visibleItemsInfo.map { it.key.toString() } } }
    val watchKeys = boardLiveWatchKeys(visibleColumns.flatMap { visibleRows[it].orEmpty() }, liveMode, expandedKeys.toSet())
    Column(modifier.testTag("home_task_board")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.sidebar_layout_board), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(end = 8.dp).semantics { heading() })
            FilterChip(selected = liveMode, onClick = { onLiveMode(!liveMode) }, label = { Text(stringResource(R.string.board_live_view)) },
                modifier = Modifier.testTag("task_board_live_mode"))
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val columnWidth = ((maxWidth - 60.dp) / 4).coerceIn(280.dp, 360.dp)
            LazyRow(Modifier.fillMaxSize().testTag("task_board_columns"), state = horizontal,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(columns, key = { it.status.name }) { column ->
                    val key = "board:${column.status.name}"
                    val expanded = key !in collapsed
                    val vertical = rememberLazyListState()
                    DisposableEffect(column.status) { onDispose { visibleRows.remove(column.status.name) } }
                    LaunchedEffect(vertical, expanded) {
                        if (!expanded) visibleRows[column.status.name] = emptyList()
                        else snapshotFlow { vertical.layoutInfo.visibleItemsInfo.map { it.key.toString() } }
                            .collect { visibleRows[column.status.name] = it }
                    }
                    val colors = Ot3Theme.colors
                    val tint = when (column.status) {
                        TaskBoardStatus.Pending -> colors.dim
                        TaskBoardStatus.Working -> colors.working
                        TaskBoardStatus.Waiting -> colors.waiting
                        TaskBoardStatus.Done -> colors.done
                        TaskBoardStatus.Failed -> colors.failed
                        TaskBoardStatus.Snoozed, TaskBoardStatus.Closed -> colors.dim
                    }
                    val label = stringResource(when (column.status) {
                        TaskBoardStatus.Pending -> R.string.task_board_pending
                        TaskBoardStatus.Working -> R.string.task_board_working
                        TaskBoardStatus.Waiting -> R.string.task_board_waiting
                        TaskBoardStatus.Done -> R.string.task_board_done
                        TaskBoardStatus.Failed -> R.string.sidebar_section_failed
                        TaskBoardStatus.Snoozed -> R.string.sidebar_section_snoozed
                        TaskBoardStatus.Closed -> R.string.sidebar_section_settled
                    })
                    val expansion = stringResource(if (expanded) R.string.about_expanded else R.string.about_collapsed)
                    Surface(Modifier.width(columnWidth).fillParentMaxHeight().testTag("task_board_${column.status.name}"),
                        shape = MaterialTheme.shapes.medium, color = colors.surface) {
                        Column(Modifier.fillMaxSize()) {
                            TextButton(onClick = { onToggle(key) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .testTag("task_board_toggle_${column.status.name}").semantics { heading(); stateDescription = expansion }) {
                                Ot3Icon(if (expanded) "chevron-down" else "chevron-right", null, tint = tint)
                                Text(label, Modifier.weight(1f).padding(horizontal = 8.dp), color = tint)
                                Text(column.rows.size.toString(), color = colors.dim)
                            }
                            if (expanded) {
                                if (column.rows.isEmpty()) Text(stringResource(R.string.ot3_home_empty),
                                    modifier = Modifier.padding(16.dp), color = colors.dim)
                                else LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("task_board_list_${column.status.name}"),
                                    state = vertical, contentPadding = PaddingValues(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(column.rows, key = { it.key }) { row ->
                                        val cardExpanded = row.key in expandedKeys
                                        Column(Modifier.fillMaxWidth()) {
                                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                Box(Modifier.weight(1f)) { rowContent(row) }
                                                Ot3IconButton(if (cardExpanded) "chevron-up" else "chevron-down", stringResource(R.string.board_live_view), {
                                                    expandedKeys = if (cardExpanded) expandedKeys - row.key else expandedKeys.takeLast(7) + row.key
                                                }, modifier = Modifier.testTag("board_live_expand_${row.key}"))
                                            }
                                            if (liveMode || cardExpanded) liveContent(row, row.key in watchKeys, cardExpanded)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Igual que Desktop: como máximo 24 lectores, solo para tarjetas dentro del viewport. */
internal fun boardLiveWatchKeys(visible: List<String>, liveMode: Boolean, expanded: Set<String>): Set<String> =
    visible.asSequence().distinct().filter { liveMode || it in expanded }.take(24).toSet()
