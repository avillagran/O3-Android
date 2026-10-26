package dev.avillagran.ot3android.ui.sky

import android.Manifest
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.avillagran.ot3android.core.data.prefs.AnimationPreference
import dev.avillagran.ot3android.ui.theme.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SystemReducedMotionTest {
    @get:Rule val rule = createComposeRule()
    @Test fun systemAnimatorScaleZeroStopsSurfacePolicyAndSky() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resolver = instrumentation.targetContext.contentResolver
        val previous = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS)
        try {
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
            rule.setContent {
                Ot3Theme(motion = rememberOt3Motion(AnimationPreference.FULL)) {
                    Column {
                        Text(Ot3Theme.motion.level.name, Modifier.testTag("motion_policy"))
                        // Request Full deliberately: the header independently enforces system limits.
                        SkyHeader(PreviewTokyoNight, SkyMotion.Full, 1f,
                            Modifier.size(240.dp, 100.dp).testTag("still_sky"))
                    }
                }
            }
            rule.onNodeWithTag("motion_policy").assertTextEquals("OFF")
            val before = rule.onNodeWithTag("still_sky").captureToImage().toPixelMap()
            rule.mainClock.advanceTimeBy(1000)
            val after = rule.onNodeWithTag("still_sky").captureToImage().toPixelMap()
            for (y in 0 until before.height step 4) for (x in 0 until before.width step 4) {
                assertEquals("System motion off must keep sky pixels still", before[x, y], after[x, y])
            }
        } finally {
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, previous)
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }
}
