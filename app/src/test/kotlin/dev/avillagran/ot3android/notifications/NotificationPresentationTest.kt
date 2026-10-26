package dev.avillagran.ot3android.notifications

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.ot3android.R
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The presentation layer of UI-N on the four API levels of T3 Mobile's own matrix
 * (`t3:docs/operations/android-notifications.md:13`). Robolectric checks what the app asks
 * for; it cannot show whether a device promotes a card (its API 36 image predates the
 * shipped rules, same file :21), so promotion is asserted as a request, never as a result.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [24, 26, 33, 36], application = Application::class)
class NotificationPresentationTest {
    private lateinit var context: Context
    private val now = 1_800_000_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun card(phase: String, active: Boolean, chip: String? = "working", count: Int = 1, since: Long? = now - 65_000) =
        JSONObject()
            .put("phase", phase)
            .put("active", active)
            .put("title", "Fix the sidebar order")
            .put("subtitle", "ot3-android · node")
            .put("status", "Working")
            .put("expiresAt", now + 2 * 60 * 60 * 1000L)
            .put("route", JSONObject().put("environmentId", "env-1").put("threadId", "thread-9"))
            .apply { chip?.let { put("chip", JSONObject().put("kind", it).put("count", count)) } }
            .apply { since?.let { put("since", it) } }

    private fun alert(group: String = "attention", phase: String = "waiting_for_approval", expiresAt: Long? = null) =
        JSONObject()
            .put("id", "[\"env-1\",\"thread-9\",\"$phase\",\"2026-10-26T10:00:00Z\"]")
            .put("group", group)
            .put("phase", phase)
            .put("title", "Fix the sidebar order")
            .put("body", "Approval: ot3-android")
            .put("route", JSONObject().put("environmentId", "env-1").put("threadId", "thread-9"))
            .apply { expiresAt?.let { put("expiresAt", it) } }

    @Test
    fun native035CapturedObjectsBuildCardsAndAlertsWithoutInventingMissingFields() {
        val output = JSONObject(javaClass.getResource("/notifications/awareness-v0.3.5.json")!!.readText())
        val running = NotificationContract.card(output.getJSONObject("running").getJSONObject("card"))!!
        val approval = output.getJSONObject("approval")
        val card = NotificationContract.card(approval.getJSONObject("card"))!!
        val alert = NotificationContract.alert(approval.getJSONArray("alerts").getJSONObject(0))!!
        assertEquals("NODE", running.rows.single().host)
        assertEquals(ChipKind.Approve, card.chip!!.kind)
        assertTrue(alert.status.isNotBlank())
        assertNotNull(AwarenessCardBuilder.build(context, running, 1792036800000L))
        assertNotNull(AwarenessAlertBuilder.build(context, alert, 1792036800000L))
        val withoutDeadline = JSONObject(approval.getJSONObject("card").toString()).apply { remove("expiresAt") }
        assertNull(NotificationContract.card(withoutDeadline)!!.expiresAt)
    }

    @Test
    fun channelsAreLowActivityAndHighAlerts() {
        Ot3NotificationChannels.ensure(context)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(Ot3NotificationChannels.ACTIVITY).importance)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(Ot3NotificationChannels.ALERTS).importance)
        assertEquals(context.getString(R.string.mobile_notif_channel_activity), manager.getNotificationChannel(Ot3NotificationChannels.ACTIVITY).name)
    }

    @Test
    fun activeCardIsOngoingRequestsPromotionAndIsNotColorized() {
        val n = AwarenessCardBuilder.build(context, NotificationContract.card(card("running", true))!!, now)!!
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(NotificationCompat.isRequestPromotedOngoing(n))
        assertFalse(n.extras.getBoolean(NotificationCompat.EXTRA_COLORIZED))
        assertNull(n.group)
        assertTrue(n.flags and Notification.FLAG_GROUP_SUMMARY == 0)
        assertEquals("Fix the sidebar order", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Working", NotificationCompat.getShortCriticalText(n))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            assertEquals(R.drawable.ic_stat_ot3, n.smallIcon.resId)
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            assertEquals(R.drawable.ic_stat_ot3, n.icon)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) assertEquals(Ot3NotificationChannels.ACTIVITY, n.channelId)
    }

    @Test
    fun cardShowsElapsedTimeOnlyFromAStablePhaseStart() {
        val timed = AwarenessCardBuilder.build(context, NotificationContract.card(card("running", true))!!, now)!!
        assertEquals(now - 65_000, timed.`when`)
        assertTrue(timed.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        val untimed = AwarenessCardBuilder.build(context, NotificationContract.card(card("running", true, since = null))!!, now)!!
        assertFalse(untimed.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
    }

    @Test
    fun chipWordsTheLibsKindAndCapsTheCount() {
        val many = NotificationContract.card(card("running", true, chip = "live", count = 12))!!
        assertEquals("9+ live", AwarenessCardBuilder.chip(context, many.chip!!))
        val two = NotificationContract.card(card("running", true, chip = "live", count = 2))!!
        assertEquals("2 live", AwarenessCardBuilder.chip(context, two.chip!!))
        for (kind in ChipKind.entries) {
            val text = AwarenessCardBuilder.chip(context, Chip(kind, 12))
            assertTrue("$kind: '$text'", text.length <= 7)
        }
    }

    @Test
    fun approvalOffersApproveThatOnlyOpensTheThread() {
        val n = AwarenessCardBuilder.build(context, NotificationContract.card(card("waiting_for_approval", true, chip = "approve"))!!, now)!!
        assertEquals("Approve", NotificationCompat.getShortCriticalText(n))
        val action = n.actions.single()
        assertEquals(context.getString(R.string.ot3_notif_approve), action.title.toString())
        val intent = shadowOf(action.actionIntent).savedIntent
        assertTrue(shadowOf(action.actionIntent).isActivityIntent)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("ot3a://thread/env-1/thread-9", intent.dataString)
        assertEquals(intent.dataString, shadowOf(n.contentIntent).savedIntent.dataString)
    }

    @Test
    fun finishedCardDropsOngoingPromotionChipAndExpiresAtItsDeadline() {
        val json = card("completed", false, chip = "working").put("expiresAt", now + 15 * 60 * 1000L)
        val n = AwarenessCardBuilder.build(context, NotificationContract.card(json)!!, now)!!
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT == 0)
        assertFalse(NotificationCompat.isRequestPromotedOngoing(n))
        assertNull(NotificationCompat.getShortCriticalText(n))
        assertTrue(n.actions.isNullOrEmpty())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) assertEquals(15 * 60 * 1000L, n.timeoutAfter)
        assertNull(AwarenessCardBuilder.build(context, NotificationContract.card(json)!!, now + 15 * 60 * 1000L))
    }

    @Test
    fun lockscreenStateHidesTitlesAndFullShowsThem() {
        val parsed = NotificationContract.card(card("running", true))!!
        val state = AwarenessCardBuilder.build(context, parsed, now, LockscreenDetail.State)!!
        assertEquals(Notification.VISIBILITY_PRIVATE, state.visibility)
        val public = state.publicVersion
        assertNotNull(public)
        assertEquals("OT3", public.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Working", public.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertNull(AwarenessCardBuilder.build(context, parsed, now, LockscreenDetail.Hidden)!!.publicVersion)
        assertEquals(Notification.VISIBILITY_PUBLIC, AwarenessCardBuilder.build(context, parsed, now, LockscreenDetail.Full)!!.visibility)
        assertEquals(Notification.VISIBILITY_PUBLIC, AwarenessCardBuilder.build(context, parsed, now)!!.visibility)
        assertEquals(Notification.VISIBILITY_PUBLIC, AwarenessAlertBuilder.build(context, NotificationContract.alert(alert())!!, now)!!.visibility)
    }

    @Test
    fun alertsAreGroupedByTheLibsGroupAndKeepTheirTag() {
        val attention = NotificationContract.alert(alert())!!
        val n = AwarenessAlertBuilder.build(context, attention, now)!!
        assertEquals("ot3-attention", n.group)
        assertTrue(n.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertFalse(NotificationCompat.isRequestPromotedOngoing(n))
        assertEquals("ot3-alert:${attention.id}", Ot3NotificationIds.alertTag(attention))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            assertEquals(Ot3NotificationChannels.ALERTS, n.channelId)
        } else {
            assertEquals(Notification.PRIORITY_HIGH, n.priority)
        }
        val finished = AwarenessAlertBuilder.build(context, NotificationContract.alert(alert("finished", "completed"))!!, now)!!
        assertEquals("ot3-finished", finished.group)
        val summary = AwarenessAlertBuilder.summary(context, AlertGroup.Attention, "2 need you", listOf("A", "B"), attention.route)
        assertTrue(summary.flags and Notification.FLAG_GROUP_SUMMARY != 0)
        assertEquals("ot3-attention", summary.group)
    }

    @Test
    fun expiredDoneAlertIsNotBuilt() {
        val done = NotificationContract.alert(alert("finished", "completed", expiresAt = now - 1))!!
        assertNull(AwarenessAlertBuilder.build(context, done, now))
    }

    @Test
    fun malformedLibObjectsAreDroppedNotGuessed() {
        assertNull(NotificationContract.card(card("thinking", true)))
        assertNull(NotificationContract.card(card("running", true).put("route", JSONObject().put("environmentId", "env-1"))))
        assertNull(NotificationContract.card(card("running", true).put("expiresAt", 0)))
        assertNull(NotificationContract.alert(alert(group = "other")))
        assertNull(NotificationContract.alert(alert().put("id", " ")))
        assertNull(NotificationContract.card(card("completed", false))!!.chip)
    }

    @Test
    fun liveUpdateSupportAndSettingsFollowTheApiLevel() {
        val support = LiveUpdates.support(context)
        val intent = LiveUpdates.settingsIntent(context)
        when {
            Build.VERSION.SDK_INT >= 36 -> {
                assertEquals(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS, intent.action)
                assertEquals(context.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
                assertEquals(LiveUpdates.Support.Unsupported, support)
            }
            else -> {
                assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
                assertEquals(LiveUpdates.Support.Unsupported, support)
            }
        }
        val n = AwarenessCardBuilder.build(context, NotificationContract.card(card("running", true))!!, now)!!
        // A request is not a promotion: an unposted card is never reported as promoted.
        assertFalse(LiveUpdates.isPromoted(n))
    }
    @Test fun privacyChangeRedactsAlreadyPostedAlertsAndSummariesWithoutChangingTheirRoute() {
        if (Build.VERSION.SDK_INT >= 33) shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        Ot3NotificationChannels.ensure(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val original = AwarenessAlertBuilder.build(context, NotificationContract.alert(alert())!!, now)!!
        manager.notify("ot3-alert:test", 1, original)
        manager.notify("ot3-alert-summary:attention", 2, AwarenessAlertBuilder.summary(context,
            AlertGroup.Attention, "Sensitive title", listOf("Private project"), ThreadRef("env-1", "thread-9")))
        refreshNotificationPrivacy(context, LockscreenDetail.State)
        manager.activeNotifications.forEach {
            assertEquals(Notification.VISIBILITY_PRIVATE, it.notification.visibility)
            assertEquals("OT3", it.notification.publicVersion.extras.getString(Notification.EXTRA_TITLE))
            assertFalse(it.notification.publicVersion.extras.toString().contains("Private project"))
        }
        assertEquals(original.contentIntent, manager.activeNotifications.first { it.id == 1 }.notification.contentIntent)
        refreshNotificationPrivacy(context, LockscreenDetail.Hidden)
        manager.activeNotifications.forEach { assertNull(it.notification.publicVersion) }
        refreshNotificationPrivacy(context, LockscreenDetail.Full)
        manager.activeNotifications.forEach { assertEquals(Notification.VISIBILITY_PUBLIC, it.notification.visibility) }
    }
}
