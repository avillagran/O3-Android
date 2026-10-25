package dev.avillagran.ot3android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import dev.avillagran.ot3android.ui.navigation.Ot3NavHost
import dev.avillagran.ot3android.ui.navigation.Routes
import dev.avillagran.ot3android.ui.pair.PairingInbox
import dev.avillagran.ot3android.ui.theme.Ot3Theme
import kotlinx.coroutines.flow.first

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A recreated activity (rotation, process restore) already handled its intent.
        if (savedInstanceState == null) receivePairingLink(intent)
        val repository = appGraph.environmentRepository
        setContent {
            Ot3Theme {
                // The catalog when something is saved, otherwise straight to connect.
                val start by produceState<String?>(null) {
                    value = if (repository.environments.first().isEmpty()) Routes.CONNECT else Routes.ENVIRONMENTS
                }
                Surface(Modifier.fillMaxSize()) {
                    start?.let { Ot3NavHost(startDestination = it) }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receivePairingLink(intent)
    }

    /** Hands a pairing link to the pairing screen (Ot3NavHost opens it) and drops it from the intent. */
    private fun receivePairingLink(intent: Intent?) {
        val link = PairingInbox.linkOf(intent) ?: return
        intent?.data = null
        appGraph.pairingInbox.offer(link)
    }
}
