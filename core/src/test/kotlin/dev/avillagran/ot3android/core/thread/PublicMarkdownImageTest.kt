package dev.avillagran.ot3android.core.thread

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class PublicMarkdownImageTest {
    @Test fun publicHttpUsesNoEnvironmentCredentialsAndFollowsBoundedRedirects() {
        ServerSocket(0, 2, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 5000
            val failure = AtomicReference<Throwable?>()
            val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aU1cAAAAASUVORK5CYII=")
            val worker = Thread {
                try {
                    repeat(2) { index -> server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader()
                        val headers = buildString {
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                                appendLine(line)
                            }
                        }.lowercase()
                        assertFalse(headers.contains("authorization:"))
                        assertFalse(headers.contains("dpop:"))
                        val output = socket.getOutputStream()
                        if (index == 0) output.write("HTTP/1.1 302 Found\r\nLocation: /image.png\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        else {
                            output.write("HTTP/1.1 200 OK\r\nContent-Length: ${png.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            output.write(png)
                        }
                        output.flush()
                    } }
                } catch (error: Throwable) { failure.set(error) }
            }.apply { isDaemon = true; start() }
            assertArrayEquals(png, PublicMarkdownImage.read("http://127.0.0.1:${server.localPort}/redirect"))
            worker.join(5000)
            assertFalse(worker.isAlive)
            failure.get()?.let { throw it }
        }
    }

    @Test fun dataLimitAndUnsupportedSourcesFailWithoutNetwork() {
        assertThrows(IllegalArgumentException::class.java) { PublicMarkdownImage.read("data:image/png;base64," + "A".repeat(14 * 1024 * 1024)) }
        assertFalse(PublicMarkdownImage.isPublic("file:///tmp/image.png"))
        assertFalse(PublicMarkdownImage.isPublic("blob:https://example.org/id"))
        assertThrows(IllegalArgumentException::class.java) { PublicMarkdownImage.read("data:image/svg+xml;base64,PHN2Zz4=") }
    }
}
