package dev.avillagran.ot3android.ui.environments

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.core.connection.ConnectionState
import dev.avillagran.ot3android.core.connection.ServerDescriptor
import dev.avillagran.ot3android.core.domain.Environment
import dev.avillagran.ot3android.testing.ScreenshotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** A portrait phone off the LAN, where a bar of text actions used to hide the list. */
@Config(qualifiers = EnvironmentsScreenshotTest.PORTRAIT_360)
class EnvironmentsScreenshotTest : ScreenshotTest() {
    private val server = ServerDescriptor("env-1", "NODE", "0.1.3", "linux", 2)
    private val items = listOf(
        EnvironmentItem(
            Environment("lan", "NODE", "http://192.168.1.177:3773", "env-1", createdAt = 0),
            ConnectionState.Connected(server, viaFallback = true),
        ),
        EnvironmentItem(
            Environment("m2", "omarchy", "https://stream.example.net:55944", "env-2", createdAt = 0),
            ConnectionState.Connected(server),
            "native_authorized",
        ),
        EnvironmentItem(
            Environment("home", "Home server", "http://192.168.1.31:3773", "env-3", createdAt = 0),
            ConnectionState.Unreachable("unreachable", null),
        ),
    )

    private fun rendered(onAppearance: () -> Unit = {}, onAdminister: () -> Unit = {}) = composeRule.setContent {
        dev.avillagran.ot3android.ui.theme.Ot3Theme {
            EnvironmentsContent(items, {}, {}, {}, {}, bridgeStatus = "offline", onRefreshBridge = {},
                onAppearance = onAppearance, onAdministerBridge = onAdminister)
        }
    }

    @Test fun offline() = snapshot("offline") {
        EnvironmentsContent(items, {}, {}, {}, {}, bridgeStatus = "offline", onRefreshBridge = {}, onAdministerBridge = {})
    }

    @Test fun theBarStaysOneLineAndLeavesTheRowsAndStatusVisible() {
        rendered()
        val status = composeRule.onNodeWithTag(EnvironmentsScreenTags.BRIDGE_STATUS).assertIsDisplayed()
        assertTrue("the bar ends above the status", status.getBoundsInRoot().top <= 72.dp)
        composeRule.onNodeWithText("Bridge offline — saved catalog retained").assertIsDisplayed()
        items.forEach { composeRule.onNodeWithTag(EnvironmentsScreenTags.row(it.environment.id)).assertIsDisplayed() }
        composeRule.onNodeWithText("via gateway").assertIsDisplayed()
    }

    @Test fun refreshIsAnIconAndTheOtherActionsAreInTheMenu() {
        var opened = ""
        rendered(onAppearance = { opened = "appearance" }, onAdminister = { opened = "administer" })
        composeRule.onNodeWithTag(EnvironmentsScreenTags.REFRESH).assertIsDisplayed()
        composeRule.onNodeWithTag(EnvironmentsScreenTags.MORE).performClick()
        composeRule.onNodeWithTag(EnvironmentsScreenTags.ADMINISTER).performClick()
        assertEquals("administer", opened)
        composeRule.onNodeWithTag(EnvironmentsScreenTags.MORE).performClick()
        composeRule.onNodeWithTag(EnvironmentsScreenTags.APPEARANCE).performClick()
        assertEquals("appearance", opened)
    }

    companion object {
        const val PORTRAIT_360 = "w360dp-h760dp-port-xhdpi"
    }
}
