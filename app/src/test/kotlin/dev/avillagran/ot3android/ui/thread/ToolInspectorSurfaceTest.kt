package dev.avillagran.ot3android.ui.thread

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ToolInspectorSurfaceTest {
    @get:Rule val rule = createComposeRule()

    private fun view(title: String = "Shell") = JSONObject().put("available", true).put("title", title)
        .put("status", "failed").put("failed", true).put("exitCode", 7)
        .put("sections", org.json.JSONArray().put(JSONObject().put("key", "output").put("label", "Output").put("text", "native output").put("truncated", true)))
        .put("metadata", "native metadata").put("metadataTruncated", true).put("raw", "native raw").put("rawTruncated", true)

    @Test fun tabsExposeNativeFieldsWithoutAnotherRead() {
        var reads = 0
        rule.setContent { Ot3Theme { ToolInspectorSurface(JSONObject().put("revision", "a"), { reads++; view() }) } }
        rule.onNodeWithText("Shell").assertIsDisplayed()
        rule.onNodeWithTag("thread_inspector_status").assertTextEquals("failed · Exit code: 7")
        rule.onNodeWithTag("thread_inspector_section_output").assertTextEquals("native output")
        rule.onNodeWithText("Display limit reached. Copy includes only the displayed text.").assertExists()
        rule.onNodeWithTag("thread_inspector_tab_metadata").performClick()
        rule.onNodeWithTag("thread_inspector_section_metadata").assertTextEquals("native metadata")
        rule.onNodeWithTag("thread_copy_inspector_metadata").assertIsEnabled()
        rule.onNodeWithTag("thread_inspector_tab_raw").performClick()
        rule.onNodeWithTag("thread_inspector_section_raw").assertTextEquals("native raw")
        rule.runOnIdle { assertEquals(1, reads) }
    }

    @Test fun failedReadCanRetryAndDistinguishesOmittedFromEmptyOutput() {
        var reads = 0
        rule.setContent { Ot3Theme { ToolInspectorSurface(JSONObject(), {
            reads++
            if (reads == 1) error("read failed")
            view().put("sections", org.json.JSONArray()).put("emptyOutput", false).put("outputOmitted", true)
        }) } }
        rule.onNodeWithText("Couldn't load output").assertIsDisplayed()
        rule.onNodeWithTag("thread_inspector_retry").performClick()
        rule.onNodeWithText("Output was withheld by the server. Retry to refresh.").assertExists()
        rule.onNodeWithText("No output.").assertDoesNotExist()
        rule.runOnIdle { assertEquals(2, reads) }
    }

    @Test fun emptyOutputAndUnavailableAreExplicit() {
        val content = mutableStateOf<JSONObject?>(view().put("emptyOutput", true).put("sections", org.json.JSONArray()))
        rule.setContent { Ot3Theme { ToolInspectorContent(content.value, false, false, "details", {}, {}) } }
        rule.onNodeWithText("No output.").assertExists()
        rule.runOnIdle { content.value = JSONObject().put("available", false) }
        rule.onNodeWithText("Output is no longer available.").assertExists()
        rule.onNodeWithTag("thread_inspector_tab_raw").assertDoesNotExist()
        rule.runOnIdle { content.value = null }
        rule.onNodeWithText("Offline").assertExists()
    }

    @Test fun cancelledOldReadCannotReplaceNewReferenceOrItsTabs() {
        val reference = mutableStateOf(JSONObject().put("revision", "old"))
        val oldResponse = CompletableDeferred<JSONObject>()
        rule.setContent { Ot3Theme { ToolInspectorSurface(reference.value, {
            if (it.getString("revision") == "old") withContext(NonCancellable) { oldResponse.await() } else view("New tool")
        }) } }
        rule.onNodeWithTag("thread_inspector_retry").assertIsNotEnabled()
        rule.runOnIdle { reference.value = JSONObject().put("revision", "new") }
        rule.onNodeWithText("New tool").assertIsDisplayed()
        rule.onNodeWithTag("thread_inspector_tab_raw").performClick()
        rule.runOnIdle { oldResponse.complete(view("Stale tool")) }
        rule.onNodeWithText("Stale tool").assertDoesNotExist()
        rule.onNodeWithTag("thread_inspector_section_raw").assertTextEquals("native raw")
    }
    @Test @Config(qualifiers = "es") fun labelsUseTheSharedSpanishCatalog() {
        rule.setContent { Ot3Theme { ToolInspectorContent(view(), false, false, "details", {}, {}) } }
        rule.onNodeWithText("Salida").assertExists()
        rule.onNodeWithTag("thread_inspector_section_output").assertTextEquals("native output")
        rule.onNodeWithText("Metadatos").assertExists()
        rule.onNodeWithText("Elemento sin procesar").assertExists()
        rule.onNodeWithTag("thread_inspector_status").assertTextEquals("fallido · Código de salida: 7")
    }

    @Test fun headerIconFollowsSelectedReferenceType() {
        val reference = mutableStateOf(JSONObject().put("type", "reasoning"))
        rule.setContent { Ot3Theme { ToolInspectorSurface(reference.value, { view() }) } }
        rule.onNodeWithTag("thread_inspector_icon_sparkles").assertExists()
        rule.onNodeWithTag("thread_inspector_icon_terminal").assertDoesNotExist()
        rule.runOnIdle { reference.value = JSONObject().put("type", "command_execution") }
        rule.onNodeWithTag("thread_inspector_icon_terminal").assertExists()
        rule.onNodeWithTag("thread_inspector_icon_sparkles").assertDoesNotExist()
    }

    @Test @Config(qualifiers = "es") fun unknownNativeLabelsAndOutputRemainUnchanged() {
        val detail = view().put("status", "provider-specific-state").put("sections",
            org.json.JSONArray().put(JSONObject().put("key", "custom").put("label", "Provider detail")
                .put("text", "failed Input Output")))
        rule.setContent { Ot3Theme { ToolInspectorContent(detail, false, false, "details", {}, {}) } }
        rule.onNodeWithText("Provider detail").assertExists()
        rule.onNodeWithTag("thread_inspector_status").assertTextEquals("provider-specific-state · Código de salida: 7")
        rule.onNodeWithTag("thread_inspector_section_custom").assertTextEquals("failed Input Output")
    }

    @Test fun groupedActivityReadsOnlyTheSelectedEntryAndCanClose() {
        var reads = 0
        val activity = JSONObject().put("title", "Grouped tools").put("entries", org.json.JSONArray()
            .put(JSONObject().put("title", "First").put("inspector", JSONObject().put("revision", "first")))
            .put(JSONObject().put("title", "Second").put("inspector", JSONObject().put("revision", "second"))))
        val projected = JSONObject().put("timeline", org.json.JSONArray().put(
            JSONObject().put("id", "group").put("role", "activity").put("activity", activity)))
        rule.setContent { Ot3Theme { TimelineReader(projected, emptyList(), onInspect = { reads++; view(it.getString("revision")) }) } }
        rule.onNodeWithTag("thread_fold_group").performClick()
        rule.runOnIdle { assertEquals(0, reads) }
        rule.onNodeWithTag("thread_inspect_group_0").performClick()
        rule.onNodeWithText("first").assertExists()
        rule.runOnIdle { assertEquals(1, reads) }
        rule.onNodeWithTag("thread_inspector_close").performClick()
        rule.onNodeWithTag("thread_inspect_group_1").performClick()
        rule.onNodeWithText("second").assertExists()
        rule.runOnIdle { assertEquals(2, reads) }
    }

    @Test fun retryKeepsTheLastRawBodyUntilTheReplacementReadCompletes() {
        var reads = 0
        val replacement = CompletableDeferred<JSONObject>()
        rule.setContent { Ot3Theme { ToolInspectorSurface(JSONObject(), {
            reads++
            if (reads == 1) view() else replacement.await()
        }) } }
        rule.onNodeWithTag("thread_inspector_tab_raw").performClick()
        rule.onNodeWithTag("thread_inspector_retry").performClick()
        rule.onNodeWithTag("thread_inspector_retry").assertIsNotEnabled()
        rule.onNodeWithTag("thread_inspector_section_raw").assertTextEquals("native raw")
        rule.runOnIdle { replacement.complete(view().put("raw", "replacement raw")) }
        rule.onNodeWithTag("thread_inspector_section_raw").assertTextEquals("replacement raw")
        rule.runOnIdle { assertEquals(2, reads) }
    }

    @Test @Config(sdk = [34], qualifiers = "w914dp-h411dp-land-xhdpi")
    fun compactTimelineKeepsCloseTouchableAboveJumpControl() {
        val row = JSONObject().put("id", "tools").put("role", "activity").put("activity",
            JSONObject().put("title", "Tools").put("entries", org.json.JSONArray().put(
                JSONObject().put("title", "Shell").put("inspector", JSONObject().put("revision", "compact")))))
        val timeline = JSONObject().put("timeline", org.json.JSONArray()
            .put(JSONObject().put("id", "older").put("role", "assistant").put("text", "Older context\n".repeat(40)))
            .put(row).put(JSONObject().put("id", "latest").put("role", "assistant").put("text", "Latest response\n".repeat(40))))
        rule.setContent { Ot3Theme(motion = dev.avillagran.ot3android.ui.theme.Ot3MotionPolicy.Off) {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxWidth().height(136.dp)) {
                TimelineReader(timeline, emptyList(), onInspect = { view().put("raw", "{ native output }\n".repeat(80)) })
            }
        } }
        val list = rule.onNodeWithTag(ThreadScreenTags.TIMELINE)
        list.performScrollToNode(hasTestTag("thread_fold_tools"))
        rule.onNodeWithTag("thread_fold_tools").performClick()
        list.performScrollToNode(hasTestTag("thread_inspect_tools_0"))
        rule.onNodeWithTag("thread_inspect_tools_0").performClick()
        rule.onNodeWithTag("thread_inspector_tab_raw").performScrollTo()
        rule.onNodeWithTag("thread_inspector_tab_raw").performClick()
        rule.onNodeWithTag("thread_inspector_retry").performScrollTo()
        rule.onNodeWithTag("thread_inspector_retry").performClick()
        rule.onNodeWithTag("thread_inspector_close").performScrollTo()
        rule.onNodeWithTag("thread_inspector_close").assertIsDisplayed().performClick()
        rule.onNodeWithTag("thread_inspector").assertDoesNotExist()
        list.performScrollToNode(hasTestTag("thread_inspect_tools_0"))
        rule.onNodeWithTag("thread_inspect_tools_0").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w411dp-h891dp-port-xhdpi")
    fun shortInspectorDoesNotReserveThePortraitViewport() {
        rule.setContent { Ot3Theme { ToolInspectorDialog(JSONObject(), {
            view().put("sections", org.json.JSONArray()).put("emptyOutput", true)
        }, {}) } }
        rule.onNodeWithText("No output.").assertExists()
        val bounds = rule.onNodeWithTag("thread_inspector_window").getUnclippedBoundsInRoot()
        org.junit.Assert.assertTrue("Short inspection wraps content instead of an empty full-height panel", bounds.bottom - bounds.top < 300.dp)
    }

    @Test fun readerKeepsInspectorWhenItsSourceRowLeavesComposition() {
        var reads = 0
        val projected = mutableStateOf(JSONObject().put("timeline", org.json.JSONArray().put(
            JSONObject().put("id", "source").put("role", "activity").put("activity",
                JSONObject().put("title", "Tool").put("inspector", JSONObject().put("revision", "source"))))))
        rule.setContent { Ot3Theme { TimelineReader(projected.value, emptyList(), onInspect = { reads++; view() }) } }
        rule.onNodeWithTag("thread_fold_source").performClick()
        rule.onNodeWithTag("thread_inspect_source_0").performClick()
        rule.onNodeWithTag("thread_inspector_tab_raw").performClick()
        rule.runOnIdle { projected.value = JSONObject().put("timeline", org.json.JSONArray().put(
            JSONObject().put("id", "replacement").put("role", "assistant").put("text", "New projection"))) }
        rule.onNodeWithTag("thread_inspector_section_raw").assertTextEquals("native raw")
        rule.runOnIdle { assertEquals(1, reads) }
        rule.onNodeWithTag("thread_inspector_close").performScrollTo().performClick()
        rule.onNodeWithTag("thread_inspector").assertDoesNotExist()
    }

}
