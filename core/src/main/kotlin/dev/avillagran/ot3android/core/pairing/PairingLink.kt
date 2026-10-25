// Temporary until lib-t3-rust exposes connection_catalog::pairing_target (LIB_REQUESTS phase-1 item 6).
//
// A port of `pairing_target` (lib-t3-rust v0.3.2, src/connection_catalog.rs:5-58): the same
// accepted forms, the same order of checks and the same error codes. That function parses with
// the WHATWG URL parser (the `url` crate); this file re-implements the part of it the function
// depends on. Known differences, all on inputs no T3 link produces: hosts are mapped to ASCII
// with java.net.IDN (IDNA 2003) instead of UTS #46, and a host that is neither a domain, an
// IPv4 address nor a bracketed IPv6 literal is rejected a little more strictly. Do not grow
// this file; delete it when the lib's `pairingTarget` operation lands.
package dev.avillagran.ot3android.core.pairing

import dev.avillagran.t3.T3Exception
import java.io.ByteArrayOutputStream
import java.net.IDN
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Where to pair and with which one-time code. [pairingCode] is a secret: it is never in
 * [toString]. It may be empty when the link carries no code (`pairBearer` then fails with
 * `invalid_pairing_code`).
 */
data class PairingTarget(val httpBaseUrl: String, val pairingCode: String) {
    override fun toString() = "PairingTarget(httpBaseUrl=$httpBaseUrl, pairingCode=<redacted>)"
}

object PairingLink {

    /**
     * Splits a pairing link (or a bare `host[:port]` with a separate [code]) into the server's
     * origin and the pairing code, offline. Accepted forms: `https://host/pair#token=…` (what
     * `t3 pair` prints), `…?token=…`, a hosted link naming the server in `?host=` with the code
     * in the fragment, `ws(s)://…` (mapped to http(s)), and `host:port` (https) with [code].
     * A non-blank [code] wins over a token in the link. Fails with [T3Exception] `invalid_endpoint`
     * (not an http(s)/ws(s) origin, user info, port 0) or `invalid_pairing_code`.
     */
    fun parse(raw: String, code: String = ""): PairingTarget {
        val input = raw.trim()
        if (input.isEmpty() || input.utf8Size() > MAX_LENGTH || input.any(Char::isISOControl)) throw invalidEndpoint()
        var url = normalize(input)
        val fragment = url.fragment?.let { queryPairs(it.substringBefore('#')) }
        val credential = code.trim().ifEmpty {
            fragment?.firstValue("token") ?: url.query?.let(::queryPairs)?.firstValue("token") ?: ""
        }
        if (credential.utf8Size() > MAX_LENGTH || credential.any(Char::isISOControl)) {
            throw T3Exception("invalid_pairing_code")
        }
        val host = url.query?.let(::queryPairs)?.firstValue("host")
        if (!host.isNullOrEmpty() && credential.isNotEmpty()) url = normalize(host)
        val scheme = when (url.scheme) {
            "http", "ws" -> "http"
            "https", "wss" -> "https"
            else -> throw invalidEndpoint()
        }
        if (url.host.isNullOrEmpty() || url.username.isNotEmpty() || url.password.isNotEmpty() || url.port == 0) {
            throw invalidEndpoint()
        }
        // Changing the scheme drops a port that is the new scheme's default, as the URL standard does.
        val port = url.port?.takeIf { it != DEFAULT_PORTS[scheme] }
        return PairingTarget("$scheme://${url.host}${port?.let { ":$it" } ?: ""}", credential)
    }

    private const val MAX_LENGTH = 16384

    private fun invalidEndpoint() = T3Exception("invalid_endpoint")

    private fun normalize(value: String): WebUrl =
        WebUrl.parse(if ("://" in value) value else "https://$value") ?: throw invalidEndpoint()

    private fun String.utf8Size() = toByteArray(Charsets.UTF_8).size

    private fun List<Pair<String, String>>.firstValue(key: String) = firstOrNull { it.first == key }?.second

    /** `application/x-www-form-urlencoded` pairs, as the `url` crate's `query_pairs`. */
    private fun queryPairs(query: String): List<Pair<String, String>> = query.split('&')
        .filter { it.isNotEmpty() }
        .map { pair -> formDecode(pair.substringBefore('=')) to formDecode(pair.substringAfter('=', "")) }

    private fun formDecode(value: String) = percentDecode(value.replace('+', ' '))

    /** Percent-decodes into UTF-8, leaving malformed escapes as they are and replacing bad bytes. */
    private fun percentDecode(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream(bytes.size)
        var i = 0
        while (i < bytes.size) {
            val hi = if (i + 2 < bytes.size && bytes[i] == '%'.code.toByte()) hex(bytes[i + 1]) else -1
            val lo = if (hi >= 0) hex(bytes[i + 2]) else -1
            if (hi >= 0 && lo >= 0) {
                out.write(hi * 16 + lo)
                i += 3
            } else {
                out.write(bytes[i].toInt())
                i++
            }
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }

    private fun hex(byte: Byte): Int = Character.digit(byte.toInt().toChar(), 16)

    private val DEFAULT_PORTS = mapOf("http" to 80, "https" to 443, "ws" to 80, "wss" to 443, "ftp" to 21)

    /** The parts of a WHATWG URL that `pairing_target` reads. Query and fragment stay encoded. */
    private class WebUrl(
        val scheme: String,
        val username: String,
        val password: String,
        val host: String?,
        val port: Int?,
        val query: String?,
        val fragment: String?,
    ) {
        companion object {
            private val SPECIAL = setOf("http", "https", "ws", "wss", "ftp", "file")
            private val SCHEME = Regex("[A-Za-z][A-Za-z0-9+.-]*")
            private const val FORBIDDEN_HOST = "\u0000\t\n\r #/:<>?@[\\]^|"
            private const val FORBIDDEN_DOMAIN = "$FORBIDDEN_HOST%\u007f"

            /** Null when the URL standard's parser (without a base URL) fails. */
            fun parse(value: String): WebUrl? {
                val input = value.trim { it <= ' ' }.filterNot { it == '\t' || it == '\n' || it == '\r' }
                val colon = input.indexOf(':')
                if (colon <= 0 || !SCHEME.matches(input.substring(0, colon))) return null
                val scheme = input.substring(0, colon).lowercase()
                var rest = input.substring(colon + 1)
                val fragment = rest.substringAfter('#', "").takeIf { '#' in rest }
                rest = rest.substringBefore('#')
                val query = rest.substringAfter('?', "").takeIf { '?' in rest }
                rest = rest.substringBefore('?')
                val special = scheme in SPECIAL
                val slash: (Char) -> Boolean = if (special) { c -> c == '/' || c == '\\' } else { c -> c == '/' }
                if (!special && !rest.startsWith("//")) {
                    return WebUrl(scheme, "", "", null, null, query, fragment)
                }
                if (scheme == "file") {
                    // Only the scheme matters for file URLs here: pairing_target rejects them.
                    return WebUrl(scheme, "", "", null, null, query, fragment)
                }
                rest = rest.dropWhile(slash)
                val authority = rest.takeWhile { !slash(it) }
                val at = authority.lastIndexOf('@')
                val userinfo = if (at >= 0) authority.substring(0, at) else ""
                val hostPort = authority.substring(at + 1)
                val (hostText, portText) = splitHostPort(hostPort) ?: return null
                val port = when {
                    portText == null || portText.isEmpty() -> null
                    portText.all { it in '0'..'9' } -> portText.trimStart('0').ifEmpty { "0" }
                        .takeIf { it.length <= 5 }?.toInt()?.takeIf { it <= 65535 } ?: return null
                    else -> return null
                }
                if (special && hostText.isEmpty()) return null
                val host = if (special) parseHost(hostText) ?: return null else hostText
                return WebUrl(
                    scheme = scheme,
                    username = userinfo.substringBefore(':'),
                    password = userinfo.substringAfter(':', ""),
                    host = host,
                    port = port?.takeIf { it != DEFAULT_PORTS[scheme] },
                    query = query,
                    fragment = fragment,
                )
            }

            /** `host` and the text after its `:`; brackets keep an IPv6 literal's colons. */
            private fun splitHostPort(value: String): Pair<String, String?>? {
                if (value.startsWith('[')) {
                    val end = value.indexOf(']')
                    if (end < 0) return null
                    val after = value.substring(end + 1)
                    if (after.isNotEmpty() && !after.startsWith(':')) return null
                    return value.substring(0, end + 1) to after.takeIf { it.isNotEmpty() }?.substring(1)
                }
                val colon = value.indexOf(':')
                return if (colon < 0) value to null else value.substring(0, colon) to value.substring(colon + 1)
            }

            private fun parseHost(value: String): String? {
                if (value.startsWith('[')) return parseIpv6(value.removePrefix("[").removeSuffix("]"))
                val decoded = percentDecode(value)
                val ascii = runCatching { IDN.toASCII(decoded, IDN.ALLOW_UNASSIGNED) }.getOrNull()?.lowercase()
                if (ascii.isNullOrEmpty() || ascii.any { it in FORBIDDEN_DOMAIN || it.isISOControl() }) return null
                return if (endsInNumber(ascii)) parseIpv4(ascii) else ascii
            }

            private fun endsInNumber(host: String): Boolean {
                val labels = host.split('.').let { if (it.size > 1 && it.last().isEmpty()) it.dropLast(1) else it }
                val last = labels.last()
                return (last.isNotEmpty() && last.all { it in '0'..'9' }) || parseNumber(last) != null
            }

            /** The URL standard's IPv4 parser: 1 to 4 parts, decimal, `0x` hex or `0` octal. */
            private fun parseIpv4(host: String): String? {
                val parts = host.split('.').let { if (it.size > 1 && it.last().isEmpty()) it.dropLast(1) else it }
                if (parts.size > 4) return null
                val numbers = parts.map { parseNumber(it) ?: return null }
                if (numbers.dropLast(1).any { it > 255 }) return null
                val last = numbers.last()
                if (last >= 1L shl (8 * (5 - numbers.size))) return null
                var address = last
                numbers.dropLast(1).forEachIndexed { i, n -> address += n shl (8 * (3 - i)) }
                return (3 downTo 0).joinToString(".") { ((address shr (8 * it)) and 0xff).toString() }
            }

            private fun parseNumber(part: String): Long? {
                if (part.isEmpty()) return null
                val (digits, radix) = when {
                    part.length >= 2 && (part.startsWith("0x") || part.startsWith("0X")) -> part.substring(2) to 16
                    part.length >= 2 && part.startsWith("0") -> part.substring(1) to 8
                    else -> part to 10
                }
                if (digits.isEmpty()) return 0
                if (digits.length > 16) return null
                return digits.toLongOrNull(radix)?.takeIf { it >= 0 }
            }

            /** A bracketed IPv6 literal, serialized as the URL standard does (RFC 5952 compression). */
            private fun parseIpv6(literal: String): String? {
                if (literal.isEmpty() || literal.any { !(it.isLetterOrDigit() && it.code < 128) && it != ':' && it != '.' }) return null
                if (literal.any { it.isLetter() && it.lowercaseChar() !in 'a'..'f' }) return null
                // Only hex digits, colons and dots: InetAddress parses it as a literal, no lookup. It
                // returns an IPv4-mapped literal (::ffff:a.b.c.d) as IPv4; map it back.
                val bytes = when (val address = runCatching { InetAddress.getByName(literal) }.getOrNull()) {
                    is Inet6Address -> address.address
                    is Inet4Address -> ByteArray(10) + byteArrayOf(-1, -1) + address.address
                    else -> return null
                }
                val pieces = IntArray(8) { ((bytes[2 * it].toInt() and 0xff) shl 8) or (bytes[2 * it + 1].toInt() and 0xff) }
                var bestStart = -1
                var bestLength = 1
                var i = 0
                while (i < 8) {
                    if (pieces[i] == 0) {
                        val start = i
                        while (i < 8 && pieces[i] == 0) i++
                        if (i - start > bestLength) { bestStart = start; bestLength = i - start }
                    } else {
                        i++
                    }
                }
                val text = StringBuilder("[")
                i = 0
                while (i < 8) {
                    if (i == bestStart) {
                        text.append(if (i == 0) "::" else ":")
                        i += bestLength
                        continue
                    }
                    text.append(Integer.toHexString(pieces[i]))
                    if (i < 7) text.append(':')
                    i++
                }
                return text.append(']').toString()
            }
        }
    }
}
