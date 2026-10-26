package dev.avillagran.ot3android.notifications

import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.avillagran.ot3android.MainActivity
import dev.avillagran.ot3android.Ot3Application
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/** Explicit opt-in, read-only real server; private localhost harness supplies a short-lived scoped token. */
class FollowLiveDeviceTest {
    @Test fun followsRealShellAndReportsSystemPromotion() = runBlocking {
        val port = InstrumentationRegistry.getArguments().getString("followLabPort")
        assumeTrue("Opt-in real follow harness required", port != null)
        assumeTrue("Private emulator only; never run this against a phone", Build.HARDWARE in listOf("ranchu", "goldfish"))
        assumeTrue("Requires API36.1 emulator", Build.VERSION.SDK_INT >= 36 && Build.VERSION.SDK_INT_FULL >= LiveUpdates.SDK_INT_FULL_36_1)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val graph = (context.applicationContext as Ot3Application).graph
        assumeTrue("Use the real visible permission consent flow first", ThreadFollowService.allowed(context))
        suspend fun harness(path: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
            val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = if (path == "/continue") 240_000 else 10_000
            if (body != null) {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            try { JSONObject(connection.inputStream.bufferedReader().use { it.readText() }) }
            finally { connection.disconnect() }
        }
        val config = harness("/config")
        val previous = graph.followPreferences.enabled.first()
        var id: String? = null
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                val environment = graph.environmentRepository.save(null, "Follow QA · local NODE (read-only)",
                    config.getString("url"), null, config.getString("token"), matchServer = false)
                id = environment.id
                graph.followPreferences.setEnabled(true)
                withContext(Dispatchers.Main) { ThreadFollowService.start(context) }
                val manager = context.getSystemService(NotificationManager::class.java)
                val posted = withTimeout(45_000) {
                    while (true) {
                        val notification = manager.activeNotifications.firstOrNull { it.id == Ot3NotificationIds.ACTIVITY_ID && it.tag == null }
                        if (notification != null && notification.notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0) return@withTimeout notification.notification
                        delay(100)
                    }
                    @Suppress("UNREACHABLE_CODE") error("No active native card")
                }
                // Promotion is asynchronous; observe the system-returned card, never the request builder.
                var actual = posted
                repeat(50) {
                    if (!LiveUpdates.isPromoted(actual)) {
                        delay(100)
                        actual = manager.activeNotifications.firstOrNull { it.id == Ot3NotificationIds.ACTIVITY_ID && it.tag == null }?.notification ?: actual
                    }
                }
                harness("/ready", JSONObject().put("sdkInt", Build.VERSION.SDK_INT).put("sdkIntFull", Build.VERSION.SDK_INT_FULL)
                    .put("promotionAllowed", LiveUpdates.support(context).name).put("promoted", LiveUpdates.isPromoted(actual))
                    .put("flags", actual.flags).put("promotable", actual.hasPromotableCharacteristics())
                    .put("ongoing", actual.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
                    .put("serviceNotice", manager.activeNotifications.any { n -> n.id == ThreadFollowService.SERVICE_ID })
                    .put("foregroundServiceCard", actual.flags and android.app.Notification.FLAG_FOREGROUND_SERVICE != 0))
                assertTrue("Capture gate released explicitly", harness("/continue").getBoolean("released"))
                assertFalse("Acceptance captures must leave OT3 in background", scenario.state.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
                assertTrue("Follow lease keeps the real shell supervisor alive in background", graph.connectionManager.supervisor(environment.id)!!.isRunning)
                val backgroundCard = manager.activeNotifications.single { n -> n.id == Ot3NotificationIds.ACTIVITY_ID && n.tag == null }.notification
                assertTrue("Live card stays promoted outside OT3", LiveUpdates.isPromoted(backgroundCard))
                harness("/ready", JSONObject().put("stage", "background").put("backgroundShellRunning", true)
                    .put("backgroundPromoted", LiveUpdates.isPromoted(backgroundCard))
                    .put("backgroundFlags", backgroundCard.flags))
                assertFalse("No duplicate service notice while live", manager.activeNotifications.any { n -> n.id == ThreadFollowService.SERVICE_ID })
                assertTrue("Posted notification must be promoted on acceptance emulator", LiveUpdates.isPromoted(actual))
                if (config.optBoolean("revokeAfterCapture")) {
                    assertTrue("QA session revocation confirmed", harness("/revoke", JSONObject()).getBoolean("revoked"))
                    // Prove reauthentication with the revoked bearer is denied, not just an HTTP probe.
                    graph.connectionManager.forget(environment.id)
                    graph.connectionManager.startIfCurrent(graph.connectionManager.supervisor(environment.id))
                    withTimeout(20_000) {
                        while (manager.activeNotifications.any { n -> n.tag == null && n.id in listOf(ThreadFollowService.SERVICE_ID, Ot3NotificationIds.ACTIVITY_ID) }) delay(100)
                    }
                    harness("/ready", JSONObject().put("revokedReconnectStoppedFollow", true))
                }
            }
        } finally {
            graph.followPreferences.setEnabled(false)
            context.stopService(Intent(context, ThreadFollowService::class.java))
            id?.let { graph.connectionManager.forget(it); graph.environmentRepository.delete(it) }
            withTimeout(5_000) {
                val manager = context.getSystemService(NotificationManager::class.java)
                while (manager.activeNotifications.any { n -> n.tag == null && n.id in listOf(ThreadFollowService.SERVICE_ID, Ot3NotificationIds.ACTIVITY_ID) }) delay(50)
            }
            graph.followPreferences.setEnabled(previous)
        }
    }
}
