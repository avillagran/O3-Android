package dev.avillagran.ot3android.core.theme

import dev.avillagran.ot3android.core.connection.Connection
import dev.avillagran.ot3android.core.data.prefs.AppearanceSettings
import dev.avillagran.ot3android.core.domain.Environment
import dev.avillagran.ot3android.core.transport.ConnectRoute
import dev.avillagran.ot3android.core.transport.DomainCalls
import dev.avillagran.ot3android.core.transport.FakeT3Transport
import dev.avillagran.t3.T3Exception
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveThemeSourceTest {

    /** One host: its Omarchy state, behind a fake transport. */
    private class Host(var themeName: String? = "tokyo-night", var omarchy: Boolean = true) {
        var failure: T3Exception? = null
        val transport = FakeT3Transport().apply {
            kotlinx.coroutines.runBlocking { connect(ConnectRoute.Bearer("http://t3.test", "token-12345678")) }
            onCall("filesystem.getMetadata") {
                failure?.let { throw it }
                JSONObject().put("entries", JSONArray().put(if (omarchy) JSONObject().put("kind", "file") else JSONObject.NULL))
            }
            onCall("filesystem.browse") { JSONObject().put("parentPath", "/home/u/.local/state/omarchy/current").put("entries", JSONArray()) }
            onCall("projects.readFile") { args ->
                failure?.let { throw it }
                val path = args.getString("relativePath")
                val contents = when {
                    !omarchy -> null
                    path.endsWith("colors.toml") -> "background = \"${background(themeName)}\""
                    else -> themeName
                }
                JSONObject().put("relativePath", path).put("contents", contents.orEmpty()).put("truncated", false)
                    .apply { if (contents == null) put("exists", false) }
            }
        }

        fun reads(file: String) = transport.calls.count { it.fn == "projects.readFile" && it.args.getString("relativePath").endsWith(file) }
    }

    private class MemoryCache : ThemeCache {
        val themes = mutableMapOf<String, CachedTheme>()
        override suspend fun read(environmentId: String) = themes[environmentId]
        override suspend fun write(environmentId: String, theme: CachedTheme) { themes[environmentId] = theme }
        override suspend fun remove(environmentId: String) { themes.remove(environmentId) }
    }

    private val domain = DomainCalls { _, args ->
        val id = args.optString("id", "custom")
        JSONObject().put("id", id).put("name", "Theme $id").put("appearance", if (id == "catppuccin-latte") "light" else "dark")
    }
    private val cache = MemoryCache()
    private val settings = MutableStateFlow(ThemeSettings())
    private val environments = MutableStateFlow(listOf(env("a", "node")))
    private val lastUsed = MutableStateFlow<String?>(null)
    private val connections = mutableMapOf<String, MutableStateFlow<Connection?>>()

    private fun connection(id: String) = connections.getOrPut(id) { MutableStateFlow(null) }

    private fun TestScope.source(): LiveThemeSource = LiveThemeSource(
        scope = backgroundScope,
        reader = EnvironmentThemeReader(domain),
        cache = cache,
        settings = settings,
        environments = environments,
        lastUsedEnvironmentId = lastUsed,
        connections = { id -> connection(id) },
    ).also { it.start() }

    @Test
    fun theCachedThemePaintsAtOnceBeforeAnyConnection() = runTest {
        cache.themes["a"] = CachedTheme("nord", "Nord", "background = \"#2e3440\"", JSONObject().put("appearance", "dark").toString())
        val source = source()
        assertEquals(ThemeSelection.Fallback(FallbackReason.Loading), source.selection.value)

        runCurrent()

        val live = source.selection.value as ThemeSelection.Live
        assertEquals("a", live.environmentId)
        assertEquals("node", live.environmentLabel)
        assertEquals("Nord", live.displayName)
        assertTrue(live.fromCache)
    }

    @Test
    fun theEnvironmentOnScreenWinsThenTheMainThenTheLastUsed() = runTest {
        environments.value = listOf(env("a", "node", connectedAt = 30), env("b", "work"), env("c", "laptop"))
        val source = source()
        runCurrent()
        assertEquals(waiting("a", "node"), source.selection.value) // last connected

        lastUsed.value = "b"
        runCurrent()
        assertEquals(waiting("b", "work"), source.selection.value)

        settings.value = ThemeSettings(mainEnvironmentId = "c")
        runCurrent()
        assertEquals(waiting("c", "laptop"), source.selection.value)

        val sidebar = source.showOnScreen("a")
        runCurrent()
        assertEquals(waiting("a", "node"), source.selection.value)

        // A transition: the next screen comes up before the previous one leaves.
        val thread = source.showOnScreen("b")
        sidebar.close()
        runCurrent()
        assertEquals(waiting("b", "work"), source.selection.value)

        thread.close()
        runCurrent()
        assertEquals(waiting("c", "laptop"), source.selection.value)

        settings.value = ThemeSettings(followEnvironment = false, mainEnvironmentId = "c")
        runCurrent()
        assertEquals(ThemeSelection.Fallback(FallbackReason.FollowingOff), source.selection.value)

        settings.value = ThemeSettings()
        environments.value = emptyList()
        runCurrent()
        assertEquals(ThemeSelection.Fallback(FallbackReason.NoEnvironment), source.selection.value)
    }

    @Test
    fun aConnectionReadsTheThemeAndCachesIt() = runTest {
        val host = Host()
        val source = source()
        source.setVisible(true)
        runCurrent()

        connection("a").value = Connection(host.transport, epoch = 1)
        runCurrent()

        val live = source.selection.value as ThemeSelection.Live
        assertEquals("tokyo-night", live.themeName)
        assertFalse(live.fromCache)
        assertEquals("tokyo-night", cache.themes.getValue("a").themeName)
        assertEquals(1, host.reads("colors.toml"))

        // A reconnect (new epoch) reads again.
        connection("a").value = Connection(host.transport, epoch = 2)
        runCurrent()
        assertEquals(2, host.reads("colors.toml"))
    }

    @Test
    fun theNameIsPolledOnlyWhileVisibleAndAChangeReadsTheTheme() = runTest {
        val host = Host()
        val source = source()
        connection("a").value = Connection(host.transport, epoch = 1)
        runCurrent()
        // Not visible yet: nothing read.
        assertEquals(0, host.transport.calls.size)

        source.setVisible(true)
        runCurrent()
        assertEquals(1, host.reads("colors.toml"))
        assertEquals(1, host.reads("theme.name"))

        advanceTimeBy(LiveThemeSource.POLL_INTERVAL_MS + 1)
        assertEquals(2, host.reads("theme.name"))
        assertEquals(1, host.reads("colors.toml")) // same name: no full read

        host.themeName = "catppuccin-latte"
        advanceTimeBy(LiveThemeSource.POLL_INTERVAL_MS)
        val live = source.selection.value as ThemeSelection.Live
        assertEquals("catppuccin-latte", live.themeName)
        assertTrue(live.isLight)
        assertEquals(2, host.reads("colors.toml"))

        source.setVisible(false)
        runCurrent()
        val before = host.transport.calls.size
        advanceTimeBy(10 * LiveThemeSource.POLL_INTERVAL_MS)
        assertEquals(before, host.transport.calls.size)

        // Back in the foreground: a full read at once.
        source.setVisible(true)
        runCurrent()
        assertEquals(3, host.reads("colors.toml"))
    }

    @Test
    fun switchingEnvironmentsFollowsTheNewOne() = runTest {
        environments.value = listOf(env("a", "node"), env("b", "omarchy"))
        val a = Host(themeName = "tokyo-night")
        val b = Host(themeName = "catppuccin-latte")
        connection("a").value = Connection(a.transport, 1)
        connection("b").value = Connection(b.transport, 1)
        val source = source()
        source.setVisible(true)
        val onA = source.showOnScreen("a")
        runCurrent()
        assertEquals("tokyo-night", (source.selection.value as ThemeSelection.Live).themeName)

        source.showOnScreen("b")
        onA.close()
        runCurrent()
        val live = source.selection.value as ThemeSelection.Live
        assertEquals("b", live.environmentId)
        assertEquals("catppuccin-latte", live.themeName)

        // Only the followed environment is polled.
        val aCalls = a.transport.calls.size
        advanceTimeBy(LiveThemeSource.POLL_INTERVAL_MS + 1)
        assertEquals(aCalls, a.transport.calls.size)
        assertEquals(2, b.reads("theme.name"))
    }

    @Test
    fun aHostWithoutOmarchyFallsBackAndDropsTheCache() = runTest {
        cache.themes["a"] = CachedTheme("nord", "Nord", "x", "{}")
        val host = Host(omarchy = false)
        val source = source()
        source.setVisible(true)
        connection("a").value = Connection(host.transport, 1)
        runCurrent()

        assertEquals(
            ThemeSelection.Fallback(FallbackReason.Unavailable("a", "node", ThemeUnavailable.NotOmarchy)),
            source.selection.value,
        )
        assertNull(cache.themes["a"])
        // A host without Omarchy is not probed by the poll.
        val calls = host.transport.calls.size
        advanceTimeBy(LiveThemeSource.POLL_INTERVAL_MS + 1)
        assertEquals(calls, host.transport.calls.size)
    }

    @Test
    fun anErrorKeepsTheThemeShownAndNoScopeFallsBack() = runTest {
        val host = Host()
        val source = source()
        source.setVisible(true)
        connection("a").value = Connection(host.transport, 1)
        runCurrent()
        val shown = source.selection.value as ThemeSelection.Live

        host.failure = T3Exception("disconnected")
        source.refresh()
        runCurrent()
        assertEquals(shown, source.selection.value)

        host.failure = T3Exception("write_not_authorized", null, "orchestration:read")
        source.refresh()
        runCurrent()
        assertEquals(
            ThemeSelection.Fallback(FallbackReason.Unavailable("a", "node", ThemeUnavailable.NoScope("filesystem:read"))),
            source.selection.value,
        )
    }

    @Test
    fun followingOffNeedsNoConnection() = runTest {
        settings.value = ThemeSettings(followEnvironment = false, manual = AppearanceSettings(darkThemeId = "nord"))
        val host = Host()
        connection("a").value = Connection(host.transport, 1)
        val source = source()
        source.setVisible(true)
        runCurrent()

        assertEquals(ThemeSelection.Fallback(FallbackReason.FollowingOff), source.selection.value)
        assertEquals(0, host.transport.calls.size)
    }

    private fun waiting(id: String, label: String) = ThemeSelection.Fallback(FallbackReason.Waiting(id, label))

    private companion object {
        fun env(id: String, label: String, connectedAt: Long? = null) =
            Environment(id = id, label = label, baseUrl = "http://$id.test", createdAt = 0, lastConnectedAt = connectedAt)

        fun background(name: String?) = if (name == "catppuccin-latte") "#eff1f5" else "#1a1b26"
    }
}
