package dev.avillagran.ot3android.notifications

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.avillagran.ot3android.Ot3Application
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.core.awareness.ThreadAwareness
import dev.avillagran.ot3android.core.connection.ConnectionState
import dev.avillagran.ot3android.core.shell.SidebarView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Local opt-in shell following, no relay/push and no separate thread subscriptions. */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadFollowService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error ->
        android.util.Log.e("ThreadFollow", "Stopped: ${error.javaClass.simpleName}")
        stopSelf()
    })
    private val graph get() = (application as Ot3Application).graph
    private val snapshots = MutableStateFlow<JSONArray?>(null)
    private val wakeups = Channel<Pair<JSONArray, ThreadRef?>>(Channel.CONFLATED)
    private var idleSince = SystemClock.elapsedRealtime()
    private val awareness = ThreadAwareness()
    @Volatile private var activeCard = false
    private val manager get() = getSystemService(NotificationManager::class.java)

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        Ot3NotificationChannels.ensure(this)
        // Immediate service notice, before network/Room/JNI work; never invent native counts.
        startForeground(SERVICE_ID, followingNotice())
        scope.launch {
            graph.followPreferences.enabled.collect { if (!it) stopSelf() }
        }
        scope.launch {
            graph.environmentRepository.environments.collectLatest { saved ->
                if (saved.isEmpty()) { stopSelf(); return@collectLatest }
                val leases = saved.mapNotNull { env -> graph.connectionManager.acquireFollowLease(env.id)?.let { env.id to it } }.toMap()
                val denied = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
                try {
                    graph.connectionManager.supervisors.collectLatest { supervisors ->
                        val flows = saved.map { env ->
                            val supervisor = supervisors[env.id]
                            if (supervisor == null) flowOf(JSONObject().put("id", env.id).put("name", env.label).put("state", "offline"))
                            else combine(supervisor.state, supervisor.shell) { state, shell ->
                                if (state == ConnectionState.Unauthorized ||
                                    state is ConnectionState.Unreachable && state.error == dev.avillagran.ot3android.core.connection.ConnectionSupervisor.MISSING_TOKEN) {
                                    denied.add(env.id)
                                    leases[env.id]?.close()
                                    if (denied.size == saved.size) stopSelf()
                                }
                                JSONObject().put("id", env.id).put("name", env.label)
                                    .put("state", if (state is ConnectionState.Connected) "ready" else "offline")
                                    .apply { if (env.id !in denied) shell?.let { put("shell", SidebarView.shellJson(it)) } }
                            }
                        }
                        combine(flows) { JSONArray(it.toList()) }.collect { snapshots.value = it }
                    }
                } finally { leases.values.forEach { it.close() } }
            }
        }
        scope.launch {
            combine(snapshots.filterNotNull(), graph.followVisibility.onScreen) { envs, screen -> envs to screen }
                .collect { wakeups.send(it) }
        }
        scope.launch {
            var deadlineJob: Job? = null
            // Input changes coalesce, but never cancel a native transition between baseline and posting.
            for ((envs, screen) in wakeups) {
                if (!allowed(this@ThreadFollowService)) { stopSelf(); break }
                val now = System.currentTimeMillis()
                val route = screen?.let { JSONObject().put("environmentId", it.environmentId).put("threadId", it.threadId) }
                val result = awareness.evaluate(envs, now, onScreen = route, locale = Locale.getDefault().toLanguageTag())
                currentCoroutineContext().ensureActive()
                val card = NotificationContract.card(result.optJSONObject("card"))
                activeCard = card?.active == true
                if (card?.active == true) idleSince = SystemClock.elapsedRealtime()
                val notification = card?.let { AwarenessCardBuilder.build(this@ThreadFollowService, it, now) }
                if (card?.active == true && notification != null) {
                    // The live card itself is the service notice: no duplicate quiet card.
                    startForeground(Ot3NotificationIds.ACTIVITY_ID, notification)
                    manager.cancel(SERVICE_ID)
                } else {
                    // Detach the live id before posting a normal, expiring Done/Failed card.
                    startForeground(SERVICE_ID, followingNotice())
                    if (notification != null) manager.notify(Ot3NotificationIds.ACTIVITY_ID, notification)
                    else manager.cancel(Ot3NotificationIds.ACTIVITY_ID)
                }
                val alerts = result.optJSONArray("alerts")
                for (i in 0 until (alerts?.length() ?: 0)) {
                    val alert = NotificationContract.alert(alerts!!.optJSONObject(i)) ?: continue
                    AwarenessAlertBuilder.build(this@ThreadFollowService, alert, now)?.let {
                        manager.notify(Ot3NotificationIds.alertTag(alert), Ot3NotificationIds.ALERT_ID, it)
                    }
                }
                val summaries = result.optJSONArray("summaries")
                for (i in 0 until (summaries?.length() ?: 0)) {
                    val summary = summaries!!.getJSONObject(i)
                    val group = AlertGroup.fromWire(summary.optString("group")) ?: continue
                    val r = summary.getJSONObject("route")
                    val lines = summary.optJSONArray("lines")
                    manager.notify(Ot3NotificationIds.summaryTag(group), Ot3NotificationIds.SUMMARY_ID,
                        AwarenessAlertBuilder.summary(this@ThreadFollowService, group, summary.optString("title"),
                            (0 until (lines?.length() ?: 0)).map { lines!!.getString(it) },
                            ThreadRef(r.getString("environmentId"), r.getString("threadId")), summary.optString("body")))
                }
                // Retire only Android group summaries whose posted children have all expired/dismissed.
                for (group in AlertGroup.entries) {
                    if (manager.activeNotifications.none { posted ->
                        posted.tag?.startsWith("ot3-alert:") == true && posted.notification.group == Ot3NotificationIds.group(group)
                    }) manager.cancel(Ot3NotificationIds.summaryTag(group), Ot3NotificationIds.SUMMARY_ID)
                }
                val idleRemaining = IDLE_MS - (SystemClock.elapsedRealtime() - idleSince)
                if (idleRemaining <= 0) { stopSelf(); break }
                val deadline = result.optLong("nextDeadline", now + 30_000)
                deadlineJob?.cancel()
                deadlineJob = launch {
                    delay(minOf(30_000L, idleRemaining, (deadline - now).coerceAtLeast(1_000L)))
                    snapshots.value?.let { wakeups.send(it to graph.followVisibility.onScreen.value) }
                }
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { scope.launch { graph.followPreferences.setEnabled(false); stopSelf() }; return START_NOT_STICKY }
        graph.connectionManager.wakeAll()
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        scope.cancel() // finally releases leases, leaving foreground screens alone.
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (activeCard) manager.cancel(Ot3NotificationIds.ACTIVITY_ID)
        super.onDestroy()
    }
    private fun followingNotice() = NotificationCompat.Builder(this, Ot3NotificationChannels.ACTIVITY)
        .setSmallIcon(R.drawable.ic_stat_ot3).setContentTitle("OT3")
        .setContentText(getString(R.string.follow_service_notice)).setOngoing(true).setOnlyAlertOnce(true)
        .addAction(0, getString(R.string.follow_stop), android.app.PendingIntent.getService(this, 1,
            Intent(this, ThreadFollowService::class.java).setAction(STOP),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT))
        .setContentIntent(android.app.PendingIntent.getActivity(this, 0, Intent(this, dev.avillagran.ot3android.MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT))
        .build()

    companion object {
        const val SERVICE_ID = 3
        const val STOP = "dev.avillagran.ot3android.STOP_FOLLOW"
        const val IDLE_MS = 15 * 60_000L
        fun allowed(context: android.content.Context) = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        /** Call only from a visible app after explicit opt-in and notification permission. */
        fun start(context: android.content.Context) {
            if (allowed(context)) ContextCompat.startForegroundService(context, Intent(context, ThreadFollowService::class.java))
        }
    }
}
