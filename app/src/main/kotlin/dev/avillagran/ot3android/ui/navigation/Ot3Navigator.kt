package dev.avillagran.ot3android.ui.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.toRoute
import androidx.navigation.NavHostController
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.ui.icons.Ot3Icons

/** The three tabs of T3 Mobile's Android workspace (UI_SPEC §2.1), each with its own back stack. */
enum class TopTab(val graph: Any, val root: Any, @param:StringRes val label: Int, @param:DrawableRes val icon: Int) {
    Chats(ChatsGraph, Home, R.string.mobile_tab_chats, Ot3Icons.MessageSquare),
    PullRequests(PullRequestsGraph, dev.avillagran.ot3android.ui.navigation.PullRequests, R.string.ot3_tab_pull_requests, Ot3Icons.GitPullRequest),
    Settings(SettingsGraph, dev.avillagran.ot3android.ui.navigation.Settings, R.string.ot3_tab_settings, Ot3Icons.Settings),
    ;

    companion object {
        /** The tab whose graph holds [entry]'s destination. */
        fun of(entry: NavBackStackEntry?): TopTab? {
            val hierarchy = entry?.destination?.hierarchy ?: return null
            return when {
                hierarchy.any { it.hasRoute(ChatsGraph::class) } -> Chats
                hierarchy.any { it.hasRoute(PullRequestsGraph::class) } -> PullRequests
                hierarchy.any { it.hasRoute(SettingsGraph::class) } -> Settings
                else -> null
            }
        }
    }
}

/**
 * Every navigation the screens ask for, in one place so the rules of UI_SPEC §2.1 hold
 * everywhere: switching tabs keeps each tab's stack; reselecting a tab returns to its root;
 * opening a thread resets the Chats stack to `[Home, Thread]` (T3 Mobile,
 * `t3m:src/lib/android-workspace-navigation.ts:30-50`); Environments lives under Settings.
 */
class Ot3Navigator(val navController: NavHostController) {

    val currentTab: TopTab? get() = TopTab.of(navController.currentBackStackEntry)

    /** Shows [tab] with its saved stack; on the current tab, pops back to its root. */
    fun selectTab(tab: TopTab) {
        if (currentTab == tab) {
            navController.popBackStack(tab.root, inclusive = false)
            return
        }
        navController.navigate(tab.graph) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    /** Opens a thread from anywhere: the Chats tab, its stack reset to `[Home, Thread]`. */
    fun openThread(environmentId: String, threadId: String, prompt: String? = null) {
        val current = navController.currentBackStackEntry
        if (current?.destination?.hasRoute(Thread::class) == true) {
            val selected = current.toRoute<Thread>()
            if (selected.environmentId == environmentId && selected.threadId == threadId) return
        }
        if (currentTab != TopTab.Chats) selectTab(TopTab.Chats)
        navController.navigate(Thread(environmentId, threadId, prompt)) {
            popUpTo(Home) { inclusive = false }
            launchSingleTop = true
        }
    }

    /** Pushes a sheet (a [sheet] destination) over the current screen. */
    fun openSheet(route: Any) {
        navController.navigate(route) { launchSingleTop = true }
    }

    /** Closes the sheet [entry] (and any sheet above it), whatever is on top. */
    fun closeSheet(entry: NavBackStackEntry) {
        navController.sheetNavigator.dismiss(entry)
    }

    /** Pushes any destination on the current tab's stack. */
    fun push(route: Any) {
        navController.navigate(route)
    }

    /** Settings → Environments, from the header status or an empty Home. */
    fun openEnvironments() {
        if (currentTab != TopTab.Settings) selectTab(TopTab.Settings)
        navController.navigate(Environments) {
            popUpTo(dev.avillagran.ot3android.ui.navigation.Settings) { inclusive = false }
            launchSingleTop = true
        }
    }

    /** Settings → Environments → Add environment (or edit [environmentId]). */
    fun addEnvironment(environmentId: String? = null) {
        openEnvironments()
        navController.navigate(AddEnvironment(environmentId = environmentId)) { launchSingleTop = true }
    }

    /**
     * The pairing screen, for a link that arrived from outside: one already in the Settings
     * stack is reused (its pairing may be in flight), else it opens over Environments.
     */
    fun openPairing() {
        if (navController.currentBackStackEntry?.destination?.hasRoute(PairEnvironment::class) == true) return
        if (currentTab != TopTab.Settings) selectTab(TopTab.Settings)
        if (navController.popBackStack(PairEnvironment, inclusive = false)) return
        openEnvironments()
        navController.navigate(PairEnvironment) { launchSingleTop = true }
    }

    /** After saving an environment: back to the list, then to Home, where its threads appear. */
    fun environmentSaved() {
        navController.popBackStack(Environments, inclusive = false)
        selectTab(TopTab.Chats)
        navController.popBackStack(Home, inclusive = false)
    }

    /** Returns from Settings to the Home list, including when Chats retained a thread. */
    fun home() {
        selectTab(TopTab.Chats)
        navController.popBackStack(Home, inclusive = false)
    }

    /** Pops the top entry (a sheet, then a detail). False at a tab root. */
    fun back(): Boolean = navController.popBackStack()
}
