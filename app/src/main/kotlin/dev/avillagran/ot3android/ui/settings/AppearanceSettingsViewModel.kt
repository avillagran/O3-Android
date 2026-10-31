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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A manual theme choice: an Omarchy theme id with its name, or Material You (null name). */
data class ManualThemeOption(val id: String, val name: String?, val tokens: dev.avillagran.ot3android.core.theme.ThemeTokens? = null)

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
    private val source: LiveThemeSource,
    repository: EnvironmentRepository,
    loadThemes: suspend () -> List<dev.avillagran.ot3android.core.theme.ThemeTokens> = { emptyList() },
) : ViewModel() {
    private val catalog = MutableStateFlow<List<dev.avillagran.ot3android.core.theme.ThemeTokens>>(emptyList())
    init { viewModelScope.launch { catalog.value = loadThemes() } }

    val state: StateFlow<AppearanceUiState?> =
        combine(preferences.settings, source.selection, repository.environments, catalog) { settings, selection, environments, themes ->
            AppearanceUiState(
                settings = settings,
                selection = selection,
                environments = environments,
                darkThemes = options(DEFAULT_DARK, settings.manual.darkThemeId, themes.filter { !it.isLight }.map { ManualThemeOption(it.id, it.name, it) }),
                lightThemes = options(DEFAULT_LIGHT, settings.manual.lightThemeId, themes.filter { it.isLight }.map { ManualThemeOption(it.id, it.name, it) }),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setFollowEnvironment(follow: Boolean) = edit { preferences.setFollowEnvironment(follow) }

    fun setMainEnvironment(id: String?) = edit { preferences.setMainEnvironmentId(id) }

    fun setMode(mode: AppearanceMode) = edit { preferences.setMode(mode); preferences.setFollowEnvironment(false) }

    fun setDarkTheme(id: String) = edit {
        preferences.setDarkTheme(id)
        preferences.setMode(AppearanceMode.DARK)
        preferences.setFollowEnvironment(false)
    }

    fun setLightTheme(id: String) = edit {
        preferences.setLightTheme(id)
        preferences.setMode(AppearanceMode.LIGHT)
        preferences.setFollowEnvironment(false)
    }

    fun setFallbackMode(mode: AppearanceMode) = edit { preferences.setMode(mode) }
    fun setFallbackDarkTheme(id: String) = edit { preferences.setDarkTheme(id); preferences.setMode(AppearanceMode.DARK) }
    fun setFallbackLightTheme(id: String) = edit { preferences.setLightTheme(id); preferences.setMode(AppearanceMode.LIGHT) }

    fun setRevealStyle(style: dev.avillagran.ot3android.core.data.prefs.RevealStyle) = edit { preferences.appearance.setRevealStyle(style) }
    fun setRevealPace(pace: dev.avillagran.ot3android.core.data.prefs.RevealPace) = edit { preferences.appearance.setRevealPace(pace) }

    fun setProviderUsage(shown: Boolean) = edit { preferences.appearance.setProviderUsage(shown) }

    fun setLocal(field: dev.avillagran.ot3android.core.data.prefs.LocalAppearancePreference, value: String) =
        edit { preferences.appearance.setLocal(field, value) }
    fun resetLocal() = edit { preferences.appearance.resetLocal() }

    fun refresh() = source.refresh()

    private fun edit(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private companion object {
        // Keep defaults available during catalog loading, plus a stored custom id and Material You.
        val DEFAULT_DARK = ManualThemeOption(AppearanceSettings.DEFAULT_THEMES.dark, "Tokyo Night")
        val DEFAULT_LIGHT = ManualThemeOption(AppearanceSettings.DEFAULT_THEMES.light, "Catppuccin Latte")
        val MATERIAL_YOU = ManualThemeOption(AppearanceSettings.MATERIAL_YOU, null)

        fun options(default: ManualThemeOption, stored: String, bundled: List<ManualThemeOption>): List<ManualThemeOption> {
            val available = bundled.ifEmpty { listOf(default) }
            val extra = stored.takeIf { id -> available.none { it.id == id } && id != MATERIAL_YOU.id }?.let { ManualThemeOption(it, it) }
            return available + listOfNotNull(extra, MATERIAL_YOU)
        }
    }
}
