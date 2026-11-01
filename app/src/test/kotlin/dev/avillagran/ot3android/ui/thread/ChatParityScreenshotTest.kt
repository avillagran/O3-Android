package dev.avillagran.ot3android.ui.thread

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.roborazziSystemPropertyOutputDirectory
import dev.avillagran.ot3android.core.composer.*
import dev.avillagran.ot3android.testing.ScreenshotTest
import dev.avillagran.ot3android.ui.thread.composer.*
import dev.avillagran.ot3android.ui.thread.reader.TranscriptActions
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Test
import org.robolectric.annotation.Config

/** Same fixture and logical widths as the offscreen real QML component captures. */
@Config(qualifiers = "w444dp-h1200dp-port-mdpi")
@OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
class ChatParityScreenshotTest : ScreenshotTest() {
    private val fixtures = JSONObject(checkNotNull(javaClass.classLoader!!.getResource("chat-parity.json")).readText())
    @Test fun components() {
        var name by mutableStateOf("user")
        var width by mutableIntStateOf(360)
        snapshot("fixture", themes = listOf("tokyo-night"), beforeCapture = {
            for (w in listOf(360, 444)) for (key in fixtures.keys().asSequence().toList()) {
                composeRule.runOnIdle { width = w; name = key }; composeRule.waitForIdle()
                if (key == "image") composeRule.waitUntil(5_000) {
                    composeRule.onAllNodesWithContentDescription("chat-parity-image.png").fetchSemanticsNodes().isNotEmpty()
                }
                if (key == "composer") {
                    val frame = composeRule.onNodeWithTag("chat_fixture").fetchSemanticsNode().boundsInRoot
                    val send = composeRule.onNodeWithTag(ThreadScreenTags.SEND).fetchSemanticsNode().boundsInRoot
                    org.junit.Assert.assertTrue("Send stays within $w logical pixels: $frame $send", send.right <= frame.right)
                }
                composeRule.onNodeWithTag("chat_fixture").captureRoboImage(
                    "${roborazziSystemPropertyOutputDirectory()}/ChatParityScreenshotTest/$key-$w.png")
            }
        }) {
            ChatStyle {
            Box(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalReaderImages provides ReaderImages("parity") {
                checkNotNull(javaClass.classLoader!!.getResourceAsStream("chat-parity-image.png")).use { it.readBytes() }
            }) {
            Surface(Modifier.requiredWidth(width.dp).wrapContentHeight().testTag("chat_fixture")) {
                Column {
                    val fixture = fixtures.getJSONObject(name)
                    when (name) {
                        "composer" -> ComposerParity(ComposerSnapshot(ComposerScope("fixture", "project", "chat"), text = fixture.getString("text")),
                            setOf(ComposerAction.Attach, ComposerAction.History, ComposerAction.ProjectDrafts, ComposerAction.Undo, ComposerAction.Redo),
                            { _, _, _, _ -> }, {}, toolbar = {
                                ComposerModels(null, null, false, true, {}, {}, { _, _ -> }, { _, _ -> })
                            })
                        "approval" -> ApprovalCard(JSONArray().put(fixture), true, null, emptySet()) { _, _ -> }
                        "question" -> QuestionCard(JSONArray().put(fixture), true, null, emptySet(), renderMarkdown = { text -> JSONArray().put(JSONObject().put("type", "paragraph").put("spans", JSONArray().put(JSONObject().put("type", "text").put("text", text)))) }) { _, _, _ -> }
                        "queue" -> ThreadQueue(fixture, true, {}, {})
                        "inspector" -> ToolInspectorContent(fixture, false, false, "details", {}, {})
                        else -> TimelineRow(fixture, null, TranscriptActions(), {}, {})
                    }
                }
            }
            }
            }
            }
        }
    }

    @Test fun activityAndComposerInAllTwentyLocales() {
        var locale by mutableStateOf("en")
        val locales = listOf("en", "ar", "cs", "da", "de", "es", "fi", "fr", "it", "ja", "ko", "nb", "nl", "pl", "pt", "ru", "sv", "tr", "uk", "zh")
        snapshot("locale", themes = listOf("tokyo-night"), beforeCapture = {
            for (tag in locales) {
                composeRule.runOnIdle { locale = tag }; composeRule.waitForIdle()
                composeRule.onNodeWithTag("locale_fixture").captureRoboImage(
                    "${roborazziSystemPropertyOutputDirectory()}/ChatParityScreenshotTest/locale-$tag.png")
            }
        }) {
            val context = androidx.compose.ui.platform.LocalContext.current
            val registry = androidx.activity.compose.LocalActivityResultRegistryOwner.current
                ?: context as androidx.activity.result.ActivityResultRegistryOwner
            val config = android.content.res.Configuration(androidx.compose.ui.platform.LocalConfiguration.current).apply {
                setLocale(java.util.Locale.forLanguageTag(locale))
            }
            CompositionLocalProvider(
                androidx.activity.compose.LocalActivityResultRegistryOwner provides registry,
                androidx.compose.ui.platform.LocalContext provides context.createConfigurationContext(config),
                androidx.compose.ui.platform.LocalConfiguration provides config,
                androidx.compose.ui.platform.LocalLayoutDirection provides if (locale == "ar") androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr,
            ) {
                ChatStyle {
                    Column(Modifier.testTag("locale_fixture")) {
                        for (key in listOf("activity", "tool", "working")) ActivityBlock(fixtures.getJSONObject(key).getJSONObject("activity"), key, {})
                        ActivityBlock(JSONObject().put("category", "execute").put("command", "cargo test").put("failed", true).put("active", false), "failed", {})
                        ComposerParity(ComposerSnapshot(ComposerScope("fixture", "project", "chat"), text = "", sendBlocked = true),
                            setOf(ComposerAction.Attach, ComposerAction.History), { _, _, _, _ -> }, {})
                    }
                }
            }
        }
    }

    @Test fun compactComposerLeavesFullWidthForTypingAndOneControlRow() {
        snapshot("compact-controls") {
            ComposerParity(ComposerSnapshot(ComposerScope("fixture", "project", "chat"), text = "Write a message"),
                setOf(ComposerAction.Attach, ComposerAction.History, ComposerAction.Undo), { _, _, _, _ -> }, {})
        }
        val input = composeRule.onNodeWithTag(ThreadScreenTags.COMPOSER).fetchSemanticsNode().boundsInRoot
        val controls = composeRule.onNodeWithTag("composer_control_row").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("Input stays above controls", input.bottom <= controls.top)
        val send = composeRule.onNodeWithTag(ThreadScreenTags.SEND).fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("Send fits the compact control row", send.right <= controls.right)
        composeRule.onNodeWithTag("composer_actions").performClick()
        composeRule.onNodeWithText("Undo").assertIsDisplayed()
    }
}
