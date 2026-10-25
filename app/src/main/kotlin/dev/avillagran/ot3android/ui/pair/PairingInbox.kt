package dev.avillagran.ot3android.ui.pair

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * A pairing link on its way to the pairing screen from outside it: a deep link or the QR
 * scanner. Memory only, never a navigation argument or saved state, because the link holds a
 * one-time code; a link not taken before the process dies is lost and the user scans again.
 */
class PairingInbox {

    /** A waiting [link]; [scanned] when the in-app scanner read it, false for a deep link. */
    class Offer(val link: String, val scanned: Boolean) {
        override fun toString() = "Offer(link=<redacted>, scanned=$scanned)"
    }

    private val _link = MutableStateFlow<Offer?>(null)

    /** The waiting link, if any. Never log it. */
    val link: StateFlow<Offer?> = _link.asStateFlow()

    fun offer(link: String, scanned: Boolean = false) {
        _link.value = Offer(link, scanned)
    }

    /** The waiting link, removed from the inbox. */
    fun take(): Offer? = _link.getAndUpdate { null }

    companion object {
        /**
         * The pairing link an intent opens (`http(s)://<host>/pair#token=…`, as `t3 pair` prints
         * it), or null. The manifest's intent filter routes these to the app.
         */
        fun linkOf(intent: Intent?): String? {
            if (intent?.action != Intent.ACTION_VIEW) return null
            val data = intent.data ?: return null
            if (data.scheme !in setOf("http", "https") || data.path != "/pair") return null
            return data.toString()
        }
    }
}
