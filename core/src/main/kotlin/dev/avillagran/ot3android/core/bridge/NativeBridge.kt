package dev.avillagran.ot3android.core.bridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Marshaling only: identity, pairing, protocol, cache, TTL and projection live in Rust. */
object NativeBridge {
    private external fun call(request: ByteArray): ByteArray
    private val loaded by lazy { System.loadLibrary("omabridge_jni") }

    fun isAvailable(): Boolean = runCatching { loaded }.isSuccess

    suspend fun request(request: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        loaded
        val bytes = request.toString().toByteArray(Charsets.UTF_8)
        try {
            val response = JSONObject(String(call(bytes), Charsets.UTF_8))
            check(!response.has("error")) {
                // Native error strings come from a fixed vocabulary, not error bodies.
                bridgeErrorCode(response.optString("error"))
            }
            response.getJSONObject("ok")
        } finally {
            bytes.fill(0)
        }
    }
}

/** Fixed vocabulary only: never echo native/network error bodies. */
internal fun bridgeErrorCode(code: String): String = when (code) {
    "invalid_input", "invalid_invitation", "unsupported or expired invitation",
    "pairing denied", "connection setup deadline reached", "no route allowed by local relay policy",
    "bridge_not_selected", "peer_address_missing", "bridge_catalog_expired", "bridge_catalog_rollback",
    "bridge_catalog_invalid_window", "bridge_catalog_ttl_exceeded", "bridge_catalog_from_future",
    "bridge_catalog_revision_reused",
    "bridge_catalog_equivocation", "bridge_catalog_identity_mismatch", "invalid_bridge_catalog",
    "route unavailable", "catalog unavailable", "authority session is not bounded read-only",
    "authority session exceeds enrollment", "authority returned unbounded session",
    "authority returned excessive scopes", "enrollment must be bounded read-only", "descriptor identity mismatch", "T3 session issuance failed" -> code
    else -> "bridge_operation_failed"
}
