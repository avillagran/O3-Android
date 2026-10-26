package dev.avillagran.ot3android.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Only visible app content suppresses alerts; navigation claims survive overlap during transitions. */
class FollowVisibility {
    private val claims = linkedMapOf<Any, ThreadRef>()
    private var visible = false
    private val current = MutableStateFlow<ThreadRef?>(null)
    val onScreen: StateFlow<ThreadRef?> = current
    @Synchronized fun setVisible(value: Boolean) { visible = value; publish() }
    @Synchronized fun claim(thread: ThreadRef): AutoCloseable {
        val token = Any(); claims[token] = thread; publish()
        return AutoCloseable { synchronized(this) { claims.remove(token); publish() } }
    }
    private fun publish() { current.value = if (visible) claims.values.lastOrNull() else null }
}
