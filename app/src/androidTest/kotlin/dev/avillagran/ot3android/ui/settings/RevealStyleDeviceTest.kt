package dev.avillagran.ot3android.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dev.avillagran.ot3android.MainActivity
import dev.avillagran.ot3android.appGraph
import dev.avillagran.ot3android.core.data.prefs.RevealStyle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

class RevealStyleDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()
    @Test fun appearanceSelectsEveryStyleAndRetainsItAcrossRecreation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.appGraph.appearancePreferences
        val previous = prefs.settings.first()
        try {
            prefs.setRevealStyle(RevealStyle.NEBULA)
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                rule.waitUntil(15_000) { rule.onNodeWithTag("nav_tab_Settings").isDisplayed() }
                rule.onNodeWithTag("nav_tab_Settings").performClick()
                rule.onNodeWithTag("settings_Appearance").performClick()
                // P02 unifies reveal controls with the persisted local appearance options.
                rule.onNodeWithTag("preference_transitionStyle_nebula").performScrollTo().assertIsSelected()
                for (style in RevealStyle.entries) {
                    rule.onNodeWithTag("preference_transitionStyle_${style.name.lowercase()}").performScrollTo().performClick()
                    rule.waitUntil(15_000) { runBlocking { prefs.settings.first().revealStyle == style } }
                    scenario.recreate()
                    rule.onNodeWithTag("preference_transitionStyle_${style.name.lowercase()}").performScrollTo().assertIsSelected()
                    assertEquals(style, prefs.settings.first().revealStyle)
                }
            }
        } finally { prefs.setRevealStyle(previous.revealStyle) }
    }
}
