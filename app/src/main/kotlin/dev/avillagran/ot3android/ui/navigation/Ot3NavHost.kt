package dev.avillagran.ot3android.ui.navigation

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.first

/** A nav controller that also knows the [SheetNavigator]. */
@Composable
fun rememberOt3NavController(): NavHostController = rememberNavController(remember { SheetNavigator() })

/**
 * The app shell: the three tabs in a `NavigationBar` (hidden while the keyboard is up, as T3
 * Mobile's Android workspace), the nav host with a nested graph per tab, and the sheet host.
 * [screens] defaults to the app's view-model screens; tests pass fakes. [deepLink] is a thread
 * to open (from [ThreadDeepLink]); [onDeepLinkHandled] runs once it is open.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Ot3NavHost(
    navController: NavHostController = rememberOt3NavController(),
    screens: Ot3Screens? = null,
    deepLink: Thread? = null,
    onDeepLinkHandled: () -> Unit = {},
) {
    val navigator = remember(navController) { Ot3Navigator(navController) }
    LaunchedEffect(navigator, deepLink) {
        val link = deepLink ?: return@LaunchedEffect
        // The NavHost is subcomposed by the Scaffold: wait until its graph is set.
        navController.currentBackStackEntryFlow.first()
        navigator.openThread(link.environmentId, link.threadId, link.prompt)
        onDeepLinkHandled()
    }
    val resolvedScreens = screens ?: remember(navigator) { AppScreens(navigator) }
    val current = navController.currentBackStackEntryAsState()
    // Built once: the graph must not change on recomposition; isTop reads the current entry.
    val graph: NavGraphBuilder.() -> Unit = remember(resolvedScreens, current) {
        { ot3Graph(resolvedScreens) { entry -> entry.id == current.value?.id } }
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (!WindowInsets.isImeVisible) Ot3NavigationBar(navigator, TopTab.of(current.value))
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = ChatsGraph,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
            builder = graph,
        )
        SheetHost(navController.sheetNavigator)
    }
}

/** Chats / Pull requests / Settings. Plain Material until the kit restyles it. */
@Composable
private fun Ot3NavigationBar(navigator: Ot3Navigator, selected: TopTab?) {
    NavigationBar(Modifier.testTag(NavTags.BAR)) {
        TopTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == selected,
                onClick = { navigator.selectTab(tab) },
                icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                label = { Text(stringResource(tab.label)) },
                modifier = Modifier.testTag(NavTags.tab(tab)),
            )
        }
    }
}
