package dev.avillagran.ot3android.core.connection

import dev.avillagran.ot3android.core.transport.ConnectRoute
import dev.avillagran.ot3android.core.transport.T3Transport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI

/** What the connect screen shows from a server's descriptor (`fetchDescriptor`). */
data class ServerDescriptor(
    val environmentId: String?,
    val label: String?,
    val serverVersion: String?,
    val platformOs: String?,
    val orchestrationProtocolVersion: Int?,
) {
    companion object {
        fun from(json: JSONObject) = ServerDescriptor(
            environmentId = json.text("environmentId"),
            label = json.text("label"),
            serverVersion = json.text("serverVersion"),
            platformOs = json.optJSONObject("platform")?.text("os"),
            orchestrationProtocolVersion = if (json.has("orchestrationProtocolVersion")) {
                json.optInt("orchestrationProtocolVersion")
            } else {
                null
            },
        )

        private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key)
    }
}

/**
 * Turns what the user typed into an origin (`scheme://host[:port]`) for the lib, which
 * validates it and rejects anything else with `invalid_endpoint`. Without a scheme, local
 * hosts (loopback, private IPv4, `.local`, `.lan`, bare names) get `http`, others `https`.
 * Returns null when nothing usable was typed; validation itself stays in the lib.
 */
fun normalizeOrigin(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.trimEnd('/').isEmpty()) return null
    val withScheme = if ("://" in trimmed) trimmed else "${defaultScheme(trimmed)}://$trimmed"
    val uri = runCatching { URI(withScheme) }.getOrNull() ?: return withScheme
    val host = uri.host ?: return withScheme
    val port = if (uri.port >= 0) ":${uri.port}" else ""
    return "${uri.scheme.lowercase()}://${host.lowercase()}$port"
}

private fun defaultScheme(hostAndRest: String): String {
    val host = hostAndRest.substringBefore('/').substringBeforeLast(':').lowercase()
    val octets = host.split('.').mapNotNull { it.toIntOrNull() }
    val privateIpv4 = octets.size == 4 && (
        octets[0] == 10 || octets[0] == 127 ||
            (octets[0] == 192 && octets[1] == 168) ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 100 && octets[1] in 64..127)
        )
    val local = host == "localhost" || host.endsWith(".local") || host.endsWith(".lan") || '.' !in host
    return if (privateIpv4 || local) "http" else "https"
}

/**
 * One-shot checks for the connect screen, each on a fresh transport that is closed after.
 * Failures are the lib's `T3Exception`, unchanged.
 */
class ServerProbe(private val transportFactory: () -> T3Transport) {

    /** The descriptor at [baseUrl]: proves it is a T3 server; needs no credential. */
    suspend fun check(baseUrl: String): ServerDescriptor = withTransport { transport ->
        ServerDescriptor.from(transport.fetchDescriptor(baseUrl))
    }

    /**
     * Descriptor, then `connect(Bearer)` (no I/O) and the shell GET, the first request that
     * proves the token (`unauthorized` otherwise). With [environmentId], a different server
     * at that URL fails with `t3_environment_mismatch`.
     */
    suspend fun verify(baseUrl: String, token: String, environmentId: String? = null): ServerDescriptor =
        withTransport { transport ->
            val descriptor = ServerDescriptor.from(transport.fetchDescriptor(baseUrl, environmentId))
            transport.connect(ConnectRoute.Bearer(baseUrl, token))
            transport.request(SHELL_PATH)
            descriptor
        }

    private suspend fun <T> withTransport(block: suspend (T3Transport) -> T): T {
        val transport = transportFactory()
        try {
            return block(transport)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { transport.close() }
        }
    }
}

/** The shell snapshot over HTTP; the lib adds the protocol header. */
const val SHELL_PATH = "/api/orchestration/shell"
