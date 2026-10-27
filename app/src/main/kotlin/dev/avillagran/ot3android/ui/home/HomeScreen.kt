package dev.avillagran.ot3android.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import dev.avillagran.ot3android.ui.sky.*
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import dev.avillagran.ot3android.ui.theme.Ot3MotionLevel
import dev.avillagran.ot3android.ui.kit.Ot3IconButton
import dev.avillagran.ot3android.ui.kit.Ot3TextField
import dev.avillagran.ot3android.ui.kit.CountPills
import dev.avillagran.ot3android.ui.kit.CountPillsModel
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.core.home.HeaderDot
import dev.avillagran.ot3android.core.home.HomeContent
import dev.avillagran.ot3android.core.home.HomeItem
import dev.avillagran.ot3android.core.home.HomeRow
import dev.avillagran.ot3android.core.home.HomeUiState
import dev.avillagran.ot3android.core.home.MessageMatch
import dev.avillagran.ot3android.core.home.Shelf
import dev.avillagran.ot3android.ui.icons.Ot3Icons
import dev.avillagran.ot3android.ui.navigation.LocalSelectedThreadKey
import dev.avillagran.ot3android.ui.kit.ThreadCardRow
import dev.avillagran.ot3android.ui.kit.ThreadSlimRow

/** Everything Home's UI can ask for; the route turns each into a view-model call or a navigation. */
sealed interface HomeIntent {
    data class SetQuery(val text: String) : HomeIntent
    data object ClearSearch : HomeIntent
    data class OpenRow(val row: HomeRow) : HomeIntent
    data class OpenPullRequest(val row: HomeRow) : HomeIntent

    /** Long press on a row: the Thread actions sheet. */
    data class RowActions(val row: HomeRow) : HomeIntent
    data class Swipe(val row: HomeRow, val action: dev.avillagran.ot3android.core.home.SwipeAction) : HomeIntent

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
 * Real ot3 brand, sky and rows; insets are owned once by this screen.
 * Back clears and closes the search while Home is the visible entry ([isTop]); sheets above
 * Home and the keyboard take back first (§2.3).
 */
@Composable
fun HomeScreen(state: HomeUiState?, isTop: Boolean, onIntent: (HomeIntent) -> Unit, modifier: Modifier = Modifier, quotaStrip: @Composable () -> Unit = {}) {
    val focusManager = LocalFocusManager.current
    var searchFocused by remember { mutableStateOf(false) }
    val query = state?.query.orEmpty()
    BackHandler(enabled = isTop && (query.isNotEmpty() || searchFocused)) {
        onIntent(HomeIntent.ClearSearch)
        focusManager.clearFocus()
    }
    Scaffold(
        modifier = modifier.testTag(HomeTags.ROOT),
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Column(Modifier.padding(padding).consumeWindowInsets(padding)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).fillMaxSize()) {
            val colors = Ot3Theme.colors
            val motion = Ot3Theme.motion
            SkyHeader(
                palette = SkyPalette(colors.background, colors.foreground, colors.accent, colors.sky.lightSky,
                    colors.sky.brightness, colors.sky.nebula, colors.sky.meteorRate, colors.sky.ink),
                motion = when (motion.level) {
                    Ot3MotionLevel.FULL -> SkyMotion.Full
                    Ot3MotionLevel.LIGHT -> SkyMotion.Light
                    Ot3MotionLevel.OFF -> SkyMotion.Off
                },
                energy = if ((state?.header?.counts?.working ?: 0) > 0) 1f else 0f,
                onAutoDegrade = if (motion.preference == dev.avillagran.ot3android.core.data.prefs.AnimationPreference.AUTO) ({}) else null,
                modifier = Modifier.fillMaxWidth().testTag("home_sky"),
            ) {
                if (LocalConfiguration.current.screenHeightDp < 480) {
                    Row(Modifier.statusBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { Header(state, onIntent) }
                        Box(Modifier.weight(1f)) {
                            SearchRow(query, state?.filter?.active == true, onIntent, onFocus = { searchFocused = it })
                        }
                    }
                } else Column(Modifier.statusBarsPadding()) {
                    Header(state, onIntent)
                    SearchRow(query, state?.filter?.active == true, onIntent, onFocus = { searchFocused = it })
                }
            }
            val content = state?.content ?: HomeContent.Connecting
            if (content == HomeContent.List && state != null) ThreadList(state, onIntent, quotaStrip)
            else LazyColumn(Modifier.fillMaxSize()) {
                item("provider_usage") { quotaStrip() }
                item("home_message") { Box(Modifier.fillParentMaxHeight()) {
                        when (content) {
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
                HomeContent.List -> Unit
            }
                } }
            }
        }
    }
}

@Composable
private fun Header(state: HomeUiState?, onIntent: (HomeIntent) -> Unit) {
    val header = state?.header
    var menuOpen by remember { mutableStateOf(false) }
    val colors = Ot3Theme.colors
    SkyBrandRow(colors.foreground, colors.dim,
        modifier = Modifier.testTag("home_brand"),
        logo = { Icon(painterResource(Ot3Icons.T3Logo), null, tint = colors.foreground) },
    ) {
        header?.counts?.let { counts ->
            CountPills(CountPillsModel(counts.working, counts.done, counts.waiting), Modifier.testTag(HomeTags.COUNTS))
        }
        if (header != null && header.dot != HeaderDot.None) {
            Row(
                Modifier.heightIn(min = 48.dp).clickable { onIntent(HomeIntent.OpenEnvironments) }.padding(horizontal = 4.dp).testTag(HomeTags.CONNECTION),
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
            Ot3IconButton("ellipsis-vertical", stringResource(R.string.home_more), { menuOpen = true },
                modifier = Modifier.testTag(HomeTags.OVERFLOW))
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.ot3_home_add_project)) }, onClick = { menuOpen = false; onIntent(HomeIntent.AddProject) })
                dev.avillagran.ot3android.ui.usage.QuotaToggleMenuItem { menuOpen = false }
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
        Ot3TextField(
            value = query,
            onValueChange = { onIntent(HomeIntent.SetQuery(it)) },
            placeholder = stringResource(R.string.ot3_search_threads_placeholder),
            leadingIcon = "search",
            framed = false,
            singleLine = true,
            trailing = if (query.isNotEmpty()) {
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
        Ot3IconButton("square-pen", stringResource(R.string.ot3_home_new_thread), { onIntent(HomeIntent.NewThread) },
            modifier = Modifier.testTag(HomeTags.NEW_THREAD))
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

/** Only presentation sections: membership and ordering remain those of sidebar.view. */
internal data class HomeListSections(val main: List<HomeItem>, val bottom: List<Pair<HomeItem.ShelfHeader, List<HomeItem>>>)

internal fun homeListSections(items: List<HomeItem>): HomeListSections {
    val main = mutableListOf<HomeItem>()
    val bottom = mutableListOf<Pair<HomeItem.ShelfHeader, MutableList<HomeItem>>>()
    var target: MutableList<HomeItem> = main
    items.forEach { item ->
        when {
            item is HomeItem.ShelfHeader && item.shelf != Shelf.Working -> {
                target = mutableListOf()
                bottom += item to target
            }
            item is HomeItem.ShelfHeader || item is HomeItem.MessagesHeader -> {
                target = main
                target += item
            }
            else -> target += item
        }
    }
    return HomeListSections(main, bottom)
}

@Composable
private fun ThreadList(state: HomeUiState, onIntent: (HomeIntent) -> Unit, quotaStrip: @Composable () -> Unit) {
    val motion = Ot3Theme.motion
    val sections = remember(state.items) { homeListSections(state.items) }
    Column(Modifier.fillMaxSize().alpha(if (state.stale) 0.6f else 1f)) {
        if (state.offline) {
            Text(
                stringResource(R.string.ot3_home_t3_offline),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag(HomeTags.OFFLINE),
            )
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            // Keep headers outside every scrolling list. Each expanded body is independently
            // bounded; the main list owns all remaining space even with no active rows.
            val bodyLimit = ((maxHeight - 48.dp * sections.bottom.size).coerceAtLeast(0.dp) * .3f)
            Column(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag(HomeTags.LIST)) {
                    item("provider_usage") { quotaStrip() }
                    items(sections.main, key = { it.key }, contentType = { it::class }) { HomeListItem(it, onIntent, Modifier.animateItem(
                        fadeInSpec = motion.rowArriveFade(), placementSpec = motion.shelfMove(), fadeOutSpec = motion.rowDepart())) }
                }
                sections.bottom.forEach { (header, members) ->
                    androidx.compose.runtime.key(header.shelf) {
                        ShelfHeader(header, onIntent)
                        if (header.expanded && members.isNotEmpty()) {
                            LazyColumn(Modifier.fillMaxWidth().heightIn(max = bodyLimit)
                                .testTag("home_shelf_list_${header.shelf.name}")) {
                                items(members, key = { it.key }, contentType = { it::class }) { HomeListItem(it, onIntent, Modifier.animateItem(
                        fadeInSpec = motion.rowArriveFade(), placementSpec = motion.shelfMove(), fadeOutSpec = motion.rowDepart())) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeListItem(item: HomeItem, onIntent: (HomeIntent) -> Unit, rowModifier: Modifier = Modifier) {
    when (item) {
        is HomeItem.Row -> ThreadRow(item.row, onIntent, rowModifier)
        is HomeItem.ShelfHeader -> ShelfHeader(item, onIntent)
        is HomeItem.ShowMoreSettled -> TextButton(
            onClick = { onIntent(HomeIntent.ShowMoreSettled) },
            modifier = Modifier.padding(horizontal = 8.dp).testTag(HomeTags.SHOW_MORE),
        ) { Text(stringResource(R.string.mobile_show_more_settled, item.hidden)) }
        is HomeItem.MessagesHeader -> Text(
            stringResource(R.string.ot3_search_messages), style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag(HomeTags.MESSAGES),
        )
        is HomeItem.Message -> MessageRow(item, onIntent)
    }
}

/** A `sidebar.view` row in the kit's card or slim row. */
@Composable
private fun ThreadRow(row: HomeRow, onIntent: (HomeIntent) -> Unit, animatedModifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val motion = Ot3Theme.motion
    val drift = remember(row.key) { Animatable(if (motion.animates) -18f else 0f) }
    LaunchedEffect(row.key, motion.animates) { drift.animateTo(0f, motion.rowArriveDrift()) }
    val density = LocalDensity.current.density
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    val model = row.toThreadRowModel()
    val onClick = { onIntent(HomeIntent.OpenRow(row)) }
    val onLongClick = {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        onIntent(HomeIntent.RowActions(row))
    }
    val onProjectLongClick = if (row.projectId != null) {
        {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onIntent(HomeIntent.ProjectActions(row))
        }
    } else {
        null
    }
    val modifier = animatedModifier.graphicsLayer { translationX = drift.value * density * direction }.padding(horizontal = 8.dp).testTag(HomeTags.row(row.key))
    ThreadSwipeActions(row, { onIntent(HomeIntent.Swipe(row, it)) }, modifier) { contentModifier ->
    if (row.slim) {
        ThreadSlimRow(model, selected = row.key == LocalSelectedThreadKey.current, onClick = onClick, onLongClick = onLongClick, onPullRequestClick = { onIntent(HomeIntent.OpenPullRequest(row)) }, modifier = contentModifier)
    } else {
        ThreadCardRow(model, selected = row.key == LocalSelectedThreadKey.current, onClick = onClick, onLongClick = onLongClick, onProjectLongClick = onProjectLongClick, onPullRequestClick = { onIntent(HomeIntent.OpenPullRequest(row)) }, modifier = contentModifier)
    }
    }
}

@Composable
private fun ShelfHeader(item: HomeItem.ShelfHeader, onIntent: (HomeIntent) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onIntent(HomeIntent.ToggleShelf(item.shelf)) }
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

    fun shelf(shelf: Shelf) = "home_shelf_${shelf.name}"

    fun message(key: String) = "home_$key"
}
