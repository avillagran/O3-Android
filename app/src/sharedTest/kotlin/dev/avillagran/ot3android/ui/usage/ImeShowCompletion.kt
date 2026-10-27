package dev.avillagran.ot3android.ui.usage

/** Event gate for one window; cached visible insets are deliberately not an input. */
internal class ImeShowCompletion {
    private val running = mutableMapOf<Any, Boolean?>()
    var complete = false
        private set

    fun prepare(animation: Any) {
        running[animation] = null
        complete = false
    }
    fun progress(animation: Any, visible: Boolean) {
        if (running.containsKey(animation)) running[animation] = visible
    }
    fun end(animation: Any) {
        if (!running.containsKey(animation)) return
        val visible = running.remove(animation)
        complete = visible == true && running.isEmpty()
    }
}
