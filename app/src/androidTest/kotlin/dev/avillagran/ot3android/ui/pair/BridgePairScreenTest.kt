package dev.avillagran.ot3android.ui.pair

import android.util.Base64
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.avillagran.ot3android.core.pairing.PairingService
import dev.avillagran.ot3android.core.transport.NativeT3TransportFactory
import dev.avillagran.ot3android.testing.fakeRepository
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** UI fixture only; the separate BridgeT3EndToEndTest verifies the actual protocol. */
class BridgePairScreenTest {
    @get:Rule val rule = createComposeRule()
    private fun service() = PairingService(NativeT3TransportFactory(), fakeRepository(), dev.avillagran.ot3android.core.transport.PairingClient(), {}, {})
    private fun link() = "omabridge://pair/" + Base64.encodeToString(
        "{\"version\":1,\"name\":\"Fixture\",\"manage\":false}".toByteArray(),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    @Test fun scannedInvitationStaysPendingUntilApprovedAndClearsSecretAfterSaving() {
        var approved = false
        var calls = 0
        val model = PairViewModel(service(), { calls++; approved }, { true })
        val inbox = PairingInbox()
        rule.setContent { Ot3Theme { PairScreen(model, inbox.link, inbox::take, {}, {}, {}) } }
        inbox.offer(link(), scanned = true)
        rule.waitUntil(5000) { model.state.value.bridge == "pending" }
        rule.onNodeWithTag("bridge_pair_status").assertTextEquals("Waiting for approval on your bridge. After approving, tap Continue.")
        assertEquals(1, calls)
        approved = true
        rule.onNodeWithTag(PairScreenTags.CONTINUE).performClick()
        rule.waitUntil(5000) { model.state.value.bridge == "approved" }
        assertEquals("", model.state.value.link)
        rule.onNodeWithTag("bridge_pair_status").assertTextEquals("Bridge saved. Authorized computers appear automatically in Home.")
        // SecureWindow intentionally blocks OS screenshots of the invitation UI.
        // Save the approved, empty-field semantics instead; no secret is present.
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it) }
            ?: InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        output.mkdirs()
        java.io.File(output, "bridge-approved-ui.txt").writeText(rule.onRoot().printToString())
    }

    @Test fun absentSdkKeepsManualT3PairingAndExplainsBridgeFallback() {
        val model = PairViewModel(service())
        val inbox = PairingInbox()
        rule.setContent { Ot3Theme { PairScreen(model, inbox.link, inbox::take, {}, {}, {}) } }
        rule.onNodeWithText("This build does not include the bridge SDK. You can still pair a computer with its T3 link.").assertIsDisplayed()
        rule.onNodeWithTag(PairScreenTags.LINK).assertIsEnabled()
        rule.onNodeWithTag(PairScreenTags.SCAN).assertIsEnabled()
        assertFalse(model.bridgeSupported)
    }
}
