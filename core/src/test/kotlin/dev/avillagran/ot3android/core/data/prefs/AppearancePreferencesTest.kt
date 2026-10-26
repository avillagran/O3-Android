package dev.avillagran.ot3android.core.data.prefs

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppearancePreferencesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dataStore = PreferenceDataStoreFactory.create(scope = scope) { folder.root.resolve("appearance.preferences_pb") }
    private val preferences = AppearancePreferences(dataStore)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `defaults are follow system, tokyo-night, catppuccin-latte and auto`() = runBlocking {
        assertEquals(
            AppearanceSettings(AppearanceMode.FOLLOW_SYSTEM, "tokyo-night", "catppuccin-latte", AnimationPreference.AUTO),
            preferences.settings.first(),
        )
    }

    @Test
    fun `every setting round-trips`() = runBlocking {
        preferences.setMode(AppearanceMode.LIGHT)
        preferences.setDarkTheme("nord")
        preferences.setLightTheme(AppearanceSettings.MATERIAL_YOU)
        preferences.setAnimation(AnimationPreference.OFF)
        assertEquals(
            AppearanceSettings(AppearanceMode.LIGHT, "nord", "material-you", AnimationPreference.OFF),
            preferences.settings.first(),
        )
    }

    @Test
    fun `unknown stored values read as the defaults`() = runBlocking {
        dataStore.edit {
            it[AppearancePreferences.Keys.MODE] = "SEPIA"
            it[AppearancePreferences.Keys.ANIMATION] = "SOMETIMES"
        }
        val settings = preferences.settings.first()
        assertEquals(AppearanceMode.FOLLOW_SYSTEM, settings.mode)
        assertEquals(AnimationPreference.AUTO, settings.animation)
    }
}
