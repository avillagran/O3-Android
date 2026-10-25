package dev.avillagran.ot3android.core.transport

import org.json.JSONObject

/**
 * How this device presents itself in T3's session list when it pairs. The lib validates it
 * (`invalid_pairing_client`): each text non-blank, at most 256 bytes, no control characters;
 * [deviceType] one of `desktop`, `mobile`, `tablet`, `bot`, `unknown`. Null fields are omitted.
 */
data class PairingClient(
    val label: String? = null,
    val deviceType: String? = "mobile",
    val os: String? = "android",
) {
    internal fun toJson(): JSONObject = JSONObject().put("label", label).put("deviceType", deviceType).put("os", os)
}

/**
 * The bearer session `pairBearer` issued for a one-time pairing code. [accessToken] is the
 * long-lived credential to keep in the credential store; it never appears in [toString].
 *
 * @property environmentId the server's environment UUID, from the descriptor checked before the
 *   exchange.
 * @property descriptor that descriptor (label, platform, version, ...).
 * @property expiresIn seconds, when the server says.
 * @property connected whether the handle that paired is now connected with [accessToken].
 */
data class PairedBearer(
    val accessToken: String,
    val tokenType: String,
    val environmentId: String,
    val descriptor: JSONObject,
    val scope: String,
    val expiresIn: Long?,
    val connected: Boolean,
) {
    override fun toString() =
        "PairedBearer(accessToken=<redacted>, tokenType=$tokenType, environmentId=$environmentId, scope=$scope, " +
            "expiresIn=$expiresIn, connected=$connected)"

    companion object {
        /** The lib's `pairBearer` result. */
        fun from(json: JSONObject) = PairedBearer(
            accessToken = json.getString("accessToken"),
            tokenType = json.getString("tokenType"),
            environmentId = json.getString("environmentId"),
            descriptor = json.optJSONObject("descriptor") ?: JSONObject(),
            scope = json.optString("scope"),
            expiresIn = if (json.isNull("expiresIn")) null else json.getLong("expiresIn"),
            connected = json.optBoolean("connected"),
        )
    }
}
