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
    private data class Input(val environments: JSONArray, val screen: ThreadRef?, val settings: dev.avillagran.ot3android.core.data.prefs.FollowSettings, val deadline: Boolean = false)
    private val wakeups = Channel<Input>(Channel.CONFLATED)
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
            // Ownership changes only for added/removed/re-authorized environments. Room metadata
            // (markConnected, label) must never close the last background follow lease.
            val leases = java.util.concurrent.ConcurrentHashMap<String, AutoCloseable>()
            val denied = java.util.concurrent.ConcurrentHashMap<String, dev.avillagran.ot3android.core.connection.ConnectionSupervisor>()
            try {
                combine(graph.environmentRepository.environments, graph.connectionManager.supervisors) { saved, supervisors -> saved to supervisors }
                    .onEach { (saved, supervisors) ->
                        val ids = saved.map { it.id }.toSet()
                        leases.keys.filter { it !in ids }.forEach { leases.remove(it)?.close() }
                        denied.keys.filter { it !in ids }.forEach { denied.remove(it) }
                        if (saved.isEmpty()) { stopSelf(); return@onEach }
                        saved.forEach { env ->
                            val rejected = denied[env.id]
                            if (rejected != null && rejected === supervisors[env.id]) return@forEach
                            if (rejected != null) denied.remove(env.id, rejected)
                            if (!leases.containsKey(env.id)) graph.connectionManager.acquireFollowLease(env.id)?.let { leases[env.id] = it }
                        }
                    }
                    .collectLatest { (saved, supervisors) ->
                        if (saved.isEmpty()) return@collectLatest
                        val flows = saved.map { env ->
                            val supervisor = supervisors[env.id]
                            if (supervisor == null) flowOf(JSONObject().put("id", env.id).put("name", env.label).put("state", "offline"))
                            else combine(supervisor.state, supervisor.shell) { state, shell ->
                                if (state == ConnectionState.Unauthorized ||
                                    state is ConnectionState.Unreachable && state.error == dev.avillagran.ot3android.core.connection.ConnectionSupervisor.MISSING_TOKEN) {
                                    if (graph.connectionManager.supervisors.value[env.id] === supervisor) {
                                        denied[env.id] = supervisor
                                        leases.remove(env.id)?.close()
                                        if (saved.all { denied[it.id] != null && denied[it.id] === supervisors[it.id] }) stopSelf()
                                    }
                                }
                                JSONObject().put("id", env.id).put("name", env.label)
                                    .put("state", if (state is ConnectionState.Connected) "ready" else "offline")
                                    .apply { if (denied[env.id] !== supervisor) shell?.let { put("shell", SidebarView.shellJson(it)) } }
                            }
                        }
                        combine(flows) { JSONArray(it.toList()) }.collect { snapshots.value = it }
                    }
            } finally { leases.values.forEach { it.close() } }
        }
        scope.launch {
            combine(snapshots.filterNotNull(), graph.followVisibility.onScreen, graph.followPreferences.settings) { envs, screen, settings -> Input(envs, screen, settings) }
                .collect { wakeups.send(it) }
        }
        scope.launch {
            var deadlineJob: Job? = null
            var wakeAtElapsed = Long.MAX_VALUE
            var lastPrivacy: LockscreenDetail? = null
            var lastCard: Pair<AwarenessCard?, LockscreenDetail>? = null
            var foregroundId = SERVICE_ID
            var presentationStamp = ""
            // Input changes coalesce, but never cancel a native transition between baseline and posting.
            for ((envs, screen, settings, deadlineTick) in wakeups) {
                if (!allowed(this@ThreadFollowService)) { stopSelf(); break }
                val now = System.currentTimeMillis()
                val route = screen?.let { JSONObject().put("environmentId", it.environmentId).put("threadId", it.threadId) }
                val result = awareness.evaluate(envs, now, onScreen = route, preferences = settings.awarenessPreferences(), locale = Locale.getDefault().toLanguageTag())
                currentCoroutineContext().ensureActive()
                // Privacy may have changed while Rust processed a queued shell transition.
                val privacy = graph.followPreferences.settings.first().lockscreen
                val card = NotificationContract.card(result.optJSONObject("card"))
                activeCard = card?.active == true
                if (card?.active == true) idleSince = SystemClock.elapsedRealtime()
                val notification = card?.let { AwarenessCardBuilder.build(this@ThreadFollowService, it, now, privacy) }
                val presentation = (card.takeIf { notification != null }) to privacy
                // Exact presentation equality only: Rust still consumes every input/deadline and
                // emits every alert. Avoid NMS shedding real changes after identical shell ticks.
                val changed = presentation != lastCard
                if (changed) presentationStamp = java.util.UUID.randomUUID().toString()
                val platformCard = manager.activeNotifications.firstOrNull { it.tag == null && it.id == Ot3NotificationIds.ACTIVITY_ID }
                val missingOrStale = if (notification == null) platformCard != null else platformCard?.notification?.extras?.getString(PRESENTATION_STAMP) != presentationStamp
                if (changed || deadlineTick && missingOrStale) {
                    notification?.extras?.putString(PRESENTATION_STAMP, presentationStamp)
                    if (card?.active == true && notification != null) {
                        LegacyNotificationExpiry.publish(this@ThreadFollowService, null, Ot3NotificationIds.ACTIVITY_ID, notification) { startForeground(Ot3NotificationIds.ACTIVITY_ID, it) }
                        foregroundId = Ot3NotificationIds.ACTIVITY_ID
                        if (manager.activeNotifications.any { it.tag == null && it.id == SERVICE_ID }) manager.cancel(SERVICE_ID)
                    } else {
                        if (foregroundId != SERVICE_ID) {
                            startForeground(SERVICE_ID, followingNotice())
                            foregroundId = SERVICE_ID
                        }
                        if (notification != null) LegacyNotificationExpiry.publish(this@ThreadFollowService, null, Ot3NotificationIds.ACTIVITY_ID, notification)
                        else if (manager.activeNotifications.any { it.tag == null && it.id == Ot3NotificationIds.ACTIVITY_ID }) LegacyNotificationExpiry.cancel(this@ThreadFollowService, null, Ot3NotificationIds.ACTIVITY_ID)
                    }
                    lastCard = presentation // Desired data only; a missing/stale platform record is retried on the existing deadline wake.
                }
                val alerts = result.optJSONArray("alerts")
                for (i in 0 until (alerts?.length() ?: 0)) {
                    val alert = NotificationContract.alert(alerts!!.optJSONObject(i)) ?: continue
                    AwarenessAlertBuilder.build(this@ThreadFollowService, alert, now, privacy)?.let {
                        LegacyNotificationExpiry.publish(this@ThreadFollowService, Ot3NotificationIds.alertTag(alert), Ot3NotificationIds.ALERT_ID, it)
                    }
                }
                val summaries = result.optJSONArray("summaries")
                val postedGroups = mutableSetOf<AlertGroup>()
                for (i in 0 until (summaries?.length() ?: 0)) {
                    val summary = summaries!!.getJSONObject(i)
                    val group = AlertGroup.fromWire(summary.optString("group")) ?: continue
                    postedGroups.add(group)
                    val r = summary.getJSONObject("route")
                    val lines = summary.optJSONArray("lines")
                    LegacyNotificationExpiry.publish(this@ThreadFollowService, Ot3NotificationIds.summaryTag(group), Ot3NotificationIds.SUMMARY_ID,
                        AwarenessAlertBuilder.summary(this@ThreadFollowService, group, summary.optString("title"),
                            (0 until (lines?.length() ?: 0)).map { lines!!.getString(it) },
                            ThreadRef(r.getString("environmentId"), r.getString("threadId")), summary.optString("body"), privacy))
                }
                // Query existing platform records, never send repeated cancels for absent IDs.
                // A just-posted native summary must not be retired before NMS enqueues its children.
                val active = manager.activeNotifications
                for (group in AlertGroup.entries) {
                    if (group !in postedGroups && active.any { it.tag == Ot3NotificationIds.summaryTag(group) } &&
                        active.none { it.tag?.startsWith("ot3-alert:") == true && it.notification.group == Ot3NotificationIds.group(group) }) {
                        LegacyNotificationExpiry.cancel(this@ThreadFollowService, Ot3NotificationIds.summaryTag(group), Ot3NotificationIds.SUMMARY_ID)
                    }
                }
                if (privacy != lastPrivacy) {
                    refreshNotificationPrivacy(this@ThreadFollowService, privacy, skipActivity = true)
                    lastPrivacy = privacy
                }
                val idleRemaining = IDLE_MS - (SystemClock.elapsedRealtime() - idleSince)
                if (idleRemaining <= 0) { stopSelf(); break }
                val deadline = result.optLong("nextDeadline", now + 30_000)
                val delayMs = minOf(30_000L, idleRemaining, (deadline - now).coerceAtLeast(1_000L))
                val proposedWake = SystemClock.elapsedRealtime() + delayMs
                // Metadata ticks must not continually postpone the existing periodic/deadline wake.
                if (deadlineJob?.isActive != true || proposedWake < wakeAtElapsed) {
                    deadlineJob?.cancel()
                    wakeAtElapsed = proposedWake
                    deadlineJob = launch {
                        delay(delayMs)
                        snapshots.value?.let { wakeups.send(Input(it, graph.followVisibility.onScreen.value, graph.followPreferences.settings.first(), deadline = true)) }
                    }
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
        if (activeCard) LegacyNotificationExpiry.cancel(this@ThreadFollowService, null, Ot3NotificationIds.ACTIVITY_ID)
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
        const val PRESENTATION_STAMP = "ot3.presentationStamp"
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
