package dev.avillagran.ot3android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.avillagran.ot3android.appViewModel
import dev.avillagran.ot3android.ui.connect.ConnectScreen
import dev.avillagran.ot3android.ui.connect.ConnectViewModel
import dev.avillagran.ot3android.ui.environments.EnvironmentsScreen
import dev.avillagran.ot3android.ui.environments.EnvironmentsViewModel
import dev.avillagran.ot3android.ui.sidebar.SidebarScreen
import dev.avillagran.ot3android.ui.sidebar.SidebarViewModel
import dev.avillagran.ot3android.ui.thread.ThreadPlaceholderScreen
import dev.avillagran.ot3android.ui.thread.ThreadPlaceholderViewModel

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

    fun connect(environmentId: String? = null) =
        if (environmentId == null) "connect" else "connect?environmentId=$environmentId"

    fun sidebar(environmentId: String) = "sidebar/$environmentId"
    fun thread(environmentId: String, threadId: String) = "thread/$environmentId/$threadId"
}

@Composable
fun Ot3NavHost(startDestination: String, navController: NavHostController = rememberNavController()) {
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
                onSaved = { id ->
                    // The catalog becomes the root, then the saved environment's sidebar opens.
                    navController.navigate(Routes.ENVIRONMENTS) { popUpTo(navController.graph.id) { inclusive = true } }
                    navController.navigate(Routes.sidebar(id))
                },
                onBack = if (navController.previousBackStackEntry != null) {
                    { navController.popBackStack() }
                } else {
                    null
                },
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
            ThreadPlaceholderScreen(
                viewModel = appViewModel { graph -> ThreadPlaceholderViewModel(environmentId, threadId, graph.connectionManager) },
                onBack = { navController.popBackStack() },
            )
        }
    }
}
