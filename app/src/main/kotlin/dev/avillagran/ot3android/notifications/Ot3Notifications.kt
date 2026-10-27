package dev.avillagran.ot3android.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.avillagran.ot3android.MainActivity
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.ui.navigation.Thread
import dev.avillagran.ot3android.ui.navigation.ThreadDeepLink

/*
 * Presentation only (UI_SPEC "Notifications and Live Updates"): channels, the ongoing card,
 * alerts and the Live Update settings link. Nothing here posts on its own, starts a service
 * or reads a stream; the follow service of the runtime tranche calls these builders with
 * the lib's [AwarenessCard] and [AwarenessAlert]. Adapted from T3 Mobile's
 * AgentNotifications.kt and AgentActivityPresentation.kt (MIT, T3 Tools Inc.;
 * docs/LICENSES.md, "T3 Mobile notification module").
 */

object Ot3NotificationChannels {
    /** The ongoing card: silent, low importance (never MIN, which cannot be promoted). */
    const val ACTIVITY = "ot3-activity"

    /** Approval, question, done and failed alerts: heads-up. */
    const val ALERTS = "ot3-alerts"

    /** Creates both channels (API 26+); idempotent, keeps the user's own changes. */
    fun ensure(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(
            listOf(
                NotificationChannel(
                    ACTIVITY,
                    context.getString(R.string.mobile_notif_channel_activity),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) },
                NotificationChannel(
                    ALERTS,
                    context.getString(R.string.mobile_notif_channel_alerts),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            ),
        )
    }
}

/** How much of a thread the lock screen shows (Settings → Notifications). */
typealias LockscreenDetail = dev.avillagran.ot3android.core.data.prefs.FollowLockscreen

/** Tags and ids of posted notifications. Alerts use their lib id as tag, so ids never collide. */
object Ot3NotificationIds {
    const val ACTIVITY_TAG = "ot3-activity"
    const val ACTIVITY_ID = 1
    const val ALERT_ID = 1
    const val SUMMARY_ID = 2

    fun alertTag(alert: AwarenessAlert) = "ot3-alert:${alert.id}"

    fun summaryTag(group: AlertGroup) = "ot3-alert-summary:${group.wire}"

    /** The notification group of an alert: one per T3 group, as the official app. */
    fun group(group: AlertGroup) = "ot3-${group.wire}"
}

/** Opening a thread from a notification: the thread deep link, never a hidden action. */
object NotificationRoutes {
    fun open(context: Context, thread: ThreadRef, requestCode: Int = 0): PendingIntent {
        val uri = ThreadDeepLink.uri(Thread(thread.environmentId, thread.threadId))
        val intent = Intent(Intent.ACTION_VIEW, uri, context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/** The ongoing card. Returns null when [AwarenessCard.expiresAt] has passed: cancel instead. */
object AwarenessCardBuilder {
    fun build(
        context: Context,
        card: AwarenessCard,
        now: Long,
        lockscreen: LockscreenDetail = LockscreenDetail.Full,
        onDismiss: PendingIntent? = null,
    ): Notification? {
        val remaining = card.expiresAt?.minus(now)
        if (remaining != null && remaining <= 0) return null
        val open = NotificationRoutes.open(context, card.route)
        val title = card.title.ifBlank { context.getString(R.string.app_name) }
        val body = body(card)
        val builder = base(context, Ot3NotificationChannels.ACTIVITY)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(card.active)
            // Promotion needs an ongoing, uncolourised, non-summary card with a title.
            .setRequestPromotedOngoing(card.active)
            .setColorized(false)
            .setShortCriticalText(card.chip?.let { chip(context, it) })
            // Quiet through the LOW channel and no defaults; NotificationCompat.setSilent would
            // put the card in a synthetic "silent" group on API 26+.
            .setOnlyAlertOnce(true)
            .setContentTitle(title)
            .setContentText(listOf(card.status, card.subtitle).filter { it.isNotBlank() }.joinToString(" · "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        if (remaining != null) builder.setTimeoutAfter(remaining).addExtras(android.os.Bundle().apply { putLong("ot3.expiresAt", now + remaining) })
        if (onDismiss != null) builder.setDeleteIntent(onDismiss)
        tint(context, card.phase)?.let(builder::setColor)
        val since = card.since
        if (card.active && since != null && since <= now) {
            builder.setWhen(since).setShowWhen(true).setUsesChronometer(true)
        }
        if (card.active) {
            // Approve and Answer open the thread, where the card asks for a visible confirmation.
            val action = when (card.phase) {
                AwarenessPhase.Approval -> context.getString(R.string.ot3_notif_approve)
                AwarenessPhase.Input -> context.getString(R.string.mobile_notif_answer)
                else -> context.getString(R.string.ot3_notif_open)
            }
            builder.addAction(0, action, open)
        }
        lockscreen(context, builder, lockscreen, card.status, Ot3NotificationChannels.ACTIVITY)
        return builder.build()
    }

    /** The chip text: the lib chose the kind and the count, Kotlin only words it. */
    fun chip(context: Context, chip: Chip): String = when (chip.kind) {
        ChipKind.Working -> context.getString(R.string.ot3_status_working)
        ChipKind.Live -> context.getString(R.string.mobile_notif_live, if (chip.count > 9) "9+" else chip.count.toString())
        ChipKind.Approve -> context.getString(R.string.ot3_notif_approve)
        ChipKind.Answer -> context.getString(R.string.mobile_notif_answer)
    }

    private fun body(card: AwarenessCard): CharSequence =
        if (card.rows.size <= 1) {
            listOf(card.status, card.subtitle).filter { it.isNotBlank() }.joinToString(" · ")
        } else {
            card.rows.take(5).joinToString("\n") { row ->
                listOf(row.title, listOf(row.project, row.host).filter { it.isNotBlank() }.joinToString(" · "))
                    .filter { it.isNotBlank() }.joinToString(" — ")
            }
        }
}

/** Alerts: one per lib decision, grouped as T3 groups them, with a silent summary. */
object AwarenessAlertBuilder {
    fun build(
        context: Context,
        alert: AwarenessAlert,
        now: Long,
        lockscreen: LockscreenDetail = LockscreenDetail.Full,
    ): Notification? {
        val timeout = alert.expiresAt?.let { it - now }
        if (timeout != null && timeout <= 0) return null
        val builder = base(context, Ot3NotificationChannels.ALERTS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentTitle(alert.title.ifBlank { context.getString(R.string.app_name) })
            .setContentText(alert.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.body))
            .setAutoCancel(true)
            .setGroup(Ot3NotificationIds.group(alert.group))
            .setCategory(if (alert.phase.needsUser) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(NotificationRoutes.open(context, alert.route))
        if (timeout != null) builder.setTimeoutAfter(timeout).addExtras(android.os.Bundle().apply { putLong("ot3.expiresAt", now + timeout) })
        tint(context, alert.phase)?.let(builder::setColor)
        lockscreen(context, builder, lockscreen, alert.status.ifBlank { statusLabel(context, alert.phase) }, Ot3NotificationChannels.ALERTS)
        return builder.build()
    }

    /** The group's silent summary; [lines] are the children's titles, newest first. */
    fun summary(context: Context, group: AlertGroup, title: String, lines: List<String>, route: ThreadRef, body: String = "", lockscreen: LockscreenDetail = LockscreenDetail.Full): Notification {
        val style = NotificationCompat.InboxStyle()
        lines.take(5).forEach { style.addLine(it) }
        val builder = base(context, Ot3NotificationChannels.ALERTS)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(style)
            .setGroup(Ot3NotificationIds.group(group))
            .setGroupSummary(true)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(NotificationRoutes.open(context, route, requestCode = 1))
        lockscreen(context, builder, lockscreen, context.getString(R.string.follow_alerts), Ot3NotificationChannels.ALERTS)
        return builder.build()
    }
}

private fun base(context: Context, channel: String): NotificationCompat.Builder =
    NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_stat_ot3)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setShowWhen(false)

private fun lockscreen(
    context: Context,
    builder: NotificationCompat.Builder,
    detail: LockscreenDetail,
    status: String,
    channel: String,
) {
    builder.addExtras(android.os.Bundle().apply { putString("ot3.publicStatus", status) })
    when (detail) {
        LockscreenDetail.Hidden -> builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        LockscreenDetail.Full -> builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        LockscreenDetail.State -> builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(
            NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_stat_ot3)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(status)
                .setShowWhen(false)
                .build(),
        )
    }
}

private fun statusLabel(context: Context, phase: AwarenessPhase): String = context.getString(
    when (phase) {
        AwarenessPhase.Approval -> R.string.ot3_notif_approval_required
        AwarenessPhase.Input -> R.string.ot3_notif_question
        AwarenessPhase.Completed -> R.string.ot3_status_done
        AwarenessPhase.Failed -> R.string.ot3_status_failed
        AwarenessPhase.Stale -> R.string.ot3_status_waiting
        AwarenessPhase.Starting, AwarenessPhase.Running -> R.string.ot3_status_working
    },
)

/** ot3's status roles, so the shade tints the icon as Home tints the row (not colorised). */
private fun tint(context: Context, phase: AwarenessPhase): Int? = when (phase) {
    AwarenessPhase.Starting, AwarenessPhase.Running -> R.color.ot3_notif_working
    AwarenessPhase.Approval, AwarenessPhase.Input -> R.color.ot3_notif_waiting
    AwarenessPhase.Completed -> R.color.ot3_notif_done
    AwarenessPhase.Failed -> R.color.ot3_notif_failed
    AwarenessPhase.Stale -> null
}?.let { ContextCompat.getColor(context, it) }

/** Reapply privacy to already posted cards without replaying alert sounds or changing routes/timeouts. */
fun refreshNotificationPrivacy(context: Context, detail: LockscreenDetail, skipActivity: Boolean = false) = synchronized(LegacyNotificationExpiry) {
    if (!ThreadFollowService.allowed(context)) return
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.activeNotifications.forEach { posted ->
        if (skipActivity && posted.tag == null && posted.id == Ot3NotificationIds.ACTIVITY_ID) return@forEach
        val notification = posted.notification
        if (!LegacyNotificationExpiry.isCurrent(context, posted.tag, posted.id, notification)) return@forEach
        val status = notification.extras.getString("ot3.publicStatus") ?: return@forEach
        val public = notification.publicVersion
        val alreadyCorrect = when (detail) {
            LockscreenDetail.Full -> notification.visibility == Notification.VISIBILITY_PUBLIC && public == null
            LockscreenDetail.Hidden -> notification.visibility == Notification.VISIBILITY_PRIVATE && public == null
            LockscreenDetail.State -> notification.visibility == Notification.VISIBILITY_PRIVATE &&
                public?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() == context.getString(R.string.app_name) &&
                public.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() == status
        }
        val expiry = notification.extras.getLong("ot3.expiresAt", 0)
        val remaining = expiry - System.currentTimeMillis()
        if (expiry > 0 && remaining <= 0) { LegacyNotificationExpiry.cancel(context, posted.tag, posted.id); return@forEach }
        if (alreadyCorrect) return@forEach
        val builder = Notification.Builder.recoverBuilder(context, notification).setOnlyAlertOnce(true)
        if (expiry > 0 && Build.VERSION.SDK_INT >= 26) builder.setTimeoutAfter(remaining)
        when (detail) {
            LockscreenDetail.Full -> builder.setVisibility(Notification.VISIBILITY_PUBLIC).setPublicVersion(null)
            LockscreenDetail.Hidden -> builder.setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(null)
            LockscreenDetail.State -> {
                val channel = if (Build.VERSION.SDK_INT >= 26) notification.channelId else Ot3NotificationChannels.ACTIVITY
                val public = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_stat_ot3)
                    .setContentTitle(context.getString(R.string.app_name)).setContentText(status).setShowWhen(false).build()
                builder.setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(public)
            }
        }
        LegacyNotificationExpiry.publish(context, posted.tag, posted.id, builder.build())
    }
}
