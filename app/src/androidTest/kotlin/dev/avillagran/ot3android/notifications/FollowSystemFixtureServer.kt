package dev.avillagran.ot3android.notifications

import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Loopback-only synthetic Effect RPC/HTTP server. The app uses its actual JNI transport. */
internal class FollowSystemFixtureServer(val label: String, val serverId: String) : Closeable {
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newCachedThreadPool()
    private val sockets = CopyOnWriteArrayList<Socket>()
    private val shellPeers = CopyOnWriteArrayList<Peer>()
    val shellSubscriptions = AtomicInteger()
    val activeShellSubscriptions: Int get() = shellPeers.size
    val writes = AtomicInteger()
    val requests = CopyOnWriteArrayList<String>()
    val failures = CopyOnWriteArrayList<String>()
    val url = "http://127.0.0.1:${server.localPort}"
    private var sequence = 1L
    @Volatile private var rows = listOf(thread("review-one", "Review private workspace", "approval"), thread("review-two", "Check private changes", "approval"), thread("visible", "Visible thread", "running"))
    init {
        workers.execute {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                sockets.add(socket)
                workers.execute { try { handle(socket) } catch (error: Exception) { failures.add(error.javaClass.simpleName) } finally { sockets.remove(socket); socket.close() } }
            }
        }
    }
    @Synchronized fun republish(titleSuffix: String? = null) {
        sequence++
        if (titleSuffix != null) rows = rows.map { JSONObject(it.toString()).put("title", it.getString("title").substringBefore(" · update") + titleSuffix) }
        val value = JSONObject().put("kind", "snapshot").put("snapshot", shell())
        shellPeers.forEach { runCatching { it.chunk(value) } }
    }
    @Synchronized fun publish(phase: String, visiblePhase: String = "running") {
        sequence++
        rows = listOf(thread("review-one", "Review private workspace", phase), thread("review-two", "Check private changes", phase), thread("visible", "Visible thread", visiblePhase))
        val value = JSONObject().put("kind", "snapshot").put("snapshot", shell())
        shellPeers.forEach { runCatching { it.chunk(value) } }
    }
    @Synchronized fun publishThread(id: String, phase: String) {
        sequence++
        rows = rows.map { if (it.getString("id") == id) thread(id, if (id == "review-one") "Review private workspace" else "Check private changes", phase) else it }
        val value = JSONObject().put("kind", "snapshot").put("snapshot", shell())
        shellPeers.forEach { runCatching { it.chunk(value) } }
    }
    @Synchronized fun publishVisible(phase: String) {
        sequence++
        rows = rows.take(2) + thread("visible", "Visible thread", phase)
        val value = JSONObject().put("kind", "snapshot").put("snapshot", shell())
        shellPeers.forEach { runCatching { it.chunk(value) } }
    }
    @Synchronized fun shell() = JSONObject().put("snapshotSequence", sequence)
        .put("projects", JSONArray().put(JSONObject().put("id", "project").put("title", "Private lab project").put("workspaceRoot", "/synthetic/follow-lab")))
        .put("threads", JSONArray(rows.map { JSONObject(it.toString()) }))
    private fun thread(id: String, title: String, phase: String): JSONObject {
        val now = Instant.now().toString()
        return JSONObject().put("id", id).put("title", "$label · $title").put("projectId", "project")
            .put("status", if (phase == "completed") "completed" else "running").put("latestRunId", "run-$id")
            .put("createdAt", now).put("updatedAt", now).put("activityRunStartedAt", Instant.now().minusSeconds(90).toString())
            .put("latestRunRequestedAt", Instant.now().minusSeconds(90).toString()).put("lineage", JSONObject())
            .apply {
                if (phase == "approval") put("pendingRuntimeRequest", JSONObject().put("id", "request-$id").put("kind", "command_approval").put("createdAt", now))
                if (phase == "completed") put("latestRunCompletedAt", now).put("latestAssistantMessageAt", now)
            }
    }
    private fun bounded(id: String) = JSONObject().put("snapshotSequence", sequence).put("projection",
        JSONObject(rows.first { it.getString("id") == id }.toString()).put("messages", JSONArray().put(JSONObject()
            .put("id", "message-lab").put("role", "assistant").put("text", "Synthetic follow lab reader").put("createdAt", Instant.now().toString()))))
    private fun handle(socket: Socket) {
        socket.soTimeout = 120_000
        val input = DataInputStream(socket.getInputStream().buffered())
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) head.append(input.readUnsignedByte().toChar())
        val path = head.lineSequence().first().split(' ')[1]
        requests.add(path.substringBefore('?'))
        if (path.startsWith("/ws")) {
            val key = head.lines().first { it.startsWith("Sec-WebSocket-Key:", true) }.substringAfter(':').trim()
            val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
            socket.getOutputStream().apply { write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n").toByteArray()); flush() }
            val peer = Peer(socket, input)
            val request = peer.read() ?: return
            peer.id = request.opt("id") ?: "1"
            val tag = request.optString("tag")
            requests.add(tag)
            when (tag) {
                "orchestration.subscribeShell" -> {
                    shellSubscriptions.incrementAndGet(); shellPeers.add(peer)
                    try { peer.chunk(JSONObject().put("kind", "snapshot").put("snapshot", shell()), JSONObject().put("kind", "synchronized")); peer.drain() }
                    finally { shellPeers.remove(peer) }
                }
                "orchestration.subscribeThread" -> {
                    val id = request.getJSONObject("payload").getString("threadId")
                    peer.chunk(bounded(id).put("kind", "snapshot"), JSONObject().put("kind", "synchronized")); peer.drain()
                }
                "server.subscribeServerConfig" -> { peer.chunk(JSONObject().put("providers", JSONArray()).put("providerInstances", JSONArray())); peer.drain() }
                else -> {
                    if (tag == "orchestration.dispatchCommand") writes.incrementAndGet()
                    peer.send(JSONObject().put("_tag", "Exit").put("requestId", peer.id).put("exit", JSONObject().put("_tag", "Success").put("value", JSONObject())))
                }
            }
        } else {
            val body = when {
                path == "/.well-known/t3/environment" -> JSONObject().put("environmentId", serverId).put("label", label)
                path == "/api/orchestration/shell" -> shell()
                path.startsWith("/api/orchestration/threads/") && path.substringBefore('?').endsWith("/bounded") -> bounded(path.substringAfter("/threads/").substringBefore('/'))
                else -> null
            }?.toString()?.toByteArray() ?: ByteArray(0)
            socket.getOutputStream().apply { write(("HTTP/1.1 ${if (body.isEmpty()) 404 else 200} Fixture\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray()); write(body); flush() }
        }
    }
    private class Peer(private val socket: Socket, private val input: DataInputStream) {
        var id: Any = "1"
        fun chunk(vararg items: JSONObject) = send(JSONObject().put("_tag", "Chunk").put("requestId", id).put("values", JSONArray(items.toList())))
        fun drain() { while (true) { val frame = read() ?: break; when (frame.optString("_tag")) { "Interrupt" -> return; "Ping" -> send(JSONObject().put("_tag", "Pong")) } } }
        fun read(): JSONObject? {
            while (true) {
                val first = input.readUnsignedByte(); val second = input.readUnsignedByte()
                var length = (second and 127).toLong()
                if (length == 126L) length = input.readUnsignedShort().toLong() else if (length == 127L) length = input.readLong()
                require(length <= 2_000_000)
                val mask = ByteArray(4); if (second and 128 != 0) input.readFully(mask)
                val bytes = ByteArray(length.toInt()); input.readFully(bytes)
                if (second and 128 != 0) for (i in bytes.indices) bytes[i] = (bytes[i].toInt() xor mask[i % 4].toInt()).toByte()
                if (first and 15 == 8) return null
                if (first and 15 == 1) return JSONObject(bytes.toString(Charsets.UTF_8))
            }
        }
        @Synchronized fun send(value: JSONObject) {
            val bytes = value.toString().toByteArray()
            socket.getOutputStream().apply {
                write(129)
                if (bytes.size < 126) write(bytes.size) else { write(126); write(bytes.size shr 8); write(bytes.size and 255) }
                write(bytes); flush()
            }
        }
    }
    override fun close() { server.close(); sockets.forEach { runCatching { it.close() } }; workers.shutdownNow() }
}
