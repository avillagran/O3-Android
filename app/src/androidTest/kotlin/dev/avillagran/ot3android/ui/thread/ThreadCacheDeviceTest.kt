package dev.avillagran.ot3android.ui.thread

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dev.avillagran.ot3android.Ot3Application
import dev.avillagran.ot3android.core.data.prefs.ReadingPosition
import dev.avillagran.ot3android.debug.workspace.WorkspaceFixture
import dev.avillagran.ot3android.debug.workspace.WorkspaceFixtureActivity
import dev.avillagran.ot3android.ui.home.HomeTags
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ThreadCacheDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()
    @Test fun reopeningOfflineKeepsMessagesDraftAndReadingPositionWithoutAnotherSnapshot(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.noBackupFilesDir.resolve("fixture-thread-cache").deleteRecursively()
        context.deleteDatabase("cache-fixture.db")
        val preferences = (context.applicationContext as Ot3Application).graph.composerPreferences
        preferences.saveReadingPosition("node", "chat", ReadingPosition("m59", 0, 0))
        ActivityScenario.launch<WorkspaceFixtureActivity>(Intent(context, WorkspaceFixtureActivity::class.java)
            .putExtra("request", "cache")).use { scenario ->
            lateinit var fixture: WorkspaceFixture
            scenario.onActivity { fixture = it.fixture }
            rule.waitUntil(15_000) { rule.onAllNodesWithTag(HomeTags.row("node/chat")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag(HomeTags.row("node/chat")).performClick()
            rule.waitUntil(15_000) { rule.onAllNodesWithTag(ThreadScreenTags.TIMELINE).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag(ThreadScreenTags.TIMELINE).performScrollToIndex(12)
            rule.waitUntil(5_000) { runBlocking { (preferences.readingPosition("node", "chat")?.index ?: 0) >= 10 } }
            val position = preferences.readingPosition("node", "chat")!!
            rule.onNodeWithTag(ThreadScreenTags.COMPOSER).performTextReplacement("Borrador sin conexión")
            val beforeOffset = rule.onNodeWithTag(ThreadScreenTags.message(position.rowId)).fetchSemanticsNode().boundsInRoot.bottom -
                rule.onNodeWithTag(ThreadScreenTags.TIMELINE).fetchSemanticsNode().boundsInRoot.bottom
            val headerBack = rule.onNodeWithContentDescription("Back")
            traceInputTransition(scenario, "cache headerBack", headerBack, surface = {
                "timeline=${rule.onAllNodesWithTag(ThreadScreenTags.TIMELINE).fetchSemanticsNodes().size} " +
                    "homeRow=${rule.onAllNodesWithTag(HomeTags.row("node/chat")).fetchSemanticsNodes().size} " +
                    "collectors=${fixture.transport.collectors("chat").value}"
            }) { click ->
                click()
                rule.waitUntil(5_000) { fixture.transport.collectors("chat").value == 0 }
            }
            fixture.setCacheOffline(true)
            rule.onNodeWithTag(HomeTags.row("node/chat")).performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag(ThreadScreenTags.TIMELINE).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag(ThreadScreenTags.message(position.rowId)).assertIsDisplayed()
            val afterOffset = rule.onNodeWithTag(ThreadScreenTags.message(position.rowId)).fetchSemanticsNode().boundsInRoot.bottom -
                rule.onNodeWithTag(ThreadScreenTags.TIMELINE).fetchSemanticsNode().boundsInRoot.bottom
            assertEquals(beforeOffset, afterOffset, 1f)
            rule.onNodeWithTag(ThreadScreenTags.STALE).assertIsDisplayed()
            rule.onNodeWithText("Saved messages", substring = true).assertIsDisplayed()
            rule.onNodeWithTag(ThreadScreenTags.COMPOSER).assertTextContains("Borrador sin conexión")
            assertEquals(1, fixture.snapshotReads.get())
            assertEquals(0, fixture.transport.collectors("chat").value)
            if (androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("cacheEvidence") == "true") {
                rule.waitForIdle()
                val output = java.io.File(context.getExternalFilesDir(null), "cache-authority/offline.png")
                output.parentFile!!.mkdirs()
                val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                try { output.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
                finally { bitmap.recycle() }
            }
            fixture.setCacheOffline(false)
            rule.waitUntil(10_000) { rule.onAllNodesWithTag(ThreadScreenTags.STALE).fetchSemanticsNodes().isEmpty() }
            assertEquals(1, fixture.snapshotReads.get())
            scenario.recreate()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag(ThreadScreenTags.message(position.rowId)).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag(ThreadScreenTags.message(position.rowId)).assertIsDisplayed()
            assertEquals(1, fixture.snapshotReads.get())
        }
        // A fresh fixture VM/cache/Room instance, with all network reads denied from launch.
        ActivityScenario.launch<WorkspaceFixtureActivity>(Intent(context, WorkspaceFixtureActivity::class.java)
            .putExtra("request", "cache").putExtra("cacheOffline", true).putExtra("cacheThread", true)).use { scenario ->
            rule.waitUntil(10_000) { rule.onAllNodesWithTag(ThreadScreenTags.TIMELINE).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("Saved messages", substring = true).assertIsDisplayed()
            rule.waitUntil(5_000) { rule.onAllNodesWithText("Borrador sin conexión").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag(ThreadScreenTags.COMPOSER).assertTextContains("Borrador sin conexión")
            scenario.onActivity { assertEquals(0, it.fixture.snapshotReads.get()) }
        }
    }
}
