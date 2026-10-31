package dev.avillagran.ot3android.notifications

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class BatchNotificationSoundTest {
    private val dispatcher = StandardTestDispatcher()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun adjacentBatchesPlayOnceAndStopOnServiceDestruction() = runTest(dispatcher) {
        var starts = 0
        var stops = 0
        val sound = BatchNotificationSound({ true }, { starts++; { stops++ } })
        repeat(20) { sound.play(context, backgroundScope) }
        runCurrent(); advanceTimeBy(200); runCurrent()
        assertEquals(1, starts)
        sound.play(context, backgroundScope); runCurrent()
        assertEquals(1, starts)
        sound.stop(); runCurrent(); assertEquals(1, stops)
        sound.play(context, backgroundScope); runCurrent(); advanceTimeBy(200); runCurrent()
        assertEquals(2, starts)
        sound.stop(); runCurrent(); assertEquals(2, stops)
    }

    @Test fun deniedAtDeliveryOrCancelledBeforeCoalescingNeverPlays() = runTest(dispatcher) {
        var allowed = true
        var starts = 0
        val sound = BatchNotificationSound({ allowed }, { starts++; {} })
        sound.play(context, backgroundScope); runCurrent()
        allowed = false
        advanceTimeBy(200); runCurrent(); assertEquals(0, starts)
        allowed = true
        sound.play(context, backgroundScope); runCurrent(); sound.stop()
        advanceTimeBy(200); runCurrent(); assertEquals(0, starts)
    }

    @Test fun revokedRuntimeNotificationPermissionPreventsBackgroundFollowing() {
        val application = context as Application
        org.robolectric.Shadows.shadowOf(application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(ThreadFollowService.allowed(context))
        org.robolectric.Shadows.shadowOf(application).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(ThreadFollowService.allowed(context))
    }

    @Test fun blockedAlertChannelDoesNotBlockActivityButBlockedActivityStopsFollowing() {
        Ot3NotificationChannels.ensure(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(Ot3NotificationChannels.ALERTS, "Alerts", NotificationManager.IMPORTANCE_NONE))
        assertFalse(Ot3NotificationChannels.alertsAllowed(context))
        manager.createNotificationChannel(NotificationChannel(Ot3NotificationChannels.ACTIVITY, "Activity", NotificationManager.IMPORTANCE_NONE))
        assertFalse(ThreadFollowService.allowed(context))
    }
}
