package dev.avillagran.ot3android.ui.thread

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class ComposerModelsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val selection = """{"instanceId":"personal","model":"same","options":[]}"""
    private val catalog = """{"favoritesReady":true,"favorites":[],"providers":[{"instanceId":"personal","name":"Personal","ready":true},{"instanceId":"work","name":"Work","ready":true}],"models":[{"instanceId":"personal","model":"same","displayName":"Personal model","providerName":"Personal","available":true,"descriptors":[{"id":"effort","label":"Reasoning effort","type":"select","currentValue":"low","options":[{"id":"low","label":"Low"},{"id":"high","label":"High"}]}]},{"instanceId":"work","model":"same","displayName":"Work model","providerName":"Work","available":true}]}"""

    @Test fun providerIdentitySurvivesDuplicateModelSlugsAndRefreshIsExplicit() {
        var chosen: JSONObject? = null
        var refreshes = 0
        rule.setContent { Ot3Theme { ComposerModels(catalog, selection, false, true,
            { refreshes++ }, { chosen = it }, { _, _ -> }, { _, _ -> }) } }
        rule.onNodeWithTag("composer_models").performClick()
        rule.onNodeWithText("Work").performClick()
        rule.onNodeWithText("Work model").performClick()
        rule.runOnIdle {
            assertEquals(1, refreshes)
            assertEquals("work", chosen!!.getString("instanceId"))
            assertEquals("same", chosen!!.getString("model"))
        }
    }

    @Test fun optionsUseTheAdvertisedDescriptorIdsAndValues() {
        val changes = mutableListOf<Pair<String, Any>>()
        rule.setContent { Ot3Theme { ComposerModels(catalog, selection, false, true,
            {}, {}, { id, value -> changes += id to value }, { _, _ -> }) } }
        rule.onNodeWithText("Model options").performClick()
        rule.onNodeWithText("High").performClick()
        rule.runOnIdle { assertEquals(listOf("effort" to "high"), changes) }
    }

    @Test fun favoritesAreServerValuesAndCannotBeChangedUntilPreferencesAreReady() {
        val unready = JSONObject(catalog).put("favoritesReady", false).toString()
        rule.setContent { Ot3Theme { ComposerModels(unready, selection, false, true,
            {}, {}, { _, _ -> }, { _, _ -> error("uninitialized favorite mutation") }) } }
        rule.onNodeWithTag("composer_models").performClick()
        rule.onNodeWithContentDescription("Add to favorites").assertIsNotEnabled()
    }
}
