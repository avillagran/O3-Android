package dev.avillagran.ot3android.core.home

import dev.avillagran.ot3android.core.connection.ConnectionState
import dev.avillagran.ot3android.core.connection.ServerDescriptor
import dev.avillagran.ot3android.core.shell.RowTime
import dev.avillagran.ot3android.core.shell.ShellFixtures.body
import dev.avillagran.ot3android.core.shell.ShellFixtures.project
import dev.avillagran.ot3android.core.shell.ShellFixtures.thread
import dev.avillagran.ot3android.core.shell.ShellReducer
import dev.avillagran.ot3android.core.shell.ShellState
import dev.avillagran.ot3android.core.transport.DomainEngine
import dev.avillagran.ot3android.core.transport.assumeHostJni
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Home over the real `sidebar.view` (host JNI library): the builder passes Home's view state
 * to the lib and maps its result. Order, details, labels, times and counts are the lib's; these
 * tests check that they reach Home unchanged and that the view state (shelves, paging, the
 * message search) is applied on top.
 */
class HomeStateBuilderTest {

    @Before
    fun setUp() = assumeHostJni()

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
            thread(
                "a-run",
                "p1",
                "createdAt" to "2026-10-25T09:00:00.000Z",
                "status" to "running",
                "latestRunId" to "r1",
                "activeRunId" to "r1",
                "latestRunStartedAt" to "2026-10-25T11:57:00.000Z",
                "worktreePath" to "/w/alpha-wt",
            ),
            thread("a-settled", "p1", "settledAt" to "2026-10-25T08:00:00.000Z", "title" to "Old alpha work"),
        ),
    )
    private val server = env(
        "e2",
        "Server",
        shell(
            listOf(project("p1", "Beta")),
            thread("b", "p1", "createdAt" to "2026-10-25T11:00:00.000Z", "pendingRuntimeRequest" to JSONObject().put("kind", "approval").put("id", "q1")),
            thread("b-fail", "p1", "createdAt" to "2026-10-25T07:00:00.000Z", "status" to "failed", "latestRunId" to "r9"),
        ),
    )

    private fun build(
        environments: List<HomeEnvironment> = listOf(laptop, server),
        query: String = "",
        filter: HomeFilter = HomeFilter(),
        shelves: ShelfState = ShelfState(),
        settledLimit: Int = HomeStateBuilder.SETTLED_INITIAL,
        messages: MessageSearchState = MessageSearchState.Idle,
    ): HomeUiState = runBlocking {
        HomeStateBuilder.build(DomainEngine.shared, HomeInput(environments, query, filter, shelves, settledLimit, messages, now = now))
    }

    private fun HomeUiState.rowKeys() = items.filterIsInstance<HomeItem.Row>().map { it.row.key }

    private fun HomeUiState.headers() = items.filterIsInstance<HomeItem.ShelfHeader>()

    @Test
    fun mergesEveryEnvironmentIntoOneListInTheLibsOrder() {
        val input = HomeInput(listOf(laptop, server), now = now)
        val sidebar = runBlocking { HomeStateBuilder.view(DomainEngine.shared, input) }
        val state = HomeStateBuilder.build(input, sidebar)

        assertEquals(HomeContent.List, state.content)
        assertEquals("the active section, as the lib orders it", sidebar.active.map { it.key }, state.rowKeys())
        assertEquals(setOf("e1/a", "e1/a-run", "e2/b", "e2/b-fail"), state.rowKeys().toSet())
        assertEquals(listOf(HomeItem.ShelfHeader(Shelf.Settled, 1, expanded = false)), state.headers())
        assertEquals("the same project id in two environments stays apart", "e2/p1", state.rows.getValue("e2/b").projectKey)
        assertEquals("e1/p1", state.rows.getValue("e1/a").projectKey)
        assertEquals("the lib's counts", CountPills(working = 1, done = 0, waiting = 1), state.header.counts)
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
        assertEquals(listOf(ProjectOption("e1/p1", "Alpha", null, "/w/alpha")), one.filterOptions.projects)
        assertEquals(mapOf("e1/p1" to ProjectOption("e1/p1", "Alpha", null, "/w/alpha")), one.projects)

        val withoutShell = build(listOf(laptop, env("e3", "Pending", null, ConnectionState.Connecting)))
        assertEquals("a saved environment counts even before its shell", "Laptop", withoutShell.rows.getValue("e1/a").environmentLabel)
    }

    @Test
    fun rowCarriesTheLibsDetailLabelRoleTimeAndChip() {
        val state = build()
        val working = state.rows.getValue("e1/a-run")
        assertEquals("working", working.detail)
        assertEquals("Working", working.statusLabel)
        assertEquals("working", working.statusRole)
        assertEquals(RowTime("elapsed", now - 180_000, "3m 0s"), working.time)
        assertTrue(working.working)
        assertEquals(RowStyle.Card, working.style)
        assertEquals("the worktree is what Copy Path copies", "/w/alpha-wt", working.path)
        assertTrue(working.worktree)

        val approval = state.rows.getValue("e2/b")
        assertEquals("approval" to "Approval", approval.detail to approval.statusLabel)
        assertEquals("warning", approval.statusRole)
        val chip = approval.identity!!
        assertEquals("the lib's monogram", "BA", chip.monogram)
        assertNotNull("slots over every project of the view", chip.colorSlot)
        assertEquals("failed", state.rows.getValue("e2/b-fail").detail)

        val quiet = state.rows.getValue("e1/a")
        assertEquals("", quiet.statusLabel)
        assertEquals("ShellFixtures' updatedAt is 26 h before now", RowTime("age", now - 26 * 3_600_000, "1d"), quiet.time)
        assertEquals("/w/alpha", quiet.path)
        assertFalse(quiet.worktree)
        assertEquals("main", quiet.branch)
        assertEquals("Alpha", quiet.projectTitle)

        val settled = state.rows.getValue("e1/a-settled")
        assertEquals(RowStyle.Slim, settled.style)
        assertEquals("a settled row shows its settled age", "4h", settled.time.label)

        assertEquals("swipes are modelled but disabled until the builders exist", SwipeActions(SwipeAction.Archive, null, false), quiet.swipe)
        assertEquals(SwipeAction.MoveToActive, settled.swipe.primary)
    }

    @Test
    fun searchIsTheLibsAndIncludesSettled() {
        assertEquals(setOf("e2/b", "e2/b-fail"), build(query = "beta").rowKeys().toSet())
        assertEquals("by title", listOf("e1/a-run"), build(query = "a-run").rowKeys())
        assertEquals("trimmed", listOf("e1/a-run"), build(query = "  a-run ").rowKeys())

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
    fun filtersWithTheLibsCounts() {
        val all = build()
        assertEquals(
            mapOf(
                StatusFilter.All to 5,
                StatusFilter.Working to 1,
                StatusFilter.Done to 0,
                StatusFilter.Failed to 1,
                StatusFilter.Pending to 2,
                StatusFilter.Settled to 1,
            ),
            all.filterOptions.statuses.associate { it.filter to it.count },
        )
        assertFalse(all.filter.active)

        val working = build(filter = HomeFilter(status = StatusFilter.Working))
        assertTrue(working.filter.active)
        assertEquals(listOf("e1/a-run"), working.rowKeys())
        assertEquals(setOf("e2/b", "e2/b-fail"), build(filter = HomeFilter(status = StatusFilter.Pending)).rowKeys().toSet())
        assertEquals(listOf("e2/b-fail"), build(filter = HomeFilter(status = StatusFilter.Failed)).rowKeys())
        assertEquals(HomeContent.NoResults, build(filter = HomeFilter(status = StatusFilter.Done)).content)

        val settledOnly = build(filter = HomeFilter(status = StatusFilter.Settled), shelves = ShelfState(settledExpanded = true))
        assertEquals(listOf("e1/a-settled"), settledOnly.rowKeys())

        val project = build(filter = HomeFilter(projectKey = "e2/p1"))
        assertEquals(setOf("e2/b", "e2/b-fail"), project.rowKeys().toSet())
        assertEquals(setOf("e2/p1"), project.projectScope)
        assertTrue("no scope while the filter is off", all.projectScope.isEmpty())
        assertEquals("counts follow the project scope", 2, project.filterOptions.statuses.first { it.filter == StatusFilter.All }.count)
        assertEquals("but never the search", 2, build(query = "zzz", filter = HomeFilter(projectKey = "e2/p1")).filterOptions.statuses.first().count)
        assertEquals("count pills ignore filters", CountPills(working = 1, waiting = 1), project.header.counts)

        val environment = build(filter = HomeFilter(environmentId = "e1"))
        assertEquals(setOf("e1/a", "e1/a-run"), environment.rowKeys().toSet())
        assertEquals(3, environment.filterOptions.statuses.first().count)
    }

    @Test
    fun projectsOfOneRepositoryFilterTogether() {
        val repository = env(
            "e1",
            "Laptop",
            shell(
                listOf(project("p1", "Alpha", identity = "github.com/o/alpha"), project("p2", "Alpha copy", identity = "github.com/o/alpha")),
                thread("x", "p1"),
                thread("y", "p2"),
            ),
        )
        val state = build(listOf(repository))
        val group = state.filterOptions.projects.single()
        assertEquals("Alpha", group.title)

        val filtered = build(listOf(repository), filter = HomeFilter(projectKey = group.key))
        assertEquals(setOf("e1/x", "e1/y"), filtered.rowKeys().toSet())
        assertEquals("a member's key covers its group", setOf("e1/p1", "e1/p2"), build(listOf(repository), filter = HomeFilter(projectKey = "e1/p2")).projectScope)
        assertEquals(setOf("e1/p1", "e1/p2"), state.projects.keys)
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
        val snoozed = open.rows.getValue("e1/s1")
        assertEquals(RowStyle.Slim, snoozed.style)
        assertEquals(RowTime("countdown", now + 3 * 3_600_000, "3h"), snoozed.time)
        assertEquals(SwipeAction.Wake, snoozed.swipe.primary)
    }

    @Test
    fun workingShelfOnlyWhenGrouped() {
        assertTrue(build().headers().none { it.shelf == Shelf.Working })
        assertTrue("e1/a-run" in build().rowKeys())

        val grouped = build(shelves = ShelfState(grouped = true))
        assertEquals(HomeItem.ShelfHeader(Shelf.Working, 1, false), grouped.headers().first())
        assertFalse("folded into the collapsed shelf", "e1/a-run" in grouped.rowKeys())

        val open = build(shelves = ShelfState(grouped = true, workingExpanded = true))
        assertEquals("e1/a-run", open.rowKeys().last())
        val workingHeader = open.items.indexOfFirst { it is HomeItem.ShelfHeader && it.shelf == Shelf.Working }
        assertEquals("the shelf follows the active list", 3, workingHeader)
    }

    @Test
    fun subThreadsNestUnderTheirParentAsSlimRows() {
        val nested = env(
            "e1",
            "Laptop",
            shell(
                listOf(project("p1", "Alpha")),
                thread("parent", "p1"),
                thread(
                    "child",
                    "p1",
                    "status" to "running",
                    "latestRunId" to "r2",
                    "activeRunId" to "r2",
                    "lineage" to JSONObject().put("relationshipToParent", "subagent").put("parentThreadId", "parent"),
                ),
            ),
        )
        val state = build(listOf(nested))
        assertEquals("a working child opens its parent", listOf("e1/parent", "e1/child"), state.rowKeys())
        val child = state.items.filterIsInstance<HomeItem.Row>().last().row
        assertEquals(1, child.depth)
        assertTrue(child.subagent)
        assertEquals(RowStyle.Slim, child.style)
        assertEquals("sub-threads are no top-level rows", 1, state.filterOptions.statuses.first().count)
    }

    @Test
    fun settledShelfPagesWithShowMore() {
        val many = (1..30).map { n ->
            thread("s$n", "p1", "settledAt" to "2026-10-${(n % 24 + 1).toString().padStart(2, '0')}T00:00:00.000Z")
        }
        val environment = env("e1", "Laptop", shell(listOf(project("p1")), *many.toTypedArray()))
        val first = build(listOf(environment), shelves = ShelfState(settledExpanded = true))
        assertEquals(10, first.rowKeys().size)
        assertEquals(HomeItem.ShowMoreSettled(20), first.items.last())
        assertEquals(HomeItem.ShelfHeader(Shelf.Settled, 30, true), first.headers().single())

        val second = build(listOf(environment), shelves = ShelfState(settledExpanded = true), settledLimit = HomeStateBuilder.nextSettledLimit(10))
        assertEquals(30, second.rowKeys().size)
        assertEquals("the first page is the start of the lib's order", first.rowKeys(), second.rowKeys().take(10))
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
    fun groupedViewsPassTheLibsReturnsBack() = runBlocking {
        val input = HomeInput(listOf(laptop, server), shelves = ShelfState(grouped = true), now = now)
        val first = HomeStateBuilder.view(DomainEngine.shared, input)
        assertNotNull("a grouped view returns what to pass back", first.returns)
        val second = HomeStateBuilder.view(DomainEngine.shared, input.copy(now = now + 1000, returns = first.returns))
        assertEquals(first.working.map { it.key }, second.working.map { it.key })
    }
}
