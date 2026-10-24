package dev.avillagran.ot3android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.avillagran.ot3android.ui.navigation.Ot3NavHost
import dev.avillagran.ot3android.ui.theme.Ot3Theme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Ot3Theme {
                Ot3NavHost()
            }
        }
    }
}
