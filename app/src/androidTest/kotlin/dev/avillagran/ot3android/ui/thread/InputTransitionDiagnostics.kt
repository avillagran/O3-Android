package dev.avillagran.ot3android.ui.thread

import android.os.SystemClock
import android.util.Log
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import dev.avillagran.ot3android.debug.workspace.WorkspaceFixtureActivity

/** Observes one real touch and its existing assertion; never changes input or readiness. */
internal fun traceInputTransition(
    scenario: ActivityScenario<WorkspaceFixtureActivity>,
    label: String,
    target: SemanticsNodeInteraction,
    surface: () -> String,
    actionAndAssertion: (click: () -> Unit) -> Unit,
) {
    lateinit var draws: ViewTreeObserver.OnPreDrawListener
    var frames = 0
    var lastWindowState = ""
    fun trace(event: String) = Log.i("InputTransition", "uptime=${SystemClock.uptimeMillis()} $label $event")
    fun snapshot(phase: String) {
        // A disappeared target is expected after navigation. Diagnostics must not mask the assertion.
        val bounds = runCatching { target.fetchSemanticsNode().boundsInRoot.toString() }.getOrElse { "absent:${it.javaClass.simpleName}" }
        trace("$phase targetRoot=$bounds surface=${runCatching(surface).getOrElse { "unavailable:${it.javaClass.simpleName}" }}")
    }
    scenario.onActivity { activity ->
        val decor = activity.window.decorView
        fun windowState(): String {
            val insets = ViewCompat.getRootWindowInsets(decor)
            val host = activity.currentFocus
            val imm = activity.getSystemService(InputMethodManager::class.java)
            val location = IntArray(2).also(decor::getLocationOnScreen)
            return "decor=${System.identityHashCode(decor)} size=${decor.width}x${decor.height} screen=${location.toList()} windowFocus=${decor.hasWindowFocus()} focus=${System.identityHashCode(host)} active=${host != null && imm.isActive(host)} accepting=${imm.isAcceptingText} ime=${insets?.isVisible(WindowInsetsCompat.Type.ime())} imeBottom=${insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom}"
        }
        draws = ViewTreeObserver.OnPreDrawListener {
            val state = windowState()
            if (state != lastWindowState && frames < 16) { frames++; lastWindowState = state; trace("preDraw $state") }
            true
        }
        decor.viewTreeObserver.addOnPreDrawListener(draws)
        trace("begin ${windowState()}")
    }
    try {
        snapshot("beforeAction")
        actionAndAssertion {
            // performClick on Android uses this same touch gesture. Log the injected
            // center here: Compose dispatches to its root view, bypassing Window.Callback.
            target.performTouchInput {
                trace("injectTouchClick localCenter=$center")
                click()
            }
            trace("touchClickReturned")
        }
        snapshot("assertionPassed")
    } catch (failure: Throwable) {
        runCatching { snapshot("assertionFailed:${failure.javaClass.simpleName}") }
        throw failure
    } finally {
        runCatching {
            scenario.onActivity {
                it.window.decorView.viewTreeObserver.removeOnPreDrawListener(draws)
            }
        }.onFailure { trace("cleanupFailed:${it.javaClass.simpleName}") }
        trace("end windowChanges=$frames")
    }
}
