package dev.avillagran.ot3android.ui.thread

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
        rule.onNodeWithText("Metadatos").assertExists()
        rule.onNodeWithText("Elemento sin procesar").assertExists()
        rule.onNodeWithTag("thread_inspector_status").assertTextEquals("failed · Código de salida: 7")
    }

    @Test fun groupedActivityReadsOnlyTheSelectedEntryAndCanClose() {
        var reads = 0
        val activity = JSONObject().put("title", "Grouped tools").put("entries", org.json.JSONArray()
            .put(JSONObject().put("title", "First").put("inspector", JSONObject().put("revision", "first")))
            .put(JSONObject().put("title", "Second").put("inspector", JSONObject().put("revision", "second"))))
        rule.setContent { Ot3Theme { ActivityBlock(activity, "group") { reads++; view(it.getString("revision")) } } }
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

}
