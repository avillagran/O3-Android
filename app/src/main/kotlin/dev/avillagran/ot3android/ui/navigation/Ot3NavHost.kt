package dev.avillagran.ot3android.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.avillagran.ot3android.appGraph
import dev.avillagran.ot3android.appViewModel
import dev.avillagran.ot3android.ui.connect.ConnectScreen
import dev.avillagran.ot3android.ui.connect.ConnectViewModel
import dev.avillagran.ot3android.ui.environments.EnvironmentsScreen
import dev.avillagran.ot3android.ui.environments.EnvironmentsViewModel
import dev.avillagran.ot3android.ui.pair.PairScreen
import dev.avillagran.ot3android.ui.pair.PairViewModel
import dev.avillagran.ot3android.ui.pair.QrScanScreen
import dev.avillagran.ot3android.ui.sidebar.SidebarScreen
import dev.avillagran.ot3android.ui.sidebar.SidebarViewModel
import dev.avillagran.ot3android.ui.thread.ThreadScreen
import dev.avillagran.ot3android.ui.thread.ThreadViewModel

/**
 * String routes for now; typed (serializable) routes are a pending decision in
 * docs/DECISIONS.md. `environmentId` in routes is the local catalog id
 * (`Environment.id`), not the server's environment UUID.
 */
object Routes {
    const val ENVIRONMENTS = "environments"
    const val CONNECT = "connect?environmentId={environmentId}"
    const val SIDEBAR = "sidebar/{environmentId}"
    const val THREAD = "thread/{environmentId}/{threadId}"

    // No arguments: a pairing link holds a one-time code and travels through PairingInbox.
    const val PAIR = "pair"
    const val SCAN = "pair/scan"

    // Ids are encoded: a thread id may hold `/`, `?` or `#`. Navigation decodes arguments.
    fun connect(environmentId: String? = null) =
        if (environmentId == null) "connect" else "connect?environmentId=${Uri.encode(environmentId)}"

    fun sidebar(environmentId: String) = "sidebar/${Uri.encode(environmentId)}"
    fun thread(environmentId: String, threadId: String) = "thread/${Uri.encode(environmentId)}/${Uri.encode(threadId)}"
}

@Composable
fun Ot3NavHost(startDestination: String, navController: NavHostController = rememberNavController()) {
    val inbox = LocalContext.current.appGraph.pairingInbox
    // A pairing link from outside (deep link) opens the pairing screen, reusing one already in
    // the back stack; the screen takes the link once it is started.
    val waitingLink by inbox.link.collectAsStateWithLifecycle()
    LaunchedEffect(waitingLink != null) {
        if (waitingLink != null && navController.currentDestination?.route != Routes.PAIR) {
            navController.navigate(Routes.PAIR) {
                popUpTo(Routes.PAIR)
                launchSingleTop = true
            }
        }
    }
    // The catalog becomes the root, then the saved environment's sidebar opens.
    val openSaved: (String) -> Unit = { id ->
        navController.navigate(Routes.ENVIRONMENTS) { popUpTo(navController.graph.id) { inclusive = true } }
        navController.navigate(Routes.sidebar(id))
    }
    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.ENVIRONMENTS) {
            EnvironmentsScreen(
                viewModel = appViewModel { graph -> EnvironmentsViewModel(graph.environmentRepository, graph.connectionManager) },
                onOpen = { id -> navController.navigate(Routes.sidebar(id)) },
                onAdd = { navController.navigate(Routes.connect()) },
                onEdit = { id -> navController.navigate(Routes.connect(id)) },
            )
        }
        composable(
            Routes.CONNECT,
            arguments = listOf(navArgument("environmentId") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            val editingId = entry.arguments?.getString("environmentId")
            ConnectScreen(
                viewModel = appViewModel { graph ->
                    ConnectViewModel(graph.serverProbe, graph.environmentRepository, editingId, graph.connectionManager::forget)
                },
                onSaved = openSaved,
                onPair = if (editingId == null) {
                    { navController.navigate(Routes.PAIR) }
                } else {
                    null
                },
                onBack = if (navController.previousBackStackEntry != null) {
                    { navController.popBackStack() }
                } else {
                    null
                },
            )
        }
        composable(Routes.PAIR) {
            PairScreen(
                viewModel = appViewModel { graph -> PairViewModel(graph.pairingService()) },
                inbox = inbox.link,
                takeFromInbox = inbox::take,
                onScan = { navController.navigate(Routes.SCAN) },
                onPaired = openSaved,
                onBack = if (navController.previousBackStackEntry != null) {
                    { navController.popBackStack() }
                } else {
                    null
                },
            )
        }
        composable(Routes.SCAN) {
            QrScanScreen(
                onScanned = { text ->
                    // Back to the pairing screen first, so it is the one that takes the link.
                    navController.popBackStack()
                    inbox.offer(text, scanned = true)
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SIDEBAR, arguments = listOf(navArgument("environmentId") { type = NavType.StringType })) { entry ->
            val environmentId = entry.arguments?.getString("environmentId").orEmpty()
            SidebarScreen(
                viewModel = appViewModel { graph ->
                    SidebarViewModel(environmentId, graph.environmentRepository, graph.connectionManager)
                },
                onOpenThread = { threadId -> navController.navigate(Routes.thread(environmentId, threadId)) },
                onEditEnvironment = { navController.navigate(Routes.connect(environmentId)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            Routes.THREAD,
            arguments = listOf(
                navArgument("environmentId") { type = NavType.StringType },
                navArgument("threadId") { type = NavType.StringType },
            ),
        ) { entry ->
            val environmentId = entry.arguments?.getString("environmentId").orEmpty()
            val threadId = entry.arguments?.getString("threadId").orEmpty()
            ThreadScreen(
                viewModel = appViewModel { graph ->
                    ThreadViewModel(
                        environmentId,
                        threadId,
                        graph.connectionManager,
                        graph.draftRepository,
                        graph.outboxRepository,
                        graph.messageSender,
                        graph.appScope,
                    )
                },
                onBack = { navController.popBackStack() },
            )
        }
    }
}
