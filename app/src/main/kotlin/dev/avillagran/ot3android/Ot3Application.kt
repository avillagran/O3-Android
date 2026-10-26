package dev.avillagran.ot3android

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

class Ot3Application : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(ConnectionLifecycle(graph))
        watchNetwork()
    }

    /** A network change may have killed open sockets: probe them (lib contract). */
    private fun watchNetwork() {
        val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivity.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = graph.connectionManager.wakeAll()
                override fun onLost(network: Network) = graph.connectionManager.wakeAll()
            },
        )
    }
}

/**
 * Streams run only while the app is visible: stopped on the process's ON_STOP, resumed from
 * their held shell on ON_START, and probed with `wakeSubscriptions` on ON_RESUME. The live
 * theme reads and polls only while visible too.
 */
private class ConnectionLifecycle(private val graph: AppGraph) : DefaultLifecycleObserver {
    override fun onStart(owner: LifecycleOwner) {
        graph.connectionManager.setForeground(true)
        graph.liveThemeSource.setVisible(true)
    }

    override fun onResume(owner: LifecycleOwner) = graph.connectionManager.wakeAll()

    override fun onStop(owner: LifecycleOwner) {
        graph.liveThemeSource.setVisible(false)
        graph.connectionManager.setForeground(false)
    }
}
