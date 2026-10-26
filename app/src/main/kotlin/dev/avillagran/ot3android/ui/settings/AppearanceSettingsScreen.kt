package dev.avillagran.ot3android.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.core.data.prefs.AppearanceMode
import dev.avillagran.ot3android.core.theme.FallbackReason
import dev.avillagran.ot3android.core.theme.ThemeSelection
import dev.avillagran.ot3android.core.theme.ThemeUnavailable

object AppearanceSettingsTags {
    const val ROOT = "appearance_settings"
    const val FOLLOW = "appearance_follow"
    const val STATUS = "appearance_status"
    fun main(id: String?) = "appearance_main_${id ?: "last_used"}"
    fun mode(mode: AppearanceMode) = "appearance_mode_${mode.name}"
    fun dark(id: String) = "appearance_dark_$id"
    fun light(id: String) = "appearance_light_$id"
}

/**
 * Settings → Appearance, minimal (UI-1 restyles it): the follow switch with the detected theme
 * and environment or why it is unavailable, the main environment when there are several, then
 * the manual choice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsScreen(
    viewModel: AppearanceSettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        modifier = modifier.testTag(AppearanceSettingsTags.ROOT),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.appearance_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        val current = state ?: return@Scaffold
        val settings = current.settings
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.appearance_follow_environment)) },
                supportingContent = if (settings.followEnvironment) {
                    { Text(status(current.selection), Modifier.testTag(AppearanceSettingsTags.STATUS)) }
                } else {
                    null
                },
                trailingContent = { Switch(checked = settings.followEnvironment, onCheckedChange = null) },
                modifier = Modifier
                    .toggleable(settings.followEnvironment, role = Role.Switch, onValueChange = viewModel::setFollowEnvironment)
                    .testTag(AppearanceSettingsTags.FOLLOW),
            )
            if (settings.followEnvironment && current.environments.size > 1) {
                Header(R.string.appearance_main_environment)
                Choice(
                    label = stringResource(R.string.appearance_main_environment_last_used),
                    selected = settings.mainEnvironmentId == null,
                    tag = AppearanceSettingsTags.main(null),
                ) { viewModel.setMainEnvironment(null) }
                current.environments.forEach { environment ->
                    Choice(environment.label, settings.mainEnvironmentId == environment.id, AppearanceSettingsTags.main(environment.id)) {
                        viewModel.setMainEnvironment(environment.id)
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                stringResource(R.string.appearance_manual_applies),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Header(R.string.appearance_theme)
            AppearanceMode.entries.forEach { mode ->
                val label = when (mode) {
                    AppearanceMode.FOLLOW_SYSTEM -> R.string.appearance_mode_follow_system
                    AppearanceMode.DARK -> R.string.appearance_mode_dark
                    AppearanceMode.LIGHT -> R.string.appearance_mode_light
                }
                Choice(stringResource(label), settings.manual.mode == mode, AppearanceSettingsTags.mode(mode)) { viewModel.setMode(mode) }
            }
            Header(R.string.appearance_dark_theme)
            current.darkThemes.forEach { option ->
                Choice(option.name ?: stringResource(R.string.appearance_material_you), settings.manual.darkThemeId == option.id, AppearanceSettingsTags.dark(option.id)) {
                    viewModel.setDarkTheme(option.id)
                }
            }
            Header(R.string.appearance_light_theme)
            current.lightThemes.forEach { option ->
                Choice(option.name ?: stringResource(R.string.appearance_material_you), settings.manual.lightThemeId == option.id, AppearanceSettingsTags.light(option.id)) {
                    viewModel.setLightTheme(option.id)
                }
            }
        }
    }
}

@Composable
private fun status(selection: ThemeSelection): String = when (selection) {
    is ThemeSelection.Live -> stringResource(
        if (selection.fromCache) R.string.appearance_live_theme_cached else R.string.appearance_live_theme,
        selection.displayName,
        selection.environmentLabel,
    )
    is ThemeSelection.Fallback -> when (val reason = selection.reason) {
        FallbackReason.Loading, FallbackReason.FollowingOff -> ""
        FallbackReason.NoEnvironment -> stringResource(R.string.appearance_no_environment)
        is FallbackReason.Waiting -> stringResource(R.string.appearance_waiting, reason.environmentLabel)
        is FallbackReason.Unavailable -> when (val why = reason.reason) {
            ThemeUnavailable.NotOmarchy -> stringResource(R.string.appearance_not_omarchy, reason.environmentLabel)
            is ThemeUnavailable.NoScope -> stringResource(R.string.appearance_no_scope, reason.environmentLabel, why.requiredScope)
            is ThemeUnavailable.Error -> stringResource(R.string.appearance_read_error, reason.environmentLabel, why.code)
        }
    }
}

@Composable
private fun Header(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Choice(label: String, selected: Boolean, tag: String, onSelect: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        modifier = Modifier.selectable(selected, role = Role.RadioButton, onClick = onSelect).testTag(tag),
    )
}
