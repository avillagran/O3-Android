package dev.avillagran.ot3android.ui.pair

import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import java.util.WeakHashMap

/**
 * Keeps the activity's window `FLAG_SECURE` (no screenshots, screen recording or recents
 * thumbnail) while this is composed: the pairing and scanner screens show a one-time code.
 * Counted per window, because during a navigation transition both screens are composed and
 * the one leaving must not clear the flag for the one arriving.
 */
@Composable
fun SecureWindow() {
    val window = LocalActivity.current?.window ?: return
    DisposableEffect(window) {
        val holders = secureHolders.getOrDefault(window, 0)
        if (holders == 0) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        secureHolders[window] = holders + 1
        onDispose {
            val left = secureHolders.getOrDefault(window, 1) - 1
            if (left == 0) {
                secureHolders.remove(window)
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                secureHolders[window] = left
            }
        }
    }
}

/** Screens holding [SecureWindow] per window. Main thread only, like composition effects. */
private val secureHolders = WeakHashMap<Window, Int>()
