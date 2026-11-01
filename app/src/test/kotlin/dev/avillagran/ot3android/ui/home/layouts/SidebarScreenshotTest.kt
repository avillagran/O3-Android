package dev.avillagran.ot3android.ui.home.layouts

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import dev.avillagran.ot3android.core.data.prefs.*
import dev.avillagran.ot3android.core.home.*
import dev.avillagran.ot3android.testing.ScreenshotTest
import dev.avillagran.ot3android.ui.home.HomeScreen
import dev.avillagran.ot3android.ui.navigation.LocalSelectedThreadKey
import org.junit.Test
import org.robolectric.annotation.Config

class SidebarScreenshotTest : ScreenshotTest() {
    private fun render(layout: String, scale: Float = 1f, vivid: Boolean = false, name: String = layout, large: Boolean = false) {
        val rows = sidebarFixture()
        val state = HomeUiState(content = HomeContent.List, shelves = ShelfState(grouped = true),
            rows = rows.associateBy { it.key }, items = rows.map { HomeItem.Row(it) }, multipleEnvironments = true)
        snapshot("$name-$scale-$vivid") {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale),
                LocalSelectedThreadKey provides "node/pin") {
                HomeScreen(state, true, {}, appearance = AppearanceSettings(local = mapOf(
                    LocalAppearancePreference.INTERFACE_LAYOUT to layout,
                    LocalAppearancePreference.THREAD_ROW_SIZE to if (large) "large" else "compact",
                    LocalAppearancePreference.SIDEBAR_STYLE to if (vivid) "vivid" else "sober")),
                    metadata = SidebarMetadata(hostStyles = mapOf("node" to HostStyle("server", "#5aa2e6", true), "mac" to HostStyle("laptop", "#e06c75", true))))
            }
        }
    }
    @Test fun classic() = render("classic")
    @Test fun focus() = render("focus")
    @Test fun projects() = render("projects")
    @Test fun font160() = render("classic", 1.6f)
    @Test @Config(qualifiers = "w891dp-h411dp-land-xhdpi") fun landscape() = render("classic", name = "landscape")
    @Test fun scrollAndSelectionSurviveSnapshotReplacement() {
        val rows = (0..35).map { fixtureRow(if (it % 2 == 0) "node" else "mac", "thread-$it") }
        var state by mutableStateOf(HomeUiState(content = HomeContent.List, rows = rows.associateBy { it.key }, items = rows.map { HomeItem.Row(it) }))
        val target = rows[25]
        var opened: String? = null
        snapshot("retention") {
            CompositionLocalProvider(LocalSelectedThreadKey provides target.key) {
                HomeScreen(state, true, { if (it is dev.avillagran.ot3android.ui.home.HomeIntent.OpenRow) opened = it.row.key }, appearance = AppearanceSettings())
            }
        }
        composeRule.onNodeWithTag("home_list").performScrollToNode(hasTestTag("home_row_${target.key}"))
        composeRule.onNodeWithTag("home_row_${target.key}").assertIsDisplayed()
        composeRule.runOnIdle { state = state.copy(items = state.items.toList(), stale = true) }
        composeRule.onNodeWithTag("home_row_${target.key}").assertIsDisplayed().performClick()
        org.junit.Assert.assertEquals(target.key, opened)
    }
    @Test fun largeRows() = render("classic", name = "large", large = true)
    @Test fun vivid() = render("classic", vivid = true)
    @Test @Config(qualifiers = "w320dp-h900dp-port-xhdpi") fun classic320Font160Sober() = render("classic", 1.6f, false, name = "classic-320")
    @Test @Config(qualifiers = "w320dp-h900dp-port-xhdpi") fun classic320Font160Vivid() = render("classic", 1.6f, true, name = "classic-320")
    @Test @Config(qualifiers = "w320dp-h900dp-port-xhdpi") fun focus320Font160Sober() = render("focus", 1.6f, false, name = "focus-320")
    @Test @Config(qualifiers = "w320dp-h900dp-port-xhdpi") fun focus320Font160Vivid() = render("focus", 1.6f, true, name = "focus-320")
    @Test @Config(qualifiers = "w320dp-h900dp-port-xhdpi") fun projects320Font160Sober() = render("projects", 1.6f, false, name = "projects-320")
    @Test @Config(qualifiers = "w320dp-h900dp-port-xhdpi") fun projects320Font160Vivid() = render("projects", 1.6f, true, name = "projects-320")
}
