package dev.avillagran.ot3android.ui.home

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.core.connection.*
import dev.avillagran.ot3android.core.home.*
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [35])
class HomeConnectionStatusTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val connected = ConnectionState.Connected(ServerDescriptor(null, null, null, null, null))
    @Test fun cachedListStaysWhileEachHostReportsItsPhaseThenProgressDisappears() {
        var hosts by mutableStateOf(listOf(HomeHostStatus("a", "NODE", ConnectionState.Connecting, true),
            HomeHostStatus("b", "M2", connected, true, syncing = true)))
        rule.setContent { Ot3Theme { androidx.compose.foundation.layout.Column {
            HomeConnectionStatus(hosts) {}
            androidx.compose.material3.Text("Saved conversation")
        } } }
        rule.onNodeWithText("Saved conversation").assertIsDisplayed()
        rule.onNodeWithText("Showing saved threads").assertIsDisplayed()
        rule.onNodeWithTag("home_host_progress_a", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Syncing threads…").assertExists()
        rule.runOnIdle { hosts = hosts.map { it.copy(connection = connected, syncing = false) } }
        rule.onNodeWithTag("home_connection_status").assertDoesNotExist()
        rule.onNodeWithText("Saved conversation").assertIsDisplayed()
    }
    @Test fun oneReadyHostDoesNotHideTheOtherHostsReconnect() {
        rule.setContent { Ot3Theme { HomeConnectionStatus(listOf(HomeHostStatus("a", "NODE", connected, true),
            HomeHostStatus("b", "M2", ConnectionState.Connecting, true))) {} } }
        rule.onNodeWithTag("home_host_status_a").assertDoesNotExist()
        rule.onNodeWithTag("home_host_status_b").assertExists()
    }
}
