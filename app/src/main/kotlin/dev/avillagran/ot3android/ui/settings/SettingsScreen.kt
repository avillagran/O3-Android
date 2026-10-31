package dev.avillagran.ot3android.ui.settings

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.avillagran.ot3android.R

/** A Settings row and the destination it pushes. */
enum class SettingsEntry { Environments, Appearance, Notifications, Usage, Archive, Licenses }

/**
 * Preferences root: search translated pages and local appearance options.
 * P20 contributes delivered server pages with their real open handlers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpen: (SettingsEntry) -> Unit,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    contributions: List<PreferencesRouteContribution> = emptyList(),
) {
    var query by rememberSaveable { mutableStateOf("") }
    Scaffold(
        modifier = modifier.testTag(SettingsTags.ROOT),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.preferences_preferences)) },
            navigationIcon = { IconButton(onClick = onBack, modifier = Modifier.testTag(SettingsTags.BACK)) {
                Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back))
            } }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            OutlinedTextField(value = query, onValueChange = { query = it },
                label = { Text(stringResource(R.string.preferences_search_settings)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("settings_search"))
            var matches = 0
            // Archive/Licenses routes are placeholders until P17/P19 supply real modules.
            listOf(SettingsEntry.Environments, SettingsEntry.Appearance, SettingsEntry.Notifications, SettingsEntry.Usage).forEach { entry ->
                val label = when (entry) {
                    SettingsEntry.Environments -> R.string.preferences_connections
                    SettingsEntry.Appearance -> R.string.appearance_title
                    SettingsEntry.Notifications -> R.string.follow_title
                    SettingsEntry.Usage -> R.string.settings_usage
                    SettingsEntry.Archive -> R.string.settings_archive
                    SettingsEntry.Licenses -> R.string.settings_licenses
                }
                val text = stringResource(label)
                val id = when (entry) {
                    SettingsEntry.Environments -> "connections"
                    SettingsEntry.Appearance -> "appearance"
                    else -> entry.name.lowercase(java.util.Locale.ROOT)
                }
                if (contributions.none { it.page.id == id } && preferenceMatches(query, text)) {
                    matches++
                    ListItem(
                    headlineContent = { Text(text) },
                    modifier = Modifier.clickable { onOpen(entry) }.testTag(SettingsTags.entry(entry)),
                    )
                }
            }
            contributions.forEach { contribution ->
                val text = stringResource(contribution.page.label)
                if (preferenceMatches(query, text)) {
                    matches++
                    ListItem(headlineContent = { Text(text) }, modifier = Modifier
                        .clickable(onClick = contribution.open).testTag("settings_page_${contribution.page.id}"))
                }
            }
            if (query.isNotBlank()) localAppearanceLabels.forEach { (_, descriptor) ->
                val label = stringResource(descriptor.label)
                val choices = descriptor.choices.map { stringResource(it) }
                if (preferenceMatches(query, label, *choices.toTypedArray())) {
                    matches++
                    ListItem(headlineContent = { Text(label) },
                        supportingContent = { Text(stringResource(R.string.preferences_appearance)) },
                        modifier = Modifier.clickable { onOpen(SettingsEntry.Appearance) })
                }
            }
            if (query.isNotBlank()) (listOf(R.string.ot3_home_show_provider_usage, R.string.preferences_grouping) +
                if (android.os.Build.VERSION.SDK_INT >= 33) listOf(R.string.preferences_language) else emptyList()).forEach { resource ->
                val label = stringResource(resource)
                if (preferenceMatches(query, label)) {
                    matches++
                    ListItem(headlineContent = { Text(label) }, supportingContent = {
                        Text(stringResource(R.string.preferences_appearance))
                    }, modifier = Modifier.clickable { onOpen(SettingsEntry.Appearance) })
                }
            }
            if (matches == 0) Text(stringResource(R.string.preferences_no_results), Modifier.padding(16.dp))
        }
    }
}

object SettingsTags {
    const val ROOT = "settings"
    const val BACK = "settings_back"

    fun entry(entry: SettingsEntry) = "settings_${entry.name}"
}
