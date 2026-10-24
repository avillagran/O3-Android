package dev.avillagran.ot3android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.avillagran.ot3android.ui.connect.ConnectScreen
import dev.avillagran.ot3android.ui.sidebar.SidebarScreen

object Routes {
    const val CONNECT = "connect"
    const val SIDEBAR = "sidebar"
}

@Composable
fun Ot3NavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = Routes.CONNECT) {
        composable(Routes.CONNECT) {
            ConnectScreen(onContinue = { navController.navigate(Routes.SIDEBAR) })
        }
        composable(Routes.SIDEBAR) {
            SidebarScreen()
        }
    }
}
