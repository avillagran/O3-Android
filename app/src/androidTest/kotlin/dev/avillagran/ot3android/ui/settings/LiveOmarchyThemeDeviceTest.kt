package dev.avillagran.ot3android.ui.settings

import android.content.Intent
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.avillagran.ot3android.MainActivity
import dev.avillagran.ot3android.Ot3Application
import dev.avillagran.ot3android.core.theme.ThemeSelection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/** Opt-in emulator-only read of a real desktop through the normal app graph and native transport. */
class LiveOmarchyThemeDeviceTest {
    @Test fun desktopThemeReachesTheRealApp(): Unit = runBlocking {
        val port = InstrumentationRegistry.getArguments().getString("themeLabPort")
        assumeTrue("Private read-only theme harness required", port != null)
        assumeTrue("Emulator only; never seed a phone", Build.HARDWARE in listOf("ranchu", "goldfish"))
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
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val graph = (context.applicationContext as Ot3Application).graph
        val previous = graph.themePreferences.settings.first()
        val following = graph.followPreferences.enabled.first()
        val config = harness("/config")
        var id: String? = null
        try {
            graph.followPreferences.setEnabled(false)
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
                val env = graph.environmentRepository.save(null, "Omarchy · theme QA", config.getString("url"), null,
                    config.getString("token"), matchServer = false)
                id = env.id
                graph.themePreferences.setMainEnvironmentId(env.id)
                graph.themePreferences.setFollowEnvironment(true)
                val live = withTimeout(45_000) {
                    graph.liveThemeSource.selection.first { it is ThemeSelection.Live && it.environmentId == env.id && !it.fromCache }
                } as ThemeSelection.Live
                assertEquals(config.getString("expectedTheme"), live.themeName)
                assertTrue(live.tokens.getJSONObject("roles").has("surface"))
                harness("/ready", JSONObject().put("theme", live.themeName).put("displayName", live.displayName)
                    .put("appearance", live.appearance).put("fromCache", live.fromCache)
                    .put("sdkInt", Build.VERSION.SDK_INT).put("source", "real desktop via AppGraph, native transport and theme.tokens"))
                assertTrue("Release the capture gate explicitly", harness("/continue").getBoolean("released"))
                graph.liveThemeSource.refresh()
                assertTrue(graph.liveThemeSource.selection.value is ThemeSelection.Live)
            }
        } finally {
            graph.themePreferences.setMainEnvironmentId(previous.mainEnvironmentId)
            graph.themePreferences.setFollowEnvironment(previous.followEnvironment)
            id?.let {
                graph.connectionManager.forget(it)
                graph.environmentRepository.delete(it)
                dev.avillagran.ot3android.core.theme.FileThemeCache(context.filesDir.resolve("live-theme")).remove(it)
            }
            graph.followPreferences.setEnabled(following)
        }
    }
}
