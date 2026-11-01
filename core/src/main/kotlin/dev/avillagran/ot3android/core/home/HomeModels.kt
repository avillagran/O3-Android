package dev.avillagran.ot3android.core.home

import dev.avillagran.ot3android.core.connection.ConnectionState
import dev.avillagran.ot3android.core.shell.ProjectIdentity
import dev.avillagran.ot3android.core.shell.RowTime
import dev.avillagran.ot3android.core.shell.ShellState

/** One saved environment as Home reads it: its catalog entry, connection and shell. */
data class HomeEnvironment(
    val id: String,
    val label: String,
    val connection: ConnectionState,
    /** Null until the first snapshot; kept while reconnecting. */
    val shell: ShellState?,
    val actions: ActionContext = ActionContext.Interim,
)

/** ot3's Status filter (`ot3:ui/qml/T3Panel.qml:1079-1089`), in ot3's order; [libName] is `sidebar.view`'s `filter`. */
enum class StatusFilter(val libName: String) {
    All("all"),
    Working("working"),
    Done("done"),
    Failed("failed"),
    Pending("pending"),
    Settled("settled"),
}

/**
 * The Thread list options: status, project (a project key `<environment>/<project>` or a
 * project group's key, as `sidebar.view` takes it) and environment.
 */
data class HomeFilter(
    val status: StatusFilter = StatusFilter.All,
    val projectKey: String? = null,
    val environmentId: String? = null,
) {
    /** The filter icon fills while any filter is on (`t3m:src/features/home/ThreadListToolbar.tsx:78-101`). */
    val active: Boolean get() = status != StatusFilter.All || projectKey != null || environmentId != null
}

enum class Shelf { Working, Snoozed, Settled }

/**
 * Grouping and collapsed shelves, persisted across search and restarts (ot3's user rule,
 * `ot3:docs/NATIVE_VISUAL_AUDIT.md:95-97`). All off by default, as ot3's view state.
 */
data class ShelfState(
    /** "Group working, snoozed and settled threads": the Working shelf exists only when on. */
    val grouped: Boolean = false,
    val workingExpanded: Boolean = false,
    val snoozedExpanded: Boolean = false,
    val settledExpanded: Boolean = false,
) {
    fun expanded(shelf: Shelf): Boolean = when (shelf) {
        Shelf.Working -> workingExpanded
        Shelf.Snoozed -> snoozedExpanded
        Shelf.Settled -> settledExpanded
    }

    fun withExpanded(shelf: Shelf, expanded: Boolean): ShelfState = when (shelf) {
        Shelf.Working -> copy(workingExpanded = expanded)
        Shelf.Snoozed -> copy(snoozedExpanded = expanded)
        Shelf.Settled -> copy(settledExpanded = expanded)
    }
}

/** Card rows (82 dp) for active, pinned and working threads; slim rows (48 dp) for snoozed and settled. */
enum class RowStyle { Card, Slim }

/**
 * One thread row of Home: a `sidebar.view` row. [detail], [statusLabel], [statusRole] and
 * [time] are the lib's, unchanged; nothing here is derived in Kotlin.
 */
data class HomeRow(
    /** `<environment id>/<thread id>`, unique across environments. */
    val key: String,
    val environmentId: String,
    val threadId: String,
    val title: String?,
    val projectId: String?,
    /** `<environment id>/<project id>`, the Project filter's key; null without a project. */
    val projectKey: String?,
    val projectTitle: String?,
    /** The environment label supplied to sidebar.view, also for a single saved environment. */
    val environmentLabel: String?,
    val branch: String?,
    /** The worktree, else the project's workspace root: Copy ▸ Path. */
    val path: String?,
    /** The thread runs in a worktree. */
    val worktree: Boolean,
    /** `approval`, `input`, `plan`, `waiting`, `goal`, `working`, `limited`, `failed`, `woke`, `done` or "". */
    val detail: String,
    /** The lib's shelf bucket: `waiting`, `working`, `failed`, `done`, `woke` or `idle`. */
    val state: String,
    /** ot3's English source ("Plan ready"), translated by the UI; "" shows the time instead. */
    val statusLabel: String,
    /** `working`, `done`, `failed`, `warning`, `input`, `waiting` or `muted`. */
    val statusRole: String,
    val time: RowTime,
    val pinned: Boolean,
    val settled: Boolean,
    val snoozed: Boolean,
    /** A run is active (the lib's `working`, or an `activeRunId`): Archive is off. */
    val working: Boolean,
    /** Nesting under the parent row (sub-threads), 0 at the top level. */
    val depth: Int,
    val subagent: Boolean,
    /** The project chip (`projectIdentities`), null without a project. */
    val identity: ProjectIdentity?,
    /** The lib's `metadata.canSnooze`. */
    val canSnooze: Boolean,
    val autoSettleDisabled: Boolean,
    val style: RowStyle,
    val swipe: SwipeActions,
    /** Already computed by sidebar.view; no PR inference in the host. */
    val pullRequestBadge: org.json.JSONObject? = null,
    val parentKey: String? = null,
)

/** One entry of Home's single lazy list. */
sealed interface HomeItem {
    val key: String

    data class Row(val row: HomeRow) : HomeItem {
        override val key: String get() = "thread:${row.key}"
    }

    /** "Working ({count})", "Snoozed ({count})", "Settled ({count})"; hidden when empty. */
    data class ShelfHeader(val shelf: Shelf, val count: Int, val expanded: Boolean) : HomeItem {
        override val key: String get() = "@${shelf.name.lowercase()}"
    }

    /** "Show more ({count} settled hidden)". */
    data class ShowMoreSettled(val hidden: Int) : HomeItem {
        override val key: String get() = "@settled-more"
    }

    /** The "Messages" section of the server's thread search. */
    data class MessagesHeader(val loading: Boolean) : HomeItem {
        override val key: String get() = "@messages"
    }

    data class Message(val match: MessageMatch, val title: String?, val environmentLabel: String?) : HomeItem {
        override val key: String get() = "message:${match.environmentId}/${match.threadId}"
    }
}

/** ot3's bar badges (`sidebar.view` `counts`): working, done (unseen completions) and waiting. */
data class CountPills(val working: Int = 0, val done: Int = 0, val waiting: Int = 0)

data class StatusCount(val filter: StatusFilter, val count: Int)

/**
 * A project, or a group of projects, of the Project filter; [key] is what the filter takes and
 * [workspaceRoot] is what Copy Path copies.
 */
data class ProjectOption(val key: String, val title: String, val environmentLabel: String?, val workspaceRoot: String? = null)

data class EnvironmentOption(val id: String, val label: String)

/** What the Thread list options sheet offers. [environments] is empty unless more than one is saved. */
data class FilterOptions(
    val statuses: List<StatusCount> = StatusFilter.entries.map { StatusCount(it, 0) },
    val projects: List<ProjectOption> = emptyList(),
    val environments: List<EnvironmentOption> = emptyList(),
)

/** ot3's 6 dp health dot: accent when every environment is live, failed when one is not. */
enum class HeaderDot { None, Live, Stale }

/** One environment's connection, for the header and Settings → Environments. */
data class EnvironmentConnection(val id: String, val label: String, val state: ConnectionState, val live: Boolean)

data class HomeHeader(
    val dot: HeaderDot = HeaderDot.None,
    /** Already delayed by [HeaderStatusDisplay.delayed]; [HeaderStatus.Hidden] shows nothing. */
    val status: HeaderStatus = HeaderStatus.Hidden,
    val counts: CountPills = CountPills(),
    val environments: List<EnvironmentConnection> = emptyList(),
)

/** What Home shows instead of, or around, its list (UI_SPEC §4.1.6). */
sealed interface HomeContent {
    data object NoEnvironments : HomeContent

    /** No shell yet and nothing connected: "Connecting to environment". */
    data object Connecting : HomeContent

    /** Connected, first snapshot pending: "Loading threads...". */
    data object Loading : HomeContent

    /** No shell yet and a token was rejected: offer Edit token for [environmentId]. */
    data class Unauthorized(val environmentId: String) : HomeContent

    /** Shells loaded, no thread at all: "No threads here". */
    data object Empty : HomeContent

    /** A search or filter matched nothing: "No results". */
    data object NoResults : HomeContent

    data object List : HomeContent
}

data class HomeUiState(
    val actionContexts: Map<String, ActionContext> = emptyMap(),
    val content: HomeContent = HomeContent.Connecting,
    val header: HomeHeader = HomeHeader(),
    val query: String = "",
    val filter: HomeFilter = HomeFilter(),
    val filterOptions: FilterOptions = FilterOptions(),
    val shelves: ShelfState = ShelfState(),
    val items: kotlin.collections.List<HomeItem> = emptyList(),
    /** Some environment shows a cached shell while not connected: rows are dimmed. */
    val stale: Boolean = false,
    /** Every environment with a cached shell is offline: "T3 is offline". */
    val offline: Boolean = false,
    /** More than one environment is saved: rows and filters name their environment. */
    val multipleEnvironments: Boolean = false,
    /** Every row by key, filtered or not, for the Thread actions sheet. */
    val rows: Map<String, HomeRow> = emptyMap(),
    /** Every project by its key `<environment>/<project>`, for the Project actions sheet. */
    val projects: Map<String, ProjectOption> = emptyMap(),
    /** The project keys the Project filter covers (`projectScope`); empty while it is off. */
    val projectScope: Set<String> = emptySet(),
)
