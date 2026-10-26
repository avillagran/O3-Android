package dev.avillagran.ot3android.core.data.prefs

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

class FollowPreferencesTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val store = PreferenceDataStoreFactory.create(scope = scope) { folder.root.resolve("follow.preferences_pb") }
    private val preferences = FollowPreferences(store)
    @After fun close() = scope.cancel()
    @Test fun existingEnabledPreferenceMigratesWithoutChangingDefaultAlertsOrPrivacy() = runBlocking {
        store.edit { it[FollowPreferences.ENABLED] = true }
        assertEquals(FollowSettings(enabled = true), preferences.settings.first())
    }
    @Test fun settingsPersistIndependentlyAndPassAllNativePreferenceKeys() = runBlocking {
        preferences.setEnabled(true)
        preferences.setLockscreen(FollowLockscreen.State)
        preferences.setAlert(FollowAlert.Plan, false)
        preferences.setAlert(FollowAlert.Approval, false)
        val saved = FollowPreferences(store).settings.first()
        assertTrue(saved.enabled)
        assertEquals(FollowLockscreen.State, saved.lockscreen)
        val native = saved.awarenessPreferences()
        assertEquals(setOf("approval", "input", "plan", "completion", "failure"), native.keys().asSequence().toSet())
        assertFalse(native.getBoolean("plan"))
        assertFalse(native.getBoolean("approval"))
        assertTrue(native.getBoolean("completion"))
    }
}
