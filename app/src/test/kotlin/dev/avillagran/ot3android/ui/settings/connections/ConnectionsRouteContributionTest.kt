package dev.avillagran.ot3android.ui.settings.connections

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.core.bridge.BridgeDeviceIdentity
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [35])
class ConnectionsRouteContributionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun contributionShowsSdkIdentityAndOpensExistingDestinations() {
        var environments = 0
        var pairing = 0
        val clientId = "a".repeat(64)
        val bridgeId = "b".repeat(64)
        rule.setContent { Ot3Theme {
            ConnectionsRouteContribution.Content({}, { environments++ }, { pairing++ }, BridgeDeviceIdentity(clientId, bridgeId))
        } }
        rule.onNodeWithTag("connections_client_id").assertTextEquals(clientId)
        rule.onNodeWithTag("connections_bridge_id").assertTextEquals(bridgeId)
        rule.onNodeWithTag("connections_environments").performClick()
        rule.onNodeWithTag("connections_pair").performClick()
        assertEquals(1, environments)
        assertEquals(1, pairing)
    }
}
