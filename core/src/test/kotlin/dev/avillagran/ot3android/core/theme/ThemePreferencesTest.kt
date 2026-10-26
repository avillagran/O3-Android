package dev.avillagran.ot3android.core.theme

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.avillagran.ot3android.core.data.prefs.AppearanceMode
import dev.avillagran.ot3android.core.data.prefs.AppearancePreferences
import dev.avillagran.ot3android.core.data.prefs.AppearanceSettings
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

class ThemePreferencesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val appearance = AppearancePreferences(
        PreferenceDataStoreFactory.create(scope = scope) { folder.root.resolve("appearance.preferences_pb") },
    )
    private val preferences = ThemePreferences(
        PreferenceDataStoreFactory.create(scope = scope) { folder.root.resolve("live_theme.preferences_pb") },
        appearance,
    )

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `following is on by default, with no main environment and the default manual themes`() = runBlocking {
        val settings = preferences.settings.first()

        assertEquals(ThemeSettings(followEnvironment = true, mainEnvironmentId = null, manual = AppearanceSettings()), settings)
        assertEquals("tokyo-night", settings.manual.darkThemeId)
        assertEquals("catppuccin-latte", settings.manual.lightThemeId)
    }

    @Test
    fun `every setting round-trips, the manual ones through AppearancePreferences`() = runBlocking {
        preferences.setFollowEnvironment(false)
        preferences.setMainEnvironmentId("env-2")
        preferences.setMode(AppearanceMode.DARK)
        preferences.setDarkTheme(AppearanceSettings.MATERIAL_YOU)
        preferences.setLightTheme("flexoki-light")

        val settings = preferences.settings.first()
        assertEquals(false, settings.followEnvironment)
        assertEquals("env-2", settings.mainEnvironmentId)
        assertEquals(AppearanceMode.DARK, settings.manual.mode)
        assertEquals("material-you", settings.manual.darkThemeId)
        assertEquals("flexoki-light", settings.manual.lightThemeId)
        assertEquals(settings.manual, appearance.settings.first())
    }

    @Test
    fun `clearing the main environment goes back to the last used`() = runBlocking {
        preferences.setMainEnvironmentId("env-2")
        preferences.setMainEnvironmentId(null)

        assertEquals(null, preferences.settings.first().mainEnvironmentId)
    }
}
