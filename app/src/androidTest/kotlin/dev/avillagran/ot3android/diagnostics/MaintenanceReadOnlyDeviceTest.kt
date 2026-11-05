package dev.avillagran.ot3android.diagnostics

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import dev.avillagran.ot3android.Ot3Application
import dev.avillagran.t3.T3Exception
import dev.avillagran.ot3android.core.maintenance.*
import dev.avillagran.ot3android.core.transport.T3Transport
import org.junit.Assert.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit opt-in: reads existing authenticated connections, never creates tasks or prints credentials. */
class MaintenanceReadOnlyDeviceTest {
    @Test fun reportScheduledTaskReadStages() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("ot3a.maintenanceReadOnly") == "true")
        val graph = (instrumentation.targetContext.applicationContext as Ot3Application).graph
        val environments = withTimeout(30_000) { graph.environmentRepository.environments.first { it.isNotEmpty() } }
        graph.connectionManager.setForeground(true)
        for (environment in environments) {
            var stage = "connection"
            var live: T3Transport? = null
            val report = JSONObject().put("host", environment.label)
            try {
                val supervisor = withTimeout(30_000) { requireNotNull(graph.connectionManager.supervisor(environment.id)) }
                graph.connectionManager.startIfCurrent(supervisor)
                val transport = withTimeout(30_000) { supervisor.connection.filterNotNull().first().transport }
                live = transport
                stage = "session"
                val session = transport.authSession()
                report.put("authenticated", session.optBoolean("authenticated"))
                stage = "registry"
                val functions = transport.describe().getJSONArray("functions")
                report.put("taskReadRegistered", (0 until functions.length()).any { functions.getJSONObject(it).optString("fn") == "scheduledTasks.list" })
                stage = "tasks"
                val tasks = transport.call("scheduledTasks.list") as JSONObject
                report.put("taskCount", tasks.optJSONArray("tasks")?.length() ?: -1).put("success", true)
            } catch (error: Exception) {
                report.put("stage", stage).put("error", if (error is T3Exception) error.code else error.javaClass.simpleName)
                if (error is T3Exception && error.reason?.trim() == "Unknown request tag: scheduledTasks.list") report.put("reason", "unsupported_scheduled_tasks")
                if (error is T3Exception && error.reason?.trim() == "Unknown request tag: scheduledTasks.list") {
                    val folder = File(instrumentation.targetContext.cacheDir, "maintenance-read-${System.nanoTime()}")
                    try {
                        val controller = MaintenanceController(MaintenanceHost(environment.id, environment.baseUrl, "read-only-test"),
                            MaintenancePage.SCHEDULES, requireNotNull(live), FileMaintenanceReceipts(folder))
                        controller.refresh()
                        assertTrue(controller.state.value.unsupported)
                        assertFalse(controller.state.value.failed)
                        assertFalse(controller.state.value.readable)
                        assertTrue(controller.state.value.writable.isEmpty())
                        report.put("unsupportedStateVerified", true)
                        controller.close()
                    } finally { folder.deleteRecursively() }
                }
            }
            instrumentation.sendStatus(0, Bundle().apply { putString("ot3a.maintenance.read", report.toString()) })
        }
    }
}
