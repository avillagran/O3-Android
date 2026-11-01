package dev.avillagran.ot3android.ui.thread

import dev.avillagran.ot3android.testing.ScreenshotTest
import dev.avillagran.ot3android.ui.thread.reader.*
import org.json.JSONObject
import org.junit.Test

class ChatActionsScreenshotTest : ScreenshotTest() {
    private val plan = JSONObject("""{"id":"plan","threadId":"chat","status":"active","title":"Ship Chat actions","markdown":"# Ship Chat actions\n\nKeep native policy and explicit receipts.","blocks":[{"type":"paragraph","spans":[{"type":"text","text":"Keep native policy and explicit receipts."}]}]}""")

    @Test fun ready() = card("ready", "")
    @Test fun refine() = card("refine", "Clarify the validation")
    @Test fun unknown() = card("unknown", "", true)

    private fun card(name: String, draft: String, unknown: Boolean = false) = snapshot(name) {
        ChatStyle {
            TimelineRow(JSONObject().put("id", "plan-row").put("role", "plan").put("plan", plan), plan,
                TranscriptActions(planDraft = draft, followUpPlan = { _, _, _ -> },
                    planOperation = if (unknown) PlanOperation("plan", uncertain = true) else null), {}, {})
        }
    }

    @Test fun fork() = snapshot("fork") {
        ChatStyle {
            TimelineRow(JSONObject().put("id", "response").put("role", "assistant").put("text", "Implementation complete.")
                .put("forkRunId", "run"), null, TranscriptActions(fork = {}), {}, {})
        }
    }
}
