package dev.avillagran.ot3android.ui.home

import android.Manifest
import android.animation.ValueAnimator
import android.provider.Settings
import android.os.SystemClock
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.test.platform.app.InstrumentationRegistry
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@androidx.test.filters.SdkSuppress(minSdkVersion = 26)
class HomeBrandIdleTest {
    @get:Rule val rule = createComposeRule()

    @Test fun homeWithDisabledAnimatorsBecomesIdle() = checkIdle(0f)
    @Test fun homeWithEnabledAnimatorsBecomesIdle() = checkIdle(1f)

    private fun checkIdle(scale: Float) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resolver = instrumentation.targetContext.contentResolver
        val previous = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS)
        val oldTimeout = androidx.test.espresso.IdlingPolicies.getMasterIdlingPolicy().idleTimeout
        val oldUnit = androidx.test.espresso.IdlingPolicies.getMasterIdlingPolicy().idleTimeoutUnit
        try {
            androidx.test.espresso.IdlingPolicies.setMasterPolicyTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
            val deadline = SystemClock.uptimeMillis() + 5000
            while (ValueAnimator.areAnimatorsEnabled() != (scale != 0f) && SystemClock.uptimeMillis() < deadline) {
                Thread.sleep(20)
            }
            assertEquals(scale != 0f, ValueAnimator.areAnimatorsEnabled())
            val start = SystemClock.uptimeMillis()
            rule.setContent { Ot3Theme { HomeScreen(null, isTop = true, onIntent = {}) } }
            // Cross the initial 2400 ms Desktop hold before checking synchronization.
            val before = if (scale == 0f) rule.onNodeWithContentDescription("O3 Code").captureToImage().toPixelMap() else null
            rule.mainClock.autoAdvance = false
            rule.mainClock.advanceTimeBy(2900)
            rule.mainClock.autoAdvance = true
            rule.waitForIdle()
            rule.onNodeWithContentDescription("O3 Code").assertIsDisplayed()
            if (before != null) {
                val after = rule.onNodeWithContentDescription("O3 Code").captureToImage().toPixelMap()
                for (y in 0 until before.height) for (x in 0 until before.width) {
                    assertEquals("Disabled animators must keep the brand still", before[x, y], after[x, y])
                }
            }
            assertTrue("Home must become idle within 10 seconds", SystemClock.uptimeMillis() - start < 10_000)
        } finally {
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, previous)
            androidx.test.espresso.IdlingPolicies.setMasterPolicyTimeout(oldTimeout, oldUnit)
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }
}
