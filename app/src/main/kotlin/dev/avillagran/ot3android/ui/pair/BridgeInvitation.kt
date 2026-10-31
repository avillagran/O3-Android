package dev.avillagran.ot3android.ui.pair

import android.util.Base64
import org.json.JSONObject

/** URI framing only. The SDK validates the invitation and owns all pairing decisions. */
object BridgeInvitation {
    private const val MANAGEMENT_UNSUPPORTED = "management_invitation_unsupported"

    fun isLink(value: String) = value.startsWith("omabridge://pair/")

    fun decode(value: String): JSONObject {
        require(isLink(value) && value.length <= 16_384) { "invalid_invitation" }
        val encoded = value.removePrefix("omabridge://pair/")
        require(encoded.isNotEmpty() && encoded.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        val bytes = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return try {
            JSONObject(String(bytes, Charsets.UTF_8)).also {
                // This entry grants catalog access only; management needs a separate consent UI.
                require(!it.optBoolean("manage", false)) { MANAGEMENT_UNSUPPORTED }
            }
        } finally { bytes.fill(0) }
    }

    /** The pairing status a failed attempt shows: an invitation asking to manage this device says so. */
    fun failureStatus(error: Exception): String =
        if (error is IllegalArgumentException && error.message == MANAGEMENT_UNSUPPORTED) "management_unsupported" else "failed"
}
