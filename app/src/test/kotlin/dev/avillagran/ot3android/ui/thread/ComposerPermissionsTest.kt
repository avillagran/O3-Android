package dev.avillagran.ot3android.ui.thread

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class ComposerPermissionsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val catalog = """{"providers":[{"instanceId":"work","supportedRuntimeModes":["approval-required","auto-accept-edits"]}],"permissionViews":{"work":{"rows":[{"id":"approval-required"},{"id":"auto-accept-edits"}]}}}"""
    @Test fun onlyAdvertisedChoicesAreOfferedAndSelectionIsExplicit() {
        var chosen: String? = null
        var refreshes = 0
        rule.setContent { Ot3Theme { ComposerPermissions(catalog, """{"instanceId":"work"}""",
            "approval-required", true, { refreshes++ }, { chosen = it }) } }
        rule.onNodeWithTag("composer_permissions").performClick()
        rule.onNodeWithTag("permission_full-access").assertDoesNotExist()
        rule.onNodeWithTag("permission_auto-accept-edits").performClick()
        rule.runOnIdle { assertEquals(1, refreshes); assertEquals("auto-accept-edits", chosen) }
        // A command acceptance is not a stream update: the displayed mode is still authoritative.
        rule.onNodeWithContentDescription("Thread permissions: Supervised").assertExists()
    }
    @Test fun offlineControlCannotChangePermissions() {
        rule.setContent { Ot3Theme { ComposerPermissions(catalog, """{"instanceId":"work"}""",
            "approval-required", false, { error("offline refresh") }, { error("offline command") }) } }
        rule.onNodeWithTag("composer_permissions").assertIsNotEnabled()
    }

    @Test fun nativeFallbackOffersAllChoicesWithoutChangingCurrentMode() {
        rule.setContent { Ot3Theme { ComposerPermissions("""{"providers":[{"instanceId":"work"}],"permissionViews":{"work":{"rows":[{"id":"approval-required"},{"id":"auto-accept-edits"},{"id":"auto"},{"id":"full-access"}]}}}""",
            """{"instanceId":"work"}""", "auto", true, {}, { error("unbound fallback") }) } }
        rule.onNodeWithTag("composer_permissions").performClick()
        rule.onNodeWithTag("permission_auto").assertExists()
        rule.onNodeWithTag("permission_approval-required").assertExists()
        rule.onNodeWithContentDescription("Thread permissions: Auto").assertExists()
    }
}
