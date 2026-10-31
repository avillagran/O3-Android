package dev.avillagran.ot3android.ui.sky

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.*
import dev.avillagran.ot3android.testing.ScreenshotTest
import dev.avillagran.ot3android.ui.theme.*
import org.junit.Test
import org.robolectric.annotation.Config

/** 320dp acceptance matrix, both Desktop themes, frozen production animation renderer. */
@Config(sdk=[36],qualifiers="w320dp-h640dp-port-xhdpi")
class O3BrandScreenshotTest : ScreenshotTest() {
    @Test fun scale1() = matrix(1f)
    @Test fun scale16() = matrix(1.6f)
    @Test fun scale2() = matrix(2f)
    @Test fun rtlScale1() = matrix(1f,true)
    @Test fun rtlScale16() = matrix(1.6f,true)
    @Test fun rtlScale2() = matrix(2f,true)
    private fun matrix(scale: Float,rtl: Boolean=false) {
        snapshot("header-$scale-${if(rtl)"rtl" else "ltr"}") {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,scale),LocalLayoutDirection provides if(rtl)LayoutDirection.Rtl else LayoutDirection.Ltr) {
                Column {
                    for(glyph in O3BrandGlyph.entries) for(sober in listOf(true,false)) {
                        SkyBrandRow(Ot3Theme.colors.foreground,Ot3Theme.colors.dim,
                            brandConfig=O3BrandConfig(glyph=glyph,sober=sober)) {
                            dev.avillagran.ot3android.ui.kit.CountPills(dev.avillagran.ot3android.ui.kit.CountPillsModel(3,1,2))
                            Text("NODE")
                        }
                    }
                }
            }
        }
        composeRule.onAllNodesWithContentDescription("O3 Code").assertCountEquals(4)
        composeRule.onAllNodesWithContentDescription("O3 Code").fetchSemanticsNodes().forEach {
            org.junit.Assert.assertTrue(it.boundsInRoot.left>=0)
            org.junit.Assert.assertTrue(it.boundsInRoot.right<=640)
        }
    }
    @Test fun desktopAnimationFixtures() {
        snapshot("animation-sequence") {
            CompositionLocalProvider(LocalOt3Motion provides Ot3MotionPolicy.Full) {
                Column {
                    for(glyph in O3BrandGlyph.entries) for(sober in listOf(true,false)) {
                        Row {
                            for(time in listOf(0L,2880L,4200L)) O3BrandMark(Ot3Theme.colors.foreground,Ot3Theme.colors.dim,Ot3Theme.colors.accent,
                                config=O3BrandConfig(glyph=glyph,sober=sober),elapsedMs=time)
                        }
                        Row {
                            for(time in listOf(5000L,9000L,10500L)) O3BrandMark(Ot3Theme.colors.foreground,Ot3Theme.colors.dim,Ot3Theme.colors.accent,
                                config=O3BrandConfig(glyph=glyph,sober=sober),elapsedMs=time)
                        }
                    }
                }
            }
        }
    }
    @Test fun tapIsAvailableAtRestAndMotionOffDisablesIt() {
        composeRule.mainClock.autoAdvance=false
        var policy by androidx.compose.runtime.mutableStateOf(Ot3MotionPolicy.Full)
        composeRule.setContent {
            CompositionLocalProvider(LocalOt3Motion provides policy) {
                O3BrandMark(androidx.compose.ui.graphics.Color.Black,androidx.compose.ui.graphics.Color.Gray,androidx.compose.ui.graphics.Color.Blue)
            }
        }
        composeRule.mainClock.advanceTimeBy(32)
        composeRule.onNodeWithContentDescription("O3 Code").performClick()
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.onNodeWithContentDescription("O3 Code").assertIsDisplayed()
        composeRule.runOnIdle { policy=Ot3MotionPolicy.Off }
        composeRule.mainClock.advanceTimeBy(32)
        composeRule.onNodeWithContentDescription("O3 Code").assertHasNoClickAction()
        composeRule.mainClock.advanceTimeBy(5000)
    }
    @Test fun androidRemoveAnimationsShowsFullStillBrand() {
        val context=androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val resolver=context.contentResolver
        val key=android.provider.Settings.Global.ANIMATOR_DURATION_SCALE
        val previous=android.provider.Settings.Global.getFloat(resolver,key,1f)
        try {
            android.provider.Settings.Global.putFloat(resolver,key,0f)
            snapshot("remove-animations") {
                val policy=rememberOt3Motion(dev.avillagran.ot3android.core.data.prefs.AnimationPreference.FULL)
                CompositionLocalProvider(LocalOt3Motion provides policy) {
                    Column {
                        for(glyph in O3BrandGlyph.entries) SkyBrandRow(Ot3Theme.colors.foreground,Ot3Theme.colors.dim,
                            brandConfig=O3BrandConfig(glyph=glyph,sober=false))
                    }
                }
            }
            composeRule.onAllNodesWithContentDescription("O3 Code").assertCountEquals(2)
            composeRule.onAllNodesWithContentDescription("O3 Code").fetchSemanticsNodes().forEach {
                org.junit.Assert.assertFalse(it.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick))
            }
        } finally { android.provider.Settings.Global.putFloat(resolver,key,previous) }
    }
}
