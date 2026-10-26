package dev.avillagran.ot3android.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.appearanceDataStore: DataStore<Preferences> by preferencesDataStore(name = "appearance")

/** Settings → Appearance (UI_SPEC 3.1.3, 3.4). */
enum class AppearanceMode { FOLLOW_SYSTEM, DARK, LIGHT }

/** ot3's "Animated starfield" preference (`ot3:ui/qml/T3Preferences.qml:260`). */
enum class AnimationPreference { AUTO, FULL, LIGHT, OFF }

/** One Omarchy theme id per appearance, or [AppearanceSettings.MATERIAL_YOU]. */
data class ThemePair(val dark: String, val light: String)

data class AppearanceSettings(
    val mode: AppearanceMode = AppearanceMode.FOLLOW_SYSTEM,
    val darkThemeId: String = DEFAULT_THEMES.dark,
    val lightThemeId: String = DEFAULT_THEMES.light,
    val animation: AnimationPreference = AnimationPreference.AUTO,
    val providerUsage: Boolean = false,
) {
    companion object {
        /** The default themes, in one place (UI_SPEC 3.1.3). */
        val DEFAULT_THEMES = ThemePair(dark = "tokyo-night", light = "catppuccin-latte")

        /** The extra entry at the end of each theme list. */
        const val MATERIAL_YOU = "material-you"
    }
}

/** Non-secret appearance preferences. An unknown stored value reads as the default. */
class AppearancePreferences(private val dataStore: DataStore<Preferences>) {

    constructor(context: Context) : this(context.applicationContext.appearanceDataStore)

    val settings: Flow<AppearanceSettings> = dataStore.data.map { prefs ->
        val defaults = AppearanceSettings()
        AppearanceSettings(
            mode = enumOrDefault(prefs[Keys.MODE], defaults.mode),
            darkThemeId = prefs[Keys.DARK_THEME] ?: defaults.darkThemeId,
            lightThemeId = prefs[Keys.LIGHT_THEME] ?: defaults.lightThemeId,
            providerUsage = prefs[Keys.PROVIDER_USAGE] ?: false,
            animation = enumOrDefault(prefs[Keys.ANIMATION], defaults.animation),
        )
    }

    suspend fun setProviderUsage(shown: Boolean) { dataStore.edit { it[Keys.PROVIDER_USAGE] = shown } }

    suspend fun setMode(mode: AppearanceMode) {
        dataStore.edit { it[Keys.MODE] = mode.name }
    }

    suspend fun setDarkTheme(id: String) {
        dataStore.edit { it[Keys.DARK_THEME] = id }
    }

    suspend fun setLightTheme(id: String) {
        dataStore.edit { it[Keys.LIGHT_THEME] = id }
    }

    suspend fun setAnimation(preference: AnimationPreference) {
        dataStore.edit { it[Keys.ANIMATION] = preference.name }
    }

    private inline fun <reified E : Enum<E>> enumOrDefault(stored: String?, default: E): E =
        stored?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

    object Keys {
        val PROVIDER_USAGE = booleanPreferencesKey("provider_usage")
        val MODE = stringPreferencesKey("mode")
        val DARK_THEME = stringPreferencesKey("dark_theme")
        val LIGHT_THEME = stringPreferencesKey("light_theme")
        val ANIMATION = stringPreferencesKey("animation")
    }
}
