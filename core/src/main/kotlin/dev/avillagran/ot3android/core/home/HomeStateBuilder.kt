package dev.avillagran.ot3android.core.home

import dev.avillagran.ot3android.core.connection.ConnectionState
import dev.avillagran.ot3android.core.shell.Sidebar
import dev.avillagran.ot3android.core.shell.SidebarEnvironment
import dev.avillagran.ot3android.core.shell.SidebarRow
import dev.avillagran.ot3android.core.shell.SidebarView
import dev.avillagran.ot3android.core.transport.DomainCalls
import org.json.JSONObject

/** Everything Home's state is made of: the saved environments and Home's view state. */
data class HomeInput(
    /** Saved environments in catalog order. */
    val environments: List<HomeEnvironment>,
    val query: String = "",
    val filter: HomeFilter = HomeFilter(),
    val shelves: ShelfState = ShelfState(),
    /** Settled rows listed before "Show more". */
    val settledLimit: Int = HomeStateBuilder.SETTLED_INITIAL,
    val messages: MessageSearchState = MessageSearchState.Idle,
    /** The header text, already delayed ([HeaderStatusDisplay.delayed]). */
    val headerStatus: HeaderStatus = HeaderStatus.Hidden,
    val now: Long,
    /** The previous view's [Sidebar.returns], for the grouped Working shelf. */
    val returns: JSONObject? = null,
    /** Per environment: what its thread actions may do. */
    val actions: (environmentId: String) -> ActionContext = { ActionContext.Interim },
)

/**
 * Builds Home from every saved environment's shell (UI_SPEC §4.1). The lib's `sidebar.view`
 * does the work: rows, their order, details, labels, colour roles, times, search, filters,
 * shelves, nesting, counts and project chips. This file maps its result to Home's list and
 * keeps only view state: which shelves are open, the settled paging and the message search.
 */
object HomeStateBuilder {

    /** T3 Mobile's settled paging (`THREAD_LIST_V2_SETTLED_INITIAL_COUNT` / `_PAGE_COUNT`). */
    const val SETTLED_INITIAL = 10
    const val SETTLED_PAGE = 25

    /** `sidebar.view` for [input], then Home's state. Runs the lib on [domain]'s worker. */
    suspend fun build(domain: DomainCalls, input: HomeInput): HomeUiState = build(input, view(domain, input))

    /** `sidebar.view` over [input]'s environments with its search, filters and grouping. */
    suspend fun view(domain: DomainCalls, input: HomeInput): Sidebar = SidebarView.build(
        domain = domain,
        environments = input.environments.map { env -> SidebarEnvironment(env.id, env.label, env.shell, libState(env.connection)) },
        now = input.now,
        environmentId = input.filter.environmentId,
        grouped = input.shelves.grouped,
        returns = input.returns,
        filter = input.filter.status.libName,
        project = input.filter.projectKey,
        query = input.query.trim(),
    )

    /** Home's state from [sidebar], the `sidebar.view` of [input]. */
    fun build(input: HomeInput, sidebar: Sidebar): HomeUiState {
        val environments = input.environments
        val multiple = environments.size > 1
        val projects = sidebar.projects.associate { project ->
            val label = environments.firstOrNull { it.id == project.environmentId }?.label
            project.key to ProjectOption(project.key, project.title, label.takeIf { multiple }, project.workspaceRoot)
        }
        // Section rows carry their nesting; `threads` adds the rows no section lists (filtered out).
        val listed = (sidebar.active + sidebar.working + sidebar.snoozed + sidebar.settled)
            .associate { row -> row.key to homeRow(row, sidebar, projects, input) }
        val rows = sidebar.threads.associate { row -> row.key to homeRow(row, sidebar, projects, input) } + listed
        fun row(lib: SidebarRow): HomeRow = listed.getValue(lib.key)

        val items = buildList {
            sidebar.active.forEach { add(HomeItem.Row(row(it))) }
            shelf(Shelf.Working, sidebar.working, sidebar.sectionCounts["working"], input.shelves, Int.MAX_VALUE, ::row)
            shelf(Shelf.Snoozed, sidebar.snoozed, sidebar.sectionCounts["snoozed"], input.shelves, Int.MAX_VALUE, ::row)
            shelf(Shelf.Settled, sidebar.settled, sidebar.sectionCounts["settled"], input.shelves, input.settledLimit, ::row)
            messages(input.messages, environments, rows, multiple)
        }

        val shells = environments.filter { it.shell != null }
        return HomeUiState(
            content = content(input, items, noRows = (sidebar.filterCounts[StatusFilter.All.libName] ?: 0) == 0),
            header = HomeHeader(
                dot = HeaderStatusDisplay.dot(environments),
                status = input.headerStatus,
                counts = CountPills(
                    working = sidebar.counts["working"] ?: 0,
                    done = sidebar.counts["done"] ?: 0,
                    waiting = sidebar.counts["waiting"] ?: 0,
                ),
                environments = environments.map { env ->
                    EnvironmentConnection(env.id, env.label, env.connection, env.connection is ConnectionState.Connected)
                },
            ),
            query = input.query,
            filter = input.filter,
            filterOptions = FilterOptions(
                statuses = StatusFilter.entries.map { StatusCount(it, sidebar.filterCounts[it.libName] ?: 0) },
                projects = sidebar.projectGroups.map { group ->
                    val members = group.memberKeys.mapNotNull(projects::get)
                    val label = members.map { it.environmentLabel }.distinct().singleOrNull()
                    ProjectOption(group.key, group.title, label, members.firstOrNull()?.workspaceRoot)
                },
                environments = if (multiple) environments.map { EnvironmentOption(it.id, it.label) } else emptyList(),
            ),
            shelves = input.shelves,
            items = items,
            stale = shells.any { it.connection !is ConnectionState.Connected },
            offline = shells.isNotEmpty() && shells.none { it.connection is ConnectionState.Connected },
            multipleEnvironments = multiple,
            rows = rows,
            projects = projects,
            projectScope = if (input.filter.projectKey == null) emptySet() else sidebar.projectScope.orEmpty(),
        )
    }

    /** Settled rows listed with [limit] so far, one page more. */
    fun nextSettledLimit(limit: Int): Int = limit + SETTLED_PAGE

    /**
     * The host's connection state for `sidebar.view` (`state`, which overrides `ready` while a
     * cached shell is listed); null while connected.
     */
    fun libState(connection: ConnectionState): String? = when (connection) {
        is ConnectionState.Connected -> null
        ConnectionState.Connecting -> "connecting"
        ConnectionState.Disconnected -> "disconnected"
        ConnectionState.Unauthorized -> "unauthorized"
        is ConnectionState.Unreachable -> "unreachable"
        else -> "unavailable"
    }

    private fun homeRow(row: SidebarRow, sidebar: Sidebar, projects: Map<String, ProjectOption>, input: HomeInput): HomeRow {
        val homeRow = HomeRow(
            key = row.key,
            environmentId = row.environmentId,
            threadId = row.threadId,
            title = row.title.ifEmpty { null },
            projectId = row.projectId,
            projectKey = row.projectKey,
            projectTitle = row.projectTitle,
            environmentLabel = row.environmentName,
            branch = row.branch,
            path = row.worktreePath ?: row.projectKey?.let { projects[it]?.workspaceRoot },
            worktree = row.worktreePath != null,
            detail = row.detail,
            state = row.state,
            statusLabel = row.statusLabel,
            statusRole = row.statusRole,
            time = row.time,
            pinned = row.pinned,
            settled = row.settled,
            snoozed = row.snoozed,
            working = row.working || row.activeRun,
            depth = row.depth,
            subagent = row.subagent,
            identity = sidebar.identity(row),
            canSnooze = row.canSnooze,
            autoSettleDisabled = row.autoSettleDisabled,
            // ot3 draws cards for the inbox and slim rows for settled, snoozed and sub-threads.
            style = if (row.settled || row.snoozed || row.depth > 0) RowStyle.Slim else RowStyle.Card,
            swipe = SwipeActions.None,
            pullRequestBadge = row.raw.optJSONObject("pullRequestBadge"),
        )
        return homeRow.copy(swipe = ThreadActions.swipe(homeRow, input.actions(row.environmentId)))
    }

    /**
     * A shelf: header (the lib's top-level count) when it has rows; its rows only while
     * expanded, at most [limit] top-level rows with their sub-threads, then "Show more".
     */
    private fun MutableList<HomeItem>.shelf(
        shelf: Shelf,
        members: List<SidebarRow>,
        count: Int?,
        shelves: ShelfState,
        limit: Int,
        row: (SidebarRow) -> HomeRow,
    ) {
        if (members.isEmpty()) return
        val total = count ?: members.count { it.depth == 0 }
        val expanded = shelves.expanded(shelf)
        add(HomeItem.ShelfHeader(shelf, total, expanded))
        if (!expanded) return
        var shown = 0
        for (member in members) {
            if (member.depth == 0 && ++shown > limit) break
            add(HomeItem.Row(row(member)))
        }
        if (total > limit) add(HomeItem.ShowMoreSettled(total - limit))
    }

    private fun MutableList<HomeItem>.messages(
        state: MessageSearchState,
        environments: List<HomeEnvironment>,
        rows: Map<String, HomeRow>,
        multiple: Boolean,
    ) {
        when (state) {
            MessageSearchState.Idle -> Unit
            is MessageSearchState.Loading -> add(HomeItem.MessagesHeader(loading = true))
            is MessageSearchState.Results -> {
                if (state.matches.isEmpty()) return
                add(HomeItem.MessagesHeader(loading = false))
                val byId = environments.associateBy { it.id }
                state.matches.forEach { match ->
                    val environment = byId[match.environmentId]
                    // Archived and sub-threads are not rows: their title comes from the shell.
                    val title = rows["${match.environmentId}/${match.threadId}"]?.title
                        ?: environment?.shell?.thread(match.threadId)?.title
                    add(HomeItem.Message(match, title, environment?.label?.takeIf { multiple }))
                }
            }
        }
    }

    private fun content(input: HomeInput, items: List<HomeItem>, noRows: Boolean): HomeContent {
        val environments = input.environments
        if (environments.isEmpty()) return HomeContent.NoEnvironments
        if (environments.all { it.shell == null }) {
            environments.firstOrNull { it.connection == ConnectionState.Unauthorized }?.let { return HomeContent.Unauthorized(it.id) }
            return if (environments.any { it.connection is ConnectionState.Connected }) HomeContent.Loading else HomeContent.Connecting
        }
        val searching = input.query.isNotBlank() || input.filter.active
        return when {
            items.isNotEmpty() -> HomeContent.List
            searching -> HomeContent.NoResults
            noRows -> HomeContent.Empty
            else -> HomeContent.List
        }
    }
}
