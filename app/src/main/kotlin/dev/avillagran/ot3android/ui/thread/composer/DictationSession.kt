package dev.avillagran.ot3android.ui.thread.composer

/** Android adapter owns the microphone; this owner rejects late callbacks after cancel/retry. */
internal interface DictationEngine {
    fun start(result: (String?) -> Unit, error: () -> Unit)
    fun cancel()
}
internal class DictationSession(private val create: () -> DictationEngine, private val changed: () -> Unit) {
    var listening = false; private set
    var failed = false; private set
    var text = ""; private set
    private var epoch = 0L
    private var engine: DictationEngine? = null
    fun cancel() {
        epoch++; listening = false
        val old = engine; engine = null
        runCatching { old?.cancel() }
        changed()
    }
    fun start() {
        cancel(); failed = false; text = ""
        val token = epoch
        listening = true
        try {
            val active = create(); engine = active
            active.start({ value ->
                if (epoch == token) {
                    text = value.orEmpty().take(MAX_TEXT)
                    cancel(); failed = text.isBlank(); changed()
                }
            }, { if (epoch == token) { cancel(); failed = true; changed() } })
        } catch (_: Exception) { cancel(); failed = true }
        changed()
    }
    fun permissionDenied() { cancel(); failed = true; changed() }
    fun timeout() { if (listening) { cancel(); failed = true; changed() } }
    companion object { const val MAX_TEXT = 16_384 }
}
