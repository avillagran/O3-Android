package dev.avillagran.ot3android.ui.newchat

import dev.avillagran.ot3android.testing.ScreenshotTest
import org.junit.Test

class NewChatScreenshotTest : ScreenshotTest() {
    @Test fun projects() = snapshot("projects") {
        NewChatProjectPicker(listOf(
            NewChatProjectOption("node", "NODE", "android", "ot3-android", "/home/node/Work/ot3-android", true),
            NewChatProjectOption("mac", "MBP-M2", "android", "ot3-android", "/Users/andres/Work/ot3-android", true),
            NewChatProjectOption("vq", "VQ", "other", "Offline project", "/work/project", false)),
            false, {}, {}, {})
    }
    @Test fun draft() = snapshot("draft") {
        NewChatDraftScreen(NewChatDraftState(loading = false, writable = true, projectTitle = "ot3-android",
            text = "Review the project and fix the failing test.",
            catalog = """{"models":[{"instanceId":"codex","model":"gpt-test","shortName":"Test model","available":true}],"providers":[{"instanceId":"codex","supportedRuntimeModes":["approval-required"]}]}""",
            selection = """{"instanceId":"codex","model":"gpt-test"}"""),
            {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, environmentLabel = "NODE")
    }
}
