package dev.avillagran.ot3android.ui.connect

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.core.connection.ServerProbe
import dev.avillagran.ot3android.testing.FakeEnvironmentDao
import dev.avillagran.ot3android.testing.FakeTokenStore
import dev.avillagran.ot3android.testing.fakeRepository
import dev.avillagran.ot3android.core.transport.FakeT3Transport
import dev.avillagran.ot3android.core.transport.FakeT3TransportFactory
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import dev.avillagran.t3.T3Exception
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val baseUrl = "http://192.168.1.20:3773"
    private val secret = "very-secret-token-0123"
    private val fake = FakeT3Transport().apply {
        onFetchDescriptor(baseUrl) {
            JSONObject().put("environmentId", "env-1").put("label", "Workstation").put("serverVersion", "0.1.3")
        }
        onShell {
            JSONObject().put("snapshotSequence", 1).put("projects", JSONArray()).put("threads", JSONArray())
        }
    }
    private val dao = FakeEnvironmentDao()
    private val tokens = FakeTokenStore()
    private val saved = mutableListOf<String>()

    private fun show(): ConnectViewModel {
        val viewModel = ConnectViewModel(ServerProbe(FakeT3TransportFactory { fake }), fakeRepository(dao, tokens), editingId = null, onSaved = {})
        composeRule.setContent {
            Ot3Theme { ConnectScreen(viewModel = viewModel, onSaved = { saved += it }, onBack = null) }
        }
        return viewModel
    }

    @Test
    fun anEmptyUrlIsRejectedBeforeAnyRequest() {
        show()

        composeRule.onNodeWithTag(ConnectScreenTags.CHECK).performClick()

        composeRule.onNodeWithTag(ConnectScreenTags.ERROR)
            .assertTextContains(context.getString(R.string.connect_error_url_required))
    }

    @Test
    fun aMissingTokenIsRejected() {
        show()
        composeRule.onNodeWithTag(ConnectScreenTags.URL).performTextInput(baseUrl)

        composeRule.onNodeWithTag(ConnectScreenTags.SAVE).performClick()

        composeRule.onNodeWithTag(ConnectScreenTags.ERROR)
            .assertTextContains(context.getString(R.string.connect_error_token_required))
    }

    @Test
    fun checkServerShowsTheDescriptor() {
        show()
        composeRule.onNodeWithTag(ConnectScreenTags.URL).performTextInput("192.168.1.20:3773")

        composeRule.onNodeWithTag(ConnectScreenTags.CHECK).performClick()

        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Workstation · T3 0.1.3")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag(ConnectScreenTags.SERVER).assertIsDisplayed()
    }

    @Test
    fun aRejectedTokenIsReportedAndNeverShown() {
        fake.onShell { throw T3Exception("unauthorized") }
        show()
        composeRule.onNodeWithTag(ConnectScreenTags.URL).performTextInput(baseUrl)
        composeRule.onNodeWithTag(ConnectScreenTags.TOKEN).performTextInput(secret)

        composeRule.onNodeWithTag(ConnectScreenTags.SAVE).performClick()

        val message = context.getString(R.string.connect_error_unauthorized)
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText(message)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(composeRule.onAllNodes(hasText(secret, substring = true)).fetchSemanticsNodes().isEmpty())
        assertTrue("nothing saved", dao.rows.value.isEmpty() && tokens.tokens.isEmpty())
    }

    @Test
    fun unreachableAndHttpErrorsAreMapped() {
        fake.onFetchDescriptor(baseUrl) { throw T3Exception("http_502") }
        show()
        composeRule.onNodeWithTag(ConnectScreenTags.URL).performTextInput(baseUrl)

        composeRule.onNodeWithTag(ConnectScreenTags.CHECK).performClick()

        val message = context.getString(R.string.connect_error_http, "502")
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText(message)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun saveStoresTheEnvironmentAndItsTokenApart() {
        show()
        composeRule.onNodeWithTag(ConnectScreenTags.URL).performTextInput(baseUrl)
        composeRule.onNodeWithTag(ConnectScreenTags.TOKEN).performTextInput(secret)

        composeRule.onNodeWithTag(ConnectScreenTags.SAVE).performClick()

        composeRule.waitUntil(5_000) { saved.isNotEmpty() }
        val row = dao.rows.value.values.single()
        assertEquals("Workstation", row.label)
        assertEquals(baseUrl, row.baseUrl)
        assertEquals("env-1", row.environmentId)
        assertEquals(secret, tokens.tokens[row.id])
    }
}
