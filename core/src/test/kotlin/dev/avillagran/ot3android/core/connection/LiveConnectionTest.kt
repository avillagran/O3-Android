package dev.avillagran.ot3android.core.connection

import dev.avillagran.ot3android.core.domain.Environment
import dev.avillagran.ot3android.core.transport.ConnectRoute
import dev.avillagran.ot3android.core.transport.NativeT3Transport
import dev.avillagran.ot3android.core.transport.NativeT3TransportFactory
import dev.avillagran.ot3android.core.transport.assumeHostJni
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Read-only check against a real T3 server, skipped unless `OT3A_LIVE_T3_URL` and
 * `OT3A_LIVE_T3_TOKEN_FILE` (a file holding the bearer token) are set. Writes only keys and
 * counts to `core/build/live-connection-report.txt`; the token is never printed.
 */
class LiveConnectionTest {

    private val url: String? = System.getenv("OT3A_LIVE_T3_URL")
    private val tokenFile: String? = System.getenv("OT3A_LIVE_T3_TOKEN_FILE")
    private val report = StringBuilder()

    @Test
    fun supervisorConnectsAndFollowsTheShell() = runBlocking {
        assumeTrue("OT3A_LIVE_T3_URL / OT3A_LIVE_T3_TOKEN_FILE not set", url != null && tokenFile != null)
        assumeHostJni()
        val baseUrl = normalizeOrigin(url!!)!!
        val token = File(tokenFile!!).readText().trim()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cursors = MemoryCursorStore()
        try {
            val supervisor = ConnectionSupervisor(
                environment = Environment(id = "live", label = "live", baseUrl = baseUrl, createdAt = 0),
                token = { token },
                cursorStore = cursors,
                transportFactory = NativeT3TransportFactory(),
                scope = scope,
            )
            supervisor.start()
            val state = withTimeout(30_000) {
                supervisor.state.first { it is ConnectionState.Connected || it is ConnectionState.Unauthorized || it is ConnectionState.Unreachable }
            }
            line("supervisor state: ${state::class.simpleName}")
            assertTrue("state $state", state is ConnectionState.Connected)
            val descriptor = (state as ConnectionState.Connected).descriptor
            line("descriptor: label=${descriptor.label != null} serverVersion=${descriptor.serverVersion} protocol=${descriptor.orchestrationProtocolVersion}")
            val shell = withTimeout(30_000) { supervisor.shell.first { it != null } }!!
            line("shell: sequence=${shell.sequence != null} projects=${shell.projects.size} threads=${shell.threads.size}")
            val sidebar = dev.avillagran.ot3android.core.shell.SidebarModel.build(shell)
            line("threads: archived=${shell.threads.count { it.archivedAt != null }} deleted=${shell.threads.count { it.deletedAt != null }} subagent=${shell.threads.count { it.relationshipToParent == "subagent" }} statuses=${shell.threads.groupingBy { it.status }.eachCount()}")
            line("sidebar: groups=${sidebar.active.size} activeRows=${sidebar.active.sumOf { it.rows.size }} settled=${sidebar.settled.size} statuses=${(sidebar.active.flatMap { it.rows } + sidebar.settled).groupingBy { it.status }.eachCount()}")
            // Let the stream run so its first updates are applied and the cursor persisted.
            withTimeoutOrNull(10_000) { cursors.cursors.first { it.isNotEmpty() } }
            line("cursor persisted: ${cursors.cursors.value.isNotEmpty()}")
            supervisor.stop()

            rawShellStream(baseUrl, token)
        } finally {
            scope.cancel()
            File("build/live-connection-report.txt").writeText(report.toString())
        }
    }

    /** The first shell stream updates, and the first few events (window `OT3A_LIVE_EVENT_WINDOW_S`, default 90 s), as shapes. */
    private suspend fun rawShellStream(baseUrl: String, token: String) {
        NativeT3Transport().use { transport ->
            transport.connect(ConnectRoute.Bearer(baseUrl, token))
            var index = 0
            var events = 0
            withTimeoutOrNull((System.getenv("OT3A_LIVE_EVENT_WINDOW_S")?.toLongOrNull() ?: 90L) * 1000) {
                transport.shellStream().takeWhile { index < 3 || events < 3 }.collect { update ->
                    if (index < 3) {
                        line("update $index: start=${update.start} idle=${update.idle} repaint=${update.repaint} closed=${update.closed} cursor=${update.cursor != null} boundaries=${update.batch.boundaries}")
                        for (i in 0 until update.batch.items.length()) line("  item: ${shape(update.batch.items.getJSONObject(i))}")
                    } else {
                        for (i in 0 until update.batch.items.length()) {
                            val item = update.batch.items.getJSONObject(i)
                            if (events < 3) line("event: ${shape(item)}")
                            events++
                        }
                    }
                    index++
                }
            }
            line("updates seen: $index, events after the first three updates: $events")
        }
    }

    private fun shape(json: JSONObject, depth: Int = 0): String = json.keys().asSequence().sorted().joinToString(", ", "{", "}") { key ->
        when (val value = json.opt(key)) {
            is JSONObject -> if (depth < 2) "$key${shape(value, depth + 1)}" else "$key{…}"
            is JSONArray -> "$key[${value.length()}]"
            JSONObject.NULL -> "$key=null"
            else -> if (key in SHOWN) "$key=$value" else key
        }
    }

    private companion object {
        /** Enumerations whose values carry no user data. */
        val SHOWN = setOf("kind", "location", "status", "relationshipToParent", "runtimeMode", "interactionMode")
    }

    private fun line(text: String) {
        report.appendLine(text)
    }
}
