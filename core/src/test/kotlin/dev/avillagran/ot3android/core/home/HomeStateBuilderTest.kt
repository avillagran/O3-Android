package dev.avillagran.ot3android.core.home

import dev.avillagran.ot3android.core.connection.ConnectionState
import dev.avillagran.ot3android.core.connection.ServerDescriptor
import dev.avillagran.ot3android.core.shell.ShellFixtures.body
import dev.avillagran.ot3android.core.shell.ShellFixtures.project
import dev.avillagran.ot3android.core.shell.ShellFixtures.thread
import dev.avillagran.ot3android.core.shell.ShellReducer
import dev.avillagran.ot3android.core.shell.ShellState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeStateBuilderTest {

    private val now = ShellReducer.parseTimestamp("2026-10-25T12:00:00.000Z")!!
    private val connected = ConnectionState.Connected(ServerDescriptor(null, null, null, null, null))

    private fun shell(projects: List<JSONObject>, vararg threads: JSONObject): ShellState =
        ShellReducer.parse(body(1, projects, threads.toList()))

    private fun env(id: String, label: String, shell: ShellState?, connection: ConnectionState = connected) =
        HomeEnvironment(id, label, connection, shell)

    private val laptop = env(
        "e1",
        "Laptop",
        shell(
            listOf(project("p1", "Alpha", root = "/w/alpha")),
            thread("a", "p1", "createdAt" to "2026-10-25T10:00:00.000Z", "branch" to "main"),
            thread("a-run", "p1", "createdAt" to "2026-10-25T09:00:00.000Z", "activeRunId" to "r1", "latestRunStartedAt" to "2026-10-25T11:57:00.000Z"),
            thread("a-settled", "p1", "settledAt" to "2026-10-25T08:00:00.000Z", "title" to "Old alpha work"),
        ),
    )
    private val server = env(
        "e2",
        "Server",
        shell(
            listOf(project("p1", "Beta")),
            thread("b", "p1", "createdAt" to "2026-10-25T11:00:00.000Z", "pendingRuntimeRequest" to JSONObject().put("kind", "approval")),
            thread("b-fail", "p1", "createdAt" to "2026-10-25T07:00:00.000Z", "status" to "failed"),
        ),
    )

    private fun build(
        environments: List<HomeEnvironment> = listOf(laptop, server),
        query: String = "",
        filter: HomeFilter = HomeFilter(),
        shelves: ShelfState = ShelfState(),
        settledLimit: Int = HomeStateBuilder.SETTLED_INITIAL,
        messages: MessageSearchState = MessageSearchState.Idle,
    ) = HomeStateBuilder.build(HomeInput(environments, query, filter, shelves, settledLimit, messages, now = now))

    private fun HomeUiState.rowKeys() = items.filterIsInstance<HomeItem.Row>().map { it.row.key }

    private fun HomeUiState.headers() = items.filterIsInstance<HomeItem.ShelfHeader>()

    @Test
    fun mergesEveryEnvironmentIntoOneOrderedList() {
        val state = build()

        assertEquals(HomeContent.List, state.content)
        assertEquals("inbox order across environments", listOf("e2/b", "e1/a", "e1/a-run", "e2/b-fail"), state.rowKeys())
        assertEquals(listOf(HomeItem.ShelfHeader(Shelf.Settled, 1, expanded = false)), state.headers())
        assertEquals("the same project id in two environments stays apart", "e2/p1", state.rows.getValue("e2/b").projectKey)
        assertEquals("e1/p1", state.rows.getValue("e1/a").projectKey)
        assertEquals(CountPills(working = 1, done = 0, waiting = 1), state.header.counts)
        assertEquals(HeaderDot.Live, state.header.dot)
        assertEquals(listOf("e1", "e2"), state.header.environments.map { it.id })
        assertEquals(5, state.rows.size)
    }

    @Test
    fun environmentLabelsOnlyWhenMoreThanOneIsSaved() {
        val two = build()
        assertTrue(two.multipleEnvironments)
        assertEquals("Laptop", two.rows.getValue("e1/a").environmentLabel)
        assertEquals("Server", two.rows.getValue("e2/b").environmentLabel)
        assertEquals(listOf(EnvironmentOption("e1", "Laptop"), EnvironmentOption("e2", "Server")), two.filterOptions.environments)
        assertEquals(listOf("Alpha" to "Laptop", "Beta" to "Server"), two.filterOptions.projects.map { it.title to it.environmentLabel })

        val one = build(listOf(laptop))
        assertFalse(one.multipleEnvironments)
        assertNull(one.rows.getValue("e1/a").environmentLabel)
        assertTrue(one.filterOptions.environments.isEmpty())
        assertEquals(listOf(ProjectOption("e1/p1", "Alpha", null)), one.filterOptions.projects)

        val withoutShell = build(listOf(laptop, env("e3", "Pending", null, ConnectionState.Connecting)))
        assertEquals("a saved environment counts even before its shell", "Laptop", withoutShell.rows.getValue("e1/a").environmentLabel)
    }

    @Test
    fun rowCarriesStatusTimeChipPathAndStyle() {
        val state = build()
        val working = state.rows.getValue("e1/a-run")
        assertEquals(RowStatusDetail.Working, working.status)
        assertEquals(RowTime.Elapsed(180), working.time)
        assertEquals(RowStyle.Card, working.style)

        val approval = state.rows.getValue("e2/b")
        assertEquals(RowStatusDetail.Approval, approval.status)
        assertEquals(ProjectChip("B", ChipTint.Accent), approval.chip)
        assertEquals(RowStatusDetail.Failed, state.rows.getValue("e2/b-fail").status)

        val quiet = state.rows.getValue("e1/a")
        assertEquals(RowStatusDetail.None, quiet.status)
        assertEquals("ShellFixtures' updatedAt is 26 h before now", RowTime.Age(1, RowTime.TimeUnit.Day), quiet.time)
        assertEquals("/w/alpha", quiet.path)
        assertEquals("main", quiet.branch)
        assertEquals("Alpha", quiet.projectTitle)

        val settled = state.rows.getValue("e1/a-settled")
        assertEquals(RowStyle.Slim, settled.style)
        assertEquals("a settled row shows its settled age", RowTime.Age(4, RowTime.TimeUnit.Hour), settled.time)

        assertEquals("swipes are modelled but disabled until the builders exist", SwipeActions(SwipeAction.Archive, null, false), quiet.swipe)
        assertEquals(SwipeAction.MoveToActive, settled.swipe.primary)
    }

    @Test
    fun searchMatchesTitleProjectAndEnvironmentAndIncludesSettled() {
        assertEquals(listOf("e2/b", "e2/b-fail"), build(query = "beta").rowKeys())
        assertEquals("by environment name", listOf("e2/b", "e2/b-fail"), build(query = "SERVER").rowKeys())
        assertEquals("by title", listOf("e1/a-run"), build(query = "a-run").rowKeys())

        val settled = build(query = "old alpha")
        assertTrue(settled.rowKeys().isEmpty())
        assertEquals("settled matches count while the shelf stays collapsed", listOf(HomeItem.ShelfHeader(Shelf.Settled, 1, false)), settled.headers())
        assertEquals(HomeContent.List, settled.content)

        val open = build(query = "old alpha", shelves = ShelfState(settledExpanded = true))
        assertEquals(listOf("e1/a-settled"), open.rowKeys())

        val none = build(query = "nothing like this")
        assertTrue(none.items.isEmpty())
        assertEquals(HomeContent.NoResults, none.content)
    }

    @Test
    fun filtersWithCounts() {
        val all = build()
        assertEquals(
            mapOf(
                StatusFilter.All to 5,
                StatusFilter.Working to 1,
                StatusFilter.Done to 0,
                StatusFilter.Failed to 1,
                StatusFilter.Pending to 1,
                StatusFilter.Settled to 1,
            ),
            all.filterOptions.statuses.associate { it.filter to it.count },
        )
        assertFalse(all.filter.active)

        val working = build(filter = HomeFilter(status = StatusFilter.Working))
        assertTrue(working.filter.active)
        assertEquals(listOf("e1/a-run"), working.rowKeys())
        assertEquals(listOf("e2/b"), build(filter = HomeFilter(status = StatusFilter.Pending)).rowKeys())
        assertEquals(listOf("e2/b-fail"), build(filter = HomeFilter(status = StatusFilter.Failed)).rowKeys())
        assertEquals(HomeContent.NoResults, build(filter = HomeFilter(status = StatusFilter.Done)).content)

        val settledOnly = build(filter = HomeFilter(status = StatusFilter.Settled), shelves = ShelfState(settledExpanded = true))
        assertEquals(listOf("e1/a-settled"), settledOnly.rowKeys())

        val project = build(filter = HomeFilter(projectKey = "e2/p1"))
        assertEquals(listOf("e2/b", "e2/b-fail"), project.rowKeys())
        assertEquals("counts follow the project scope", 2, project.filterOptions.statuses.first { it.filter == StatusFilter.All }.count)
        assertEquals("but never the search", 2, build(query = "zzz", filter = HomeFilter(projectKey = "e2/p1")).filterOptions.statuses.first().count)
        assertEquals("count pills ignore filters", CountPills(working = 1, waiting = 1), project.header.counts)

        val environment = build(filter = HomeFilter(environmentId = "e1"))
        assertEquals(listOf("e1/a", "e1/a-run"), environment.rowKeys())
        assertEquals(3, environment.filterOptions.statuses.first().count)
    }

    @Test
    fun collapsedShelvesStayCollapsedAndEmptyOnesAreHidden() {
        val snoozedShell = env(
            "e1",
            "Laptop",
            shell(
                listOf(project("p1", "Alpha")),
                thread("s1", "p1", "snoozedUntil" to "2026-10-25T15:00:00.000Z"),
                thread("x", "p1"),
            ),
        )
        val collapsed = build(listOf(snoozedShell))
        assertEquals("no settled rows, no Settled shelf", listOf(HomeItem.ShelfHeader(Shelf.Snoozed, 1, false)), collapsed.headers())
        assertEquals(listOf("e1/x"), collapsed.rowKeys())

        val searching = build(listOf(snoozedShell), query = "s1", shelves = ShelfState())
        assertEquals(listOf(HomeItem.ShelfHeader(Shelf.Snoozed, 1, false)), searching.headers())
        assertTrue(searching.rowKeys().isEmpty())
        assertEquals("the search never opens a shelf", ShelfState(), searching.shelves)

        val open = build(listOf(snoozedShell), shelves = ShelfState(snoozedExpanded = true))
        assertEquals(listOf("e1/x", "e1/s1"), open.rowKeys())
        assertEquals(RowStyle.Slim, open.rows.getValue("e1/s1").style)
        assertEquals(RowTime.Countdown(3, RowTime.TimeUnit.Hour), open.rows.getValue("e1/s1").time)
        assertEquals(SwipeAction.Wake, open.rows.getValue("e1/s1").swipe.primary)
    }

    @Test
    fun workingShelfOnlyWhenGrouped() {
        assertTrue(build().headers().none { it.shelf == Shelf.Working })
        assertTrue("e1/a-run" in build().rowKeys())

        val grouped = build(shelves = ShelfState(grouped = true))
        assertEquals(HomeItem.ShelfHeader(Shelf.Working, 1, false), grouped.headers().first())
        assertFalse("folded into the collapsed shelf", "e1/a-run" in grouped.rowKeys())

        val open = build(shelves = ShelfState(grouped = true, workingExpanded = true))
        assertEquals(listOf("e2/b", "e1/a", "e2/b-fail", "e1/a-run"), open.rowKeys())
        val workingHeader = open.items.indexOfFirst { it is HomeItem.ShelfHeader && it.shelf == Shelf.Working }
        assertEquals("the shelf follows the active list", 3, workingHeader)
    }

    @Test
    fun settledShelfPagesWithShowMore() {
        val many = (1..30).map { n ->
            thread("s$n", "p1", "settledAt" to "2026-10-${(n % 28 + 1).toString().padStart(2, '0')}T00:00:00.000Z")
        }
        val environment = env("e1", "Laptop", shell(listOf(project("p1")), *many.toTypedArray()))
        val first = build(listOf(environment), shelves = ShelfState(settledExpanded = true))
        assertEquals(10, first.rowKeys().size)
        assertEquals(HomeItem.ShowMoreSettled(20), first.items.last())
        assertEquals(HomeItem.ShelfHeader(Shelf.Settled, 30, true), first.headers().single())

        val second = build(listOf(environment), shelves = ShelfState(settledExpanded = true), settledLimit = HomeStateBuilder.nextSettledLimit(10))
        assertEquals(30, second.rowKeys().size)
        assertTrue(second.items.none { it is HomeItem.ShowMoreSettled })
        assertEquals(35, HomeStateBuilder.nextSettledLimit(10))
    }

    @Test
    fun messagesSectionFollowsTheServerSearch() {
        val loading = build(query = "al", messages = MessageSearchState.Loading("al"))
        assertEquals(HomeItem.MessagesHeader(loading = true), loading.items.last())

        val match = MessageMatch("e2", "b", "p1", "message", "a snippet", "2026-10-25T11:00:00Z")
        val results = build(query = "zz", messages = MessageSearchState.Results("zz", listOf(match)))
        assertEquals(listOf(HomeItem.MessagesHeader(false), HomeItem.Message(match, "Thread b", "Server")), results.items)
        assertEquals("message matches are results", HomeContent.List, results.content)

        assertEquals(HomeContent.NoResults, build(query = "zz", messages = MessageSearchState.Results("zz", emptyList())).content)
    }

    @Test
    fun contentStates() {
        assertEquals(HomeContent.NoEnvironments, build(emptyList()).content)
        assertEquals(HomeContent.Connecting, build(listOf(env("e1", "L", null, ConnectionState.Connecting))).content)
        assertEquals(HomeContent.Connecting, build(listOf(env("e1", "L", null, ConnectionState.Unreachable("unreachable", now + 1000)))).content)
        assertEquals(HomeContent.Loading, build(listOf(env("e1", "L", null))).content)
        assertEquals(
            HomeContent.Unauthorized("e2"),
            build(listOf(env("e1", "L", null, ConnectionState.Connecting), env("e2", "S", null, ConnectionState.Unauthorized))).content,
        )
        assertEquals(HomeContent.Empty, build(listOf(env("e1", "L", shell(emptyList())))).content)

        val stale = build(listOf(laptop.copy(connection = ConnectionState.Unreachable("unreachable", now + 1000)), server))
        assertTrue(stale.stale)
        assertFalse(stale.offline)
        assertEquals(HeaderDot.Stale, stale.header.dot)
        assertEquals(HomeContent.List, stale.content)

        val offline = build(listOf(laptop.copy(connection = ConnectionState.Disconnected)))
        assertTrue(offline.stale)
        assertTrue("cached list kept", offline.offline && offline.rowKeys().isNotEmpty())
    }

    @Test
    fun chipIsTheFirstLetterUntilU21() {
        assertEquals(ProjectChip("O", ChipTint.Accent), HomeStateBuilder.chip("omarchy-t3-code"))
        assertEquals(ProjectChip("A", ChipTint.Accent), HomeStateBuilder.chip("  .alpha"))
        assertEquals(ProjectChip("3", ChipTint.Accent), HomeStateBuilder.chip("3d"))
        assertNull(HomeStateBuilder.chip(null))
        assertNull(HomeStateBuilder.chip("--"))
    }
}
