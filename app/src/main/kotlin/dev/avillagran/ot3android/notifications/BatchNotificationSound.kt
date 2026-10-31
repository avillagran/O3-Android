package dev.avillagran.ot3android.notifications

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import kotlinx.coroutines.*

/** Adjacent native batches share one channel sound. Banners and summaries are silent.
 * Android uses the user's channel sound for both input and completion transitions.
 */
internal class BatchNotificationSound(
    private val permitted: (Context) -> Boolean = Ot3NotificationChannels::alertsAllowed,
    private val startSound: (Context) -> (() -> Unit)? = ::startChannelSound,
) {
    private var job: Job? = null

    fun play(context: Context, scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.Main) {
            delay(200)
            if (!permitted(context)) return@launch
            var stopSound: (() -> Unit)? = null
            try {
                stopSound = startSound(context)
                delay(3_000)
            } catch (_: SecurityException) {
                // A revoked sound URI must not stop background following.
            } finally { stopSound?.invoke() }
        }
    }

    fun stop() { job?.cancel(); job = null }
}

private fun startChannelSound(context: Context): (() -> Unit)? {
    val manager = context.getSystemService(NotificationManager::class.java)
    val uri = if (Build.VERSION.SDK_INT >= 26) {
        val channel = manager.getNotificationChannel(Ot3NotificationChannels.ALERTS)
        if (channel == null || channel.importance < NotificationManager.IMPORTANCE_DEFAULT) return null
        channel.sound ?: return null
    } else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    val ringtone = RingtoneManager.getRingtone(context, uri) ?: return null
    ringtone.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    ringtone.play()
    return { ringtone.stop() }
}
