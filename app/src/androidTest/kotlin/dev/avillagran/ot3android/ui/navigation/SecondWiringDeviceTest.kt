package dev.avillagran.ot3android.ui.navigation

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dev.avillagran.ot3android.debug.workspace.WorkspaceFixtureActivity
import dev.avillagran.ot3android.ui.home.HomeTags
import dev.avillagran.ot3android.ui.settings.SettingsTags
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class SecondWiringDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()
    @org.junit.Before fun isolateFixtureJournal() {
        ApplicationProvider.getApplicationContext<Context>().noBackupFilesDir.resolve("fixture-git-journal").deleteRecursively()
    }
    private fun awaitRecoveredDraft() {
        rule.waitUntil(10_000) {
            rule.onAllNodesWithTag(dev.avillagran.ot3android.ui.thread.ThreadScreenTags.COMPOSER).fetchSemanticsNodes().any {
                it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.EditableText) && it.config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text == "New typing\n\nOriginal checkpoint prompt"
            }
        }
    }
    @Test fun nativeTextCheckpointRecoversOnceWithoutReplayingDispatch() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (dark in listOf(false, true)) for (landscape in listOf(false, true)) {
            ActivityScenario.launch<WorkspaceFixtureActivity>(Intent(context, WorkspaceFixtureActivity::class.java)
                .putExtra("request", "checkpoint").putExtra("parity", true).putExtra("dark", dark).putExtra("landscape", landscape)).use { scenario ->
                rule.waitUntil(15_000) { rule.onAllNodesWithTag(HomeTags.row("node/chat")).fetchSemanticsNodes().isNotEmpty() }
                rule.onNodeWithTag(HomeTags.row("node/chat")).performClick()
                val composer = rule.onNodeWithTag(dev.avillagran.ot3android.ui.thread.ThreadScreenTags.COMPOSER)
                composer.performTextReplacement("New typing")
                scenario.onActivity { it.currentFocus?.clearFocus() }
                androidx.test.espresso.Espresso.closeSoftKeyboard()
                rule.waitUntil(10_000) { rule.onAllNodesWithTag("checkpoint_preview_message-one").fetchSemanticsNodes().isNotEmpty() }
                rule.onNodeWithTag("checkpoint_preview_message-one").performClick()
                rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("checkpoint_confirm") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                rule.onNodeWithTag("checkpoint_confirm").performClick()
                rule.waitUntil(10_000) { var dispatched = false; scenario.onActivity { dispatched = it.fixture.checkpointCommand != null }; dispatched }
                rule.onNodeWithText("Refresh").performClick()
                rule.waitUntil(10_000) { rule.onAllNodesWithText("Retry").fetchSemanticsNodes().isNotEmpty() }
                lateinit var fixture: dev.avillagran.ot3android.debug.workspace.WorkspaceFixture
                scenario.onActivity { fixture = it.fixture }
                runBlocking { fixture.connections.supervisor("node")?.stopAndJoin() }
                rule.onNodeWithText("Retry").performClick()
                rule.waitUntil(10_000) { rule.onAllNodesWithText("Completed").fetchSemanticsNodes().isNotEmpty() }
                rule.onNodeWithText("Close").performClick()
                awaitRecoveredDraft()
                rule.onNodeWithTag(dev.avillagran.ot3android.ui.thread.ThreadScreenTags.COMPOSER).assertTextContains("New typing\n\nOriginal checkpoint prompt")
                scenario.onActivity { activity ->
                    assertEquals(1, activity.fixture.transport.calls.count { it.fn == "orchestration.dispatchCommand" })
                    assertEquals("complete", activity.fixture.gitJournal.find(activity.fixture.checkpointCommand!!.getString("commandId").removePrefix("omarchy-"))!!.getString("state"))
                }
                runBlocking { fixture.connections.startIfCurrent(fixture.connections.supervisor("node")) }
                rule.onNodeWithTag("composer_back").performClick()
                rule.onNodeWithTag(HomeTags.row("node/chat")).performClick()
                awaitRecoveredDraft()
                rule.onNodeWithTag(dev.avillagran.ot3android.ui.thread.ThreadScreenTags.COMPOSER).assertTextContains("New typing\n\nOriginal checkpoint prompt")
                scenario.onActivity { activity -> assertEquals(1, activity.fixture.transport.calls.count { it.fn == "orchestration.dispatchCommand" }) }
            }
        }
    }

    @Test fun gitEntryAndNativePullKeepHostInBothOrientationsAndThemes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (dark in listOf(false, true)) for (landscape in listOf(false, true)) {
            ActivityScenario.launch<WorkspaceFixtureActivity>(Intent(context, WorkspaceFixtureActivity::class.java)
                .putExtra("request", "wiring").putExtra("dark", dark).putExtra("landscape", landscape)).use { scenario ->
                for (host in listOf("node", "remote")) {
                    rule.waitUntil(15_000) { rule.onAllNodesWithTag(HomeTags.row("$host/chat")).fetchSemanticsNodes().isNotEmpty() }
                    rule.onNodeWithTag(HomeTags.row("$host/chat")).performClick()
                    rule.onNodeWithTag("thread_overflow").performClick()
                    rule.onNodeWithText("Git & scripts").performClick()
                    rule.onNodeWithTag(NavTags.BAR).assertDoesNotExist()
                    rule.waitUntil(10_000) {
                        rule.onAllNodes(hasText("Pull") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
                    }
                    var previous = emptySet<String>()
                    scenario.onActivity { previous = it.fixture.gitJournal.records().map { row -> row.getString("id") }.toSet() }
                    rule.onNodeWithText("Pull").performClick()
                    rule.onNodeWithTag("git_confirm").performScrollTo().performClick()
                    rule.waitUntil(10_000) { rule.onAllNodesWithText("Completed").fetchSemanticsNodes().isNotEmpty() }
                    scenario.onActivity { activity ->
                        val record = activity.fixture.gitJournal.records().single { it.getString("id") !in previous }
                        assertEquals(host, record.getJSONObject("plan").getJSONObject("scope").getString("environment"))
                        assertEquals("complete", record.getString("state"))
                    }
                    lateinit var fixture: dev.avillagran.ot3android.debug.workspace.WorkspaceFixture
                    scenario.onActivity { fixture = it.fixture }
                    runBlocking { fixture.connections.supervisor(host)?.stopAndJoin() }
                    rule.waitUntil(10_000) { rule.onAllNodesWithText("Reconnect this environment before rewinding.").fetchSemanticsNodes().isNotEmpty() }
                    rule.onNodeWithTag("git_confirm").assertDoesNotExist()
                    rule.onNodeWithText("Back").performClick()
                    rule.onNodeWithTag("composer_back").performClick()
                }
                scenario.onActivity { activity ->
                    assertEquals(2, activity.fixture.maintenanceHosts.sumOf { transport -> transport.calls.count { it.fn == "vcs.pull" } })
                }
            }
        }
    }

    @Test fun deliveredMaintenanceEntriesAcrossThemesOrientationsHostsAndOffline() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (dark in listOf(false, true)) for (landscape in listOf(false, true)) {
            ActivityScenario.launch<WorkspaceFixtureActivity>(Intent(context, WorkspaceFixtureActivity::class.java)
                .putExtra("request", "wiring").putExtra("dark", dark).putExtra("landscape", landscape)).use { scenario ->
                rule.waitUntil(15_000) { rule.onAllNodesWithTag(HomeTags.row("node/chat")).fetchSemanticsNodes().isNotEmpty() }
                rule.onNodeWithTag(NavTags.tab(TopTab.Settings)).performClick()
                for (page in listOf("storage", "archived", "schedules", "sessions", "diagnostics")) {
                    rule.onNodeWithTag("settings_page_$page").performScrollTo().performClick()
                    rule.onNodeWithTag(NavTags.BAR).assertDoesNotExist()
                    rule.onNode(hasText("All environments") and hasClickAction()).performClick()
                    rule.onNodeWithText("node · Linux").performClick()
                    rule.waitUntil(10_000) { rule.onAllNodesWithTag("maintenance_page").fetchSemanticsNodes().isNotEmpty() }
                    rule.onNodeWithTag("maintenance_page").assertIsDisplayed()
                    rule.onAllNodesWithText("node · Linux").onFirst().performClick()
                    rule.onNodeWithText("remote · Linux").performClick()
                    rule.waitUntil(10_000) { rule.onAllNodesWithTag("maintenance_page").fetchSemanticsNodes().isNotEmpty() }
                    rule.onNodeWithTag("maintenance_page").assertIsDisplayed()
                    rule.onNodeWithText("remote", substring = false).assertIsDisplayed()
                    rule.onNodeWithText("Back").performClick()
                    rule.onNodeWithTag(SettingsTags.ROOT).assertIsDisplayed()
                }
                // A missing transport must remove the old host's controller and actions.
                rule.onNodeWithTag("settings_page_schedules").performScrollTo().performClick()
                rule.onNode(hasText("All environments") and hasClickAction()).performClick()
                rule.onNodeWithText("node · Linux").performClick()
                rule.waitUntil(10_000) { rule.onAllNodesWithTag("maintenance_page").fetchSemanticsNodes().isNotEmpty() }
                lateinit var fixture: dev.avillagran.ot3android.debug.workspace.WorkspaceFixture
                scenario.onActivity { fixture = it.fixture }
                runBlocking { fixture.connections.supervisor("node")?.stopAndJoin() }
                rule.waitUntil(10_000) { rule.onAllNodesWithTag("maintenance_page").fetchSemanticsNodes().isEmpty() }
                rule.onNodeWithText("Reconnect this environment before rewinding.").assertIsDisplayed()
                scenario.onActivity { activity -> assertTrue(activity.fixture.maintenanceHosts.all { host ->
                    host.calls.none { it.fn in setOf("scheduledTasks.runNow", "server.runStorageCleanup", "orchestration.dispatchCommand", "server.signalProcess") }
                }) }
            }
        }
    }
}
