package dev.avillagran.ot3android.ui.settings.providers

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.core.providers.ModelPresentation
import dev.avillagran.ot3android.core.providers.ProviderAdministration
import kotlinx.coroutines.launch
import org.json.JSONObject

/** P20 contribution. Construct a controller for the selected host; dispose it on host changes. */
object ProvidersRouteContribution { const val route = "settings/providers" }

@Composable
fun ProvidersRoute(controller: ProviderAdministration, onBack: () -> Unit, openUrl: (String) -> Unit) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(controller) { controller.refresh() }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(stringResource(R.string.providers_title), style = MaterialTheme.typography.headlineSmall)
            Text(controller.environmentId, style = MaterialTheme.typography.labelMedium)
            Row {
                TextButton(onClick = onBack) { Text(stringResource(R.string.providers_back)) }
                TextButton(onClick = { scope.launch { controller.refresh(true) } }, enabled = !state.busy) {
                    Text(stringResource(R.string.providers_refresh))
                }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.errorCode?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.uncertain) Text(stringResource(R.string.providers_unknown_result))
            if (state.uncertain && state.configuration != null) {
                var reviewed by remember(state.configuration.toString()) { mutableStateOf(false) }
                Row { Checkbox(reviewed, { reviewed = it }); Text(stringResource(R.string.providers_unknown_result)) }
                TextButton(enabled = reviewed && !state.busy, onClick = controller::reviewedConfiguration) { Text(stringResource(R.string.providers_continue)) }
            }
        }
        state.configuration?.let { configuration ->
            val drivers = ModelPresentation.objects(configuration.optJSONArray("drivers"))
            val instances = ModelPresentation.objects(configuration.optJSONArray("instances"))
            items(instances, key = { "configuration_" + it.getString("id") }) { instance ->
                drivers.firstOrNull { it.getString("id") == instance.getString("driver") }?.let { driver ->
                    ProviderConfigurationForm(instance, driver, state.canConfigure && !state.busy && !state.uncertain) { input ->
                        scope.launch { controller.configure(input); controller.refresh() }
                    }
                    HorizontalDivider()
                }
            }
            items(drivers, key = { "create_" + it.getString("id") }) { driver ->
                var open by remember { mutableStateOf(false) }
                TextButton(onClick = { open = !open }, enabled = state.canConfigure && !state.busy && !state.uncertain) { Text("+ " + driver.optString("label")) }
                if (open) ProviderConfigurationForm(null, driver, state.canConfigure && !state.busy && !state.uncertain) { input ->
                    scope.launch { controller.configure(input); controller.refresh() }
                }
            }
        }
        items(state.providers, key = { it.getString("instanceId") }) { provider ->
            val id = provider.getString("instanceId")
            Column(Modifier.fillMaxWidth().testTag("provider_instance_$id")) {
                Text(provider.optString("displayName").ifBlank { id }, style = MaterialTheme.typography.titleMedium)
                Text(listOf(id, provider.optString("driver"), provider.optString("authStatus"),
                    provider.optString("version"), provider.optString("latestVersion")).filter(String::isNotBlank).joinToString(" · "))
                val actions = listOf(
                    Triple("canUpdate", ProviderAdministration.Action.Update, R.string.providers_update),
                    Triple("canInstall", ProviderAdministration.Action.Install, R.string.providers_install),
                    Triple("canAuthenticate", ProviderAdministration.Action.SignIn, R.string.providers_sign_in),
                    Triple("canLogout", ProviderAdministration.Action.SignOut, R.string.providers_sign_out),
                )
                actions.filter { provider.optBoolean(it.first) }.forEach { (_, action, label) ->
                    TextButton(onClick = { scope.launch { controller.act(id, action) } },
                        enabled = state.canManage && !state.busy && !state.uncertain) { Text(stringResource(label)) }
                }
                Row {
                    TextButton(onClick = { scope.launch { controller.refreshSetup(id, false) } }, enabled = state.canManage && !state.busy) {
                        Text(stringResource(R.string.providers_auth_status))
                    }
                    TextButton(onClick = { scope.launch { controller.refreshSetup(id, true) } }, enabled = !state.busy) {
                        Text(stringResource(R.string.providers_install_status))
                    }
                }
                if (state.setupInstanceId == id) state.setup?.let { setup ->
                    Text(setup.optString("phase"))
                    val interaction = setup.optJSONObject("interaction")
                    if (interaction != null) key(controller, id, setup.optString("flowId"), interaction.optString("id")) {
                        ProviderInteraction(interaction, state.canManage && !state.busy && !state.uncertain, openUrl) { response ->
                            scope.launch { controller.respond(id, setup.getString("flowId"), interaction.getString("id"), response) }
                        }
                    }
                    val cancel = when {
                        setup.opt("flowId") is String -> ProviderAdministration.Action.CancelSignIn
                        setup.opt("operationId") is String -> ProviderAdministration.Action.CancelInstall
                        else -> null
                    }
                    if (cancel != null && setup.optString("phase") in listOf("starting", "waiting", "verifying", "downloading", "extracting")) TextButton(onClick = { scope.launch { controller.act(id, cancel) } },
                        enabled = state.canManage && !state.busy && !state.uncertain) { Text(stringResource(R.string.providers_cancel)) }
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
internal fun ProviderInteraction(interaction: JSONObject, enabled: Boolean, openUrl: (String) -> Unit, respond: (JSONObject) -> Unit) {
    when (interaction.optString("type")) {
        "browser", "deviceCode" -> {
            if (interaction.has("userCode")) Text(interaction.optString("userCode"))
            val url = interaction.optString("url")
            val uri = runCatching { java.net.URI(url) }.getOrNull()
            val canOpen = uri?.scheme in listOf("https", "http") && !uri?.host.isNullOrBlank() && uri.userInfo == null
            TextButton(onClick = { openUrl(url) }, enabled = enabled && canOpen) { Text(stringResource(R.string.providers_open_sign_in)) }
            // Opening a URL does not imply the user's consent to a host-side response.
            if (interaction.optBoolean("requiresConsent")) {
                TextButton(onClick = { respond(JSONObject().put("type", "browser").put("action", "accept")) }, enabled = enabled) {
                    Text(stringResource(R.string.providers_continue))
                }
                TextButton(onClick = { respond(JSONObject().put("type", "browser").put("action", "decline")) }, enabled = enabled) {
                    Text(stringResource(R.string.providers_cancel))
                }
            }
        }
        "credentials" -> {
            // Never rememberSaveable: credentials must not enter SavedState or disk.
            val values = remember { mutableStateMapOf<String, String>() }
            DisposableEffect(Unit) { onDispose { values.clear() } }
            ModelPresentation.objects(interaction.optJSONArray("fields")).forEach { field ->
                val name = field.getString("name")
                OutlinedTextField(values[name].orEmpty(), { values[name] = it }, enabled = enabled,
                    label = { Text(field.optString("label")) },
                    visualTransformation = if (field.optBoolean("secret")) PasswordVisualTransformation() else VisualTransformation.None)
            }
            TextButton(onClick = {
                val payload = JSONObject().put("type", "credentials").put("values", JSONObject(values.toMap()))
                values.clear(); respond(payload)
            }, enabled = enabled) { Text(stringResource(R.string.providers_continue)) }
        }
        "terminal" -> {
            Text(interaction.optString("output").takeLast(1200), style = MaterialTheme.typography.bodySmall)
            var input by remember { mutableStateOf("") }
            OutlinedTextField(input, { input = it }, enabled = enabled, visualTransformation = PasswordVisualTransformation(),
                label = { Text(stringResource(R.string.providers_terminal_input)) })
            TextButton(onClick = { val value = input; input = ""; respond(JSONObject().put("type", "terminal").put("data", value)) }, enabled = enabled) {
                Text(stringResource(R.string.providers_continue))
            }
        }
        else -> Text(stringResource(R.string.providers_interaction_unavailable))
    }
}
