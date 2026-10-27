package dev.avillagran.ot3android.notifications

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.avillagran.ot3android.MainActivity
import dev.avillagran.ot3android.Ot3Application
import dev.avillagran.ot3android.core.data.prefs.FollowAlert
import dev.avillagran.ot3android.core.data.prefs.FollowLockscreen
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File

/** Preparation/cleanup only. The accepted lifecycle run uses a separate helper UID. */
class FollowResilienceDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val graph get() = (context.applicationContext as Ot3Application).graph
    private val state get() = File(context.filesDir, "follow-resilience-state.json")
    private fun guard() {
        assumeTrue("Explicit isolated lab required", InstrumentationRegistry.getArguments().getString("followResilienceLab") == "true")
        assertTrue(Build.HARDWARE in listOf("ranchu", "goldfish"))
        assertTrue(Build.VERSION.SDK_INT_FULL >= LiveUpdates.SDK_INT_FULL_36_1)
        assertFalse(context.getSystemService(KeyguardManager::class.java).isDeviceSecure)
    }
    private suspend fun await(check: suspend () -> Boolean) { withTimeout(15_000) { while (!check()) delay(50) } }
    private fun settings() {
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("nav_tab_Settings").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("nav_tab_Settings").performClick()
        rule.onNodeWithTag("settings_Notifications").performClick()
        rule.onNodeWithTag("follow_lock_State").assertExists()
    }
    private fun find(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == text && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) find(node.getChild(i), text)?.let { return it }
        return null
    }
    private fun launch() = ActivityScenario.launch<MainActivity>(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        .setClass(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))

    @Test fun prepareState(): Unit = runBlocking {
        guard()
        assertFalse("Clean up the previous explicit lab before preparing", state.exists())
        assertTrue("Never contact saved real environments", graph.environmentRepository.environments.first().isEmpty())
        val previous = graph.followPreferences.settings.first()
        val record = JSONObject().put("permissionInitiallyGranted", ThreadFollowService.allowed(context))
            .put("previousEnabled", previous.enabled).put("previousLockscreen", previous.lockscreen.name)
            .put("previousAlerts", JSONObject(previous.alerts.mapKeys { it.key.name })).put("ids", JSONArray())
        state.writeText(record.toString()) // Arm cleanup before any mutation, no token is stored here.
        graph.followPreferences.setEnabled(false)
        context.stopService(Intent(context, ThreadFollowService::class.java))
        context.getSystemService(NotificationManager::class.java).cancelAll()
        launch().use { scenario ->
            scenario.onActivity { context.getSystemService(KeyguardManager::class.java).requestDismissKeyguard(it, null) }
            settings()
            if (!ThreadFollowService.allowed(context)) {
                rule.onNodeWithTag("follow_enabled").performScrollTo().performClick()
                var button: AccessibilityNodeInfo? = null
                await { button = find(instrumentation.uiAutomation.rootInActiveWindow, "Allow"); button != null }
                assertTrue(button!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                await { ThreadFollowService.allowed(context) }
                graph.followPreferences.setEnabled(false)
            }
            rule.onNodeWithTag("follow_lock_State").performScrollTo().performClick()
            await { graph.followPreferences.settings.first().lockscreen == FollowLockscreen.State }
            val ports = listOf("a" to "Resilience A", "b" to "Resilience B")
            ports.forEach { (key, label) ->
                val port = InstrumentationRegistry.getArguments().getString("port${key.uppercase()}")!!.toInt()
                require(port in 1024..65535)
                val env = graph.environmentRepository.save(null, "$label · synthetic", "http://127.0.0.1:$port", null,
                    "resilience-synthetic-" + "a".repeat(64), matchServer = false)
                record.getJSONArray("ids").put(env.id); state.writeText(record.toString())
            }
            FollowAlert.entries.forEach { graph.followPreferences.setAlert(it, true) }
            graph.followPreferences.setEnabled(true)
            withContext(Dispatchers.Main) { ThreadFollowService.start(context) }
            await { context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == Ot3NotificationIds.ACTIVITY_ID && it.notification.publicVersion != null } }
        }
    }

    @Test fun selectFull(): Unit = runBlocking {
        guard(); assertTrue(state.isFile)
        launch().use {
            settings()
            rule.onNodeWithTag("follow_lock_Full").performScrollTo().performClick()
            await { graph.followPreferences.settings.first().lockscreen == FollowLockscreen.Full }
        }
    }

    @Test fun cleanup(): Unit = runBlocking {
        guard()
        if (!state.isFile) return@runBlocking
        val record = JSONObject(state.readText())
        graph.followPreferences.setEnabled(false)
        context.stopService(Intent(context, ThreadFollowService::class.java))
        val ids = record.getJSONArray("ids").let { array -> (0 until array.length()).map { array.getString(it) } }
        ids.forEach { id ->
            val env = graph.environmentRepository.find(id) ?: return@forEach
            assertTrue(env.label.startsWith("Resilience ") && env.label.endsWith(" · synthetic") && java.net.URI(env.baseUrl).host == "127.0.0.1")
            graph.connectionManager.forget(id); graph.environmentRepository.delete(id)
        }
        assertTrue(graph.environmentRepository.environments.first().none { it.id in ids })
        graph.followPreferences.setLockscreen(FollowLockscreen.valueOf(record.getString("previousLockscreen")))
        FollowAlert.entries.forEach { graph.followPreferences.setAlert(it, record.getJSONObject("previousAlerts").getBoolean(it.name)) }
        graph.followPreferences.setEnabled(record.getBoolean("previousEnabled"))
        context.getSystemService(NotificationManager::class.java).cancelAll()
        val result = File(context.getExternalFilesDir(null), "follow-resilience/cleanup.json").apply { parentFile!!.mkdirs() }
        result.writeText(JSONObject().put("ownedEnvironmentsRemoved", true).put("preferencesRestored", graph.followPreferences.settings.first().enabled == record.getBoolean("previousEnabled"))
            .put("permissionInitiallyGranted", record.getBoolean("permissionInitiallyGranted")).toString(2))
        state.delete()
    }
}
