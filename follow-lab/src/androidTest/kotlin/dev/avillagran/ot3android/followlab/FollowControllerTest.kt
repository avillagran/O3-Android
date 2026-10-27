package dev.avillagran.ot3android.followlab

import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

/** Isolated test APK UID: the real target is never instrumented during lifecycle acceptance. */
class FollowControllerTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val target = "dev.avillagran.ot3android"
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
    }
    private fun key(code: Int) {
        val now = SystemClock.uptimeMillis()
        assertTrue(instrumentation.uiAutomation.injectInputEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0), true))
        assertTrue(instrumentation.uiAutomation.injectInputEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0), true))
    }
    private fun notifications(): JSONArray = JSONArray().apply {
        shell("cmd notification list").lines().filter { it.contains("|$target|") }.forEach { key ->
            require(key.none { it.isWhitespace() })
            put(JSONObject().put("key", key.trim()).put("record", shell("cmd notification get ${key.trim()}")))
        }
    }
    @Test(timeout = 1_200_000) fun isolatedController() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("followControllerLab") == "true")
        assertTrue(Build.HARDWARE in listOf("ranchu", "goldfish"))
        assertTrue(Build.VERSION.SDK_INT >= 36)
        val context = instrumentation.targetContext
        assertNotEquals(target, context.packageName)
        val info = context.packageManager.getApplicationInfo(target, 0)
        assertNotEquals(info.uid, Process.myUid())
        assertTrue(info.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        val saved = JSONObject(shell("run-as $target cat files/follow-resilience-state.json"))
        assertEquals(2, saved.getJSONArray("ids").length())
        val nonce = args.getString("followControllerNonce")!!
        require(nonce.length >= 32)
        val port = args.getString("followControllerPort")!!.toInt().also { require(it in 1024..65535) }
        val originalLock = shell("locksettings get-disabled")
        val originalBattery = shell("dumpsys battery")
        assertFalse("Do not replace someone else's battery simulation", originalBattery.contains("UPDATES STOPPED"))
        val originalIdle = shell("dumpsys deviceidle")
        assertFalse("Do not replace another forced idle session", originalIdle.contains("mForceIdle=true"))
        val whitelist = shell("dumpsys deviceidle whitelist").lines().filter { it.contains(target) }
        val originalWifi = shell("cmd wifi status").contains("is enabled", true)
        var idleTouched = false
        var batteryTouched = false
        var wifiTouched = false
        var stop = false
        fun status() = JSONObject().put("targetPid", shell("pidof $target"))
            .put("activeWindowPackage", instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
            .put("targetUid", info.uid).put("helperUid", Process.myUid()).put("instrumentationTargetPackage", context.packageName)
            .put("deepIdle", shell("dumpsys deviceidle get deep")).put("deviceIdle", shell("dumpsys deviceidle"))
            .put("service", shell("dumpsys activity services $target")).put("processes", shell("dumpsys activity processes $target"))
            .put("packageState", shell("dumpsys package $target").lines().filter { it.contains("stopped=") }.joinToString("\n"))
            .put("notifications", notifications()).put("wifi", shell("cmd wifi status"))
        try {
            // No credential is installed/changed. Avoid a swipe keyguard hiding the real app after waking.
            shell("locksettings set-disabled true")
            ServerSocket(port, 4, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 1_000
                while (!stop && !Thread.currentThread().isInterrupted) {
                    val accepted = try { server.accept() } catch (_: java.net.SocketTimeoutException) { continue }
                    accepted.use { socket ->
                        socket.soTimeout = 5_000
                        val reader = socket.getInputStream().bufferedReader()
                        val first = reader.readLine().orEmpty()
                        var length = 0
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                        }
                        require(length in 0..4096)
                        val body = CharArray(length)
                        var read = 0
                        while (read < length) { val n = reader.read(body, read, length - read); require(n > 0); read += n }
                        val response = try {
                            require(first.startsWith("POST /$nonce "))
                            val input = JSONObject(String(body))
                            when (input.getString("action")) {
                                "status" -> status()
                                "home" -> { key(KeyEvent.KEYCODE_HOME); JSONObject() }
                                "sleep" -> { key(KeyEvent.KEYCODE_SLEEP); JSONObject() }
                                "wake" -> { key(KeyEvent.KEYCODE_WAKEUP); JSONObject() }
                                "idle" -> {
                                    batteryTouched = true; shell("dumpsys battery unplug")
                                    idleTouched = true
                                    JSONObject().put("result", shell("dumpsys deviceidle force-idle deep")).put("state", shell("dumpsys deviceidle get deep"))
                                }
                                "unidle" -> {
                                    shell("dumpsys deviceidle unforce"); shell("dumpsys battery reset")
                                    JSONObject().put("state", shell("dumpsys deviceidle get deep"))
                                }
                                "wifiOff" -> { wifiTouched = true; JSONObject().put("result", shell("svc wifi disable")) }
                                "wifiOn" -> { wifiTouched = true; JSONObject().put("result", shell("svc wifi enable")) }
                                "kill" -> {
                                    val pid = input.getInt("pid")
                                    require(shell("pidof $target") == pid.toString())
                                    require(shell("run-as $target cat /proc/$pid/cmdline").trim('\u0000') == target)
                                    JSONObject().put("pid", pid).put("signal", "SIGKILL").put("result", shell("run-as $target kill -9 $pid"))
                                }
                                "restorePermission" -> {
                                    if (!saved.getBoolean("permissionInitiallyGranted")) shell("pm revoke $target android.permission.POST_NOTIFICATIONS")
                                    JSONObject().put("restoredGranted", saved.getBoolean("permissionInitiallyGranted"))
                                }
                                "stop" -> { stop = true; JSONObject() }
                                else -> error("Unknown isolated lab action")
                            }.put("ok", true)
                        } catch (error: Exception) { JSONObject().put("ok", false).put("error", error.javaClass.simpleName) }
                        val bytes = response.toString().toByteArray()
                        socket.getOutputStream().apply { write("HTTP/1.1 200 Lab\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(bytes); flush() }
                    }
                }
            }
        } finally {
            if (idleTouched) shell("dumpsys deviceidle unforce")
            if (batteryTouched) shell("dumpsys battery reset")
            if (wifiTouched) shell("svc wifi ${if (originalWifi) "enable" else "disable"}")
            shell("locksettings set-disabled $originalLock")
            assertEquals(whitelist, shell("dumpsys deviceidle whitelist").lines().filter { it.contains(target) })
            assertFalse(shell("dumpsys battery").contains("UPDATES STOPPED"))
        }
    }
}
