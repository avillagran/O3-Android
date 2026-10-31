package dev.avillagran.ot3android.core.thread

import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class PublicMarkdownImageTest {
    @Test fun nonPublicV4AndV6RangesAreRejected() {
        for (host in listOf("0.0.0.0", "10.0.0.1", "100.64.0.1", "100.127.255.255", "127.0.0.1", "169.254.169.254",
            "172.16.0.1", "192.168.1.1", "192.0.0.8", "192.0.2.1", "192.88.99.1", "198.18.0.1", "198.51.100.1", "203.0.113.1",
            "224.0.0.1", "239.255.255.255", "255.255.255.255", "::", "::1", "fe80::1", "fc00::1", "ff02::1",
            "::ffff:127.0.0.1", "64:ff9b::a00:1", "2001::1", "2001:db8::1", "2002:7f00:1::", "3fff::1")) {
            assertFalse(host, PublicImageHttp.isPublicAddress(InetAddress.getByName(host)))
        }
        for (host in listOf("8.8.8.8", "1.1.1.1", "100.128.0.1", "172.32.0.1", "2606:4700:4700::1111", "2001:4860:4860::8888")) {
            assertTrue(host, PublicImageHttp.isPublicAddress(InetAddress.getByName(host)))
        }
    }

    @Test fun directPrivateAndMixedDnsAnswersNeverReachConnector() {
        for (addresses in listOf(listOf("127.0.0.1"), listOf("8.8.8.8", "192.168.1.1"), emptyList())) {
            assertThrows(IllegalArgumentException::class.java) {
                PublicImageHttp.read("http://images.example/action", { addresses.map(InetAddress::getByName) }) { _, _, _ ->
                    fail("must not connect"); error("unreachable")
                }
            }
        }
        assertThrows(IllegalArgumentException::class.java) { PublicMarkdownImage.read("http://127.0.0.1:12345/action") }
    }

    @Test fun everyRedirectRevalidatesDnsAndRejectsLanBeforeGet() {
        val visited = mutableListOf<String>()
        assertThrows(IllegalArgumentException::class.java) {
            PublicImageHttp.read("https://images.example/start", { host ->
                listOf(InetAddress.getByName(if (host == "images.example") "8.8.8.8" else "169.254.169.254"))
            }) { uri, _, _ ->
                visited += uri.host
                PublicImageHttp.Reply(302, "http://metadata.example/action", byteArrayOf())
            }
        }
        assertEquals(listOf("images.example"), visited)
    }

    @Test fun sameHostRebindingOnRedirectIsRejectedAndLookupResultsArePinned() {
        var lookups = 0
        var requests = 0
        assertThrows(IllegalArgumentException::class.java) {
            PublicImageHttp.read("https://images.example/start", {
                lookups++
                listOf(InetAddress.getByName(if (lookups == 1) "8.8.8.8" else "127.0.0.1"))
            }) { _, pinned, _ ->
                requests++
                assertEquals("8.8.8.8", pinned.single().hostAddress)
                PublicImageHttp.Reply(307, "/next", byteArrayOf())
            }
        }
        assertEquals(2, lookups)
        assertEquals(1, requests)
    }

    @Test fun connectorUsesSuppliedAddressWithoutResolvingUrlHostAndSendsNoCredentials() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 5000
            val failure = AtomicReference<Throwable?>()
            val worker = Thread {
                try { server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    val headers = buildString {
                        while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; appendLine(line) }
                    }.lowercase()
                    assertTrue(headers.contains("host: never-resolve.invalid:${server.localPort}"))
                    assertFalse(headers.contains("authorization:"))
                    assertFalse(headers.contains("cookie:"))
                    assertFalse(headers.contains("dpop:"))
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 3\r\nConnection: close\r\n\r\nPNG".toByteArray())
                } } catch (error: Throwable) { failure.set(error) }
            }.apply { isDaemon = true; start() }
            // Exercise the real connector with a test-only supplied loopback address.
            val reply = try {
                PublicImageHttp.exchange(URI("http://never-resolve.invalid:${server.localPort}/image"), listOf(InetAddress.getLoopbackAddress()), 5000)
            } catch (error: Throwable) {
                worker.join(5000)
                failure.get()?.let(error::addSuppressed)
                throw error
            }
            assertArrayEquals("PNG".toByteArray(), reply.bytes)
            worker.join(5000)
            assertFalse(worker.isAlive)
            failure.get()?.let { throw it }
        }
    }

    @Test fun redirectsAndCredentialsAreBounded() {
        var requests = 0
        assertThrows(IllegalArgumentException::class.java) {
            PublicImageHttp.read("https://images.example/start", { listOf(InetAddress.getByName("8.8.8.8")) }) { _, _, _ ->
                requests++; PublicImageHttp.Reply(302, "/next", byteArrayOf())
            }
        }
        assertEquals(4, requests)
        for (url in listOf("https://user:pass@images.example/x", "file:///etc/passwd")) {
            assertThrows(IllegalArgumentException::class.java) { PublicImageHttp.read(url, { fail("no DNS"); emptyList() }) }
        }
    }

    @Test fun dataLimitAndUnsupportedSourcesFailWithoutNetwork() {
        assertThrows(IllegalArgumentException::class.java) { PublicMarkdownImage.read("data:image/png;base64," + "A".repeat(14 * 1024 * 1024)) }
        assertFalse(PublicMarkdownImage.isPublic("file:///tmp/image.png"))
        assertFalse(PublicMarkdownImage.isPublic("blob:https://example.org/id"))
        assertThrows(IllegalArgumentException::class.java) { PublicMarkdownImage.read("data:image/svg+xml;base64,PHN2Zz4=") }
    }
}
