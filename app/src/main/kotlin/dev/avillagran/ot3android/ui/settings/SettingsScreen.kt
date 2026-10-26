package dev.avillagran.ot3android.ui.settings

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.avillagran.ot3android.R

/** A Settings row and the destination it pushes. */
enum class SettingsEntry { Environments, Appearance, Notifications, Usage, Archive, Licenses }

/**
 * Settings root (UI_SPEC §4.9), structure only: the pages that exist or have a route. Search,
 * scope and the grouped cards come with UI-6.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onOpen: (SettingsEntry) -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier.testTag(SettingsTags.ROOT),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.ot3_tab_settings)) }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SettingsEntry.entries.forEach { entry ->
                val label = when (entry) {
                    SettingsEntry.Environments -> R.string.ot3_env_title
                    SettingsEntry.Appearance -> R.string.appearance_title
                    SettingsEntry.Notifications -> R.string.follow_title
                    SettingsEntry.Usage -> R.string.settings_usage
                    SettingsEntry.Archive -> R.string.settings_archive
                    SettingsEntry.Licenses -> R.string.settings_licenses
                }
                ListItem(
                    headlineContent = { Text(stringResource(label)) },
                    modifier = Modifier.clickable { onOpen(entry) }.testTag(SettingsTags.entry(entry)),
                )
            }
        }
    }
}

object SettingsTags {
    const val ROOT = "settings"

    fun entry(entry: SettingsEntry) = "settings_${entry.name}"
}
