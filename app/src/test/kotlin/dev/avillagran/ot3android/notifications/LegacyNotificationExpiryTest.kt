package dev.avillagran.ot3android.notifications

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import android.content.IntentFilter
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(AndroidJUnit4::class)
@Config(sdk = [24, 25], application = Application::class)
class LegacyNotificationExpiryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private var receiverNow = System.currentTimeMillis()

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag") // API24/25 lack the flag overload.
    @Before fun registerReceiverForRobolectricDelivery() {
        // Plain Application does not instantiate manifest receivers. Register the production
        // instance with a controlled wall clock; no process restart is simulated here.
        val receiver = LegacyNotificationExpiryReceiver { receiverNow }
        val filter = IntentFilter().apply {
            addAction("dev.avillagran.ot3android.notification.EXPIRE")
            addAction("dev.avillagran.ot3android.notification.DISMISS")
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, filter)
    }

    @Test fun privacyRepostHasServiceIndependentExpiry() {
        val now = System.currentTimeMillis()
        val card = NotificationContract.card(JSONObject().put("phase", "completed").put("active", false)
            .put("title", "Finished").put("status", "Done").put("expiresAt", now + 60_000)
            .put("route", JSONObject().put("environmentId", "lab").put("threadId", "thread")))!!
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(1, AwarenessCardBuilder.build(context, card, now)!!)
        refreshNotificationPrivacy(context, LockscreenDetail.State)
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
        assertEquals("API24/25 need expiry delivery even after the follow service stops", 1, alarms.size)
    }

    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val alarmManager get() = context.getSystemService(AlarmManager::class.java)
    private val alarms get() = shadowOf(alarmManager).scheduledAlarms
    private fun notification(deadline: Long, group: String? = null, summary: Boolean = false): Notification =
        androidx.core.app.NotificationCompat.Builder(context, Ot3NotificationChannels.ACTIVITY)
            .setSmallIcon(dev.avillagran.ot3android.R.drawable.ic_stat_ot3).setContentTitle("Synthetic lab")
            .setGroup(group).setGroupSummary(summary).build().apply { extras.putLong("ot3.expiresAt", deadline) }
    private fun broadcast(intent: Intent) {
        context.sendBroadcast(intent)
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }
    private fun expireFirst() {
        val alarm = alarms.first()
        val remaining = (alarm.triggerAtTime - System.currentTimeMillis()).coerceAtLeast(0)
        receiverNow = alarm.triggerAtTime + 1
        ShadowSystemClock.advanceBy(Duration.ofMillis(remaining + 1))
        shadowOf(alarmManager).fireAlarm(alarm)
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test fun actualAlarmDeliveryAfterServiceTeardownUsesPersistentRegistration() {
        LegacyNotificationExpiry.publish(context, null, 1, notification(System.currentTimeMillis() + 60_000))
        // No onCreate/network setup: onDestroy must not discard a posted normal card's owner.
        Robolectric.buildService(ThreadFollowService::class.java).get().onDestroy()
        assertEquals(1, manager.activeNotifications.size)
        assertEquals(AlarmManager.RTC, alarms.single().type)
        assertFalse(alarms.single().allowWhileIdle)
        assertEquals(0, alarms.single().interval)
        // AlarmManager/PendingIntent delivers to the production receiver registered above.
        // Its injected wall clock is controlled independently of Robolectric uptime; this is not process restart.
        expireFirst()
        assertTrue(manager.activeNotifications.isEmpty())
        assertTrue(alarms.isEmpty())
        assertTrue(context.getSharedPreferences("notification-expiry-v1", Context.MODE_PRIVATE).all.isEmpty())
    }

    @Test fun alreadyDispatchedOldAlarmAndDeleteCannotRetireReplacementEvenWhileNmsEnqueueIsPending() {
        LegacyNotificationExpiry.publish(context, "child", 1, notification(System.currentTimeMillis() + 1_000))
        val oldAlarm = Intent(shadowOf(alarms.single().operation).savedIntent)
        val oldDelete = Intent(shadowOf(manager.activeNotifications.single().notification.deleteIntent).savedIntent)
        var pending: Notification? = null
        LegacyNotificationExpiry.publish(context, "child", 1, notification(System.currentTimeMillis() + 60_000)) { pending = it }
        receiverNow += 2_000
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
        broadcast(oldAlarm)
        broadcast(oldDelete)
        assertEquals(1, manager.activeNotifications.size)
        assertEquals(1, alarms.size)
        manager.notify("child", 1, pending!!)
        expireFirst()
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun privacyRefreshKeepsAbsoluteDeadlineAndReplacesGeneration() {
        val now = System.currentTimeMillis()
        val card = NotificationContract.card(JSONObject().put("phase", "completed").put("active", false)
            .put("title", "Finished").put("status", "Done").put("expiresAt", now + 60_000)
            .put("route", JSONObject().put("environmentId", "lab").put("threadId", "thread")))!!
        LegacyNotificationExpiry.publish(context, null, 1, AwarenessCardBuilder.build(context, card, now)!!)
        val old = Intent(shadowOf(alarms.single().operation).savedIntent)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(5))
        refreshNotificationPrivacy(context, LockscreenDetail.Hidden)
        assertEquals(now + 60_000, alarms.single().triggerAtTime)
        broadcast(old)
        assertEquals(Notification.VISIBILITY_PRIVATE, manager.activeNotifications.single().notification.visibility)
        expireFirst()
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun replacementWithoutDeadlineRetiresOldAlarmBeforeEnqueue() {
        LegacyNotificationExpiry.publish(context, null, 1, notification(System.currentTimeMillis() + 1_000))
        val old = Intent(shadowOf(alarms.single().operation).savedIntent)
        var pending: Notification? = null
        LegacyNotificationExpiry.publish(context, null, 1, notification(0)) { pending = it }
        receiverNow += 2_000
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
        broadcast(old)
        assertEquals(1, manager.activeNotifications.size)
        assertTrue(alarms.isEmpty())
        assertTrue(context.getSharedPreferences("notification-expiry-v1", Context.MODE_PRIVATE).all.isEmpty())
        manager.notify(1, pending!!)
        assertEquals(1, manager.activeNotifications.size)
    }

    @Test fun earlyDeliveryReschedulesSameAbsoluteDeadline() {
        val deadline = System.currentTimeMillis() + 60_000
        LegacyNotificationExpiry.publish(context, null, 1, notification(deadline))
        val operation = alarms.single().operation!!
        alarmManager.cancel(operation)
        operation.send()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(deadline, alarms.single().triggerAtTime)
        assertEquals(1, manager.activeNotifications.size)
        expireFirst()
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun deleteDeliveryRetiresRegistrationAndAlarm() {
        LegacyNotificationExpiry.publish(context, null, 1, notification(System.currentTimeMillis() + 60_000))
        val delete = manager.activeNotifications.single().notification.deleteIntent
        manager.cancel(1) // Framework removes a dismissed child before deleteIntent delivery.
        delete!!.send()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(alarms.isEmpty())
        assertTrue(context.getSharedPreferences("notification-expiry-v1", Context.MODE_PRIVATE).all.isEmpty())
    }

    @Test fun stalePlatformPrivacySnapshotCannotOverwriteQueuedReplacementOrCancelIt() {
        val now = System.currentTimeMillis()
        val card = NotificationContract.card(JSONObject().put("phase", "completed").put("active", false)
            .put("title", "Finished").put("status", "Done").put("expiresAt", now + 60_000)
            .put("route", JSONObject().put("environmentId", "lab").put("threadId", "thread")))!!
        LegacyNotificationExpiry.publish(context, null, 1, AwarenessCardBuilder.build(context, card, now)!!)
        val old = manager.activeNotifications.single().notification
        LegacyNotificationExpiry.publish(context, null, 1, notification(now + 120_000)) { }
        refreshNotificationPrivacy(context, LockscreenDetail.Hidden)
        assertSame(old, manager.activeNotifications.single().notification)
        assertEquals(now + 120_000, alarms.single().triggerAtTime)
        LegacyNotificationExpiry.publish(context, null, 1, notification(0)) { }
        refreshNotificationPrivacy(context, LockscreenDetail.State)
        assertSame(old, manager.activeNotifications.single().notification)
        assertTrue(alarms.isEmpty())
    }

    @Test @Config(sdk = [26, 36]) fun modernPlatformKeepsNativeTimeoutAndHasNoLegacyAlarm() {
        val now = System.currentTimeMillis()
        val card = NotificationContract.card(JSONObject().put("phase", "completed").put("active", false)
            .put("title", "Finished").put("status", "Done").put("expiresAt", now + 60_000)
            .put("route", JSONObject().put("environmentId", "lab").put("threadId", "thread")))!!
        LegacyNotificationExpiry.publish(context, null, 1, AwarenessCardBuilder.build(context, card, now)!!)
        assertEquals(60_000, manager.activeNotifications.single().notification.timeoutAfter)
        assertTrue(alarms.isEmpty())
    }
}
