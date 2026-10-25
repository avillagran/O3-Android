package dev.avillagran.ot3android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.avillagran.ot3android.ui.navigation.Ot3NavHost
import dev.avillagran.ot3android.ui.navigation.Routes
import dev.avillagran.ot3android.ui.theme.OmarchyThemeCatalog
import dev.avillagran.ot3android.ui.theme.Ot3AppTheme
import kotlinx.coroutines.flow.first

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = appGraph.environmentRepository
        val appearance = appGraph.appearancePreferences.settings
        setContent {
            // The window background matches the default theme until both are read (a few ms).
            val catalog by produceState<OmarchyThemeCatalog?>(null) { value = appGraph.themeCatalog() }
            val settings by appearance.collectAsStateWithLifecycle(initialValue = null)
            val themeCatalog = catalog
            val appearanceSettings = settings
            if (themeCatalog != null && appearanceSettings != null) {
                Ot3AppTheme(themeCatalog, appearanceSettings) {
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
    }
}
