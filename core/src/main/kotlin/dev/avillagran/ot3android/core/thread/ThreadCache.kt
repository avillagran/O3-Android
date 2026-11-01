package dev.avillagran.ot3android.core.thread

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Opaque Rust projection + its cursor/history. No messages are merged in this storage layer. */
class ThreadCache(
    private val directory: File,
    private var maxBytes: Long = 200L * 1024 * 1024,
    private val maxEntries: Int = 128,
    private val maxEntryBytes: Long = 8L * 1024 * 1024,
    private val maxAgeMs: Long = 7L * 24 * 60 * 60 * 1000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Lease internal constructor(val environment: String, val thread: String, val authority: String, internal val token: String)
    private val mutex = Mutex()
    private var pinned = emptySet<String>()
    private var environments: Set<String>? = null
    data class Usage(val bytes: Long, val limit: Long, val entries: Int)
    suspend fun usage(): Usage = locked {
        val files = directory.listFiles()?.filter { it.extension == "json" }.orEmpty()
        Usage(files.sumOf { it.length() }, maxBytes, files.size)
    }
    suspend fun configure(bytes: Long) = locked {
        maxBytes = bytes.coerceIn(10L * 1024 * 1024, 1024L * 1024 * 1024)
        prune()
    }
    /** Only disposable projections live here; Room drafts/outbox are never opened. */
    suspend fun clear() = locked {
        owners.clear()
        directory.listFiles()?.filter { it.extension in setOf("json", "tmp") }?.forEach { it.delete() }
    }
    suspend fun retainEnvironments(ids: Set<String>) = locked {
        val removed = environments.orEmpty() - ids
        environments = ids
        if (removed.isNotEmpty()) owners.clear() // Invalidate in-flight writes when the environment set changes.
        directory.listFiles()?.filter { it.extension == "json" }?.forEach { file ->
            val record = readRecord(file)
            if (record == null || record.optString("environment") !in ids) file.delete()
        }
    }
    suspend fun pin(environment: String, threads: Set<String>) = locked {
        val prefix = "$environment/"
        pinned = pinned.filterNot { it.startsWith(prefix) }.toSet() + threads.map { prefix + it }
    }
    private val owners = mutableMapOf<String, String>()
    private val allowed = mutableMapOf<String, Set<String>>()

    suspend fun trim() = locked {
        directory.listFiles()?.filter { it.extension == "tmp" }?.forEach { it.delete() }
        prune()
    }

    suspend fun open(environment: String, thread: String, authority: String): Lease? = locked {
        prune()
        if (environments?.contains(environment) == false) return@locked null
        if (thread != SIDEBAR && allowed[environment]?.contains(thread) == false) return@locked null
        Lease(environment, thread, authority, UUID.randomUUID().toString()).also { owners[key(environment, thread)] = it.token }
    }

    suspend fun read(lease: Lease): JSONObject? = locked {
        if (owners[key(lease.environment, lease.thread)] != lease.token) return@locked null
        val file = file(lease.environment, lease.thread)
        val record = readRecord(file) ?: return@locked null
        if (record.optString("environment") != lease.environment || record.optString("thread") != lease.thread ||
            record.optString("authority") != lease.authority || record.optInt("version") != 1 ||
            clock() - record.optLong("savedAt") > maxAgeMs) {
            file.delete()
            return@locked null
        }
        file.setLastModified(clock())
        record.optJSONObject("snapshot")
    }

    suspend fun write(lease: Lease, snapshot: JSONObject) = locked {
        val key = key(lease.environment, lease.thread)
        if (owners[key] != lease.token || (lease.thread != SIDEBAR && allowed[lease.environment]?.contains(lease.thread) == false)) return@locked
        val bounded = JSONObject(snapshot.toString())
        val projection = bounded.optJSONObject("projection")
        var windowed = false
        listOf("messages", "turnItems", "runs").forEach { field ->
            val items = projection?.optJSONArray(field)
            if (items != null && items.length() > 500) {
                projection.put(field, org.json.JSONArray().also { tail ->
                    for (i in items.length() - 500 until items.length()) tail.put(items.get(i))
                })
                windowed = true
            }
        }
        if (windowed) {
            // The original history cursor precedes omitted messages. Never resume that
            // cursor or paginate through the resulting gap; refresh the bounded snapshot.
            bounded.put("cacheWindowed", true).put("hasMoreHistory", false)
            bounded.remove("historyCursor")
        }
        val bytes = JSONObject().put("version", 1).put("environment", lease.environment).put("thread", lease.thread)
            .put("authority", lease.authority).put("savedAt", clock()).put("snapshot", bounded).toString().toByteArray(Charsets.UTF_8)
        val target = file(lease.environment, lease.thread)
        if (bytes.size > maxEntryBytes || bytes.size > maxBytes) { target.delete(); return@locked }
        directory.mkdirs()
        val temp = File(directory, target.name + ".tmp")
        try {
            temp.writeBytes(bytes)
            if (!temp.renameTo(target)) temp.delete()
        } finally { temp.delete() }
        target.setLastModified(clock())
        prune()
    }

    suspend fun close(lease: Lease) = locked {
        val key = key(lease.environment, lease.thread)
        if (owners[key] == lease.token) owners.remove(key)
    }

    /** Allowed ids come from Rust sidebar.view; closing/archiving invalidates in-flight writes. */
    suspend fun reconcile(environment: String, openThreads: Set<String>) = locked {
        if (allowed[environment] == openThreads) return@locked
        allowed[environment] = openThreads
        directory.listFiles()?.filter { it.extension == "json" }?.forEach { candidate ->
            val record = readRecord(candidate) ?: return@forEach
            if (record.optString("environment") == environment && record.optString("thread") != SIDEBAR && record.optString("thread") !in openThreads) {
                owners.remove(key(environment, record.optString("thread")))
                candidate.delete()
            }
        }
        prune()
    }

    suspend fun remove(lease: Lease) = locked {
        if (owners[key(lease.environment, lease.thread)] != lease.token) return@locked
        file(lease.environment, lease.thread).delete()
    }

    suspend fun readSidebar(environment: String, authority: String): JSONObject? {
        val lease = open(environment, SIDEBAR, authority) ?: return null
        return try { read(lease) } finally { close(lease) }
    }
    suspend fun writeSidebar(environment: String, authority: String, shell: JSONObject) {
        val lease = open(environment, SIDEBAR, authority) ?: return
        try { write(lease, shell) } finally { close(lease) }
    }
    companion object { private const val SIDEBAR = "\u0000sidebar" }

    private fun readRecord(file: File): JSONObject? {
        if (!file.isFile || file.length() > maxEntryBytes || file.length() > maxBytes) { file.delete(); return null }
        return try { JSONObject(file.readText()) } catch (_: Exception) { file.delete(); null }
    }

    private fun prune() {
        var total = 0L
        var count = 0
        directory.listFiles()?.filter { it.extension == "json" }?.sortedWith(compareByDescending<File> {
            val record = readRecord(it)
            record != null && "${record.optString("environment")}/${record.optString("thread")}" in pinned
        }.thenByDescending { it.lastModified() })?.forEach { file ->
            val size = file.length()
            if (clock() - file.lastModified() > maxAgeMs || size > maxEntryBytes || total + size > maxBytes || count >= maxEntries) file.delete()
            else { total += size; count++ }
        }
    }

    private fun file(environment: String, thread: String) = File(directory, key(environment, thread) + ".json")
    private fun key(environment: String, thread: String): String = MessageDigest.getInstance("SHA-256")
        .digest("${environment.length}:$environment$thread".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private suspend fun <T> locked(block: () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }
}
