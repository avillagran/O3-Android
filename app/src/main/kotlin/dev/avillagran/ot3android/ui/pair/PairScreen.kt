package dev.avillagran.ot3android.ui.pair

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.core.pairing.PairingFailure
import dev.avillagran.ot3android.core.pairing.PairingState
import dev.avillagran.ot3android.ui.common.errorText
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

object PairScreenTags {
    const val ROOT = "pair_screen"
    const val LINK = "pair_link"
    const val PASTE = "pair_paste"
    const val SCAN = "pair_scan"
    const val CONTINUE = "pair_continue"
    const val SERVER = "pair_server"
    const val CONFIRM = "pair_confirm"
    const val ERROR = "pair_error"
    const val PROGRESS = "pair_progress"
}

/**
 * Minimal pairing entry: paste a link or scan its QR code, check the server, confirm. Links
 * arriving through [inbox] (deep link, scanner) are taken only while this screen is started.
 * [onPaired] runs once with the saved environment's local id.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairScreen(
    viewModel: PairViewModel,
    inbox: StateFlow<String?>,
    takeFromInbox: () -> String?,
    onScan: () -> Unit,
    onPaired: (environmentId: String) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val waiting by inbox.collectAsStateWithLifecycle()
    LaunchedEffect(waiting) {
        if (waiting != null) takeFromInbox()?.let(viewModel::open)
    }
    LaunchedEffect(state.pairing) {
        val paired = state.pairing as? PairingState.Paired ?: return@LaunchedEffect
        viewModel.pairedHandled()
        onPaired(paired.environmentId)
    }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier.testTag(PairScreenTags.ROOT),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pair_title)) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val idle = !state.busy
            Text(stringResource(R.string.pair_help), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = state.link,
                onValueChange = viewModel::setLink,
                label = { Text(stringResource(R.string.pair_link_label)) },
                placeholder = { Text(stringResource(R.string.pair_link_hint)) },
                singleLine = true,
                enabled = idle,
                // The link holds a one-time code: masked like a token.
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                trailingIcon = {
                    TextButton(
                        enabled = idle,
                        onClick = {
                            scope.launch {
                                val clip = clipboard.getClipEntry()?.clipData
                                val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                                if (text != null) viewModel.open(text)
                            }
                        },
                        modifier = Modifier.testTag(PairScreenTags.PASTE),
                    ) { Text(stringResource(R.string.connect_paste)) }
                },
                modifier = Modifier.fillMaxWidth().testTag(PairScreenTags.LINK),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onScan, enabled = idle, modifier = Modifier.testTag(PairScreenTags.SCAN)) {
                    Text(stringResource(R.string.pair_scan))
                }
                OutlinedButton(
                    onClick = viewModel::inspect,
                    enabled = idle && state.link.isNotEmpty(),
                    modifier = Modifier.testTag(PairScreenTags.CONTINUE),
                ) { Text(stringResource(R.string.pair_continue)) }
                if (!idle) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp).testTag(PairScreenTags.PROGRESS), strokeWidth = 2.dp)
                }
            }

            when (val pairing = state.pairing) {
                is PairingState.Ready -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag(PairScreenTags.SERVER)) {
                        Text(
                            stringResource(
                                R.string.connect_server_found,
                                pairing.server.label ?: stringResource(R.string.connect_server_unnamed),
                                pairing.server.serverVersion ?: stringResource(R.string.connect_server_version_unknown),
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(pairing.origin, style = MaterialTheme.typography.bodyMedium)
                        pairing.existingLabel?.let { Text(stringResource(R.string.pair_existing, it), style = MaterialTheme.typography.bodyMedium) }
                    }
                    Button(onClick = viewModel::confirm, modifier = Modifier.testTag(PairScreenTags.CONFIRM)) {
                        Text(stringResource(R.string.pair_confirm))
                    }
                }
                is PairingState.Failed -> Text(
                    text = pairingErrorText(pairing.failure, pairing.code),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(PairScreenTags.ERROR),
                )
                else -> Unit
            }
        }
    }
}

@Composable
private fun pairingErrorText(failure: PairingFailure, code: String): String = when (failure) {
    PairingFailure.InvalidLink -> stringResource(R.string.pair_error_invalid_link)
    PairingFailure.NoCode -> stringResource(R.string.pair_error_no_code)
    PairingFailure.InvalidCode -> stringResource(R.string.pair_error_invalid_code)
    PairingFailure.Rejected -> stringResource(R.string.pair_error_rejected)
    PairingFailure.WrongServer -> stringResource(R.string.pair_error_wrong_server)
    PairingFailure.Incompatible -> stringResource(R.string.pair_error_incompatible)
    PairingFailure.Unreachable, PairingFailure.NotT3, PairingFailure.Internal, PairingFailure.Other -> errorText(code)
}
