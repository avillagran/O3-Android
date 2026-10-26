package dev.avillagran.ot3android.core.pairing

import dev.avillagran.ot3android.core.transport.DomainCalls
import org.json.JSONObject

/**
 * Where to pair and with which one-time code. [pairingCode] is a secret: it is never in
 * [toString]. It may be empty when the link carries no code (`pairBearer` then fails with
 * `invalid_pairing_code`).
 */
data class PairingTarget(val httpBaseUrl: String, val pairingCode: String) {
    override fun toString() = "PairingTarget(httpBaseUrl=$httpBaseUrl, pairingCode=<redacted>)"
}

/** The lib's `connectionCatalog.pairingTarget`: a pairing link split offline, no network. */
object PairingTargets {

    const val FUNCTION = "connectionCatalog.pairingTarget"

    /**
     * Splits a pairing link (or a bare `host[:port]` with a separate [code]) into the server's
     * origin and the pairing code. Accepted forms are the lib's: `https://host/pair#token=…`
     * (what `t3 pair` prints), `…?token=…`, a hosted link naming the server in `?host=`,
     * `ws(s)://…` (mapped to http(s)) and `host:port` (https) with [code]; a non-blank [code]
     * wins over a token in the link. Fails with the lib's `T3Exception` (`invalid_endpoint`,
     * `invalid_pairing_code`). The arguments and the result hold the code: neither is logged
     * or kept here, and only the redacted [PairingTarget] leaves.
     */
    suspend fun parse(domain: DomainCalls, link: String, code: String = ""): PairingTarget {
        val args = JSONObject().put("link", link)
        if (code.isNotEmpty()) args.put("pairingCode", code)
        val result = domain.domain(FUNCTION, args) as JSONObject
        return PairingTarget(result.getString("httpBaseUrl"), result.getString("pairingCode"))
    }
}
