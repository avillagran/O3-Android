package dev.avillagran.ot3android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.avillagran.ot3android.core.data.EnvironmentRepository
import dev.avillagran.ot3android.core.data.prefs.AppearanceMode
import dev.avillagran.ot3android.core.data.prefs.AppearanceSettings
import dev.avillagran.ot3android.core.domain.Environment
import dev.avillagran.ot3android.core.theme.LiveThemeSource
import dev.avillagran.ot3android.core.theme.ThemePreferences
import dev.avillagran.ot3android.core.theme.ThemeSelection
import dev.avillagran.ot3android.core.theme.ThemeSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A manual theme choice: an Omarchy theme id with its name, or Material You (null name). */
data class ManualThemeOption(val id: String, val name: String?)

data class AppearanceUiState(
    val settings: ThemeSettings,
    val selection: ThemeSelection,
    val environments: List<Environment>,
    val darkThemes: List<ManualThemeOption>,
    val lightThemes: List<ManualThemeOption>,
)

/**
 * Settings → Appearance (UI_SPEC 3.1.3): following the environment's live theme, the main
 * environment, and the manual choice used when following is off or unavailable.
 */
class AppearanceSettingsViewModel(
    private val preferences: ThemePreferences,
    source: LiveThemeSource,
    repository: EnvironmentRepository,
) : ViewModel() {

    val state: StateFlow<AppearanceUiState?> =
        combine(preferences.settings, source.selection, repository.environments) { settings, selection, environments ->
            AppearanceUiState(
                settings = settings,
                selection = selection,
                environments = environments,
                darkThemes = options(DEFAULT_DARK, settings.manual.darkThemeId),
                lightThemes = options(DEFAULT_LIGHT, settings.manual.lightThemeId),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setFollowEnvironment(follow: Boolean) = edit { preferences.setFollowEnvironment(follow) }

    fun setMainEnvironment(id: String?) = edit { preferences.setMainEnvironmentId(id) }

    fun setMode(mode: AppearanceMode) = edit { preferences.setMode(mode) }

    fun setDarkTheme(id: String) = edit { preferences.setDarkTheme(id) }

    fun setLightTheme(id: String) = edit { preferences.setLightTheme(id) }

    private fun edit(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private companion object {
        // The bundled catalog of the 22 Omarchy themes comes with UI-1; until then the lists
        // hold the defaults, the stored choice and Material You.
        val DEFAULT_DARK = ManualThemeOption(AppearanceSettings.DEFAULT_THEMES.dark, "Tokyo Night")
        val DEFAULT_LIGHT = ManualThemeOption(AppearanceSettings.DEFAULT_THEMES.light, "Catppuccin Latte")
        val MATERIAL_YOU = ManualThemeOption(AppearanceSettings.MATERIAL_YOU, null)

        fun options(default: ManualThemeOption, stored: String): List<ManualThemeOption> {
            val extra = stored.takeIf { it != default.id && it != MATERIAL_YOU.id }?.let { ManualThemeOption(it, it) }
            return listOfNotNull(default, extra, MATERIAL_YOU)
        }
    }
}
