package dev.avillagran.ot3android

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel

/** The application's [AppGraph], reachable from any [Context] of this app. */
val Context.appGraph: AppGraph
    get() = (applicationContext as Ot3Application).graph

/**
 * Returns the [ViewModel] of type [VM] scoped to the current owner, creating it from the
 * [AppGraph] on first use: `val vm = appViewModel { graph -> SidebarViewModel(graph.database) }`.
 */
@Composable
inline fun <reified VM : ViewModel> appViewModel(crossinline create: (AppGraph) -> VM): VM {
    val graph = LocalContext.current.appGraph
    return viewModel { create(graph) }
}
