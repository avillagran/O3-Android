package dev.avillagran.ot3android.diagnostics

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import dev.avillagran.ot3android.Ot3Application
import dev.avillagran.ot3android.core.shell.*
import dev.avillagran.ot3android.core.transport.DomainEngine
import dev.avillagran.t3.T3Exception
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in diagnostic: counts and domain error codes only, no thread text or credentials. */
class HomeReadOnlyDeviceTest {
    @Test fun projectExistingShells() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("ot3a.homeReadOnly") == "true")
        val graph = (instrumentation.targetContext.applicationContext as Ot3Application).graph
        graph.connectionManager.setForeground(true)
        val saved = withTimeout(30_000) { graph.environmentRepository.environments.first { it.isNotEmpty() } }
        val environments = saved.map { env ->
            val supervisor = requireNotNull(graph.connectionManager.supervisor(env.id))
            graph.connectionManager.startIfCurrent(supervisor)
            val shell = withTimeout(30_000) { supervisor.shell.filterNotNull().first() }
            report(JSONObject().put("host", env.label).put("threads", shell.threads.size))
            val transport = withTimeout(30_000) { supervisor.connection.filterNotNull().first().transport }
            try {
                val shared = dev.avillagran.ot3android.core.settings.SharedClientPreferencesReader().followUp(transport)
                graph.generalClientPreferences.mirrorFollowUp(env.id, shared)
                report(JSONObject().put("host", env.label).put("desktopFollowUp", shared)
                    .put("androidFollowUp", graph.generalClientPreferences.followUpBehavior(env.id)))
            } catch (error: Exception) {
                report(JSONObject().put("host", env.label).put("preferencesReadError", if (error is T3Exception) error.code else error.javaClass.simpleName))
            }
            val requestedTitle = InstrumentationRegistry.getArguments().getString("ot3a.queueThreadTitle")
            if (requestedTitle != null) {
                shell.threads.filter { it.title.equals(requestedTitle, ignoreCase = true) }.forEach { thread ->
                    val snapshot = transport.boundedThread(thread.id).getJSONObject("projection")
                    val queue = DomainEngine.shared.domain("queue.derive", JSONObject().put("projection", snapshot)) as JSONObject
                    report(JSONObject().put("host", env.label).put("queueThreadFound", true)
                        .put("queuedMessages", queue.getJSONArray("items").length()).put("held", queue.optBoolean("held"))
                        .put("hasActiveRun", !queue.isNull("activeRunId")).put("canSteer", queue.optBoolean("canSteer")))
                }
            }
            SidebarEnvironment(env.id, env.label, shell, "ready")
        }
        var fallback = false
        val measured = dev.avillagran.ot3android.core.transport.DomainCalls { fn, args ->
            if (args.has("compact") && !args.optBoolean("compact")) fallback = true
            DomainEngine.shared.domain(fn, args)
        }
        val result = SidebarView.build(measured, environments, System.currentTimeMillis())
        org.junit.Assert.assertEquals(environments.sumOf { it.shell!!.threads.size }, result.threads.size)
        report(JSONObject().put("success", true).put("rows", result.threads.size).put("standardFallback", fallback))
        val offlineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var networkCalls = 0
            val cold = dev.avillagran.ot3android.core.connection.ConnectionManager(graph.environmentRepository, graph.shellCursors,
                dev.avillagran.ot3android.core.transport.T3TransportFactory { networkCalls++; error("offline_probe_opened_network") },
                offlineScope, cache = dev.avillagran.ot3android.core.thread.ThreadCache(
                    instrumentation.targetContext.noBackupFilesDir.resolve("thread-cache-v1")))
            cold.setForeground(false)
            val cached = saved.map { env ->
                val owner = requireNotNull(cold.supervisor(env.id))
                org.junit.Assert.assertNotNull(owner.shell.value)
                dev.avillagran.ot3android.core.home.HomeEnvironment(env.id, env.label, owner.state.value, owner.shell.value)
            }
            val offline = dev.avillagran.ot3android.core.home.HomeStateBuilder.build(DomainEngine.shared,
                dev.avillagran.ot3android.core.home.HomeInput(cached, now = System.currentTimeMillis()))
            org.junit.Assert.assertEquals(dev.avillagran.ot3android.core.home.HomeContent.List, offline.content)
            org.junit.Assert.assertTrue(offline.stale)
            org.junit.Assert.assertEquals(0, networkCalls)
            report(JSONObject().put("diskOnlyRows", offline.rows.size).put("networkCalls", networkCalls).put("cachedList", true))
        } finally { offlineScope.cancel() }
        val store = androidx.lifecycle.ViewModelStore()
        try {
            val vm = withContext(Dispatchers.Main) {
                dev.avillagran.ot3android.ui.home.HomeViewModel(graph.environmentRepository, graph.connectionManager,
                    graph.homePreferences).also { store.put("home-probe", it) }
            }
            val state = withTimeout(30_000) { vm.state.filterNotNull().first { it.content == dev.avillagran.ot3android.core.home.HomeContent.List } }
            org.junit.Assert.assertTrue(state.items.isNotEmpty())
            report(JSONObject().put("homeList", true).put("homeRows", state.rows.size).put("visibleItems", state.items.size))
            val synced = withTimeout(30_000) { vm.state.filterNotNull().first { ui -> ui.hostStatuses.size == saved.size &&
                ui.hostStatuses.all { it.connection is dev.avillagran.ot3android.core.connection.ConnectionState.Connected && !it.syncing } } }
            report(JSONObject().put("allHostsSynced", true).put("hostCount", synced.hostStatuses.size))
        } finally { withContext(Dispatchers.Main) { store.clear() } }

    }
    private fun report(value: JSONObject) = InstrumentationRegistry.getInstrumentation().sendStatus(0,
        Bundle().apply { putString("ot3a.home.read", value.toString()) })
}
