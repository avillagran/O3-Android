package dev.avillagran.ot3android.core.thread

import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request

/** Separate, credential-free HTTP stack. Every hop resolves once, validates ALL answers,
 * then supplies those exact addresses to the socket connector. URL host remains intact
 * for Host, TLS SNI and certificate verification. No system proxy/cookies/authenticator. */
internal object PublicImageHttp {
    internal data class Reply(val status: Int, val location: String?, val bytes: ByteArray)

    fun read(source: String, resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
             exchange: (URI, List<InetAddress>, Int) -> Reply = ::exchange): ByteArray {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        var uri = URI(source)
        repeat(4) { hop ->
            require(uri.scheme in setOf("http", "https") && uri.rawUserInfo == null && uri.host != null)
            val addresses = resolve(uri.host.removePrefix("[").removeSuffix("]"))
            require(addresses.isNotEmpty() && addresses.all(::isPublicAddress)) { "non_public_image_destination" }
            val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).toInt()
            require(remaining > 0)
            val response = exchange(uri, addresses, remaining)
            if (response.status in setOf(301, 302, 303, 307, 308)) {
                require(hop < 3)
                uri = uri.resolve(requireNotNull(response.location))
            } else {
                require(response.status in 200..299)
                return response.bytes
            }
        }
        error("image_redirect_limit")
    }

    internal fun exchange(uri: URI, addresses: List<InetAddress>, timeoutMs: Int): Reply {
        val pool = ConnectionPool(0, 1, TimeUnit.MILLISECONDS)
        val client = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    require(hostname.equals(uri.host.removePrefix("[").removeSuffix("]"), ignoreCase = true))
                    return addresses
                }
            })
            .connectionPool(pool)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .callTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .build()
        try {
            client.newCall(Request.Builder().url(uri.toASCIIString()).build()).execute().use { response ->
                if (response.code in setOf(301, 302, 303, 307, 308)) return Reply(response.code, response.header("Location"), byteArrayOf())
                require(response.code in 200..299)
                val body = requireNotNull(response.body)
                require(body.contentLength() <= PublicMarkdownImage.LIMIT)
                val output = java.io.ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer, 0, minOf(buffer.size, PublicMarkdownImage.LIMIT + 1 - output.size()))
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        require(output.size() <= PublicMarkdownImage.LIMIT)
                    }
                }
                return Reply(response.code, null, output.toByteArray())
            }
        } finally { pool.evictAll(); client.dispatcher.executorService.shutdown() }
    }

    /** Conservative global-unicast allowlist; special-use/transition ranges fail closed.
     * IANA IPv4/IPv6 special registries, checked 2026-10-31. */
    internal fun isPublicAddress(address: InetAddress): Boolean {
        val b = address.address.map { it.toInt() and 255 }
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        if (b.size == 4) return !(b[0] in setOf(0, 10, 127) || b[0] >= 224 ||
            (b[0] == 100 && b[1] in 64..127) || (b[0] == 169 && b[1] == 254) ||
            (b[0] == 172 && b[1] in 16..31) ||
            (b[0] == 192 && (b[1] == 0 && b[2] in setOf(0, 2) || b[1] == 168 || b[1] == 88 && b[2] == 99)) ||
            (b[0] == 198 && (b[1] in 18..19 || b[1] == 51 && b[2] == 100)) ||
            (b[0] == 203 && b[1] == 0 && b[2] == 113))
        // Only 2000::/3 global unicast, excluding protocol assignments, Teredo/6to4,
        // documentation and reserved 3fff::/20. Also excludes NAT64 and mapped IPv4.
        return b.size == 16 && b[0] in 0x20..0x3f &&
            !(b[0] == 0x20 && (b[1] == 0x01 && (b[2] <= 1 || b[2] == 0x0d && b[3] == 0xb8) || b[1] == 0x02)) &&
            !(b[0] == 0x3f && b[1] == 0xff && b[2] < 0x10)
    }
}
