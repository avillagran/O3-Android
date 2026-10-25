package dev.avillagran.ot3android.core.thread

import dev.avillagran.ot3android.core.connection.SHELL_PATH
import dev.avillagran.ot3android.core.connection.normalizeOrigin
import dev.avillagran.ot3android.core.shell.ShellReducer
import dev.avillagran.ot3android.core.transport.ConnectRoute
import dev.avillagran.ot3android.core.transport.NativeT3Transport
import dev.avillagran.ot3android.core.transport.assumeHostJni
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Read-only check of the thread path against a real T3 server, skipped unless
 * `OT3A_LIVE_T3_URL` and `OT3A_LIVE_T3_TOKEN_FILE` are set. Opens the most recently updated
 * non-subagent thread with `boundedThread` and `threadStream` for `OT3A_LIVE_THREAD_WINDOW_S`
 * (default 10 s), then the same through [ThreadSession]. Sends nothing. Writes only keys,
 * counts and protocol enums to `core/build/live-thread-report.txt`; never text or the token.
 */
class LiveThreadTest {

    private val url: String? = System.getenv("OT3A_LIVE_T3_URL")
    private val tokenFile: String? = System.getenv("OT3A_LIVE_T3_TOKEN_FILE")
    private val windowMs = (System.getenv("OT3A_LIVE_THREAD_WINDOW_S")?.toLongOrNull() ?: 10L) * 1000
    private val report = StringBuilder()

    @Test
    fun opensTheLatestThreadReadOnly() = runBlocking {
        assumeTrue("OT3A_LIVE_T3_URL / OT3A_LIVE_T3_TOKEN_FILE not set", url != null && tokenFile != null)
        assumeHostJni()
        val baseUrl = normalizeOrigin(url!!)!!
        val token = File(tokenFile!!).readText().trim()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            NativeT3Transport().use { transport ->
                transport.connect(ConnectRoute.Bearer(baseUrl, token))
                val shell = ShellReducer.parse(transport.request(SHELL_PATH) as JSONObject)
                val thread = shell.threads
                    .filter { it.relationshipToParent != "subagent" && it.archivedAt == null && it.deletedAt == null }
                    .maxByOrNull { it.updatedAt.orEmpty() } ?: error("no thread")
                line("thread: status=${thread.status} working=${thread.activeRunId != null}")

                val bounded = transport.boundedThread(thread.id)
                line("boundedThread: ${shape(bounded)}")
                val projection = bounded.optJSONObject("projection")
                line("projection keys: ${projection?.keys()?.asSequence()?.sorted()?.toList()}")
                line("thread keys (${projection?.optJSONObject("thread")?.length()}): ${projection?.optJSONObject("thread")?.let { shape(it) }}")
                line("counts: ${counts(projection)}")
                line("message row keys: ${rowKeys(projection?.optJSONArray("messages"))}")
                line("message roles: ${values(projection?.optJSONArray("messages"), "role")} streaming=${values(projection?.optJSONArray("messages"), "streaming")}")
                line("run statuses: ${values(projection?.optJSONArray("runs"), "status")}")
                line("runtime request statuses: ${values(projection?.optJSONArray("runtimeRequests"), "status")}")

                val start = bounded.optLong("snapshotSequence")
                var index = 0
                withTimeoutOrNull(windowMs) {
                    transport.threadStream(thread.id, afterSequence = start).collect { update ->
                        val items = update.batch.items
                        val kinds = (0 until items.length()).map { i ->
                            val item = items.getJSONObject(i)
                            when (item.optString("kind")) {
                                "event" -> "event(${item.optJSONObject("event")?.optString("type")}, seq+${item.optLong("sequence") - start})"
                                "snapshot" -> "snapshot(seq+${item.optLong("snapshotSequence") - start}, ${counts(item.optJSONObject("projection"))})"
                                else -> item.optString("kind") + (if (item.has("sequence")) "(seq+${item.optLong("sequence") - start})" else "")
                            }
                        }
                        line(
                            "update $index: start=${update.start} idle=${update.idle} repaint=${update.repaint} " +
                                "closed=${update.closed} error=${update.error} cursor=seq+${update.cursor?.minus(start)} items=$kinds",
                        )
                        index++
                    }
                }
                line("raw stream updates in ${windowMs / 1000} s: $index")

                val session = ThreadSession(thread.id, flowOf(transport), scope)
                session.start()
                delay(windowMs)
                val view = session.view.value
                line(
                    "session: loaded=${view.loaded} connection=${view.connectionState::class.simpleName} " +
                        "messages=${view.messages.size} working=${view.working} pending=${view.pendingRequests.size} " +
                        "stale=${view.stale} sequence=seq+${view.sequence?.minus(start)} modelSelection=${view.modelSelection != null}",
                )
                session.close()
            }
        } finally {
            scope.cancel()
            File("build/live-thread-report.txt").writeText(report.toString())
        }
    }

    private fun counts(projection: JSONObject?): Map<String, Int> =
        projection?.keys()?.asSequence()?.sorted()?.mapNotNull { key ->
            projection.optJSONArray(key)?.let { key to it.length() }
        }?.toMap().orEmpty()

    private fun rowKeys(rows: JSONArray?): List<String> =
        (0 until (rows?.length() ?: 0)).flatMap { rows!!.getJSONObject(it).keys().asSequence().toList() }.distinct().sorted()

    private fun values(rows: JSONArray?, key: String): Map<String, Int> =
        (0 until (rows?.length() ?: 0)).map { rows!!.getJSONObject(it).opt(key).toString() }.groupingBy { it }.eachCount()

    private fun shape(json: JSONObject, depth: Int = 0): String = json.keys().asSequence().sorted().joinToString(", ", "{", "}") { key ->
        when (val value = json.opt(key)) {
            is JSONObject -> if (depth < 1) "$key{${value.length()} keys}" else "$key{…}"
            is JSONArray -> "$key[${value.length()}]"
            JSONObject.NULL -> "$key=null"
            is Boolean -> "$key=$value"
            else -> if (key in SHOWN) "$key=$value" else key
        }
    }

    private fun line(text: String) {
        report.appendLine(text)
    }

    private companion object {
        /** Enumerations whose values carry no user data. */
        val SHOWN = setOf("kind", "status", "runtimeMode", "interactionMode")
    }
}
