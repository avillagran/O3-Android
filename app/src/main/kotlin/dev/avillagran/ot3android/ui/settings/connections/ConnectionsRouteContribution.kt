package dev.avillagran.ot3android.ui.settings.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.R

/** P20 registers this destination and supplies the existing environment/pairing destinations. */
object ConnectionsRouteContribution {
    const val route = "settings/connections"

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun Content(
        onBack: () -> Unit,
        onEnvironments: () -> Unit,
        onPair: () -> Unit,
        identity: dev.avillagran.ot3android.core.bridge.BridgeDeviceIdentity? = null,
    ) {
        Scaffold(topBar = {
            TopAppBar(title = { Text(stringResource(R.string.connections_title)) }, navigationIcon = {
                TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
            })
        }, modifier = Modifier.testTag("connections_settings")) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(onClick = onEnvironments, modifier = Modifier.testTag("connections_environments")) { Text(stringResource(R.string.environments_title)) }
                TextButton(onClick = onPair, modifier = Modifier.testTag("connections_pair")) { Text(stringResource(R.string.pair_title)) }
                identity?.let { device ->
                    Text(stringResource(R.string.connections_local_id))
                    androidx.compose.foundation.text.selection.SelectionContainer { Text(device.clientId, modifier = Modifier.testTag("connections_client_id")) }
                    device.bridgeId?.let { peer ->
                        Text(stringResource(R.string.connections_bridge_id))
                        androidx.compose.foundation.text.selection.SelectionContainer { Text(peer, modifier = Modifier.testTag("connections_bridge_id")) }
                    }
                }
                Text(stringResource(R.string.connections_host_authority))
            }
        }
    }
}
