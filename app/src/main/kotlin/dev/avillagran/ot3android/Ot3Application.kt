package dev.avillagran.ot3android

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.Lifecycle
import dev.avillagran.ot3android.notifications.ThreadFollowService
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

class Ot3Application : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { dev.avillagran.ot3android.ui.thread.ImageClipboard.cleanup(this@Ot3Application) }
            dev.avillagran.ot3android.ui.thread.ReaderMediaFiles.prune(java.io.File(cacheDir, "reader-media"))
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(ConnectionLifecycle(graph))
        watchNetwork()
    }

    /** A network change may have killed open sockets: probe them (lib contract). */
    private fun watchNetwork() {
        val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivity.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { graph.connectionManager.wakeAll(); graph.bridgeCatalog.reconnect() }
                override fun onLost(network: Network) { graph.connectionManager.wakeAll(); graph.bridgeCatalog.reconnect() }
            },
        )
    }
}

/**
 * Foreground screens start shared supervisors; ON_STOP stops those without a follow
 * lease. Leased supervisors may keep following in the background. ON_RESUME probes
 * existing streams with `wakeSubscriptions`; the live theme reads only while visible.
 */
private class ConnectionLifecycle(private val graph: AppGraph) : DefaultLifecycleObserver {
    private var starter: Job? = null
    override fun onStart(owner: LifecycleOwner) {
        graph.followVisibility.setVisible(true)
        starter?.cancel()
        starter = graph.appScope.launch {
            combine(graph.followPreferences.enabled, graph.connectionManager.states) { enabled, states ->
                enabled && states.values.any { it is dev.avillagran.ot3android.core.connection.ConnectionState.Connected }
            }.distinctUntilChanged().collect { start ->
                if (start && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    ThreadFollowService.start(graph.applicationContext)
                }
            }
        }
        graph.connectionManager.setForeground(true)
        graph.bridgeCatalog.setForeground(true)
        graph.liveThemeSource.setVisible(true)
    }

    override fun onResume(owner: LifecycleOwner) = graph.connectionManager.wakeAll()

    override fun onStop(owner: LifecycleOwner) {
        starter?.cancel(); starter = null
        graph.followVisibility.setVisible(false)
        graph.liveThemeSource.setVisible(false)
        graph.bridgeCatalog.setForeground(false)
        graph.connectionManager.setForeground(false)
    }
}
