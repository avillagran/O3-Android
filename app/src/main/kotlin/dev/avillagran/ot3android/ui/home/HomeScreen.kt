package dev.avillagran.ot3android.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.core.home.HeaderDot
import dev.avillagran.ot3android.core.home.HomeContent
import dev.avillagran.ot3android.core.home.HomeItem
import dev.avillagran.ot3android.core.home.HomeRow
import dev.avillagran.ot3android.core.home.HomeUiState
import dev.avillagran.ot3android.core.home.MessageMatch
import dev.avillagran.ot3android.core.home.RowStatusDetail
import dev.avillagran.ot3android.core.home.RowStyle
import dev.avillagran.ot3android.core.home.Shelf
import dev.avillagran.ot3android.ui.icons.Ot3Icons

/** Everything Home's UI can ask for; the route turns each into a view-model call or a navigation. */
sealed interface HomeIntent {
    data class SetQuery(val text: String) : HomeIntent
    data object ClearSearch : HomeIntent
    data class OpenRow(val row: HomeRow) : HomeIntent

    /** Long press on a row: the Thread actions sheet. */
    data class RowActions(val row: HomeRow) : HomeIntent

    /** Long press on the project chip or name: the Project actions sheet. */
    data class ProjectActions(val row: HomeRow) : HomeIntent
    data class OpenMessage(val match: MessageMatch) : HomeIntent
    data class ToggleShelf(val shelf: Shelf) : HomeIntent
    data object ShowMoreSettled : HomeIntent
    data object OpenFilters : HomeIntent
    data object ToggleGrouping : HomeIntent
    data object Refresh : HomeIntent
    data object OpenEnvironments : HomeIntent
    data object AddEnvironment : HomeIntent
    data class EditEnvironment(val environmentId: String) : HomeIntent
    data object AddProject : HomeIntent
    data object NewThread : HomeIntent
    data object CommandPalette : HomeIntent
}

/**
 * Home (UI_SPEC §4.1): header, search and filter, one list with shelves, the New thread FAB.
 * Structure and state only: plain Material until the kit and the sky header restyle it.
 * Back clears and closes the search while Home is the visible entry ([isTop]); sheets above
 * Home and the keyboard take back first (§2.3).
 */
@Composable
fun HomeScreen(state: HomeUiState?, isTop: Boolean, onIntent: (HomeIntent) -> Unit, modifier: Modifier = Modifier) {
    val focusManager = LocalFocusManager.current
    var searchFocused by remember { mutableStateOf(false) }
    val query = state?.query.orEmpty()
    BackHandler(enabled = isTop && (query.isNotEmpty() || searchFocused)) {
        onIntent(HomeIntent.ClearSearch)
        focusManager.clearFocus()
    }
    Scaffold(
        modifier = modifier.testTag(HomeTags.ROOT),
        floatingActionButton = {
            if (state != null && state.content != HomeContent.NoEnvironments) {
                ExtendedFloatingActionButton(
                    onClick = { onIntent(HomeIntent.NewThread) },
                    icon = { Icon(painterResource(Ot3Icons.SquarePen), contentDescription = null) },
                    text = { Text(stringResource(R.string.ot3_home_new_thread)) },
                    modifier = Modifier.testTag(HomeTags.NEW_THREAD),
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Header(state, onIntent)
            SearchRow(query, state?.filter?.active == true, onIntent, onFocus = { searchFocused = it })
            when (val content = state?.content ?: HomeContent.Connecting) {
                HomeContent.NoEnvironments -> Message(stringResource(R.string.mobile_no_environments), HomeTags.NO_ENVIRONMENTS) {
                    Button(onClick = { onIntent(HomeIntent.AddEnvironment) }) { Text(stringResource(R.string.environments_add)) }
                }
                HomeContent.Connecting -> Message(stringResource(R.string.mobile_connecting_environment), HomeTags.CONNECTING)
                HomeContent.Loading -> Message(stringResource(R.string.mobile_loading_threads), HomeTags.LOADING)
                is HomeContent.Unauthorized -> Message(stringResource(R.string.connect_error_unauthorized), HomeTags.UNAUTHORIZED) {
                    Button(onClick = { onIntent(HomeIntent.EditEnvironment(content.environmentId)) }) {
                        Text(stringResource(R.string.sidebar_edit_token))
                    }
                }
                HomeContent.Empty -> Message(stringResource(R.string.ot3_home_empty), HomeTags.EMPTY)
                HomeContent.NoResults -> Message(stringResource(R.string.ot3_home_no_results), HomeTags.NO_RESULTS)
                HomeContent.List -> ThreadList(state ?: return@Column, onIntent)
            }
        }
    }
}

@Composable
private fun Header(state: HomeUiState?, onIntent: (HomeIntent) -> Unit) {
    val header = state?.header
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ot3 does not translate the wordmark.
        Text(WORDMARK, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        header?.counts?.let { counts ->
            // ot3's bar badges (work / ready / input), digits only until the kit draws the pills.
            if (counts.working + counts.done + counts.waiting > 0) {
                Text(
                    "${counts.working} · ${counts.done} · ${counts.waiting}",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.testTag(HomeTags.COUNTS),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        if (header != null && header.dot != HeaderDot.None) {
            Row(
                Modifier.clickable { onIntent(HomeIntent.OpenEnvironments) }.padding(4.dp).testTag(HomeTags.CONNECTION),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val colors = MaterialTheme.colorScheme
                Box(Modifier.size(6.dp).clip(CircleShape).background(if (header.dot == HeaderDot.Live) colors.primary else colors.error))
                val text = headerText(header.status)
                if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag(HomeTags.OVERFLOW)) {
                Icon(painterResource(Ot3Icons.EllipsisVertical), contentDescription = stringResource(R.string.home_more))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.ot3_home_add_project)) }, onClick = { menuOpen = false; onIntent(HomeIntent.AddProject) })
                // The quota strip needs usage data (U14): listed, not offered yet.
                DropdownMenuItem(text = { Text(stringResource(R.string.ot3_home_show_provider_usage)) }, onClick = {}, enabled = false)
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.ot3_home_group_shelves)) },
                    trailingIcon = { Checkbox(checked = state?.shelves?.grouped == true, onCheckedChange = null) },
                    onClick = { menuOpen = false; onIntent(HomeIntent.ToggleGrouping) },
                    modifier = Modifier.testTag(HomeTags.GROUP),
                )
                DropdownMenuItem(text = { Text(stringResource(R.string.ot3_home_refresh)) }, onClick = { menuOpen = false; onIntent(HomeIntent.Refresh) })
                DropdownMenuItem(text = { Text(stringResource(R.string.ot3_home_command_palette)) }, onClick = { menuOpen = false; onIntent(HomeIntent.CommandPalette) })
            }
        }
    }
}

@Composable
private fun SearchRow(query: String, filterActive: Boolean, onIntent: (HomeIntent) -> Unit, onFocus: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = query,
            onValueChange = { onIntent(HomeIntent.SetQuery(it)) },
            placeholder = { Text(stringResource(R.string.ot3_search_threads_placeholder)) },
            singleLine = true,
            trailingIcon = if (query.isNotEmpty()) {
                {
                    IconButton(onClick = { onIntent(HomeIntent.ClearSearch) }, modifier = Modifier.testTag(HomeTags.SEARCH_CLEAR)) {
                        Icon(painterResource(Ot3Icons.X), contentDescription = stringResource(R.string.ot3_search_clear))
                    }
                }
            } else {
                null
            },
            modifier = Modifier.weight(1f).onFocusChanged { onFocus(it.isFocused) }.testTag(HomeTags.SEARCH),
        )
        // The filter control fills while a filter is on.
        val filterIcon: @Composable () -> Unit = {
            Icon(painterResource(Ot3Icons.ListFilter), contentDescription = stringResource(R.string.ot3_filter_button))
        }
        if (filterActive) {
            FilledTonalIconButton(onClick = { onIntent(HomeIntent.OpenFilters) }, modifier = Modifier.testTag(HomeTags.FILTER), content = filterIcon)
        } else {
            IconButton(onClick = { onIntent(HomeIntent.OpenFilters) }, modifier = Modifier.testTag(HomeTags.FILTER), content = filterIcon)
        }
    }
}

@Composable
private fun Message(text: String, tag: String, action: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.fillMaxSize().padding(24.dp).testTag(tag),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
        if (action != null) {
            Spacer(Modifier.size(12.dp))
            action()
        }
    }
}

@Composable
private fun ThreadList(state: HomeUiState, onIntent: (HomeIntent) -> Unit) {
    Column {
        if (state.offline) {
            Text(
                stringResource(R.string.ot3_home_t3_offline),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag(HomeTags.OFFLINE),
            )
        }
        LazyColumn(Modifier.fillMaxSize().alpha(if (state.stale) 0.6f else 1f).testTag(HomeTags.LIST)) {
            items(state.items, key = { it.key }, contentType = { it::class }) { item ->
                when (item) {
                    is HomeItem.Row -> ThreadRow(item.row, onIntent)
                    is HomeItem.ShelfHeader -> ShelfHeader(item, onIntent)
                    is HomeItem.ShowMoreSettled -> TextButton(
                        onClick = { onIntent(HomeIntent.ShowMoreSettled) },
                        modifier = Modifier.padding(horizontal = 8.dp).testTag(HomeTags.SHOW_MORE),
                    ) { Text(stringResource(R.string.mobile_show_more_settled, item.hidden)) }
                    is HomeItem.MessagesHeader -> Text(
                        stringResource(R.string.ot3_search_messages),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag(HomeTags.MESSAGES),
                    )
                    is HomeItem.Message -> MessageRow(item, onIntent)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThreadRow(row: HomeRow, onIntent: (HomeIntent) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val untitled = stringResource(R.string.ot3_row_untitled)
    // Swipes (Settle, Move to active, Wake, Archive; Snooze) are modelled in row.swipe but all
    // mutate, so no gesture is attached until the lib's command builders are reachable.
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (row.style == RowStyle.Card) 82.dp else 48.dp)
            .combinedClickable(
                onClick = { onIntent(HomeIntent.OpenRow(row)) },
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onIntent(HomeIntent.RowActions(row))
                },
            )
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag(HomeTags.row(row.key)),
        verticalArrangement = Arrangement.Center,
    ) {
        val caption = MaterialTheme.typography.labelSmall
        val status = if (row.status == RowStatusDetail.None) timeText(row.time) else statusText(row.status)
        if (row.style == RowStyle.Card) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ProjectLabel(row, onIntent, haptics)
                row.environmentLabel?.let { Text(it, style = caption, maxLines = 1) }
                if (row.pinned) {
                    Icon(painterResource(Ot3Icons.Pin), contentDescription = stringResource(R.string.sidebar_pinned), modifier = Modifier.size(12.dp))
                }
                Spacer(Modifier.weight(1f))
                Text(status, style = caption, modifier = Modifier.testTag(HomeTags.rowStatus(row.key)))
            }
            Text(row.title ?: untitled, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            row.branch?.let { Text(it, style = caption, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(row)
                Text(row.title ?: untitled, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(timeText(row.time), style = caption, modifier = Modifier.testTag(HomeTags.rowStatus(row.key)))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectLabel(row: HomeRow, onIntent: (HomeIntent) -> Unit, haptics: HapticFeedback) {
    Row(
        Modifier.combinedClickable(
            enabled = row.projectId != null,
            onClick = { onIntent(HomeIntent.OpenRow(row)) },
            onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onIntent(HomeIntent.ProjectActions(row))
            },
        ).testTag(HomeTags.project(row.key)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Chip(row)
        row.projectTitle?.let { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
    }
}

/** Interim chip (U21): the project's first letter on an accent tint. */
@Composable
private fun Chip(row: HomeRow) {
    val chip = row.chip ?: return
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).background(colors.primary.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(chip.monogram, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = colors.primary)
    }
}

@Composable
private fun ShelfHeader(item: HomeItem.ShelfHeader, onIntent: (HomeIntent) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable { onIntent(HomeIntent.ToggleShelf(item.shelf)) }
            .padding(horizontal = 16.dp).testTag(HomeTags.shelf(item.shelf)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = if (item.shelf == Shelf.Working) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        Text(shelfText(item.shelf, item.count), style = MaterialTheme.typography.labelLarge, color = color, modifier = Modifier.weight(1f))
        Icon(painterResource(if (item.expanded) Ot3Icons.ChevronUp else Ot3Icons.ChevronDown), contentDescription = null, tint = color)
    }
}

@Composable
private fun MessageRow(item: HomeItem.Message, onIntent: (HomeIntent) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable { onIntent(HomeIntent.OpenMessage(item.match)) }.padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag(HomeTags.message(item.key)),
    ) {
        Text(item.title ?: stringResource(R.string.ot3_row_untitled), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(item.match.snippet, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private const val WORDMARK = "T3 Code"

object HomeTags {
    const val ROOT = "home"
    const val SEARCH = "home_search"
    const val SEARCH_CLEAR = "home_search_clear"
    const val FILTER = "home_filter"
    const val OVERFLOW = "home_overflow"
    const val GROUP = "home_group"
    const val CONNECTION = "home_connection"
    const val COUNTS = "home_counts"
    const val LIST = "home_list"
    const val NEW_THREAD = "home_new_thread"
    const val NO_ENVIRONMENTS = "home_no_environments"
    const val CONNECTING = "home_connecting"
    const val LOADING = "home_loading"
    const val UNAUTHORIZED = "home_unauthorized"
    const val EMPTY = "home_empty"
    const val NO_RESULTS = "home_no_results"
    const val OFFLINE = "home_offline"
    const val SHOW_MORE = "home_show_more"
    const val MESSAGES = "home_messages"

    fun row(key: String) = "home_row_$key"

    fun rowStatus(key: String) = "home_row_status_$key"

    fun project(key: String) = "home_project_$key"

    fun shelf(shelf: Shelf) = "home_shelf_${shelf.name}"

    fun message(key: String) = "home_$key"
}
