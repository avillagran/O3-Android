package dev.avillagran.ot3android.ui.connect

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
import dev.avillagran.ot3android.ui.common.errorText
import kotlinx.coroutines.launch

object ConnectScreenTags {
    const val ROOT = "connect_screen"
    const val URL = "connect_url"
    const val TOKEN = "connect_token"
    const val LABEL = "connect_label"
    const val PASTE = "connect_paste"
    const val CHECK = "connect_check"
    const val SAVE = "connect_save"
    const val SERVER = "connect_server"
    const val ERROR = "connect_error"
    const val PROGRESS = "connect_progress"
    const val PAIR = "connect_pair"
}

/**
 * URL + token entry; [onSaved] runs once with the saved environment's local id. [onPair], when
 * given, offers pairing with a link or QR code instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    viewModel: ConnectViewModel,
    onSaved: (environmentId: String) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onPair: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.savedEnvironmentId) {
        val saved = state.savedEnvironmentId ?: return@LaunchedEffect
        viewModel.savedHandled()
        onSaved(saved)
    }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier.testTag(ConnectScreenTags.ROOT),
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (state.editingId == null) R.string.connect_title else R.string.connect_edit_title))
                },
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
            val idle = state.busy == null
            if (onPair != null) {
                OutlinedButton(onClick = onPair, enabled = idle, modifier = Modifier.fillMaxWidth().testTag(ConnectScreenTags.PAIR)) {
                    Text(stringResource(R.string.connect_pair))
                }
            }
            OutlinedTextField(
                value = state.url,
                onValueChange = viewModel::setUrl,
                label = { Text(stringResource(R.string.connect_url_label)) },
                placeholder = { Text(stringResource(R.string.connect_url_hint)) },
                singleLine = true,
                enabled = idle,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().testTag(ConnectScreenTags.URL),
            )
            OutlinedTextField(
                value = state.token,
                onValueChange = viewModel::setToken,
                label = { Text(stringResource(R.string.connect_token_label)) },
                supportingText = if (state.hasSavedToken) {
                    { Text(stringResource(R.string.connect_token_keep)) }
                } else {
                    null
                },
                singleLine = true,
                enabled = idle,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                trailingIcon = {
                    TextButton(
                        enabled = idle,
                        onClick = {
                            scope.launch {
                                val clip = clipboard.getClipEntry()?.clipData
                                val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                                if (text != null) viewModel.setToken(text)
                            }
                        },
                        modifier = Modifier.testTag(ConnectScreenTags.PASTE),
                    ) { Text(stringResource(R.string.connect_paste)) }
                },
                modifier = Modifier.fillMaxWidth().testTag(ConnectScreenTags.TOKEN),
            )
            OutlinedTextField(
                value = state.label,
                onValueChange = viewModel::setLabel,
                label = { Text(stringResource(R.string.connect_label_label)) },
                singleLine = true,
                enabled = idle,
                modifier = Modifier.fillMaxWidth().testTag(ConnectScreenTags.LABEL),
            )

            state.server?.let { server ->
                Text(
                    stringResource(
                        R.string.connect_server_found,
                        server.label ?: stringResource(R.string.connect_server_unnamed),
                        server.serverVersion ?: stringResource(R.string.connect_server_version_unknown),
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag(ConnectScreenTags.SERVER),
                )
            }
            state.error?.let { error ->
                Text(
                    text = when (error) {
                        ConnectError.UrlRequired -> stringResource(R.string.connect_error_url_required)
                        ConnectError.TokenRequired -> stringResource(R.string.connect_error_token_required)
                        is ConnectError.Failed -> errorText(error.code)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(ConnectScreenTags.ERROR),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = viewModel::check, enabled = idle, modifier = Modifier.testTag(ConnectScreenTags.CHECK)) {
                    Text(stringResource(R.string.connect_check))
                }
                Button(onClick = viewModel::save, enabled = idle, modifier = Modifier.testTag(ConnectScreenTags.SAVE)) {
                    Text(stringResource(R.string.connect_save))
                }
                if (!idle) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp).testTag(ConnectScreenTags.PROGRESS), strokeWidth = 2.dp)
                }
            }
        }
    }
}
