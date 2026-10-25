package dev.avillagran.ot3android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.avillagran.ot3android.ui.navigation.Ot3NavHost
import dev.avillagran.ot3android.ui.navigation.Thread
import dev.avillagran.ot3android.ui.navigation.ThreadDeepLink
import dev.avillagran.ot3android.ui.theme.OmarchyThemeCatalog
import dev.avillagran.ot3android.ui.theme.Ot3AppTheme
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The single activity. Home is always the start: with nothing saved it offers Add environment.
 * A thread link (`ot3a://thread/...`) opens over Home, at launch or while running.
 */
class MainActivity : ComponentActivity() {

    /** A thread link not opened yet. Not restored: after process death the back stack already has it. */
    private val deepLink = MutableStateFlow<Thread?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) deepLink.value = ThreadDeepLink.parse(intent?.data)
        val appearance = appGraph.appearancePreferences.settings
        setContent {
            // The window background matches the default theme until both are read (a few ms).
            val catalog by produceState<OmarchyThemeCatalog?>(null) { value = appGraph.themeCatalog() }
            val settings by appearance.collectAsStateWithLifecycle(initialValue = null)
            val link by deepLink.collectAsState()
            val themeCatalog = catalog
            val appearanceSettings = settings
            if (themeCatalog != null && appearanceSettings != null) {
                Ot3AppTheme(themeCatalog, appearanceSettings) {
                    Surface(Modifier.fillMaxSize()) {
                        Ot3NavHost(deepLink = link, onDeepLinkHandled = { deepLink.value = null })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        ThreadDeepLink.parse(intent.data)?.let { deepLink.value = it }
    }
}
