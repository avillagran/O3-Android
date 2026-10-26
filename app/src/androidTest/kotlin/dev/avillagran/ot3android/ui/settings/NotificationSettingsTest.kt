package dev.avillagran.ot3android.ui.settings

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import dev.avillagran.ot3android.core.data.prefs.*
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*

class NotificationSettingsTest {
    @get:Rule val rule = createComposeRule()
    @Test fun alertAndLockscreenChoicesPersistWithoutEnablingFollow(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = java.io.File(context.cacheDir, "follow-${System.nanoTime()}.preferences_pb")
        val preferences = FollowPreferences(PreferenceDataStoreFactory.create(scope = scope) { file })
        try {
            rule.setContent { Ot3Theme { NotificationsScreen(preferences, {}) } }
            rule.onNodeWithTag("follow_alert_plan").performScrollTo().performClick()
            rule.onNodeWithTag("follow_lock_State").performScrollTo().performClick()
            rule.waitUntil(5_000) { runBlocking { preferences.settings.first().lockscreen == FollowLockscreen.State } }
            val settings = preferences.settings.first()
            assertFalse(settings.enabled)
            assertFalse(settings.alerts.getValue(FollowAlert.Plan))
            assertTrue(settings.alerts.getValue(FollowAlert.Approval))
            rule.onNodeWithTag("follow_lock_State").assertIsSelected()
        } finally { scope.cancel(); file.delete() }
    }
}
