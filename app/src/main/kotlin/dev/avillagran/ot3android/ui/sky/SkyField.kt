package dev.avillagran.ot3android.ui.sky

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import dev.avillagran.ot3android.core.data.prefs.AnimationPreference
import dev.avillagran.ot3android.ui.theme.LocalOt3Motion
import dev.avillagran.ot3android.ui.theme.rememberOt3Tick

/**
 * One sky per screen: ot3's `T3Starfield` leader (`ot3:ui/qml/T3Starfield.qml:8-19`). Every
 * region of the same panel (the whole background and the sidebar header) draws from this
 * clock, so the two skies share drift and star positions and the header's darker band
 * dissolves into the sky behind it instead of adding a second, unrelated field.
 *
 * Regions read [tick] and [motion] while drawing; that is what makes them redraw.
 */
@Stable
class SkyField internal constructor() {
    internal val clock = SkyClock()

    /** Bumped on every frame the sky moves. Read it in a draw lambda to redraw. */
    var tick by mutableIntStateOf(0)
        internal set

    /** Effective level after [SkyMotion.limitedBy] and Auto's downgrade. */
    var motion by mutableStateOf(SkyMotion.Off)
        internal set

    /** Auto gave up on Full for the rest of the screen's life (`T3Starfield.qml:113-115`). */
    var degraded by mutableStateOf(false)
        internal set

    private val working = mutableStateMapOf<Any, Boolean>()

    /** Any region of this screen reports work: the chat's run and the sidebar's count. */
    internal val busy: Boolean get() = working.isNotEmpty()

    /** A region's share of the energy: ot3's `busy` (`ot3:ui/qml/T3Panel.qml:848`). */
    fun working(owner: Any, busy: Boolean) {
        // Only real changes touch the map: a region re-registers on every recomposition.
        if (busy) {
            if (!working.containsKey(owner)) working[owner] = true
        } else {
            working.remove(owner)
        }
    }
}

/** The screen's [SkyField]; null outside a screen that draws a sky. */
val LocalSkyField = staticCompositionLocalOf<SkyField?> { null }

/**
 * The sky of the nearest owner, or a new one: a region never starts a second clock. The
 * owner (Home alone, or the chat workspace, which covers the whole panel) passes the level
 * its theme resolved; nested regions inherit it, as ot3's follower skies inherit `mode`.
 *
 * [paused] mirrors ot3's `paused: !root.opened`; leaving the app is already handled by the
 * shared ticker, which stops with the lifecycle (`Ot3Ticker`).
 */
@Composable
fun rememberSkyField(motion: SkyMotion, paused: Boolean = false, onAutoDegrade: (() -> Unit)? = null): SkyField {
    val inherited = LocalSkyField.current
    if (inherited != null) return inherited
    return skyFieldHost(motion, paused, onAutoDegrade)
}

@Composable
private fun skyFieldHost(motion: SkyMotion, paused: Boolean, onAutoDegrade: (() -> Unit)?): SkyField {
    val policy = LocalOt3Motion.current
    val system by rememberSystemMotionLimits()
    val field = remember { SkyField() }
    val requested = if (field.degraded && motion == SkyMotion.Full) SkyMotion.Light else motion
    val effective = requested.limitedBy(system.animatorScale, system.powerSave)
    SideEffect { field.motion = effective }

    val animated = effective != SkyMotion.Off && !paused
    // Auto is a property of the preference, not of the caller's flag: only Auto gives up.
    val auto = onAutoDegrade != null || policy.preference == AnimationPreference.AUTO
    val currentAuto by rememberUpdatedState(auto && !field.degraded)
    val currentMotion by rememberUpdatedState(effective)
    val currentDegrade by rememberUpdatedState(onAutoDegrade)
    // Busy energy has to be known before the first tick picks a pace.
    var interval by remember { mutableIntStateOf(if (field.busy) SkyClock.AMBIENT_MS.toInt() else SkyClock.SLOW_MS.toInt()) }
    val frame by rememberOt3Tick(animated, interval)
    LaunchedEffect(frame.nanos, animated, effective) {
        if (!animated || frame.nanos <= 0L) {
            field.clock.resetTiming()
            return@LaunchedEffect
        }
        field.clock.setEnergyTarget(if (field.busy) 1f else 0f)
        val wasDegraded = field.clock.degraded
        if (field.clock.onFrame(frame.nanos, currentMotion, currentAuto, frame.seconds).ticked) field.tick++
        interval = field.clock.intervalMs(currentMotion, currentAuto).toInt()
        if (!wasDegraded && field.clock.degraded) {
            field.degraded = true
            currentDegrade?.invoke()
        }
    }
    return field
}

/** Registers [working] with the screen's field while this region is composed. */
@Composable
internal fun SkyWorking(field: SkyField, working: Boolean) {
    val owner = remember { Any() }
    SideEffect { field.working(owner, working) }
    DisposableEffect(field, owner) { onDispose { field.working(owner, false) } }
}
