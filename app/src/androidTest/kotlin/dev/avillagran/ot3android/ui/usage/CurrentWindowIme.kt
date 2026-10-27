package dev.avillagran.ot3android.ui.usage

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import dev.avillagran.ot3android.debug.workspace.WorkspaceFixtureActivity

/** Test-only observer installed before recreation can restore the editor/IME. */
internal class CurrentWindowIme(private val application: Application) : Application.ActivityLifecycleCallbacks, AutoCloseable {
    private class Window(val activity: Activity) {
        val decor = activity.window.decorView
        val completion = ImeShowCompletion()
    }
    private val windows = mutableMapOf<Activity, Window>()
    init { application.registerActivityLifecycleCallbacks(this) }

    private fun observe(activity: Activity) {
        if (activity !is WorkspaceFixtureActivity || activity in windows) return
        val window = Window(activity)
        windows[activity] = window
        ViewCompat.setWindowInsetsAnimationCallback(window.decor, object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
            override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                if (animation.typeMask and WindowInsetsCompat.Type.ime() == 0) return
                window.completion.prepare(animation)
                trace(window, "prepare")
            }
            override fun onProgress(insets: WindowInsetsCompat, runningAnimations: MutableList<WindowInsetsAnimationCompat>): WindowInsetsCompat {
                val visible = insets.isVisible(WindowInsetsCompat.Type.ime()) && insets.getInsets(WindowInsetsCompat.Type.ime()).bottom > 0
                runningAnimations.forEach { window.completion.progress(it, visible) }
                return insets
            }
            override fun onEnd(animation: WindowInsetsAnimationCompat) {
                if (animation.typeMask and WindowInsetsCompat.Type.ime() == 0) return
                window.completion.end(animation)
                trace(window, "end showEnded=${window.completion.complete}")
            }
        })
        trace(window, "observe")
    }
    fun ready(activity: Activity): Boolean {
        val window = windows[activity] ?: return false
        val host = activity.currentFocus ?: return false
        val imm = activity.getSystemService(InputMethodManager::class.java)
        val insets = ViewCompat.getRootWindowInsets(window.decor)
        return window.completion.complete && window.decor.isAttachedToWindow && window.decor.hasWindowFocus() &&
            host.isAttachedToWindow && host.hasFocus() && host.windowToken == window.decor.windowToken &&
            imm.isActive(host) && imm.isAcceptingText && insets?.isVisible(WindowInsetsCompat.Type.ime()) == true &&
            insets.getInsets(WindowInsetsCompat.Type.ime()).bottom > 0
    }
    fun beforeBack(activity: Activity) {
        val window = checkNotNull(windows[activity])
        check(ready(activity)) { "Current window IME show has not completed" }
        trace(window, "beforeBack")
    }
    private fun trace(window: Window, event: String) {
        val host = window.activity.currentFocus
        val imm = window.activity.getSystemService(InputMethodManager::class.java)
        val insets = ViewCompat.getRootWindowInsets(window.decor)
        Log.i("QuotaImeHandshake", "uptime=${SystemClock.uptimeMillis()} activity=${System.identityHashCode(window.activity)} decor=${System.identityHashCode(window.decor)} host=${System.identityHashCode(host)} windowFocus=${window.decor.hasWindowFocus()} active=${host != null && imm.isActive(host)} accepting=${imm.isAcceptingText} ime=${insets?.isVisible(WindowInsetsCompat.Type.ime())} bottom=${insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom} $event")
    }
    override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) = observe(activity)
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = observe(activity)
    override fun onActivityDestroyed(activity: Activity) { windows.remove(activity)?.let { ViewCompat.setWindowInsetsAnimationCallback(it.decor, null) } }
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun close() {
        application.unregisterActivityLifecycleCallbacks(this)
        windows.values.forEach { ViewCompat.setWindowInsetsAnimationCallback(it.decor, null) }
        windows.clear()
    }
}
