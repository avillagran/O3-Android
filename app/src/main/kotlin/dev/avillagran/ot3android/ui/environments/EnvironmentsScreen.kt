package dev.avillagran.ot3android.ui.environments

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.core.connection.ConnectionState
import dev.avillagran.ot3android.core.connection.ConnectionSupervisor
import dev.avillagran.ot3android.core.connection.RouteFallbackView
import dev.avillagran.ot3android.core.connection.routeOriginLabel
import dev.avillagran.ot3android.ui.common.ConnectionChip
import dev.avillagran.ot3android.ui.icons.Ot3Icons

object EnvironmentsScreenTags {
    const val ROOT = "environments_screen"
    const val ADD = "environments_add"
    const val LIST = "environments_list"
    const val EMPTY = "environments_empty"
    const val APPEARANCE = "environments_appearance"
    const val ADMINISTER = "environments_administer"
    const val REFRESH = "environments_refresh"
    const val MORE = "environments_more"
    const val BRIDGE_STATUS = "environments_bridge_status"
    fun row(id: String) = "environment_row_$id"
    fun edit(id: String) = "environment_edit_$id"
    fun delete(id: String) = "environment_delete_$id"
    fun routeReview(id: String) = "environment_route_review_$id"
    fun routeStop(id: String) = "environment_route_stop_$id"
    const val ROUTE_APPROVE = "environments_route_approve"
    const val CONFIRM_DELETE = "environments_confirm_delete"
}

/** Full access and read-only stay distinct: a row must not promise what its token cannot do. */
@androidx.annotation.StringRes
internal fun bridgeRowLabel(status: String): Int = when (status) {
    "revoked" -> R.string.bridge_revoked
    "expired" -> R.string.bridge_expired
    "authorized" -> R.string.bridge_authorized
    dev.avillagran.ot3android.core.data.local.NATIVE_AUTHORIZED -> R.string.bridge_full_access
    dev.avillagran.ot3android.core.data.local.NATIVE_EXPIRED -> R.string.bridge_full_access_ended
    else -> R.string.bridge_needs_authority
}

@androidx.annotation.StringRes
private fun bridgeStatusLabel(status: String): Int = when (status) {
    "offline" -> R.string.bridge_offline
    "revoked" -> R.string.bridge_revoked
    "expired" -> R.string.bridge_expired
    "available_requires_authority" -> R.string.bridge_needs_authority
    else -> R.string.bridge_available
}

@Composable
fun EnvironmentsScreen(
    viewModel: EnvironmentsViewModel,
    onOpen: (environmentId: String) -> Unit,
    onAdd: () -> Unit,
    onEdit: (environmentId: String) -> Unit,
    modifier: Modifier = Modifier,
    onAppearance: () -> Unit = {},
    bridgeStatus: String? = null,
    onRefreshBridge: (() -> Unit)? = null,
    onBack: () -> Unit = {},
    onPairBridge: (() -> Unit)? = null,
    onAdministerBridge: (() -> Unit)? = null,
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    EnvironmentsContent(
        items, onOpen, onAdd, onEdit, viewModel::delete, modifier, onAppearance, bridgeStatus,
        onRefreshBridge, onBack, onPairBridge, onAdministerBridge, viewModel::approveRoute, viewModel::stopRoute,
    )
}

/**
 * The bar keeps to one line at any width, or an off-LAN phone sees it swallow the list: actions
 * are an icon and a menu, and the bridge status has a line of its own, under the bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EnvironmentsContent(
    items: List<EnvironmentItem>?,
    onOpen: (environmentId: String) -> Unit,
    onAdd: () -> Unit,
    onEdit: (environmentId: String) -> Unit,
    onDelete: (environmentId: String) -> Unit,
    modifier: Modifier = Modifier,
    onAppearance: () -> Unit = {},
    bridgeStatus: String? = null,
    onRefreshBridge: (() -> Unit)? = null,
    onBack: () -> Unit = {},
    onPairBridge: (() -> Unit)? = null,
    onAdministerBridge: (() -> Unit)? = null,
    onApproveRoute: (environmentId: String, origin: String) -> Unit = { _, _ -> },
    onStopRoute: (environmentId: String) -> Unit = {},
) {
    var reviewing by rememberSaveable { mutableStateOf<String?>(null) }
    var reviewedOrigin by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var menu by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.testTag(EnvironmentsScreenTags.ROOT),
        topBar = { TopAppBar(title = {
            Text(stringResource(R.string.environments_title), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }, navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back))
            }
        }, actions = {
            if (onRefreshBridge != null && bridgeStatus != "not_loaded") {
                IconButton(onClick = onRefreshBridge, modifier = Modifier.testTag(EnvironmentsScreenTags.REFRESH)) {
                    Icon(painterResource(Ot3Icons.RefreshCw), stringResource(R.string.bridge_refresh))
                }
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag(EnvironmentsScreenTags.MORE)) {
                    Icon(painterResource(Ot3Icons.EllipsisVertical), stringResource(R.string.home_more))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (onAdministerBridge != null) DropdownMenuItem(
                        text = { Text(stringResource(R.string.bridge_admin_title)) },
                        onClick = { menu = false; onAdministerBridge() },
                        modifier = Modifier.testTag(EnvironmentsScreenTags.ADMINISTER),
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.appearance_title)) },
                        onClick = { menu = false; onAppearance() },
                        modifier = Modifier.testTag(EnvironmentsScreenTags.APPEARANCE),
                    )
                }
            }
        }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd, modifier = Modifier.testTag(EnvironmentsScreenTags.ADD)) {
                Icon(painterResource(R.drawable.ic_add), stringResource(R.string.environments_add))
            }
        },
    ) { padding ->
        val list = items
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().padding(padding)) {
        if (bridgeStatus != null && bridgeStatus != "not_loaded") Text(
            stringResource(bridgeStatusLabel(bridgeStatus)),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag(EnvironmentsScreenTags.BRIDGE_STATUS),
        )
        if (bridgeStatus == "not_loaded" && onPairBridge != null) TextButton(onClick = onPairBridge,
            modifier = Modifier.testTag("environments_pair_bridge")) { Text(stringResource(R.string.bridge_pair_help)) }
        when {
            list == null -> Unit
            list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.environments_empty),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag(EnvironmentsScreenTags.EMPTY),
                )
            }
            else -> LazyColumn(Modifier.fillMaxSize().testTag(EnvironmentsScreenTags.LIST)) {
                items(list, key = { it.environment.id }) { item ->
                    val id = item.environment.id
                    androidx.compose.foundation.layout.Column {
                    ListItem(
                        headlineContent = { Text(item.environment.label) },
                        supportingContent = {
                            androidx.compose.foundation.layout.Column {
                                Text(item.environment.baseUrl)
                                val fallback = (item.state as? ConnectionState.Connected)?.viaFallback == true
                                if (fallback || item.route is RouteFallbackView.InUse) {
                                    Text(stringResource(R.string.environments_via_gateway))
                                }
                                item.bridgeStatus?.let { status -> Text(stringResource(bridgeRowLabel(status))) }
                            }
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ConnectionChip(item.state)
                                IconButton(onClick = { onEdit(id) }, modifier = Modifier.testTag(EnvironmentsScreenTags.edit(id))) {
                                    Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.action_edit))
                                }
                                IconButton(onClick = { deleting = id }, modifier = Modifier.testTag(EnvironmentsScreenTags.delete(id))) {
                                    Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.action_delete))
                                }
                            }
                        },
                        modifier = Modifier.clickable { onOpen(id) }.testTag(EnvironmentsScreenTags.row(id)),
                    )
                    item.route?.let { route ->
                        RouteActions(id, route, onReview = { origin -> reviewing = id; reviewedOrigin = origin }, onStop = { onStopRoute(id) })
                    }
                    }
                }
            }
        }
    }

    }

    // The dialog names one origin. An offer that changes under it closes it, so a tap can never
    // approve a host the user did not read.
    val review = items?.firstOrNull { it.environment.id == reviewing }
        ?.route?.takeIf { it == RouteFallbackView.NeedsApproval(reviewedOrigin.orEmpty()) }
    if (review is RouteFallbackView.NeedsApproval) {
        val close = { reviewing = null; reviewedOrigin = null }
        AlertDialog(
            onDismissRequest = close,
            title = { Text(stringResource(R.string.environments_gateway_approve_title, routeOriginLabel(review.origin))) },
            text = { Text(stringResource(R.string.environments_gateway_approve_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        reviewing?.let { onApproveRoute(it, review.origin) }
                        close()
                    },
                    modifier = Modifier.testTag(EnvironmentsScreenTags.ROUTE_APPROVE),
                ) { Text(stringResource(R.string.environments_gateway_approve)) }
            },
            dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    val target = deleting?.let { id -> items?.firstOrNull { it.environment.id == id } }
    if (target != null) {
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.environments_delete_title, target.environment.label)) },
            text = { Text(stringResource(R.string.environments_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(target.environment.id)
                        deleting = null
                    },
                    modifier = Modifier.testTag(EnvironmentsScreenTags.CONFIRM_DELETE),
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/**
 * Under the row, at full width: a hostname the user is asked to trust must never wrap mid-word
 * in the narrow column beside the status chip.
 */
@Composable
private fun RouteActions(id: String, route: RouteFallbackView, onReview: (origin: String) -> Unit, onStop: () -> Unit) {
    androidx.compose.foundation.layout.Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
        when (route) {
            is RouteFallbackView.NeedsApproval -> {
                Text(stringResource(R.string.environments_gateway_offered, routeOriginLabel(route.origin)), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { onReview(route.origin) }, modifier = Modifier.testTag(EnvironmentsScreenTags.routeReview(id))) {
                    Text(stringResource(R.string.environments_gateway_review))
                }
            }
            is RouteFallbackView.InUse -> Unit
            is RouteFallbackView.Refused -> {
                val host = routeOriginLabel(route.origin)
                Text(
                    if (route.error == ConnectionSupervisor.UNAUTHORIZED) stringResource(R.string.environments_gateway_refused, host)
                    else stringResource(R.string.environments_gateway_unusable, host),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (route !is RouteFallbackView.NeedsApproval) {
            TextButton(onClick = onStop, modifier = Modifier.testTag(EnvironmentsScreenTags.routeStop(id))) {
                Text(stringResource(R.string.environments_gateway_stop))
            }
        }
    }
}
