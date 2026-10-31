package dev.avillagran.ot3android.core.transport

import dev.avillagran.t3.T3Exception
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference

/** Actual JNI signed grant + same-origin binary GET; all server data is synthetic. */
class NativeImageTest {
    @Test fun binaryGrantPreservesBytesAndDoesNotForwardBearerToSignedGet() {
        assumeHostJni()
        val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aU1cAAAAASUVORK5CYII=")
        serve("/api/assets/signed/shot.png?sig=fixture", png) { transport ->
            assertArrayEquals(png, runBlocking { transport.image("att-1", "shot.png", "image/png") })
        }
    }

    @Test fun foreignGrantIsRefusedBeforeFetchingBytes() {
        assumeHostJni()
        serve("https://foreign.invalid/api/assets/shot.png", null) { transport ->
            val error = assertThrows(T3Exception::class.java) {
                runBlocking { transport.image("att-1", "shot.png", "image/png") }
            }
            assertEquals("invalid_endpoint", error.code)
        }
    }

    @Test fun hostImageUsesThreadScopedGrantWithoutAttachmentMetadata() {
        assumeHostJni()
        val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aU1cAAAAASUVORK5CYII=")
        for (path in listOf("/home/avillagran/Work/gliff-fork.VaT72V/.github/assets/screenshots/control-driving.png", "assets/shot.png")) {
            serve("/api/assets/signed/shot.png?sig=fixture", png, path) { transport ->
                assertArrayEquals(png, runBlocking { transport.mediaImage("thread-1", path) })
            }
        }
    }

    @Test fun hostImageRejectsForeignGrant() {
        assumeHostJni()
        serve("https://foreign.invalid/api/assets/shot.png", null, "shot.png") { transport ->
            assertThrows(T3Exception::class.java) { runBlocking { transport.mediaImage("thread-1", "shot.png") } }
        }
    }

    private fun serve(relative: String, bytes: ByteArray?, hostPath: String? = null, read: (NativeT3Transport) -> Unit) {
        ServerSocket(0, 2, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 5_000
            val failure = AtomicReference<Throwable?>()
            val thread = Thread {
                try {
                    server.accept().use { socket ->
                        val peer = FakeWebSocketServer.Peer(socket)
                        val request = checkNotNull(peer.read())
                        assertEquals("assets.createUrl", request.getString("tag"))
                        val resource = request.getJSONObject("payload").getJSONObject("resource")
                        if (hostPath != null) {
                            assertEquals("media-file", resource.getString("_tag"))
                            assertEquals("thread-1", resource.getString("threadId"))
                            assertEquals(hostPath, resource.getString("path"))
                            assertFalse(resource.has("attachmentId"))
                        } else {
                        assertEquals("attachment", resource.getString("_tag"))
                        assertEquals("att-1", resource.getString("attachmentId"))
                        assertEquals("shot.png", resource.getString("fileName"))
                        assertEquals("image/png", resource.getString("mimeType"))
                        assertEquals("inline", resource.getString("disposition"))
                        }
                        peer.send(JSONObject().put("_tag", "Exit").put("requestId", request.getString("id"))
                            .put("exit", JSONObject().put("_tag", "Success").put("value", JSONObject().put("relativeUrl", relative))))
                    }
                    if (bytes != null) server.accept().use { socket ->
                        val input = socket.getInputStream()
                        val head = StringBuilder()
                        while (!head.endsWith("\r\n\r\n")) head.append(input.read().also { check(it >= 0) }.toChar())
                        assertTrue(head.startsWith("GET $relative HTTP/1.1"))
                        assertFalse(head.toString().lowercase().contains("authorization:"))
                        assertFalse(head.toString().lowercase().contains("dpop:"))
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes); flush()
                        }
                    }
                } catch (error: Throwable) { failure.set(error) }
            }.apply { isDaemon = true; start() }
            val transport = NativeT3Transport()
            try {
                runBlocking { transport.connect(ConnectRoute.Bearer("http://127.0.0.1:${server.localPort}", "fixture-image-token")) }
                read(transport)
                thread.join(5_000)
                assertFalse(thread.isAlive)
                failure.get()?.let { throw it }
                if (bytes == null) {
                    server.soTimeout = 150
                    assertThrows(java.net.SocketTimeoutException::class.java) { server.accept() }
                }
            } finally { runBlocking { transport.shutdown() } }
        }
    }
}
