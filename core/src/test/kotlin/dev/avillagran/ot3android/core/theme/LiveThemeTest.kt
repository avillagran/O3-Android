package dev.avillagran.ot3android.core.theme

import dev.avillagran.ot3android.core.connection.Connection
import dev.avillagran.ot3android.core.domain.Environment
import dev.avillagran.ot3android.core.transport.ConnectRoute
import dev.avillagran.ot3android.core.transport.DomainEngine
import dev.avillagran.ot3android.core.transport.NativeT3Transport
import dev.avillagran.ot3android.core.transport.T3Transport
import dev.avillagran.ot3android.core.transport.assumeHostJni
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Reads the live Omarchy theme of a real T3 server, read-only. Skipped unless
 * `OT3A_LIVE_T3_URL` and `OT3A_LIVE_T3_TOKEN_FILE` (a file holding a bearer token with
 * `filesystem:read`) are set. With `OT3A_LIVE_THEME_SWITCH_TO=<theme>` it then follows the
 * environment with a [LiveThemeSource] polling every `OT3A_LIVE_THEME_POLL_MS` (default
 * 5000), writes `build/live-theme-ready` and waits up to 3 min for the host to switch to that
 * theme (someone runs `omarchy-theme-set`, or rewrites the files). Writes the theme names, the
 * appearance and three roles to `build/live-theme-report.txt`; never a token or a path.
 */
class LiveThemeTest {

    private val url: String? = System.getenv("OT3A_LIVE_T3_URL")
    private val tokenFile: String? = System.getenv("OT3A_LIVE_T3_TOKEN_FILE")
    private val switchTo: String? = System.getenv("OT3A_LIVE_THEME_SWITCH_TO")
    private val pollMs: Long = System.getenv("OT3A_LIVE_THEME_POLL_MS")?.toLongOrNull() ?: 5_000
    private val report = StringBuilder()

    @Test
    fun readsTheHostThemeThroughT3AndFollowsASwitch() = runBlocking {
        assumeTrue("OT3A_LIVE_T3_URL / OT3A_LIVE_T3_TOKEN_FILE not set", url != null && tokenFile != null)
        assumeHostJni()
        val transport = TimedTransport(NativeT3Transport())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ready = File("build/live-theme-ready").apply { delete() }
        try {
            transport.connect(ConnectRoute.Bearer(url!!, File(tokenFile!!).readText().trim()))
            val reader = EnvironmentThemeReader(DomainEngine.shared)

            val read = reader.read(transport)
            line("read: ${read::class.simpleName}")
            assertTrue("read $read", read is ThemeRead.Live)
            describe(read as ThemeRead.Live)
            line("location: cwd is HOME=${read.location == ThemeLocation.underHome(read.location.cwd)}")

            if (switchTo != null) {
                val source = LiveThemeSource(
                    scope = scope,
                    reader = reader,
                    cache = object : ThemeCache {
                        override suspend fun read(environmentId: String): CachedTheme? = null
                        override suspend fun write(environmentId: String, theme: CachedTheme) = Unit
                        override suspend fun remove(environmentId: String) = Unit
                    },
                    settings = flowOf(ThemeSettings()),
                    environments = flowOf(listOf(Environment(id = "live", label = "node", baseUrl = url, createdAt = 0))),
                    lastUsedEnvironmentId = flowOf(null),
                    connections = { flowOf(Connection(transport, epoch = 1)) },
                    pollIntervalMs = pollMs,
                )
                source.setVisible(true)
                source.start()
                val first = withTimeout(30_000) { source.selection.first { it is ThemeSelection.Live } } as ThemeSelection.Live
                line("source: ${first.displayName} · ${first.environmentLabel} (fromCache=${first.fromCache})")
                ready.writeText("ready")
                val started = System.currentTimeMillis()
                val switched = withTimeout(180_000) {
                    source.selection.first { it is ThemeSelection.Live && it.themeName == switchTo }
                } as ThemeSelection.Live
                line("calls after ready: ${transport.timings.filter { it.first + it.third >= started }.joinToString { "${it.second}@${(it.first - started) / 1000}s/${it.third}ms" }}")
                line("poll picked up the switch after ${(System.currentTimeMillis() - started) / 1000} s (poll ${pollMs / 1000} s)")
                line("switched: ${switched.displayName} · ${switched.environmentLabel}, appearance=${switched.appearance}")
                val roles = switched.tokens.getJSONObject("roles")
                line("switched roles: dim=${roles.getString("dim")} surface=${roles.getString("surface")} failed=${roles.getString("failed")}")
                assertEquals(switchTo, switched.themeName)
            }
        } finally {
            ready.delete()
            scope.cancel()
            transport.close()
            File("build/live-theme-report.txt").writeText(report.toString())
        }
    }

    /** Records when each typed call started and how long it took (function and file name only). */
    private class TimedTransport(private val inner: T3Transport) : T3Transport by inner {
        val timings = java.util.concurrent.CopyOnWriteArrayList<Triple<Long, String, Long>>()

        override suspend fun call(fn: String, args: JSONObject, timeoutMs: Int?): Any? {
            val start = System.currentTimeMillis()
            try {
                return inner.call(fn, args, timeoutMs)
            } finally {
                val file = args.optString("relativePath").substringAfterLast('/').ifEmpty { "-" }
                timings += Triple(start, "$fn($file)", System.currentTimeMillis() - start)
            }
        }
    }

    private fun describe(read: ThemeRead.Live) {
        val roles = read.tokens.getJSONObject("roles")
        line("theme: name=${read.themeName} display=${read.displayName} appearance=${read.tokens.getString("appearance")}")
        line("roles: dim=${roles.getString("dim")} surface=${roles.getString("surface")} failed=${roles.getString("failed")}")
        line("tokens keys: ${read.tokens.keys().asSequence().sorted().toList()}")
    }

    private fun line(text: String) {
        report.append(text).append('\n')
    }
}
