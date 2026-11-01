package dev.avillagran.ot3android.core.surfaces

import org.json.JSONObject

/** In-memory, single-use confirmation. Never serialize this controller or its tokens. */
class DesktopSurfaces(
    private val exchange: suspend (String, String, String?) -> JSONObject,
    private val clock: () -> Long = android.os.SystemClock::elapsedRealtime,
    private val decode: (String) -> ByteArray = { android.util.Base64.decode(it, android.util.Base64.DEFAULT) },
) {
    private var pending: JSONObject? = null
    private var deadline = 0L
    var unknown = false
        private set
    suspend fun read(path: String): JSONObject = request("GET", path, null)
    suspend fun prepare(surface: String, action: String, reportId: String, settings: JSONObject? = null): JSONObject {
        check(!unknown)
        pending = null
        val body = JSONObject().put("surface", surface).put("action", action).put("reportId", reportId)
        if (settings != null) body.put("settings", settings)
        val started = clock()
        val response = request("POST", "/v1/maintenance/prepare", body.toString())
        require(response.getInt("expiresIn") == 60 && !response.getBoolean("retry"))
        require(response.getJSONObject("report").getString("reportId") == reportId)
        require(equalJson(response.getJSONObject("request"), body))
        require(response.getJSONObject("report").getString("surface") == surface)
        java.util.UUID.fromString(response.getString("confirmationToken"))
        pending = response
        deadline = started + 60000
        return JSONObject().put("report", response.getJSONObject("report")).put("request", response.getJSONObject("request"))
    }
    private fun equalJson(a: JSONObject, b: JSONObject): Boolean = a.keys().asSequence().toSet() == b.keys().asSequence().toSet() && a.keys().asSequence().toSet().all { key ->
        val x = a.get(key); val y = b.get(key)
        if (x is JSONObject && y is JSONObject) equalJson(x, y) else x == y
    }
    val canConfirm: Boolean get() = pending != null && clock() < deadline && !unknown
    fun discard() { pending = null }
    suspend fun confirm(): JSONObject {
        val confirmation = checkNotNull(pending)
        pending = null // Consume before sending, including failure and cancellation.
        check(clock() < deadline && !unknown)
        val body = JSONObject().put("confirmationToken", confirmation.getString("confirmationToken"))
            .put("reportId", confirmation.getJSONObject("report").getString("reportId"))
        return request("POST", "/v1/maintenance/confirm", body.toString())
    }
    private suspend fun request(method: String, path: String, body: String?): JSONObject {
        val mutating = path == "/v1/maintenance/confirm"
        if (mutating) unknown = true
        val result = exchange(method, path, body)
        when (result.getString("outcome")) {
            "notSent" -> { if (mutating) unknown = false; error("not_sent") }
            "unknown" -> error("unknown_outcome")
            "http" -> {
                val status = result.getInt("httpStatus")
                val raw = decode(result.getString("bodyBase64"))
                val json = JSONObject(String(raw, Charsets.UTF_8))
                if (mutating) unknown = status >= 500 || json.optString("outcome") == "unknown" || json.optString("error") == "unknown"
                if (status !in 200..299) { pending = null; error("host_refused_$status") }
                return json
            }
            else -> error("invalid_outcome")
        }
    }
}
