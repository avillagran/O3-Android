package dev.avillagran.ot3android.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
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
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
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
 * Settings → Appearance: the follow switch with the detected theme
 * and environment or why it is unavailable, the main environment when there are several, then
 * the native-token palette gallery.
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
            Header(R.string.appearance_source)
            Choice(stringResource(R.string.appearance_follow_environment), settings.followEnvironment,
                AppearanceSettingsTags.FOLLOW) { viewModel.setFollowEnvironment(true) }
            Choice(stringResource(R.string.appearance_phone_theme), !settings.followEnvironment,
                "appearance_phone") { viewModel.setFollowEnvironment(false) }
            if (settings.followEnvironment) {
                Text(status(current.selection), Modifier.padding(horizontal = 16.dp).testTag(AppearanceSettingsTags.STATUS),
                    style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(if (current.selection is ThemeSelection.Fallback)
                    R.string.appearance_fallback_active else R.string.appearance_live_explanation),
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
            }
            if (settings.followEnvironment) {
                TextButton(onClick = viewModel::refresh, modifier = Modifier.padding(horizontal = 8.dp).testTag("appearance_refresh")) {
                    Text(stringResource(R.string.appearance_refresh))
                }
            }
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
                stringResource(if (settings.followEnvironment) R.string.appearance_fallback_explanation else R.string.appearance_manual_applies),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Header(if (settings.followEnvironment) R.string.appearance_fallback_title else R.string.appearance_theme)
            AppearanceMode.entries.forEach { mode ->
                val label = when (mode) {
                    AppearanceMode.FOLLOW_SYSTEM -> R.string.appearance_mode_follow_system
                    AppearanceMode.DARK -> R.string.appearance_mode_dark
                    AppearanceMode.LIGHT -> R.string.appearance_mode_light
                }
                Choice(stringResource(label), settings.manual.mode == mode, AppearanceSettingsTags.mode(mode)) { if (settings.followEnvironment) viewModel.setFallbackMode(mode) else viewModel.setMode(mode) }
            }
            LocalAppearanceOptions(settings.manual, viewModel::setLocal, viewModel::setProviderUsage, viewModel::resetLocal)
            Header(R.string.appearance_dark_theme)
            ThemeGrid(current.darkThemes, settings.manual.darkThemeId.takeUnless { settings.manual.mode == AppearanceMode.LIGHT }, AppearanceSettingsTags::dark) { if (settings.followEnvironment) viewModel.setFallbackDarkTheme(it) else viewModel.setDarkTheme(it) }
            Header(R.string.appearance_light_theme)
            ThemeGrid(current.lightThemes, settings.manual.lightThemeId.takeUnless { settings.manual.mode == AppearanceMode.DARK }, AppearanceSettingsTags::light) { if (settings.followEnvironment) viewModel.setFallbackLightTheme(it) else viewModel.setLightTheme(it) }

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

/** Native theme.tokens colors, shown before selection; no palette or contrast calculations here. */
@Composable
private fun ThemeGrid(options: List<ManualThemeOption>, selected: String?, tag: (String) -> String, select: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { option ->
                    val name = option.name ?: stringResource(R.string.appearance_material_you)
                    val tokens = option.tokens
                    val background = tokens?.base?.get("background")?.let { Color(0xff000000L or it.toLong()) }
                        ?: MaterialTheme.colorScheme.surface
                    val foreground = tokens?.base?.get("foreground")?.let { Color(0xff000000L or it.toLong()) }
                        ?: MaterialTheme.colorScheme.onSurface
                    Surface(modifier = Modifier.weight(1f).testTag(tag(option.id))
                        .selectable(option.id == selected, role = Role.RadioButton, onClick = { select(option.id) }),
                        color = background, contentColor = foreground, shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(if (option.id == selected) 2.dp else 1.dp,
                            if (option.id == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Aa", style = MaterialTheme.typography.titleLarge)
                                Spacer(Modifier.weight(1f))
                                listOf("accent", "red", "green", "blue").forEach { key ->
                                    tokens?.base?.get(key)?.let { rgb ->
                                        Box(Modifier.size(12.dp).background(Color(0xff000000L or rgb.toLong()), RoundedCornerShape(4.dp)))
                                    }
                                }
                            }
                            Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Local persisted configuration is consumed by P01/P04; animation settings survive master off. */
@Composable
internal fun LocalAppearanceOptions(
    settings: dev.avillagran.ot3android.core.data.prefs.AppearanceSettings,
    select: (dev.avillagran.ot3android.core.data.prefs.LocalAppearancePreference, String) -> Unit,
    setProviderUsage: (Boolean) -> Unit,
    reset: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val home = androidx.compose.runtime.remember(context) { dev.avillagran.ot3android.core.data.prefs.HomePreferences(context) }
    val shelves by home.shelves.collectAsStateWithLifecycle(dev.avillagran.ot3android.core.home.ShelfState())
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var query by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    androidx.compose.material3.OutlinedTextField(
        value = query, onValueChange = { query = it },
        label = { Text(stringResource(R.string.preferences_search_settings)) },
        modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("appearance_search"),
        singleLine = true,
    )
    Header(R.string.preferences_local_appearance_options)
    var matches = 0
    localAppearanceLabels.forEach { (field, descriptor) ->
        val label = stringResource(descriptor.label)
        val choices = descriptor.choices.map { stringResource(it) }
        if (preferenceMatches(query, label, *choices.toTypedArray())) {
            matches++
            val value = settings.localValue(field)
            val enabled = settings.animationsEnabled || field.ordinal < dev.avillagran.ot3android.core.data.prefs.LocalAppearancePreference.STARFIELD_MODE.ordinal
            if (descriptor.choices.isEmpty()) {
                ListItem(headlineContent = { Text(label) }, trailingContent = {
                    Switch(value == "true", { select(field, it.toString()) }, enabled = enabled,
                        modifier = Modifier.testTag("preference_${field.id}"))
                })
            } else {
                Text(label, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
                field.choices.forEachIndexed { index, option ->
                    ListItem(headlineContent = { Text(choices[index]) }, leadingContent = {
                        RadioButton(value == option, null, enabled = enabled)
                    }, modifier = Modifier.selectable(value == option, enabled = enabled, role = Role.RadioButton) {
                        select(field, option)
                    }.testTag("preference_${field.id}_$option"))
                }
            }
        }
    }
    val languageLabel = stringResource(R.string.preferences_language)
    val languageNames = preferenceLanguages.map { stringResource(it.second) }
    if (android.os.Build.VERSION.SDK_INT >= 33 && preferenceMatches(query, languageLabel, *languageNames.toTypedArray())) {
        matches++
        LanguageOption(languageLabel)
    }
    val usageLabel = stringResource(R.string.ot3_home_show_provider_usage)
    if (preferenceMatches(query, usageLabel)) {
        matches++
        ListItem(headlineContent = { Text(usageLabel) }, trailingContent = {
            Switch(settings.providerUsage, setProviderUsage, modifier = Modifier.testTag("appearance_provider_usage"))
        })
    }
    val groupingLabel = stringResource(R.string.preferences_grouping)
    if (preferenceMatches(query, groupingLabel)) {
        matches++
        ListItem(headlineContent = { Text(groupingLabel) }, trailingContent = {
            Switch(shelves.grouped, { grouped -> scope.launch { home.update { it.copy(grouped = grouped) } } },
                modifier = Modifier.testTag("appearance_grouping"))
        })
    }
    if (matches == 0) Text(stringResource(R.string.preferences_no_results), Modifier.padding(16.dp))
    TextButton(onClick = {
        reset()
        scope.launch { home.update { it.copy(grouped = false) } }
    }, modifier = Modifier.testTag("appearance_reset")) {
        Text(stringResource(R.string.preferences_reset_appearance_options))
    }
}

/** LocaleManager owns persistence and recreates the activity; no parallel language preference. */
@androidx.annotation.RequiresApi(33)
@Composable
private fun LanguageOption(label: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val manager = context.getSystemService(android.app.LocaleManager::class.java)
    val current = manager.applicationLocales.toLanguageTags().substringBefore(",").substringBefore("-")
        .let { if (it == "nb") "no" else it }.ifEmpty { "system" }
    var expanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    ListItem(headlineContent = { Text(label) }, trailingContent = {
        Box {
            TextButton(onClick = { expanded = true }, modifier = Modifier.testTag("appearance_language")) {
                Text(stringResource(preferenceLanguages.firstOrNull { it.first == current }?.second
                    ?: R.string.preferences_language_system))
            }
            androidx.compose.material3.DropdownMenu(expanded, { expanded = false }) {
                preferenceLanguages.forEach { (id, resource) ->
                    androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(resource)) }, onClick = {
                        expanded = false
                        manager.applicationLocales = if (id == "system") android.os.LocaleList.getEmptyLocaleList()
                            else android.os.LocaleList.forLanguageTags(if (id == "no") "nb" else id)
                    })
                }
            }
        }
    })
}
