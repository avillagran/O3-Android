package dev.avillagran.ot3android.ui.navigation

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph
import androidx.navigation.NavHostController
import androidx.navigation.toRoute
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.core.home.HomeContent
import dev.avillagran.ot3android.core.home.HomeUiState
import dev.avillagran.ot3android.ui.home.HomeIntent
import dev.avillagran.ot3android.ui.home.HomeScreen
import dev.avillagran.ot3android.ui.home.HomeTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The real graph, navigator and sheet navigator with fake screens (Home is the real stateless
 * HomeScreen over a fake query), on the JVM with Robolectric.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class Ot3NavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navController: NavHostController
    private val navigator get() = Ot3Navigator(navController)
    private val screens = FakeScreens()

    private fun launch(restoration: StateRestorationTester? = null) {
        val content: @Composable () -> Unit = {
            navController = rememberOt3NavController()
            Ot3NavHost(navController, screens)
        }
        if (restoration != null) restoration.setContent(content) else composeRule.setContent(content)
        composeRule.waitForIdle()
    }

    private fun onUi(action: () -> Unit) {
        composeRule.runOnUiThread(action)
        composeRule.waitForIdle()
    }

    private fun pressBack() = onUi { composeRule.activity.onBackPressedDispatcher.onBackPressed() }

    /** The destinations on the back stack (graphs left out), as route class names. */
    private fun stack(): List<String> = navController.currentBackStack.value
        .filter { it.destination !is NavGraph }
        .map { it.destination.route.orEmpty().substringBefore('/').substringBefore('?').substringAfterLast('.') }

    private fun NavBackStackEntry.thread(): Thread? = if (destination.hasRoute(Thread::class)) toRoute<Thread>() else null

    @Test
    fun startsOnHomeInTheChatsTabWithTheBarShown() {
        launch()
        assertEquals(listOf("Home"), stack())
        assertEquals(TopTab.Chats, navigator.currentTab)
        composeRule.onNodeWithTag(NavTags.BAR).assertIsDisplayed()
        composeRule.onNodeWithTag(HomeTags.ROOT).assertIsDisplayed()
    }

    @Test
    fun chatHidesGlobalFooterAndBackRestoresIt() {
        launch()
        onUi { navigator.openThread("e1", "t1") }
        composeRule.onNodeWithTag(NavTags.BAR).assertDoesNotExist()
        val entry = navController.currentBackStackEntry
        onUi { navigator.openThread("e1", "t1") }
        assertEquals("Reselecting must keep the session owner", entry, navController.currentBackStackEntry)
        pressBack()
        composeRule.onNodeWithTag(NavTags.BAR).assertIsDisplayed()
    }

    @Test
    fun openingAThreadResetsTheChatsStackToHomeAndThread() {
        launch()
        onUi { navigator.openThread("e1", "t1") }
        onUi { navigator.openThread("e2", "t2") }
        assertEquals(listOf("Home", "Thread"), stack())
        assertEquals(Thread("e2", "t2"), navController.currentBackStackEntry?.thread())
        composeRule.onNodeWithTag("fake_thread_e2_t2").assertIsDisplayed()

        // From another tab: back to Chats, same reset.
        onUi { navigator.selectTab(TopTab.Settings) }
        onUi { navigator.push(Environments) }
        onUi { navigator.openThread("e1", "t3") }
        assertEquals(TopTab.Chats, navigator.currentTab)
        assertEquals(listOf("Home", "Thread"), stack().filter { it == "Home" || it == "Thread" })
        assertEquals(Thread("e1", "t3"), navController.currentBackStackEntry?.thread())
        onUi { navigator.selectTab(TopTab.Settings) }
        assertEquals("the Settings stack was kept", "Environments", stack().last())
    }

    @Test
    fun aPairingLinkOpensThePairingScreenOnceUnderSettings() {
        launch()
        onUi { navigator.openThread("e1", "t1") }
        onUi { navigator.openPairing() }
        assertEquals(TopTab.Settings, navigator.currentTab)
        assertEquals(listOf("Settings", "Environments", "PairEnvironment"), stack().takeLast(3))
        composeRule.onNodeWithTag("fake_pair").assertIsDisplayed()

        // The scanner on top: a second link goes back to the same pairing entry.
        onUi { navigator.push(ScanPairingCode) }
        val pairing = navController.currentBackStack.value.last { it.destination.hasRoute(PairEnvironment::class) }.id
        onUi { navigator.selectTab(TopTab.Chats) }
        onUi { navigator.openPairing() }
        assertEquals("PairEnvironment", stack().last())
        assertEquals("the screen (and its pairing) is reused", pairing, navController.currentBackStackEntry?.id)
        onUi { navigator.openPairing() }
        assertEquals(1, stack().count { it == "PairEnvironment" })

        onUi { navigator.environmentSaved() }
        assertEquals(TopTab.Chats, navigator.currentTab)
    }

    @Test
    fun tabSwitchingPreservesEachStackAndReselectingReturnsToTheRoot() {
        launch()
        onUi { navigator.openThread("e1", "t1") }
        onUi { navigator.selectTab(TopTab.Settings) }
        composeRule.waitForIdle()
        onUi { navigator.push(Environments) }
        assertEquals("Environments", stack().last())

        composeRule.onNodeWithTag(NavTags.tab(TopTab.PullRequests)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(NavTags.notYet("PullRequests")).assertIsDisplayed()

        composeRule.onNodeWithTag(NavTags.tab(TopTab.Chats)).performClick()
        composeRule.waitForIdle()
        assertEquals(Thread("e1", "t1"), navController.currentBackStackEntry?.thread())

        onUi { navigator.selectTab(TopTab.Settings) }
        composeRule.waitForIdle()
        assertEquals("Environments", stack().last())

        onUi { navigator.selectTab(TopTab.Settings) }
        composeRule.waitForIdle()
        assertEquals("reselecting pops to the root", "Settings", stack().last())
    }

    @Test
    fun backClosesTheSheetThenTheSearchThenTheDetail() {
        launch()
        screens.query = "fix"
        onUi { navigator.openSheet(ThreadListOptionsSheet) }
        assertEquals(listOf("Home", "ThreadListOptionsSheet"), stack())
        composeRule.onNodeWithTag("fake_list_options").assertExists()

        pressBack()
        assertEquals("the sheet goes first", listOf("Home"), stack())
        assertEquals("the search is still there", "fix", screens.query)

        pressBack()
        assertEquals("then the search clears", "", screens.query)
        assertEquals(listOf("Home"), stack())
        assertFalse(composeRule.activity.isFinishing)

        onUi { navigator.openThread("e1", "t1") }
        pressBack()
        assertEquals("then detail → list", listOf("Home"), stack())
    }

    @Test
    fun sheetsAreBackStackEntriesWithArgumentsAndCloseByEntry() {
        launch()
        onUi { navigator.openThread("e1", "t1") }
        onUi { navController.popBackStack() }
        onUi { navigator.openSheet(ThreadActionsSheet("e1", "t1")) }
        val entry = navController.currentBackStackEntry!!
        assertEquals(ThreadActionsSheet("e1", "t1"), entry.toRoute<ThreadActionsSheet>())
        composeRule.onNodeWithTag("fake_thread_actions_e1_t1").assertExists()
        assertTrue("Home stays resumed under a floating sheet", navController.getBackStackEntry(Home).lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))

        onUi { navigator.openSheet(SnoozeUntilSheet("e1", "t1")) }
        assertEquals(listOf("Home", "ThreadActionsSheet", "SnoozeUntilSheet"), stack())
        onUi { navigator.closeSheet(entry) }
        assertEquals("closing a sheet closes the ones above it", listOf("Home"), stack())
    }

    @Test
    fun sheetsSurviveProcessDeathWithTheirArguments() {
        val restoration = StateRestorationTester(composeRule)
        launch(restoration)
        onUi { navigator.openSheet(ProjectActionsSheet("e1", "p1")) }
        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        assertEquals(listOf("Home", "ProjectActionsSheet"), stack())
        assertEquals(ProjectActionsSheet("e1", "p1"), navController.currentBackStackEntry!!.toRoute<ProjectActionsSheet>())
        composeRule.onNodeWithTag("fake_project_actions_e1_p1").assertExists()
    }

    @Test
    fun threadDeepLinkOpensOverHomeFromAnyTab() {
        var link by mutableStateOf<Thread?>(null)
        composeRule.setContent {
            navController = rememberOt3NavController()
            Ot3NavHost(navController, screens, deepLink = link, onDeepLinkHandled = { link = null })
        }
        composeRule.waitForIdle()
        onUi { navigator.selectTab(TopTab.Settings) }
        onUi { navigator.push(Environments) }
        link = ThreadDeepLink.parse(Uri.parse("ot3a://thread/e1/t%2F1?prompt=hi"))
        composeRule.waitForIdle()
        assertEquals(TopTab.Chats, navigator.currentTab)
        assertEquals(listOf("Home", "Thread"), stack().filter { it == "Home" || it == "Thread" })
        assertEquals(Thread("e1", "t/1", "hi"), navController.currentBackStackEntry?.thread())
        assertEquals("handled once", null, link)
        pressBack()
        assertEquals("Home", stack().last())
    }

    @Test
    fun threadDeepLinkAtLaunchOpensOverHome() {
        var link by mutableStateOf(ThreadDeepLink.parse(Uri.parse("ot3a://thread/e1/t1")))
        composeRule.setContent {
            navController = rememberOt3NavController()
            Ot3NavHost(navController, screens, deepLink = link, onDeepLinkHandled = { link = null })
        }
        composeRule.waitForIdle()
        assertEquals(listOf("Home", "Thread"), stack())
        assertEquals(null, link)
    }

    @Test
    fun threadDeepLinkParsesOnlyItsOwnShape() {
        assertEquals(Thread("e1", "t1"), ThreadDeepLink.parse(Uri.parse("ot3a://thread/e1/t1")))
        assertEquals(Thread("e 1", "a/b?c", "x y"), ThreadDeepLink.parse(ThreadDeepLink.uri(Thread("e 1", "a/b?c", "x y"))))
        assertEquals(null, ThreadDeepLink.parse(Uri.parse("ot3a://thread/e1")))
        assertEquals(null, ThreadDeepLink.parse(Uri.parse("ot3a://other/e1/t1")))
        assertEquals(null, ThreadDeepLink.parse(Uri.parse("https://thread/e1/t1")))
        assertEquals(null, ThreadDeepLink.parse(null))
    }

    @Test
    fun environmentsLiveUnderSettingsAndSavingReturnsHome() {
        launch()
        onUi { navigator.addEnvironment() }
        assertEquals(TopTab.Settings, navigator.currentTab)
        assertEquals(listOf("Settings", "Environments", "AddEnvironment"), stack().takeLast(3))
        onUi { navigator.environmentSaved() }
        assertEquals(TopTab.Chats, navigator.currentTab)
        assertEquals("Home", stack().last())
        onUi { navigator.selectTab(TopTab.Settings) }
        assertEquals("the Settings stack ends at the list", "Environments", stack().last())

        onUi { navigator.selectTab(TopTab.Chats) }
        onUi { navigator.openEnvironments() }
        assertEquals(listOf("Settings", "Environments"), stack().takeLast(2))
    }

    @Test
    fun everyUnbuiltDestinationRoutesToThePlaceholder() {
        launch()
        listOf(
            ThreadChanges("e1", "t1", "turn") to "ThreadChanges",
            NewThread to "NewThread",
            NewThreadDraft("e1", "p1") to "NewThreadDraft",
            AddProject() to "AddProject",
            CommandPalette to "CommandPalette",
        ).forEach { (route, name) ->
            onUi { navigator.push(route) }
            composeRule.onNodeWithTag(NavTags.notYet(name)).assertIsDisplayed()
            onUi { navController.popBackStack() }
        }
        onUi { navigator.selectTab(TopTab.PullRequests) }
        onUi { navigator.push(PullRequest("e1", "p1", "o/r", 3)) }
        composeRule.onNodeWithTag(NavTags.notYet("PullRequest")).assertIsDisplayed()
        onUi { navigator.push(PullRequestFile("e1", "p1", "o/r", 3, "a.kt")) }
        composeRule.onNodeWithTag(NavTags.notYet("PullRequestFile")).assertIsDisplayed()
        onUi { navigator.selectTab(TopTab.Settings) }
        listOf(Usage to "Usage", Archive to "Archive", Licenses to "Licenses").forEach { (route, name) ->
            onUi { navigator.push(route) }
            composeRule.onNodeWithTag(NavTags.notYet(name)).assertIsDisplayed()
            onUi { navController.popBackStack() }
        }
    }
}

/** Screens that only show where navigation went; Home is the real stateless screen. */
private class FakeScreens : Ot3Screens {
    var query by mutableStateOf("")

    @Composable
    override fun Home(entry: NavBackStackEntry, isTop: Boolean) {
        HomeScreen(
            state = HomeUiState(content = HomeContent.Empty, query = query),
            isTop = isTop,
            onIntent = { intent ->
                when (intent) {
                    is HomeIntent.SetQuery -> query = intent.text
                    HomeIntent.ClearSearch -> query = ""
                    else -> Unit
                }
            },
        )
    }

    @Composable
    override fun Thread(route: Thread) = Tagged("fake_thread_${route.environmentId}_${route.threadId}")

    @Composable
    override fun Settings() = Tagged("fake_settings")

    @Composable
    override fun Environments() = Tagged("fake_environments")

    @Composable
    override fun AddEnvironment(route: AddEnvironment) = Tagged("fake_add_environment")

    @Composable
    override fun PairEnvironment() = Tagged("fake_pair")

    @Composable
    override fun ScanPairingCode() = Tagged("fake_scan")

    @Composable
    override fun ThreadActions(entry: NavBackStackEntry, route: ThreadActionsSheet) {
        // Saved state of a sheet entry survives with it.
        val opened by rememberSaveable { mutableStateOf(true) }
        if (opened) Tagged("fake_thread_actions_${route.environmentId}_${route.threadId}")
    }

    @Composable
    override fun ProjectActions(entry: NavBackStackEntry, route: ProjectActionsSheet) =
        Tagged("fake_project_actions_${route.environmentId}_${route.projectId}")

    @Composable
    override fun ThreadListOptions(entry: NavBackStackEntry) = Tagged("fake_list_options")

    @Composable
    override fun SnoozeUntil(entry: NavBackStackEntry, route: SnoozeUntilSheet) = Tagged("fake_snooze")

    @Composable
    private fun Tagged(tag: String) {
        Text(tag, modifier = Modifier.testTag(tag))
    }
}
