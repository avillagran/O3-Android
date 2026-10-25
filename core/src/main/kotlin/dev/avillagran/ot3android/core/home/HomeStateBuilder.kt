package dev.avillagran.ot3android.core.home

import dev.avillagran.ot3android.core.connection.ConnectionState
import dev.avillagran.ot3android.core.shell.RowStatus
import dev.avillagran.ot3android.core.shell.ShellProject
import dev.avillagran.ot3android.core.shell.SidebarModel
import dev.avillagran.ot3android.core.shell.SidebarRow

/** Everything Home's state is made of; [HomeStateBuilder.build] is a pure function of it. */
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
    /** Per environment: what its thread actions may do. */
    val actions: (environmentId: String) -> ActionContext = { ActionContext.Interim },
)

/**
 * Builds Home from every saved environment's shell (UI_SPEC §4.1): one list keyed by
 * environment, ot3's search, filters, shelves and order, T3 Mobile's settled paging. Rows and
 * their order come from the temporary [SidebarModel] (U2 replaces it); everything here is
 * presentation that stays when the lib ships `sidebar.view`.
 */
object HomeStateBuilder {

    /** T3 Mobile's settled paging (`THREAD_LIST_V2_SETTLED_INITIAL_COUNT` / `_PAGE_COUNT`). */
    const val SETTLED_INITIAL = 10
    const val SETTLED_PAGE = 25

    fun build(input: HomeInput): HomeUiState {
        val environments = input.environments
        val multiple = environments.size > 1
        val labels = environments.associate { it.id to it.label }

        val projects = environments.flatMap { env -> env.shell?.projects.orEmpty().map { env.id to it } }
            .associate { (envId, project) -> "$envId/${project.id}" to project }
        val all = environments.flatMap { env ->
            env.shell?.let { SidebarModel.rows(env.id, it, input.now) }.orEmpty()
        }
        val details = all.associate { it.key to StatusMapping.detail(it) }
        val rows = all.associate { row ->
            row.key to homeRow(row, details.getValue(row.key), projects, labels.takeIf { multiple }, input)
        }

        val scoped = all.filter { row -> inScope(row, input.filter) }
        val needle = input.query.trim().lowercase()
        val visible = scoped.filter { row ->
            matchesStatus(row, details.getValue(row.key), input.filter.status) && matchesQuery(rows.getValue(row.key), needle)
        }
        val sections = SidebarModel.sections(visible, input.shelves.grouped)
        val items = buildList {
            sections.active.forEach { add(HomeItem.Row(rows.getValue(it.key))) }
            shelf(Shelf.Working, sections.working, input.shelves, Int.MAX_VALUE, rows)
            shelf(Shelf.Snoozed, sections.snoozed, input.shelves, Int.MAX_VALUE, rows)
            shelf(Shelf.Settled, sections.settled, input.shelves, input.settledLimit, rows)
            messages(input.messages, environments, labels.takeIf { multiple })
        }

        val shells = environments.filter { it.shell != null }
        val stale = shells.any { it.connection !is ConnectionState.Connected }
        return HomeUiState(
            content = content(input, items, all.isEmpty()),
            header = HomeHeader(
                dot = HeaderStatusDisplay.dot(environments),
                status = input.headerStatus,
                counts = counts(all, details),
                environments = environments.map { env ->
                    EnvironmentConnection(env.id, env.label, env.connection, env.connection is ConnectionState.Connected)
                },
            ),
            query = input.query,
            filter = input.filter,
            filterOptions = filterOptions(all, details, input.filter, projects, environments),
            shelves = input.shelves,
            items = items,
            stale = stale,
            offline = shells.isNotEmpty() && shells.none { it.connection is ConnectionState.Connected },
            multipleEnvironments = multiple,
            rows = rows,
        )
    }

    /** Settled rows listed with [limit] so far, one page more. */
    fun nextSettledLimit(limit: Int): Int = limit + SETTLED_PAGE

    private fun homeRow(
        row: SidebarRow,
        detail: RowStatusDetail,
        projects: Map<String, ShellProject>,
        labels: Map<String, String>?,
        input: HomeInput,
    ): HomeRow {
        val thread = row.thread
        val projectKey = thread.projectId?.let { "${row.environmentId}/$it" }
        val project = projectKey?.let { projects[it] }
        val time = RowTimes.rowTime(
            now = input.now,
            working = row.status == RowStatus.Working,
            runStartedAt = SidebarModel.runStartedAt(thread),
            snoozed = row.snoozed,
            snoozedUntil = SidebarModel.snoozedUntil(thread),
            changedAt = if (row.settled) SidebarModel.settledSortAt(thread) else thread.updatedAt,
        )
        val homeRow = HomeRow(
            key = row.key,
            environmentId = row.environmentId,
            threadId = thread.id,
            title = thread.title,
            projectId = thread.projectId,
            projectKey = projectKey,
            projectTitle = project?.title,
            environmentLabel = labels?.get(row.environmentId),
            branch = thread.branch,
            path = SidebarModel.worktreePath(thread) ?: project?.workspaceRoot,
            status = detail,
            time = time,
            pinned = row.pinned,
            settled = row.settled,
            snoozed = row.snoozed,
            chip = chip(project?.title),
            style = if (row.settled || row.snoozed) RowStyle.Slim else RowStyle.Card,
            swipe = SwipeActions.None,
        )
        return homeRow.copy(swipe = ThreadActions.swipe(homeRow, input.actions(row.environmentId)))
    }

    /** Interim chip (U21): the project title's first letter or digit, upper case, accent tint. */
    fun chip(projectTitle: String?): ProjectChip? {
        val letter = projectTitle?.firstOrNull { it.isLetterOrDigit() } ?: return null
        return ProjectChip(letter.uppercase(), ChipTint.Accent)
    }

    private fun inScope(row: SidebarRow, filter: HomeFilter): Boolean =
        (filter.projectKey == null || filter.projectKey == row.thread.projectId?.let { "${row.environmentId}/$it" }) &&
            (filter.environmentId == null || filter.environmentId == row.environmentId)

    /** ot3 `matches`: settled and pending have their own rule; the rest compare buckets. */
    private fun matchesStatus(row: SidebarRow, detail: RowStatusDetail, status: StatusFilter): Boolean = when (status) {
        StatusFilter.All -> true
        StatusFilter.Settled -> row.settled
        StatusFilter.Pending -> StatusMapping.pending(row, detail)
        StatusFilter.Working -> StatusMapping.bucket(detail) == StatusBucket.Working
        StatusFilter.Done -> StatusMapping.bucket(detail) == StatusBucket.Done
        StatusFilter.Failed -> StatusMapping.bucket(detail) == StatusBucket.Failed
    }

    /** ot3 `rows`' needle: title, project and environment; settled rows included. */
    private fun matchesQuery(row: HomeRow, needle: String): Boolean {
        if (needle.isEmpty()) return true
        val haystack = listOfNotNull(row.title, row.projectTitle, row.environmentLabel ?: row.environmentId)
        return haystack.joinToString(" ").lowercase().contains(needle)
    }

    /** A shelf: header when it has rows; its rows only while expanded, at most [limit], then "Show more". */
    private fun MutableList<HomeItem>.shelf(
        shelf: Shelf,
        members: List<SidebarRow>,
        shelves: ShelfState,
        limit: Int,
        rows: Map<String, HomeRow>,
    ) {
        if (members.isEmpty()) return
        val expanded = shelves.expanded(shelf)
        add(HomeItem.ShelfHeader(shelf, members.size, expanded))
        if (!expanded) return
        members.take(limit).forEach { add(HomeItem.Row(rows.getValue(it.key))) }
        if (members.size > limit) add(HomeItem.ShowMoreSettled(members.size - limit))
    }

    private fun MutableList<HomeItem>.messages(
        state: MessageSearchState,
        environments: List<HomeEnvironment>,
        labels: Map<String, String>?,
    ) {
        when (state) {
            MessageSearchState.Idle -> Unit
            is MessageSearchState.Loading -> add(HomeItem.MessagesHeader(loading = true))
            is MessageSearchState.Results -> {
                if (state.matches.isEmpty()) return
                add(HomeItem.MessagesHeader(loading = false))
                val shells = environments.associate { it.id to it.shell }
                state.matches.forEach { match ->
                    val title = shells[match.environmentId]?.thread(match.threadId)?.title
                    add(HomeItem.Message(match, title, labels?.get(match.environmentId)))
                }
            }
        }
    }

    /** ot3 `counts`: unsettled top-level rows by bucket; settled rows never count. */
    private fun counts(rows: List<SidebarRow>, details: Map<String, RowStatusDetail>): CountPills {
        val buckets = rows.filterNot { it.settled }.map { StatusMapping.bucket(details.getValue(it.key)) }
        return CountPills(
            working = buckets.count { it == StatusBucket.Working },
            done = buckets.count { it == StatusBucket.Done },
            waiting = buckets.count { it == StatusBucket.Waiting },
        )
    }

    /** ot3 `Sidebar.count` per status within the project and environment scope (never the search). */
    private fun filterOptions(
        rows: List<SidebarRow>,
        details: Map<String, RowStatusDetail>,
        filter: HomeFilter,
        projects: Map<String, ShellProject>,
        environments: List<HomeEnvironment>,
    ): FilterOptions {
        val multiple = environments.size > 1
        val labels = environments.associate { it.id to it.label }
        val scoped = rows.filter { inScope(it, filter) }
        return FilterOptions(
            statuses = StatusFilter.entries.map { status ->
                StatusCount(status, scoped.count { matchesStatus(it, details.getValue(it.key), status) })
            },
            projects = projects.map { (key, project) ->
                val environmentId = key.substringBefore('/')
                ProjectOption(key, project.title ?: project.id, labels[environmentId].takeIf { multiple }, project.workspaceRoot)
            }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, ProjectOption::title).thenBy { it.environmentLabel.orEmpty() }),
            environments = if (multiple) environments.map { EnvironmentOption(it.id, it.label) } else emptyList(),
        )
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
