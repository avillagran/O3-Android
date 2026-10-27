package dev.avillagran.ot3android.core.data.prefs

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
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
    fun `provider usage is opt in and survives a new preferences reader`() = runBlocking {
        assertEquals(false, preferences.settings.first().providerUsage)
        preferences.setProviderUsage(true)
        assertEquals(true, AppearancePreferences(dataStore).settings.first().providerUsage)
        preferences.setProviderUsage(false)
        assertEquals(false, AppearancePreferences(dataStore).settings.first().providerUsage)
    }

    @Test
    fun `provider usage toggles atomically across readers`() = runBlocking {
        preferences.setProviderUsage(true)
        kotlinx.coroutines.coroutineScope {
            repeat(20) { launch { AppearancePreferences(dataStore).toggleProviderUsage() } }
        }
        assertEquals(true, preferences.settings.first().providerUsage)
        preferences.toggleProviderUsage()
        assertEquals(false, preferences.settings.first().providerUsage)
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
