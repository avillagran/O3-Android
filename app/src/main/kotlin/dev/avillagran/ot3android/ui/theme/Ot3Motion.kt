package dev.avillagran.ot3android.ui.theme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import dev.avillagran.ot3android.core.data.prefs.AnimationPreference
import dev.avillagran.ot3android.core.data.prefs.RevealPace
import kotlin.math.roundToInt

/** ot3's durations and curves (docs/ui/tokens.md section 5). */
object Ot3Motion {
    /** Colour state changes (`ot3:ui/qml/T3ThreadRow.qml:181`). */
    const val COLOR_CHANGE_MS = 130

    /** Text field border (`ot3:ui/qml/T3TextField.qml:23`). */
    const val FIELD_BORDER_MS = 140

    /** Switch knob, OutCubic (`ot3:ui/qml/T3Switch.qml:17`). */
    const val SWITCH_MS = 120

    /** Row arrival: 520 × 0.8 ms, pause 20%, opacity OutCubic, drift −18 → 0 dp OutBack 1.2 (`ot3:ui/qml/T3ThreadRow.qml:127-136`). */
    const val ROW_ARRIVE_MS = 416
    const val ROW_ARRIVE_DELAY_MS = 83
    const val ROW_ARRIVE_DRIFT_DP = -18f

    /** Row departure: 520 × 0.6 ms, InCubic (`ot3:ui/qml/T3ThreadRow.qml:138-142`). */
    const val ROW_DEPART_MS = 312

    /** Row moving between shelves, InOutCubic (`ot3:ui/qml/T3Panel.qml:586,757`). */
    const val SHELF_MOVE_MS = 520

    /** Status icon pop, OutBack 2.4 (`ot3:ui/qml/T3ThreadRow.qml:283`). */
    const val STATUS_POP_MS = 460

    /** Spinner cycle (`ot3:ui/qml/WorkIndicator.qml:21,47`). */
    const val SPINNER_CYCLE_MS = 1250

    /** Live activity shimmer: 72 dp band, 2.2 s (`ot3:ui/qml/T3ActivityText.qml:18-35`). */
    const val SHIMMER_CYCLE_MS = 2200
    const val SHIMMER_BAND_DP = 72f

    /** T3Reveal software/spring path (T3Reveal.qml:34), natural-size content clipped at the edge. */
    const val REVEAL_MS = 520

    /** Panes, cubic (`t3m:src/features/layout/workspace-pane-animation.ts:8-11`). */
    const val PANE_MS = 260

    val EaseOutCubic: Easing = Easing { t -> val u = 1f - t; 1f - u * u * u }
    val EaseInCubic: Easing = Easing { t -> t * t * t }
    val EaseInOutCubic: Easing = Easing { t -> if (t < 0.5f) 4f * t * t * t else { val u = -2f * t + 2f; 1f - u * u * u / 2f } }

    /** Qt's OutBack with overshoot [overshoot]: `t' = t − 1; t'² ((s + 1) t' + s) + 1`. */
    fun easeOutBack(overshoot: Float): Easing = Easing { t ->
        val u = t - 1f
        u * u * ((overshoot + 1f) * u + overshoot) + 1f
    }

    val EaseInOutQuart: Easing = Easing { t -> if (t < 0.5f) 8f * t * t * t * t else { val u = -2f * t + 2f; 1f - u * u * u * u / 2f } }

    val RowDriftEasing: Easing = easeOutBack(1.2f)
    val StatusPopEasing: Easing = easeOutBack(2.4f)
}

/** How much moves: Full, Light (the sky keeps comets in the header only) or Off (everything snaps). */
enum class Ot3MotionLevel { FULL, LIGHT, OFF }

/**
 * The preference with Android's rules on top (UI_SPEC 3.4): animator duration scale 0 → Off;
 * battery saver → at most Light. Auto is Full here; the sky also drops Auto to Light after
 * 90 slow ticks, as ot3 does (`ot3:ui/qml/T3Starfield.qml:113-115`).
 */
fun resolveMotionLevel(preference: AnimationPreference, animatorsOff: Boolean, powerSave: Boolean): Ot3MotionLevel {
    val chosen = when (preference) {
        AnimationPreference.AUTO, AnimationPreference.FULL -> Ot3MotionLevel.FULL
        AnimationPreference.LIGHT -> Ot3MotionLevel.LIGHT
        AnimationPreference.OFF -> Ot3MotionLevel.OFF
    }
    return when {
        animatorsOff -> Ot3MotionLevel.OFF
        powerSave && chosen == Ot3MotionLevel.FULL -> Ot3MotionLevel.LIGHT
        else -> chosen
    }
}

/**
 * Motion as provided by [LocalOt3Motion]. Every animation opts in through it (T3 Mobile's
 * rule, `t3m:src/lib/designTokens.ts:8`), so Off snaps all of them.
 */
@Immutable
data class Ot3MotionPolicy(val level: Ot3MotionLevel, val preference: AnimationPreference = AnimationPreference.AUTO, val revealPace: RevealPace = RevealPace.NORMAL) {
    val animates: Boolean get() = level != Ot3MotionLevel.OFF

    fun <T> spec(full: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> = if (animates) full else snap()

    fun <T> tween(durationMillis: Int, easing: Easing = LinearEasing): FiniteAnimationSpec<T> =
        spec(androidx.compose.animation.core.tween(durationMillis, easing = easing))

    fun <T> colorChange(): AnimationSpec<T> = tween(Ot3Motion.COLOR_CHANGE_MS)

    fun <T> fieldBorder(): AnimationSpec<T> = tween(Ot3Motion.FIELD_BORDER_MS)

    fun <T> switchKnob(): AnimationSpec<T> = tween(Ot3Motion.SWITCH_MS, Ot3Motion.EaseOutCubic)

    fun <T> rowArriveFade(): FiniteAnimationSpec<T> = spec(androidx.compose.animation.core.tween(Ot3Motion.ROW_ARRIVE_MS - Ot3Motion.ROW_ARRIVE_DELAY_MS,
            delayMillis = Ot3Motion.ROW_ARRIVE_DELAY_MS, easing = Ot3Motion.EaseOutCubic))

    fun <T> rowArriveDrift(): FiniteAnimationSpec<T> = spec(androidx.compose.animation.core.tween(Ot3Motion.ROW_ARRIVE_MS - Ot3Motion.ROW_ARRIVE_DELAY_MS,
            delayMillis = Ot3Motion.ROW_ARRIVE_DELAY_MS, easing = Ot3Motion.RowDriftEasing))

    fun <T> rowDepart(): FiniteAnimationSpec<T> = tween(Ot3Motion.ROW_DEPART_MS, Ot3Motion.EaseInCubic)

    fun <T> shelfMove(): FiniteAnimationSpec<T> = tween(Ot3Motion.SHELF_MOVE_MS, Ot3Motion.EaseInOutQuart)

    fun <T> statusPop(): FiniteAnimationSpec<T> = tween(Ot3Motion.STATUS_POP_MS, Ot3Motion.StatusPopEasing)

    fun <T> reveal(nebula: Boolean = false): FiniteAnimationSpec<T> = tween(
        ((if (nebula) 560 else Ot3Motion.REVEAL_MS) * revealPace.multiplier).roundToInt(),
        if (nebula) Ot3Motion.EaseInOutCubic else Ot3Motion.easeOutBack(1.3f),
    )

    fun <T> pane(): FiniteAnimationSpec<T> = tween(Ot3Motion.PANE_MS, Ot3Motion.EaseInOutCubic)

    companion object {
        val Full = Ot3MotionPolicy(Ot3MotionLevel.FULL)
        val Off = Ot3MotionPolicy(Ot3MotionLevel.OFF)
    }
}

val LocalOt3Motion = staticCompositionLocalOf { Ot3MotionPolicy.Full }

/** [resolveMotionLevel] for [preference], following the animator scale and battery saver live. */
@Composable
fun rememberOt3Motion(preference: AnimationPreference, revealPace: RevealPace = RevealPace.NORMAL): Ot3MotionPolicy {
    val context = LocalContext.current.applicationContext
    var animatorsOff by remember { mutableStateOf(animatorsOff(context)) }
    var powerSave by remember { mutableStateOf(powerSave(context)) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                animatorsOff = animatorsOff(context)
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                powerSave = powerSave(context)
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose {
            context.contentResolver.unregisterContentObserver(observer)
            context.unregisterReceiver(receiver)
        }
    }
    val level = resolveMotionLevel(preference, animatorsOff, powerSave)
    return remember(level, preference, revealPace) { Ot3MotionPolicy(level, preference, revealPace) }
}

private fun animatorsOff(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

private fun powerSave(context: Context): Boolean =
    (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode
