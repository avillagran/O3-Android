package dev.avillagran.ot3android.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.core.connection.ConnectionState
import dev.avillagran.ot3android.core.connection.ServerDescriptor
import dev.avillagran.ot3android.core.home.HomeEnvironment
import dev.avillagran.ot3android.core.home.HomeInput
import dev.avillagran.ot3android.core.home.HomeStateBuilder
import dev.avillagran.ot3android.core.home.Shelf
import dev.avillagran.ot3android.core.shell.ShellReducer
import dev.avillagran.ot3android.core.transport.DomainEngine
import kotlinx.coroutines.runBlocking
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Home's stateless screen over a built state: rows, shelves and the intents they emit. */
@RunWith(AndroidJUnit4::class)
class HomeScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun thread(id: String, vararg fields: Pair<String, Any>) =
        JSONObject().put("id", id).put("projectId", "p1").put("title", "Thread $id")
            .put("updatedAt", "2026-10-25T11:00:00.000Z").apply { fields.forEach { (k, v) -> put(k, v) } }

    /** Built by the lib's `sidebar.view` through the app's JNI library. */
    private val state = runBlocking {
        HomeStateBuilder.build(
        DomainEngine.shared,
        HomeInput(
            environments = listOf(
                HomeEnvironment(
                    "e1",
                    "Laptop",
                    ConnectionState.Connected(ServerDescriptor(null, null, null, null, null)),
                    ShellReducer.parse(
                        JSONObject().put("snapshotSequence", 1)
                            .put("projects", JSONArray().put(JSONObject().put("id", "p1").put("title", "Alpha")))
                            .put(
                                "threads",
                                JSONArray().put(thread("a", "pendingRuntimeRequest" to JSONObject().put("kind", "approval").put("id", "q1")))
                                    .put(thread("s", "settledAt" to "2026-10-24T00:00:00.000Z")),
                            ),
                    ),
                ),
            ),
            now = ShellReducer.parseTimestamp("2026-10-25T12:00:00.000Z")!!,
        ),
        )
    }

    @Test
    fun rowsAndShelvesEmitIntents() {
        val intents = mutableListOf<HomeIntent>()
        composeRule.setContent { Ot3Theme { HomeScreen(state, isTop = true, onIntent = { intents += it }) } }

        val approval = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.ot3_status_approval)
        composeRule.onNodeWithTag(HomeTags.row("e1/a")).assertContentDescriptionContains(approval, substring = true)
        composeRule.onNodeWithTag(HomeTags.row("e1/a")).performClick()
        composeRule.onNodeWithTag(HomeTags.row("e1/a")).performTouchInput { longClick() }
        composeRule.onNodeWithTag(HomeTags.shelf(Shelf.Settled)).assertIsDisplayed().performClick()
        composeRule.onNodeWithTag(HomeTags.FILTER).performClick()

        val row = state.rows.getValue("e1/a")
        assertEquals(
            listOf(HomeIntent.OpenRow(row), HomeIntent.RowActions(row), HomeIntent.ToggleShelf(Shelf.Settled), HomeIntent.OpenFilters),
            intents,
        )
    }
}
