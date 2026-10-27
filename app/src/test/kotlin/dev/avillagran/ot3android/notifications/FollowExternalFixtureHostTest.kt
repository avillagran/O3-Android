package dev.avillagran.ot3android.notifications

import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in host process; survives killing the separately installed Android application. */
class FollowExternalFixtureHostTest {
    @Test(timeout = 1_200_000) fun serveExternalResilienceFixture() {
        val path = System.getProperty("ot3a.followLabDir")
        assumeTrue("Explicit private host lab directory required", !path.isNullOrBlank())
        val directory = File(path!!).apply { mkdirs() }
        FollowSystemFixtureServer("Resilience A", "follow-resilience-a").use { a ->
            FollowSystemFixtureServer("Resilience B", "follow-resilience-b").use { b ->
                fun stats() = JSONObject().apply {
                    listOf("a" to a, "b" to b).forEach { (key, server) ->
                        put(key, JSONObject().put("url", server.url).put("subscriptions", server.shellSubscriptions.get())
                            .put("active", server.activeShellSubscriptions).put("pings", server.shellPings.get())
                            .put("disconnectedPeers", server.disconnectedPeers.get()).put("writes", server.writes.get())
                            .put("failures", server.failures.toList()))
                    }
                }
                fun output(name: String, value: JSONObject) {
                    val temp = File(directory, "$name.tmp")
                    temp.writeText(value.toString(2)); check(temp.renameTo(File(directory, name)))
                }
                output("ready.json", stats())
                val command = File(directory, "command.json")
                while (!Thread.currentThread().isInterrupted) {
                    if (!command.isFile) { Thread.sleep(50); continue } // Host fixture control only.
                    val input = JSONObject(command.readText()); command.delete()
                    var stop = false
                    val result = try {
                        val targets = when (input.optString("server", "both")) { "a" -> listOf(a); "b" -> listOf(b); "both" -> listOf(a, b); else -> error("unknown fixture") }
                        when (input.getString("action")) {
                            "stats" -> Unit
                            "republish" -> targets.forEach { it.republish() }
                            "thread" -> {
                                val thread = input.getString("thread")
                                val phase = input.getString("phase")
                                require(thread in listOf("review-one", "review-two", "visible"))
                                require(phase in listOf("running", "approval", "completed"))
                                targets.forEach { it.publishThread(thread, phase) }
                            }
                            "publish" -> { val phase = input.getString("phase"); require(phase in listOf("running", "approval", "completed")); targets.forEach { it.publish(phase, phase) } }
                            "available" -> targets.forEach { it.setAvailable(input.getBoolean("value")) }
                            "blackhole" -> targets.forEach { it.blackholeExistingShellPongs() }
                            "stop" -> stop = true
                            else -> error("unknown action")
                        }
                        stats().put("ok", true)
                    } catch (error: Exception) { JSONObject().put("ok", false).put("error", error.javaClass.simpleName) }
                    output("response.json", result.put("id", input.getString("id")))
                    if (stop) return
                }
            }
        }
    }
}
