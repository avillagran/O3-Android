package dev.avillagran.ot3android.ui.thread

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.testing.ScreenshotTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/** Actual reader surface with representative native projection fields; no server claim. */
class ToolInspectorScreenshotTest : ScreenshotTest() {
    private fun view() = JSONObject().put("available", true).put("title", "Run verification")
        .put("status", "failed").put("failed", true).put("exitCode", 1)
        .put("sections", JSONArray()
            .put(JSONObject().put("key", "input").put("label", "Command").put("text", "./gradlew :app:testDebugUnitTest"))
            .put(JSONObject().put("key", "output").put("label", "Output")
                .put("text", "327 tests completed\n1 assertion failed\nSee verification report for details.").put("truncated", true)))
        .put("metadata", "{\n  \"tool\": \"shell\",\n  \"exitCode\": 1\n}")
        .put("raw", "{\n  \"type\": \"tool_result\",\n  \"exitCode\": 1\n}")

    @Test fun details() = snapshot("details") {
        Column(Modifier.padding(12.dp)) { ToolInspectorContent(view(), false, false, "details", {}, {}, {}) }
    }
    @Test fun raw() = snapshot("raw") {
        Column(Modifier.padding(12.dp)) { ToolInspectorContent(view(), false, false, "raw", {}, {}, {}) }
    }
    @Test fun dialog() = snapshot("dialog", screen = true) {
        ToolInspectorDialog(JSONObject().put("revision", "capture"), { view() }, {})
    }

}
