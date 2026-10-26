package dev.avillagran.ot3android.core.theme

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.avillagran.ot3android.core.data.prefs.AppearanceMode
import dev.avillagran.ot3android.core.data.prefs.AppearancePreferences
import dev.avillagran.ot3android.core.data.prefs.AppearanceSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

private val Context.liveThemeDataStore: DataStore<Preferences> by preferencesDataStore(name = "live_theme")

/**
 * Settings → Appearance (UI_SPEC 3.1.3). [followEnvironment] (default on) wears the live
 * Omarchy theme of the environment on screen; in the merged Home, of [mainEnvironmentId], or
 * of the last used one while it is null. [manual] (mode, dark and light theme ids, Material
 * You as [AppearanceSettings.MATERIAL_YOU]) applies when following is off or unavailable.
 */
data class ThemeSettings(
    val followEnvironment: Boolean = true,
    val mainEnvironmentId: String? = null,
    val manual: AppearanceSettings = AppearanceSettings(),
)

/**
 * Non-secret theme preferences. The live-theme keys live in their own DataStore
 * (`live_theme`); the manual choice is [AppearancePreferences] (DataStore `appearance`,
 * shared with the UI-1 theme), so there is one source for each value.
 */
class ThemePreferences(
    private val dataStore: DataStore<Preferences>,
    val appearance: AppearancePreferences,
) {

    constructor(context: Context, appearance: AppearancePreferences) :
        this(context.applicationContext.liveThemeDataStore, appearance)

    val settings: Flow<ThemeSettings> = combine(dataStore.data, appearance.settings) { prefs, manual ->
        ThemeSettings(
            followEnvironment = prefs[Keys.FOLLOW_ENVIRONMENT] ?: true,
            mainEnvironmentId = prefs[Keys.MAIN_ENVIRONMENT_ID],
            manual = manual,
        )
    }.distinctUntilChanged()

    suspend fun setFollowEnvironment(follow: Boolean) {
        dataStore.edit { it[Keys.FOLLOW_ENVIRONMENT] = follow }
    }

    /** null: the last used environment is the main one. */
    suspend fun setMainEnvironmentId(id: String?) {
        dataStore.edit { prefs ->
            if (id == null) prefs.remove(Keys.MAIN_ENVIRONMENT_ID) else prefs[Keys.MAIN_ENVIRONMENT_ID] = id
        }
    }

    suspend fun setMode(mode: AppearanceMode) = appearance.setMode(mode)

    suspend fun setDarkTheme(id: String) = appearance.setDarkTheme(id)

    suspend fun setLightTheme(id: String) = appearance.setLightTheme(id)

    object Keys {
        val FOLLOW_ENVIRONMENT = booleanPreferencesKey("follow_environment")
        val MAIN_ENVIRONMENT_ID = stringPreferencesKey("main_environment_id")
    }
}
