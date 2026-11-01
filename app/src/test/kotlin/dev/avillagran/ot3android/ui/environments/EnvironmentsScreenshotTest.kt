package dev.avillagran.ot3android.ui.environments

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.core.connection.ConnectionState
import dev.avillagran.ot3android.core.connection.RouteFallbackView
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

    private val offered = "https://gateway.example:39670"
    private val prompt = EnvironmentItem(
        Environment("home", "Home server", "http://192.168.1.31:3773", "env-3", createdAt = 0),
        ConnectionState.Unreachable("unreachable", 1_000),
        route = RouteFallbackView.NeedsApproval(offered),
    )
    private val inUse = EnvironmentItem(
        Environment("lan", "NODE", "http://192.168.1.177:3773", "env-1", createdAt = 0),
        ConnectionState.Connecting,
        route = RouteFallbackView.InUse(offered),
    )
    private val refused = EnvironmentItem(
        Environment("old", "Old box", "http://192.168.1.40:3773", "env-4", createdAt = 0),
        ConnectionState.Unreachable("unreachable", 1_000),
        route = RouteFallbackView.Refused(offered, "unauthorized"),
    )

    @Test fun routeStates() = snapshot("route-states") {
        EnvironmentsContent(listOf(prompt, inUse, refused), {}, {}, {}, {}, bridgeStatus = "offline", onRefreshBridge = {})
    }

    @Test fun routeApproval() {
        var opened = false
        snapshot("route-approval", screen = true, beforeCapture = {
            if (!opened) composeRule.onNodeWithTag(EnvironmentsScreenTags.routeReview("home")).performClick()
            opened = true
            repeat(2) {
                composeRule.mainClock.advanceTimeBy(1_000)
                composeRule.waitForIdle()
            }
        }) {
            EnvironmentsContent(listOf(prompt), {}, {}, {}, {}, bridgeStatus = "offline", onRefreshBridge = {})
        }
    }

    @Test fun aRowShowsTheOfferedHostAndApprovesOnlyAfterItsDialog() {
        val approved = mutableListOf<Pair<String, String>>()
        composeRule.setContent {
            dev.avillagran.ot3android.ui.theme.Ot3Theme {
                EnvironmentsContent(listOf(prompt), {}, {}, {}, {}, onApproveRoute = { id, origin -> approved += id to origin })
            }
        }
        composeRule.onNodeWithText("Own URL out of reach. The bridge offers gateway.example:39670").assertIsDisplayed()
        assertTrue(approved.isEmpty())

        composeRule.onNodeWithTag(EnvironmentsScreenTags.routeReview("home")).performClick()
        composeRule.onNodeWithText("Send this token to gateway.example:39670?").assertIsDisplayed()
        assertTrue("a review approves nothing", approved.isEmpty())

        composeRule.onNodeWithTag(EnvironmentsScreenTags.ROUTE_APPROVE).performClick()
        assertEquals(listOf("home" to offered), approved)
    }

    @Test fun anOfferThatChangesUnderItsDialogClosesIt() {
        var items by mutableStateOf(listOf(prompt))
        val approved = mutableListOf<Pair<String, String>>()
        composeRule.setContent {
            dev.avillagran.ot3android.ui.theme.Ot3Theme {
                EnvironmentsContent(items, {}, {}, {}, {}, onApproveRoute = { id, origin -> approved += id to origin })
            }
        }
        composeRule.onNodeWithTag(EnvironmentsScreenTags.routeReview("home")).performClick()
        composeRule.onNodeWithTag(EnvironmentsScreenTags.ROUTE_APPROVE).assertIsDisplayed()

        items = listOf(prompt.copy(route = RouteFallbackView.NeedsApproval("https://evil.example:443")))
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(EnvironmentsScreenTags.ROUTE_APPROVE).assertDoesNotExist()
        assertTrue(approved.isEmpty())
    }

    @Test fun aRowInUseSaysViaGatewayWhileConnectingAndARefusalIsOnTheRow() {
        var stopped = ""
        composeRule.setContent {
            dev.avillagran.ot3android.ui.theme.Ot3Theme {
                EnvironmentsContent(listOf(inUse, refused), {}, {}, {}, {}, onStopRoute = { stopped = it })
            }
        }
        composeRule.onNodeWithText("via gateway").assertIsDisplayed()
        composeRule.onNodeWithText("gateway.example:39670 refused this token").assertIsDisplayed()
        composeRule.onNodeWithTag(EnvironmentsScreenTags.routeStop("old")).performClick()
        assertEquals("old", stopped)
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
