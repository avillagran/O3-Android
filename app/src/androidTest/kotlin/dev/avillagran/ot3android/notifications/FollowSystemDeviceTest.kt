package dev.avillagran.ot3android.notifications

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.avillagran.ot3android.MainActivity
import dev.avillagran.ot3android.Ot3Application
import dev.avillagran.ot3android.core.data.prefs.FollowAlert
import dev.avillagran.ot3android.core.data.prefs.FollowLockscreen
import dev.avillagran.ot3android.ui.navigation.NavTags
import dev.avillagran.ot3android.ui.navigation.TopTab
import dev.avillagran.ot3android.ui.thread.ThreadScreenTags
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Opt-in isolated API36.1 lab: actual service/graph/leases/JNI, synthetic loopback servers. */
class FollowSystemDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val graph get() = (context.applicationContext as Ot3Application).graph
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val keyguard get() = context.getSystemService(KeyguardManager::class.java)
    private val records = JSONObject()
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText() }
    private suspend fun await(reason: String, timeoutMs: Long = 20_000, predicate: suspend () -> Boolean) {
        records.put("waitingFor", reason)
        try { withTimeout(timeoutMs) { while (!predicate()) delay(50) } }
        catch (error: TimeoutCancellationException) { throw AssertionError("Timed out: $reason", error) }
        records.put(reason, true)
    }
    private fun card() = manager.activeNotifications.firstOrNull { it.id == Ot3NotificationIds.ACTIVITY_ID && it.tag == null }
    private fun alerts() = manager.activeNotifications.filter { it.tag?.startsWith("ot3-alert:") == true }
    private fun summaries() = manager.activeNotifications.filter { it.tag?.startsWith("ot3-alert-summary:") == true }

    @Test fun lockscreenModesGroupedNativeTransitionsAndOpenUseRealService() = runBlocking<Unit> {
        assumeTrue("Explicit isolated emulator lab required", InstrumentationRegistry.getArguments().getString("followSystemLab") == "true")
        assertTrue(Build.HARDWARE in listOf("ranchu", "goldfish"))
        assertTrue(Build.VERSION.SDK_INT >= 36 && Build.VERSION.SDK_INT_FULL >= LiveUpdates.SDK_INT_FULL_36_1)
        assertFalse("Never replace an existing user's credential", keyguard.isDeviceSecure)
        records.put("notificationPermissionInitiallyGranted", ThreadFollowService.allowed(context))
        val savedAtStart = graph.environmentRepository.environments.first()
        if (savedAtStart.isNotEmpty() && InstrumentationRegistry.getArguments().getString("followCleanupOrphans") == "true") {
            // Explicit recovery only for this lab's known synthetic rows after a killed guest.
            assertTrue(savedAtStart.all { java.net.URI(it.baseUrl).host == "127.0.0.1" && it.environmentId in listOf("follow-lab-a", "follow-lab-b") && (it.label.startsWith("Lab A · synthetic") || it.label.startsWith("Lab B · synthetic")) })
            graph.followPreferences.setEnabled(false)
            context.stopService(Intent(context, ThreadFollowService::class.java))
            savedAtStart.forEach { graph.connectionManager.forget(it.id); graph.environmentRepository.delete(it.id) }
        }
        assertTrue("Use an empty isolated app DB; never contact saved servers", graph.environmentRepository.environments.first().isEmpty())
        val priorAccessibilityFlags = instrumentation.uiAutomation.serviceInfo.flags
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        val previous = graph.followPreferences.settings.first()
        val settingNames = listOf("lock_screen_show_notifications", "lock_screen_allow_private_notifications", "lock_screen_show_silent_notifications")
        val settings = settingNames.associateWith { shell("settings get secure $it").trim() }
        val previousDisabled = shell("locksettings get-disabled").trim()
        val ids = mutableListOf<String>()
        var pinInstalled = false
        FollowSystemFixtureServer("Lab A", "follow-lab-a").use { a -> FollowSystemFixtureServer("Lab B", "follow-lab-b").use { b ->
            try {
                graph.followPreferences.setEnabled(false)
                context.stopService(Intent(context, ThreadFollowService::class.java)); manager.cancelAll()
                graph.followPreferences.setLockscreen(FollowLockscreen.Full)
                FollowAlert.entries.forEach { graph.followPreferences.setAlert(it, true) }
                shell("settings put secure lock_screen_show_notifications 1")
                shell("settings put secure lock_screen_allow_private_notifications 0")
                shell("settings put secure lock_screen_show_silent_notifications 1")
                ActivityScenario.launch<MainActivity>(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClass(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use { scenario ->
                    scenario.onActivity { activity ->
                        keyguard.requestDismissKeyguard(activity, null)
                        activity.addOnNewIntentListener { intent ->
                            val route = dev.avillagran.ot3android.ui.navigation.ThreadDeepLink.parse(intent.data)
                            records.put("receivedIntentAction", intent.action).put("receivedIntentScheme", intent.data?.scheme)
                                .put("receivedIntentEnvironment", route?.environmentId).put("receivedIntentThread", route?.threadId)
                        }
                    }
                    await("initialNonsecureKeyguardDismissed") { !keyguard.isKeyguardLocked }
                    // Establish the real UI before adding asynchronous environments/theme sources.
                    notificationsSettings()
                    if (!ThreadFollowService.allowed(context)) {
                        notificationsSettings()
                        rule.onNodeWithTag("follow_enabled").performScrollTo().performClick()
                        touchSystemText("Allow")
                        await("visibleNotificationPermissionGranted") { ThreadFollowService.allowed(context) }
                        graph.followPreferences.setEnabled(false)
                        context.stopService(Intent(context, ThreadFollowService::class.java))
                    }
                    val envA = graph.environmentRepository.save(null, "Lab A · synthetic", a.url, null, "follow-lab-token-123", matchServer = false)
                    val envB = graph.environmentRepository.save(null, "Lab B · synthetic", b.url, null, "follow-lab-token-123", matchServer = false)
                    ids.add(envA.id); ids.add(envB.id)
                    graph.followPreferences.setEnabled(true)
                    withContext(Dispatchers.Main) { ThreadFollowService.start(context) }
                    await("twoNativeShellStreams") { a.activeShellSubscriptions == 1 && b.activeShellSubscriptions == 1 && card()?.notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() == "4 need you" }
                    // Both cold environments already need approval: native cold baseline emits no alerts.
                    assertEquals(0, alerts().size); assertEquals(0, summaries().size)
                    records.put("coldPendingNoAlert", true)
                    notificationsSettings()
                    for (detail in FollowLockscreen.entries) {
                        rule.onNodeWithTag("follow_lock_${detail.name}").performScrollTo().performClick()
                        await("preference${detail.name}") { graph.followPreferences.settings.first().lockscreen == detail && privacy(detail) }
                    }
                    capture("preferences")
                    pinInstalled = true // Cleanup is armed before the single lab credential is installed.
                    shell("locksettings set-pin 2468")
                    await("labCredentialInstalled") { keyguard.isDeviceSecure }
                    shell("locksettings set-disabled false")
                    key(KeyEvent.KEYCODE_HOME)
                    await("processBackgroundBeforeMetadata") { !androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }
                    for (detail in listOf(FollowLockscreen.Full, FollowLockscreen.State, FollowLockscreen.Hidden)) {
                        // Same real DataStore input as the radio buttons exercised above; no second UI/VM.
                        graph.followPreferences.setLockscreen(detail)
                        await("lockedPreference${detail.name}") { privacy(detail) }
                        repeat(2) { index ->
                            val before = card()!!.postTime
                            a.republish(if (index == 0) " · update" else "")
                            await("reposted${detail.name}$index") { (card()?.postTime ?: 0) > before }
                            assertTrue("Privacy survives every service repost: $detail", privacy(detail))
                        }
                        key(KeyEvent.KEYCODE_SLEEP); key(KeyEvent.KEYCODE_WAKEUP)
                        await("locked${detail.name}") { keyguard.isDeviceLocked && keyguard.isKeyguardLocked }
                        assertEquals(1, a.activeShellSubscriptions); assertEquals(1, b.activeShellSubscriptions)
                        capture("lock-${detail.name.lowercase()}")
                        clickSystemView("com.android.systemui:id/notificationShelf")
                        assertTrue(keyguard.isDeviceLocked)
                        capture("lock-${detail.name.lowercase()}-expanded")
                    }
                    graph.followPreferences.setLockscreen(FollowLockscreen.Full)
                    await("fullRestored") { privacy(FollowLockscreen.Full) }
                    shell("locksettings clear --old 2468")
                    await("labCredentialRemoved") { !keyguard.isDeviceSecure }
                    pinInstalled = false
                    shell("locksettings set-disabled $previousDisabled")
                    key(KeyEvent.KEYCODE_SLEEP); key(KeyEvent.KEYCODE_WAKEUP)
                    await("labNoCredentialRequired") { !keyguard.isDeviceLocked }
                    instrumentation.runOnMainSync { context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!) }
                    scenario.onActivity { keyguard.requestDismissKeyguard(it, null) }
                    await("nonsecureKeyguardDismissed") { !keyguard.isKeyguardLocked }
                    key(KeyEvent.KEYCODE_HOME)
                    await("backgroundAfterLockscreen") { !androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }
                    val streamCounts = listOf(a.shellSubscriptions.get(), b.shellSubscriptions.get())
                    repeat(3) { index ->
                        graph.environmentRepository.markConnected(envA.id, a.serverId)
                        graph.environmentRepository.markConnected(envB.id, b.serverId)
                        assertEquals(streamCounts, listOf(a.shellSubscriptions.get(), b.shellSubscriptions.get()))
                    }
                    val shownBody = card()!!.notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
                    val selectedHost = listOf(envA to a, envB to b).firstOrNull { (env, _) -> shownBody.contains(env.label) }
                        ?: error("Native card did not expose either fixture host: $shownBody")
                    val renamed = selectedHost.first.label + " · renamed"
                    records.put("renamedEnvironmentId", selectedHost.first.id).put("shownBodyBeforeRename", shownBody)
                    graph.environmentRepository.save(selectedHost.first.id, renamed, selectedHost.second.url, selectedHost.second.serverId, null, matchServer = false)
                    await("renamedWithoutRestart") { card()?.notification?.extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.contains(renamed) == true }
                    assertEquals(streamCounts, listOf(a.shellSubscriptions.get(), b.shellSubscriptions.get()))
                    val expectedStamp = requireNotNull(card()!!.notification.extras.getString(ThreadFollowService.PRESENTATION_STAMP))
                    val staleRecord = Notification.Builder.recoverBuilder(context, card()!!.notification).build().apply { extras.remove(ThreadFollowService.PRESENTATION_STAMP) }
                    manager.notify(Ot3NotificationIds.ACTIVITY_ID, staleRecord)
                    await("stalePlatformRecordInjected") { card()?.notification?.extras?.getString(ThreadFollowService.PRESENTATION_STAMP) == null }
                    a.republish() // Identical desired card: recovery depends on the real platform record, not a send cache.
                    val recoveryStarted = SystemClock.elapsedRealtime()
                    await("stalePlatformRecordRecovered", timeoutMs = 40_000) { card()?.notification?.extras?.getString(ThreadFollowService.PRESENTATION_STAMP) == expectedStamp }
                    records.put("recoveryOnExistingDeadlineElapsedMs", SystemClock.elapsedRealtime() - recoveryStarted)
                    await("backgroundNotOnScreen") { graph.followVisibility.onScreen.value == null }
                    a.publish("running"); b.publish("running")
                    await("runningSnapshot") { graph.connectionManager.supervisor(envA.id)?.shell?.value?.thread("review-one")?.pendingRuntimeRequest == null && graph.connectionManager.supervisor(envB.id)?.shell?.value?.thread("review-one")?.pendingRuntimeRequest == null }
                    await("runningBaselineProcessed") { card()?.notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() == "6 working" }
                    a.publish("approval")
                    await("twoAttentionAlerts") { alerts().count { it.notification.group == "ot3-attention" } == 2 }
                    b.publish("completed")
                    await("twoCompletionAlerts") { alerts().count { it.notification.group == "ot3-finished" } == 2 }
                    await("nativeGroupSummaries") { summaries().size == 2 }
                    assertEquals(4, alerts().map { it.tag }.toSet().size)
                    summaries().forEach { assertTrue(it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0); assertEquals(2, it.notification.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)!!.size) }
                    val posted = manager.activeNotifications
                    records.put("posted", JSONArray(posted.map { JSONObject().put("tag", it.tag).put("group", it.notification.group).put("title", it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()).put("flags", it.notification.flags).put("visibility", it.notification.visibility) }))
                    records.put("shellSubscriptions", JSONArray(listOf(a.shellSubscriptions.get(), b.shellSubscriptions.get())))
                    records.put("promoted", card()?.notification?.let(LiveUpdates::isPromoted) ?: false)
                    shade(); capture("grouped-shade")
                    // Expand native group headers, then physically tap the observed child title.
                    expandSystemGroups()
                    capture("grouped-shade-expanded")
                    openSystemNotification("Lab A · Review private workspace")
                    // SystemUI click is outside Compose's test input pipeline: pump its test
                    // clock while the delivered Intent drives navigation/recomposition.
                    rule.waitUntil(20_000) { graph.followVisibility.onScreen.value == ThreadRef(envA.id, "review-one") }
                    records.put("notificationOpenedCorrectThread", true)
                    rule.onNodeWithTag(ThreadScreenTags.ROOT).assertIsDisplayed()
                    capture("notification-open-thread")
                    val beforeSuppression = alerts().associate { it.tag to it.postTime }
                    var oldPost = card()!!.postTime
                    a.publishThread("review-one", "running"); await("onScreenRunningProcessed") { (card()?.postTime ?: 0) > oldPost }
                    oldPost = card()!!.postTime
                    a.publishThread("review-one", "approval"); await("onScreenApprovalProcessed") { (card()?.postTime ?: 0) > oldPost }
                    assertEquals("On-screen transition must not post an alert", beforeSuppression, alerts().associate { it.tag to it.postTime })
                    key(KeyEvent.KEYCODE_HOME)
                    await("noOnScreenClaimInBackground") { graph.followVisibility.onScreen.value == null }
                    oldPost = card()!!.postTime
                    a.republish(" · update"); await("suppressedRevisionConsumed") { (card()?.postTime ?: 0) > oldPost }
                    assertEquals(beforeSuppression, alerts().associate { it.tag to it.postTime })
                    assertEquals(1, a.activeShellSubscriptions); assertEquals(1, b.activeShellSubscriptions)
                    assertEquals(0, a.writes.get() + b.writes.get())
                    records.put("onScreenNoAlertAndNoReplay", true).put("dispatches", 0)
                    a.publish("completed", "completed"); b.publish("completed", "completed")
                    await("finishedCardDetachedFromFgs") { card()?.notification?.let { it.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) == 0 } == true && manager.activeNotifications.any { it.id == ThreadFollowService.SERVICE_ID && it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0 } }
                    records.put("finishedDeadline", card()!!.notification.extras.getLong("ot3.expiresAt"))
                    assertTrue(records.getLong("finishedDeadline") > System.currentTimeMillis())
                    capture("finished-card")
                    val aStreamsBeforeRemoval = a.shellSubscriptions.get()
                    graph.environmentRepository.delete(envB.id)
                    await("removeOnlyCorrespondingLease") { b.activeShellSubscriptions == 0 && a.activeShellSubscriptions == 1 }
                    assertEquals(aStreamsBeforeRemoval, a.shellSubscriptions.get())
                    graph.followPreferences.setEnabled(false)
                    context.stopService(Intent(context, ThreadFollowService::class.java))
                    await("followLeasesReleasedInBackground") { a.activeShellSubscriptions == 0 && b.activeShellSubscriptions == 0 }
                }
            } finally {
                records.put("requestsA", JSONArray(a.requests)).put("requestsB", JSONArray(b.requests))
                    .put("serverFailuresA", JSONArray(a.failures)).put("serverFailuresB", JSONArray(b.failures))
                    .put("nativeShellSubscriptionsA", a.shellSubscriptions.get()).put("nativeShellSubscriptionsB", b.shellSubscriptions.get())
                    .put("lastPostedBody", card()?.notification?.extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString())
                    .put("connectionStates", graph.connectionManager.states.first().mapValues { (_, state) -> state.toString() }.toString())
                if (pinInstalled) { shell("locksettings clear --old 2468"); await("labCredentialRemoved") { !keyguard.isDeviceSecure } }; shell("locksettings set-disabled $previousDisabled")
                settings.forEach { (name, value) -> shell(if (value == "null") "settings delete secure $name" else "settings put secure $name $value") }
                graph.followPreferences.setEnabled(false); context.stopService(Intent(context, ThreadFollowService::class.java))
                ids.forEach { graph.connectionManager.forget(it); graph.environmentRepository.delete(it) }
                await("ownedEnvironmentsRemoved") { graph.environmentRepository.environments.first().none { it.id in ids } }
                graph.followPreferences.setLockscreen(previous.lockscreen)
                previous.alerts.forEach { (alert, value) -> graph.followPreferences.setAlert(alert, value) }
                graph.followPreferences.setEnabled(previous.enabled)
                manager.cancelAll()
                instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply { flags = priorAccessibilityFlags }
                records.put("credentialRestored", !keyguard.isDeviceSecure)
                File(context.getExternalFilesDir(null), "follow-system/verification.json").apply { parentFile!!.mkdirs(); writeText(records.toString(2)) }
            }
        } }
    }
    private fun notificationsSettings() {
        // Returning from Home is asynchronous; do not query a stopped Compose root.
        try { rule.waitUntil(15_000) { rule.onAllNodes(isRoot()).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() } }
        catch (error: Throwable) {
            records.put("noComposeActiveWindow", instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
                .put("noComposeKeyguardLocked", keyguard.isKeyguardLocked).put("noComposeDeviceLocked", keyguard.isDeviceLocked)
                .put("noComposeTasks", context.getSystemService(android.app.ActivityManager::class.java).appTasks.map { it.taskInfo.topActivity?.flattenToShortString() }.toString())
            capture("no-compose-root"); throw error
        }
        if (rule.onAllNodesWithTag("follow_lock_Full").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()) return
        rule.waitUntil(15_000) { rule.onAllNodesWithTag(NavTags.tab(TopTab.Settings)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(NavTags.tab(TopTab.Settings)).performClick()
        rule.onNodeWithTag("settings_Notifications").performClick()
    }
    private fun privacy(detail: FollowLockscreen): Boolean {
        val n = card()?.notification ?: return false
        return when (detail) {
            FollowLockscreen.Full -> n.visibility == Notification.VISIBILITY_PUBLIC && n.publicVersion == null
            FollowLockscreen.Hidden -> n.visibility == Notification.VISIBILITY_PRIVATE && n.publicVersion == null
            FollowLockscreen.State -> n.visibility == Notification.VISIBILITY_PRIVATE && n.publicVersion?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() == "OT3"
        }
    }
    private suspend fun clickSystemView(id: String) {
        var node: AccessibilityNodeInfo? = null
        await("systemView:${id.substringAfterLast('/')}") { node = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId(id)?.firstOrNull { it.isVisibleToUser }; node != null }
        val rect = Rect(); node!!.getBoundsInScreen(rect)
        touch(rect)
    }
    private fun touch(rect: Rect) {
        val time = SystemClock.uptimeMillis()
        for ((action, stamp) in listOf(MotionEvent.ACTION_DOWN to time, MotionEvent.ACTION_UP to time + 80)) {
            val event = MotionEvent.obtain(time, stamp, action, rect.exactCenterX(), rect.exactCenterY(), 0).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)); event.recycle()
        }
    }
    private fun key(code: Int) {
        val down = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) assertTrue(instrumentation.uiAutomation.injectInputEvent(KeyEvent(down, SystemClock.uptimeMillis(), action, code, 0), true))
    }
    private fun shade() { val m = context.resources.displayMetrics; swipe(m.widthPixels * 0.2f, 1f, m.widthPixels * 0.2f, m.heightPixels * 0.75f) }
    private fun swipe(x0: Float, y0: Float, x1: Float, y1: Float) {
        val down = SystemClock.uptimeMillis()
        for (step in 0..16) {
            val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 16 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x0 + (x1 - x0) * step / 16, y0 + (y1 - y0) * step / 16, 0).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)); event.recycle()
            SystemClock.sleep(16) // Native gesture cadence, not an assertion/retry delay.
        }
    }
    private fun expandSystemGroups() {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: error("No SystemUI root")
        root.findAccessibilityNodeInfosByViewId("android:id/expand_button").forEach {
            if (it.contentDescription?.toString()?.contains("Expand", true) == true) it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }
    private fun findSystemText(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && node.text?.toString() == text) return node
        for (index in 0 until node.childCount) findSystemText(node.getChild(index), text)?.let { return it }
        return null
    }
    private suspend fun openSystemNotification(text: String) {
        var title: AccessibilityNodeInfo? = null
        await("notificationChildVisible") { title = findSystemText(instrumentation.uiAutomation.rootInActiveWindow, text); title != null }
        var row = title
        while (row != null && row.viewIdResourceName != "com.android.systemui:id/expandableNotificationRow") row = row.parent
        requireNotNull(row) { "Observed notification child has no SystemUI row" }
        assertTrue(row.isClickable)
        val rect = Rect(); row.getBoundsInScreen(rect)
        records.put("openedSystemRowBounds", rect.toShortString())
        // The title overlaps the expand header on API36.1. This is the real accessible
        // row Open action, invoking its system PendingIntent rather than synthesizing an Intent.
        assertTrue(row.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private suspend fun touchSystemText(text: String) {
        var target: AccessibilityNodeInfo? = null
        await("systemChildVisible") { target = findSystemText(instrumentation.uiAutomation.rootInActiveWindow, text); target != null }
        val rect = Rect(); target!!.getBoundsInScreen(rect)
        touch(rect)
    }
    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("followSystemEvidence") != "true") return
        // Evidence-only wait for SystemUI shade/keyguard window transitions, not an assertion retry.
        SystemClock.sleep(700)
        val file = File(context.getExternalFilesDir(null), "follow-system/$name.png").apply { parentFile!!.mkdirs() }
        records.put("$name-deviceLocked", keyguard.isDeviceLocked)
            .put("$name-flags", card()?.notification?.flags)
            .put("$name-visibility", card()?.notification?.visibility)
            .put("$name-publicTitle", card()?.notification?.publicVersion?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        val nodes = JSONArray()
        fun visit(node: AccessibilityNodeInfo?, depth: Int = 0) {
            if (node == null || depth > 24) return
            val rect = Rect(); node.getBoundsInScreen(rect)
            nodes.put(JSONObject().put("id", node.viewIdResourceName).put("text", node.text?.toString()).put("description", node.contentDescription?.toString()).put("clickable", node.isClickable).put("visible", node.isVisibleToUser).put("bounds", rect.toShortString()))
            for (index in 0 until node.childCount) visit(node.getChild(index), depth + 1)
        }
        visit(instrumentation.uiAutomation.rootInActiveWindow)
        File(file.parentFile, "$name-tree.json").writeText(nodes.toString(2))
        instrumentation.uiAutomation.takeScreenshot().let { bitmap -> file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
    }
}
