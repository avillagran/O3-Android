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
        onRefreshBridge, onBack, onPairBridge, onAdministerBridge,
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
) {
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
                    ListItem(
                        headlineContent = { Text(item.environment.label) },
                        supportingContent = {
                            androidx.compose.foundation.layout.Column {
                                Text(item.environment.baseUrl)
                                if ((item.state as? ConnectionState.Connected)?.viaFallback == true) {
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
                }
            }
        }
    }

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
