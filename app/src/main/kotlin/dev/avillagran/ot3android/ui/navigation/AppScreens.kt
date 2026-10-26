package dev.avillagran.ot3android.ui.navigation

import android.content.ClipData
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import dev.avillagran.ot3android.appGraph
import dev.avillagran.ot3android.appViewModel
import dev.avillagran.ot3android.core.home.ActionContext
import dev.avillagran.ot3android.core.home.ProjectAction
import dev.avillagran.ot3android.core.home.ThreadAction
import dev.avillagran.ot3android.core.home.ThreadActions
import dev.avillagran.ot3android.core.home.ThreadMenuTarget
import dev.avillagran.ot3android.ui.connect.ConnectScreen
import dev.avillagran.ot3android.ui.connect.ConnectViewModel
import dev.avillagran.ot3android.ui.environments.EnvironmentsScreen
import dev.avillagran.ot3android.ui.environments.EnvironmentsViewModel
import dev.avillagran.ot3android.ui.home.HomeIntent
import dev.avillagran.ot3android.ui.home.HomeScreen
import dev.avillagran.ot3android.ui.home.HomeViewModel
import dev.avillagran.ot3android.ui.home.ProjectActionsContent
import dev.avillagran.ot3android.ui.home.SnoozeUntilContent
import dev.avillagran.ot3android.ui.home.ThreadActionsContent
import dev.avillagran.ot3android.ui.home.ThreadListOptionsContent
import dev.avillagran.ot3android.ui.pair.PairScreen
import dev.avillagran.ot3android.ui.pair.PairViewModel
import dev.avillagran.ot3android.ui.pair.QrScanScreen
import dev.avillagran.ot3android.ui.settings.SettingsEntry
import dev.avillagran.ot3android.ui.settings.SettingsScreen
import dev.avillagran.ot3android.ui.thread.ThreadScreen
import dev.avillagran.ot3android.ui.thread.ThreadViewModel
import kotlinx.coroutines.launch

/** The app's screens, built from view models over the [dev.avillagran.ot3android.AppGraph]. */
class AppScreens(private val navigator: Ot3Navigator) : Ot3Screens {

    /** Home's view model, shared by Home and its sheets: scoped to the Chats graph's entry. */
    @Composable
    private fun homeViewModel(entry: NavBackStackEntry): HomeViewModel {
        val owner = remember(entry) { navigator.navController.getBackStackEntry(ChatsGraph) }
        val graph = LocalContext.current.appGraph
        return viewModel(viewModelStoreOwner = owner) {
            HomeViewModel(graph.environmentRepository, graph.connectionManager, graph.homePreferences)
        }
    }

    @Composable
    override fun Home(entry: NavBackStackEntry, isTop: Boolean) {
        val viewModel = homeViewModel(entry)
        val state by viewModel.state.collectAsStateWithLifecycle()
        HomeScreen(state, isTop, onIntent = { intent -> onHomeIntent(intent, viewModel) })
    }

    private fun onHomeIntent(intent: HomeIntent, viewModel: HomeViewModel) {
        when (intent) {
            is HomeIntent.SetQuery -> viewModel.setQuery(intent.text)
            HomeIntent.ClearSearch -> viewModel.clearSearch()
            is HomeIntent.OpenRow -> navigator.openThread(intent.row.environmentId, intent.row.threadId)
            is HomeIntent.RowActions -> navigator.openSheet(ThreadActionsSheet(intent.row.environmentId, intent.row.threadId))
            is HomeIntent.ProjectActions -> intent.row.projectId?.let { navigator.openSheet(ProjectActionsSheet(intent.row.environmentId, it)) }
            is HomeIntent.OpenMessage -> navigator.openThread(intent.match.environmentId, intent.match.threadId)
            is HomeIntent.ToggleShelf -> viewModel.toggleShelf(intent.shelf)
            HomeIntent.ShowMoreSettled -> viewModel.showMoreSettled()
            HomeIntent.OpenFilters -> navigator.openSheet(ThreadListOptionsSheet)
            HomeIntent.ToggleGrouping -> viewModel.toggleGrouping()
            HomeIntent.Refresh -> viewModel.refresh()
            HomeIntent.OpenEnvironments -> navigator.openEnvironments()
            HomeIntent.AddEnvironment -> navigator.addEnvironment()
            is HomeIntent.EditEnvironment -> navigator.addEnvironment(intent.environmentId)
            HomeIntent.AddProject -> navigator.push(AddProject())
            HomeIntent.NewThread -> navigator.push(NewThread)
            HomeIntent.CommandPalette -> navigator.push(CommandPalette)
        }
    }

    @Composable
    override fun Thread(route: Thread) {
        // `prompt` prefills the composer once the composer is rebuilt (UI-3).
        ThreadScreen(
            viewModel = appViewModel { graph ->
                ThreadViewModel(
                    route.environmentId,
                    route.threadId,
                    graph.connectionManager,
                    graph.draftRepository,
                    graph.outboxRepository,
                    graph.messageSender,
                    graph.appScope,
                )
            },
            onBack = { navigator.back() },
        )
    }

    @Composable
    override fun Settings() {
        SettingsScreen(onOpen = { entry ->
            when (entry) {
                SettingsEntry.Environments -> navigator.push(Environments)
                SettingsEntry.Usage -> navigator.push(Usage)
                SettingsEntry.Archive -> navigator.push(Archive)
                SettingsEntry.Licenses -> navigator.push(Licenses)
            }
        })
    }

    @Composable
    override fun Environments() {
        val graph = LocalContext.current.appGraph
        val bridgeStatus by graph.bridgeCatalog.status.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        EnvironmentsScreen(
            viewModel = appViewModel { graph -> EnvironmentsViewModel(graph.environmentRepository, graph.connectionManager) },
            // Rows expand in place with UI-6; until then a tap edits, as the edit action does.
            onOpen = { id -> navigator.addEnvironment(id) },
            onAdd = { navigator.addEnvironment() },
            onEdit = { id -> navigator.addEnvironment(id) },
            bridgeStatus = bridgeStatus,
            onRefreshBridge = { scope.launch { graph.bridgeCatalog.reconnect() } },
        )
    }

    @Composable
    override fun AddEnvironment(route: AddEnvironment) {
        ConnectScreen(
            viewModel = appViewModel { graph ->
                ConnectViewModel(graph.serverProbe, graph.environmentRepository, route.environmentId, graph.connectionManager::forget)
            },
            onSaved = { navigator.environmentSaved() },
            onBack = { navigator.back() },
            onPair = if (route.environmentId == null) {
                { navigator.push(PairEnvironment) }
            } else {
                null
            },
        )
    }

    @Composable
    override fun PairEnvironment() {
        val inbox = LocalContext.current.appGraph.pairingInbox
        PairScreen(
            viewModel = appViewModel { graph -> PairViewModel(graph.pairingService(), graph::pairBridge, dev.avillagran.ot3android.core.bridge.NativeBridge::isAvailable) },
            inbox = inbox.link,
            takeFromInbox = inbox::take,
            onScan = { navigator.push(ScanPairingCode) },
            onPaired = { navigator.environmentSaved() },
            onBack = { navigator.back() },
        )
    }

    @Composable
    override fun ScanPairingCode() {
        val inbox = LocalContext.current.appGraph.pairingInbox
        QrScanScreen(
            onScanned = { text ->
                // Back to the pairing screen first, so it is the one that takes the link.
                navigator.back()
                inbox.offer(text, scanned = true)
            },
            onBack = { navigator.back() },
            accept = { dev.avillagran.ot3android.ui.pair.BridgeInvitation.isLink(it) || dev.avillagran.ot3android.core.pairing.PairingService.isPairingLink(it) },
        )
    }

    @Composable
    override fun ThreadActions(entry: NavBackStackEntry, route: ThreadActionsSheet) {
        val viewModel = homeViewModel(entry)
        val state by viewModel.state.collectAsStateWithLifecycle()
        val clipboard = LocalClipboard.current
        val scope = rememberCoroutineScope()
        val current = state ?: return
        val row = current.rows["${route.environmentId}/${route.threadId}"] ?: return
        val target = ThreadMenuTarget(
            row = row,
            projectScoped = row.projectKey != null && row.projectKey in current.projectScope,
            working = row.working,
            autoSettleDisabled = row.autoSettleDisabled,
            canSnooze = row.canSnooze,
        )
        ThreadActionsContent(row, ThreadActions.menu(target, ActionContext.Interim)) { action ->
            when (action) {
                ThreadAction.CopyPath -> row.path
                ThreadAction.CopyBranch -> row.branch
                ThreadAction.CopyThreadId -> row.threadId
                else -> null
            }?.let { text -> scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(text, text))) } }
            when (action) {
                ThreadAction.FilterByProject -> viewModel.setProjectFilter(row.projectKey)
                ThreadAction.AllProjects -> viewModel.setProjectFilter(null)
                // Mutating actions are not offered until the lib's builders are reachable.
                else -> Unit
            }
            navigator.closeSheet(entry)
        }
    }

    @Composable
    override fun ProjectActions(entry: NavBackStackEntry, route: ProjectActionsSheet) {
        val viewModel = homeViewModel(entry)
        val state by viewModel.state.collectAsStateWithLifecycle()
        val clipboard = LocalClipboard.current
        val scope = rememberCoroutineScope()
        val project = state?.projects?.get("${route.environmentId}/${route.projectId}")
        ProjectActionsContent(project?.title, ThreadActions.projectMenu(project?.workspaceRoot, ActionContext.Interim)) { action ->
            val root = project?.workspaceRoot
            if (action == ProjectAction.CopyPath && root != null) {
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(root, root))) }
            }
            navigator.closeSheet(entry)
        }
    }

    @Composable
    override fun ThreadListOptions(entry: NavBackStackEntry) {
        val viewModel = homeViewModel(entry)
        val state by viewModel.state.collectAsStateWithLifecycle()
        val current = state ?: return
        // As ot3's filter menu, each choice applies and closes.
        ThreadListOptionsContent(
            options = current.filterOptions,
            filter = current.filter,
            onStatus = { viewModel.setStatusFilter(it); navigator.closeSheet(entry) },
            onProject = { key ->
                if (key == null) viewModel.setProjectFilter(null) else if (key != current.filter.projectKey) viewModel.setProjectFilter(key)
                navigator.closeSheet(entry)
            },
            onEnvironment = { viewModel.setEnvironmentFilter(it); navigator.closeSheet(entry) },
        )
    }

    @Composable
    override fun SnoozeUntil(entry: NavBackStackEntry, route: SnoozeUntilSheet) {
        val presets = remember(entry) { ThreadActions.snoozePresets(System.currentTimeMillis()) }
        SnoozeUntilContent(presets, enabled = ActionContext.Interim.canOperate) { navigator.closeSheet(entry) }
    }
}
