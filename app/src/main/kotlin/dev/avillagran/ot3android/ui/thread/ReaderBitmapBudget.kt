package dev.avillagran.ot3android.ui.thread

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** Reservations include decoding and every bitmap still displayed. No offscreen pixels
 * are cached. Reserving the worst-case decode size prevents transient over-allocation. */
internal class ReaderBitmapBudget(val limit: Int = 32 * 1024 * 1024) {
    private val changed = MutableStateFlow(0L)
    private val waiting = ArrayDeque<Any>()
    var used: Int = 0
        private set

    suspend fun acquire(bytes: Int): Lease {
        require(bytes in 1..limit)
        val request = Any()
        synchronized(this) { waiting.addLast(request); changed.value++ }
        try {
            while (true) {
                val version = changed.value
                synchronized(this) {
                    // Only visible readers enqueue. FIFO prevents a succession of
                    // small visible requests starving an older larger request.
                    if (waiting.firstOrNull() === request && used + bytes <= limit) {
                        waiting.removeFirst()
                        used += bytes
                        changed.value++
                        return Lease(bytes)
                    }
                }
                changed.first { it != version }
            }
        } finally {
            synchronized(this) { if (waiting.remove(request)) changed.value++ }
        }
    }

    inner class Lease(private val bytes: Int) : AutoCloseable {
        private var closed = false
        override fun close() = synchronized(this@ReaderBitmapBudget) {
            if (!closed) { closed = true; used -= bytes; changed.value++ }
        }
    }

    companion object {
        val shared = ReaderBitmapBudget()
        // Leave 16 MiB for a fullscreen decode even when many thumbnails are near.
        val thumbnails = ReaderBitmapBudget(16 * 1024 * 1024)
        // Legacy BitmapFactory scaling can temporarily hold one sampled source.
        // Total pixel reservations: 32 MiB displayed/decoding + 16 MiB legacy scratch.
        val scratch = ReaderBitmapBudget(16 * 1024 * 1024)
    }
}
