package dev.avillagran.ot3android.core.transport

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException

/**
 * Minimal HTTP/1.1 server on 127.0.0.1 for JVM tests: one request per connection, answered by
 * [handle] from the request path and headers (lower-case names), or from the whole [Request]
 * with [withRequests]. Android unit tests compile against android.jar, which has no
 * `com.sun.net.httpserver`.
 */
internal class FakeHttpServer private constructor(private val handle: (Request) -> Response) : Closeable {

    constructor(handle: (path: String, headers: Map<String, String>) -> Response) :
        this({ request: Request -> handle(request.path, request.headers) })

    class Response(val status: Int, val body: String = "")

    /** One request: method, path with query, headers (lower-case names) and the body as UTF-8. */
    class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    companion object {
        /** A server whose [handle] also sees the method and the body. */
        fun withRequests(handle: (Request) -> Response) = FakeHttpServer(handle)
    }

    private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())

    val baseUrl = "http://127.0.0.1:${server.localPort}"

    init {
        Thread {
            while (true) {
                val socket = try { server.accept() } catch (closed: SocketException) { break }
                socket.use {
                    it.soTimeout = 5000
                    val input = it.getInputStream()
                    val head = StringBuilder()
                    while (!head.endsWith("\r\n\r\n")) head.append(input.read().also { b -> check(b >= 0) }.toChar())
                    val lines = head.lines()
                    val (method, path) = lines.first().split(' ')
                    val headers = lines.drop(1).filter { line -> ':' in line }
                        .associate { line -> line.substringBefore(':').trim().lowercase() to line.substringAfter(':').trim() }
                    val length = headers["content-length"]?.toInt() ?: 0
                    val body = ByteArray(length).also { bytes ->
                        var read = 0
                        while (read < length) read += input.read(bytes, read, length - read).also { n -> check(n >= 0) }
                    }
                    val response = handle(Request(method, path, headers, body.toString(Charsets.UTF_8)))
                    val answer = response.body.toByteArray(Charsets.UTF_8)
                    it.getOutputStream().apply {
                        write(
                            ("HTTP/1.1 ${response.status} Fixture\r\nContent-Type: application/json\r\n" +
                                "Content-Length: ${answer.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII),
                        )
                        write(answer)
                        flush()
                    }
                }
            }
        }.apply { isDaemon = true; start() }
    }

    override fun close() = server.close()
}
