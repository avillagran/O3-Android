package dev.avillagran.ot3android.notifications

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import org.json.JSONObject
import java.util.UUID

/** API24/25 presentation cleanup. The absolute deadline belongs to Rust, not this scheduler.
 * RTC is inexact and non-wakeup: cleanup can wait for the device to leave idle.
 * Persist before enqueueing to NMS: an old alarm/delete callback must not retire a new post.
 * This lock covers service workers, privacy refresh and the main-thread receiver alike.
 */
internal object LegacyNotificationExpiry {
    private const val STAMP = "ot3.legacyExpiryGeneration"
    private const val STORE = "notification-expiry-v1"
    private const val EXPIRE = "dev.avillagran.ot3android.notification.EXPIRE"
    private const val DISMISS = "dev.avillagran.ot3android.notification.DISMISS"
    private fun prefs(context: Context) = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
    private fun key(tag: String?, id: Int) = org.json.JSONArray().put(tag ?: JSONObject.NULL).put(id).toString()
    private fun intent(context: Context, action: String, key: String, generation: String) =
        Intent(context, LegacyNotificationExpiryReceiver::class.java).setAction(action)
            .setData(Uri.Builder().scheme("ot3-notification").authority("expiry").appendPath(key).build())
            .putExtra("key", key).putExtra("generation", generation)
    private fun pending(context: Context, action: String, key: String, generation: String) =
        PendingIntent.getBroadcast(context, 0, intent(context, action, key, generation),
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun cancelAlarm(context: Context, key: String) {
        val existing = PendingIntent.getBroadcast(context, 0, intent(context, EXPIRE, key, ""),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) ?: return
        context.getSystemService(AlarmManager::class.java).cancel(existing)
        existing.cancel()
    }

    @Synchronized fun publish(context: Context, tag: String?, id: Int, notification: Notification,
                             post: (Notification) -> Unit = { context.getSystemService(NotificationManager::class.java).notify(tag, id, it) }) {
        if (Build.VERSION.SDK_INT >= 26) { post(notification); return }
        val key = key(tag, id)
        val expiry = notification.extras.getLong("ot3.expiresAt", 0)
        if (expiry <= 0) {
            notification.extras.remove(STAMP)
            check(prefs(context).edit().remove(key).commit())
            cancelAlarm(context, key)
            post(notification)
            return
        }
        val generation = UUID.randomUUID().toString()
        val record = JSONObject().put("generation", generation).put("id", id)
            .put("tag", tag ?: JSONObject.NULL).put("expiry", expiry)
        notification.extras.putString(STAMP, generation)
        // commit is intentional: an immediately killed service must not lose its alarm owner.
        check(prefs(context).edit().putString(key, record.toString()).commit())
        cancelAlarm(context, key)
        notification.deleteIntent = pending(context, DISMISS, key, generation)
        if (expiry > 0) context.getSystemService(AlarmManager::class.java).set(
            AlarmManager.RTC, expiry, pending(context, EXPIRE, key, generation))
        post(notification)
    }

    @Synchronized fun cancel(context: Context, tag: String?, id: Int) {
        if (Build.VERSION.SDK_INT < 26) {
            val key = key(tag, id)
            check(prefs(context).edit().remove(key).commit())
            cancelAlarm(context, key)
        }
        context.getSystemService(NotificationManager::class.java).cancel(tag, id)
    }

    @Synchronized fun isCurrent(context: Context, tag: String?, id: Int, notification: Notification): Boolean {
        if (Build.VERSION.SDK_INT >= 26) return true
        val generation = prefs(context).getString(key(tag, id), null)?.let(::JSONObject)?.getString("generation")
        return notification.extras.getString(STAMP) == generation
    }

    @Synchronized fun receive(context: Context, intent: Intent, now: Long) {
        if (Build.VERSION.SDK_INT >= 26 || intent.action !in listOf(EXPIRE, DISMISS)) return
        val key = intent.getStringExtra("key") ?: return
        val record = prefs(context).getString(key, null)?.let(::JSONObject) ?: return
        if (record.getString("generation") != intent.getStringExtra("generation")) return
        val expiry = record.getLong("expiry")
        if (intent.action == EXPIRE && expiry > now) {
            context.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC, expiry,
                pending(context, EXPIRE, key, record.getString("generation")))
            return
        }
        val tag = record.optString("tag").takeUnless { record.isNull("tag") }
        val id = record.getInt("id")
        cancel(context, tag, id)
    }
}

/** Explicit, non-exported delivery survives service/process teardown without opening streams. */
class LegacyNotificationExpiryReceiver internal constructor(private val clock: () -> Long) : BroadcastReceiver() {
    constructor() : this(System::currentTimeMillis)
    override fun onReceive(context: Context, intent: Intent) = LegacyNotificationExpiry.receive(context, intent, clock())
}
