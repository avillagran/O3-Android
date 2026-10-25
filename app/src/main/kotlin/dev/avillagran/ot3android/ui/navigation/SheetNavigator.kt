package dev.avillagran.ot3android.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.navigation.FloatingWindow
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestinationBuilder
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavOptions
import androidx.navigation.NavType
import androidx.navigation.Navigator
import androidx.navigation.compose.LocalOwnersProvider
import androidx.navigation.get
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * Bottom sheets as back-stack entries (UI_SPEC §2.2). Each sheet is a destination of the nav
 * graph, so it has its own [NavBackStackEntry] (saved state, view model store, lifecycle),
 * survives process death with the back stack, and back pops it like any screen. The sheets are
 * [FloatingWindow]s: the screen below stays composed and resumed. [SheetHost] draws them as M3
 * [ModalBottomSheet]s, whose back gesture (predictive on Android 14+) and swipe-down dismiss
 * pop the entry. Modelled on Navigation's own `DialogNavigator`.
 */
@Navigator.Name("ot3-sheet")
class SheetNavigator : Navigator<SheetNavigator.Destination>() {

    /** The sheet entries, bottom first. */
    internal val backStack: StateFlow<List<NavBackStackEntry>>
        get() = if (isAttached) state.backStack else MutableStateFlow(emptyList())

    override fun createDestination(): Destination = Destination(this) {}

    override fun navigate(entries: List<NavBackStackEntry>, navOptions: NavOptions?, navigatorExtras: Extras?) {
        entries.forEach { state.push(it) }
    }

    override fun popBackStack(popUpTo: NavBackStackEntry, savedState: Boolean) {
        state.pop(popUpTo, savedState)
    }

    /** The sheet was dismissed by the user (swipe, scrim, back): pops it and everything above it. */
    fun dismiss(entry: NavBackStackEntry) {
        if (entry in state.backStack.value) popBackStack(entry, false)
    }

    @NavDestination.ClassType(Composable::class)
    class Destination(
        navigator: SheetNavigator,
        internal val content: @Composable (NavBackStackEntry) -> Unit,
    ) : NavDestination(navigator), FloatingWindow
}

class SheetDestinationBuilder(
    navigator: SheetNavigator,
    route: KClass<*>,
    typeMap: Map<KType, NavType<*>>,
    private val content: @Composable (NavBackStackEntry) -> Unit,
) : NavDestinationBuilder<SheetNavigator.Destination>(navigator, route, typeMap) {
    override fun instantiateDestination(): SheetNavigator.Destination = SheetNavigator.Destination(navigator as SheetNavigator, content)
}

/** A sheet destination for the typed route [T]. */
inline fun <reified T : Any> NavGraphBuilder.sheet(
    typeMap: Map<KType, NavType<*>> = emptyMap(),
    noinline content: @Composable (NavBackStackEntry) -> Unit,
) {
    destination(SheetDestinationBuilder(provider[SheetNavigator::class], T::class, typeMap, content))
}

/** The [SheetNavigator] registered on this controller. */
val NavHostController.sheetNavigator: SheetNavigator
    get() = navigatorProvider[SheetNavigator::class]

/**
 * Draws every sheet entry, bottom first, each in its own [ModalBottomSheet] (content-height
 * stop; ot3's styling comes with the kit). Place it next to the `NavHost`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetHost(navigator: SheetNavigator) {
    val saveableStateHolder = rememberSaveableStateHolder()
    val entries by navigator.backStack.collectAsState()
    // The sheet's own window takes back first while it has focus; this keeps the order (sheet,
    // then whatever is below) when the activity window gets the event instead.
    BackHandler(enabled = entries.isNotEmpty()) { entries.lastOrNull()?.let(navigator::dismiss) }
    val shown = remember { mutableSetOf<String>() }
    LaunchedEffect(entries) {
        // A popped sheet's rememberSaveable state goes with it.
        val ids = entries.map { it.id }.toSet()
        (shown - ids).forEach { saveableStateHolder.removeState(it) }
        shown.retainAll(ids)
        shown.addAll(ids)
    }
    entries.forEach { entry ->
        val destination = entry.destination as? SheetNavigator.Destination ?: return@forEach
        ModalBottomSheet(
            onDismissRequest = { navigator.dismiss(entry) },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(Modifier.navigationBarsPadding()) {
                entry.LocalOwnersProvider(saveableStateHolder) { destination.content(entry) }
            }
        }
    }
}
