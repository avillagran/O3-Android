package dev.avillagran.ot3android.ui.sky

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.core.content.ContextCompat

/** The platform's motion settings UI_SPEC 3.4 applies on top of ot3's preference. */
internal data class SystemMotionLimits(val animatorScale: Float = 1f, val powerSave: Boolean = false)

/** Animator duration scale and battery saver, updated while composed. */
@Composable
internal fun rememberSystemMotionLimits(): State<SystemMotionLimits> {
    if (LocalInspectionMode.current) return remember { mutableStateOf(SystemMotionLimits()) }
    val context = LocalContext.current.applicationContext
    val state: MutableState<SystemMotionLimits> = remember(context) { mutableStateOf(readLimits(context)) }
    DisposableEffect(context) {
        val refresh = { state.value = readLimits(context) }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = refresh()
        }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = refresh()
        }
        // A system broadcast: delivered to non-exported receivers too.
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        refresh()
        onDispose {
            context.contentResolver.unregisterContentObserver(observer)
            context.unregisterReceiver(receiver)
        }
    }
    return state
}

private fun readLimits(context: Context): SystemMotionLimits {
    val scale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    val power = context.getSystemService(PowerManager::class.java)
    return SystemMotionLimits(animatorScale = scale, powerSave = power?.isPowerSaveMode == true)
}
