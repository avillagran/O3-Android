package dev.avillagran.ot3android.core.git

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Durable at-most-once admission. Keep one instance per app-private directory in P20.
 * Records contain recovery prompts: use credential-protected filesDir, exclude from backup.
 * Never delete a record to retry an uncertain write. A crash leaves it unresolved.
 */
class GitJournal(private val directory: File) {
    private val lock = locks.getOrPut(directory.canonicalPath) { Any() }
    private fun file(id: String) = File(directory, digest(id) + ".json")

    fun records(): List<JSONObject> = synchronized(lock) { readAll() }
    fun find(id: String): JSONObject? = synchronized(lock) { file(id).takeIf { it.exists() }?.readText()?.let(::JSONObject) }

    /** Persist and fsync before the first network effect; no replacement admission. */
    fun reserve(id: String, checkout: String, record: JSONObject): Boolean = synchronized(lock) {
        directory.mkdirs()
        if (file(id).exists() || readAll().any { it.getString("checkout") == checkout && it.getString("state") !in TERMINAL }) return false
        write(id, JSONObject(record.toString()).put("id", id).put("checkout", checkout).put("state", "unknown"))
        true
    }

    fun transition(id: String, state: String, result: JSONObject? = null) = synchronized(lock) {
        require(state in STATES)
        val record = find(id) ?: error("git_record_missing")
        // Terminal outcomes cannot be overwritten by late receipts.
        if (state !in TRANSITIONS.getValue(record.getString("state"))) return@synchronized
        write(id, record.put("state", state).put("result", result ?: JSONObject.NULL))
    }

    /** Atomically claim draft application across service/view recreations. */
    fun claimRecovery(id: String): JSONObject? = synchronized(lock) {
        val record = find(id) ?: return@synchronized null
        if (record.getString("state") !in setOf("recovery_ready", "recovery_failed")) return@synchronized null
        transition(id, "recovering")
        record
    }

    /** Explicit user/native reconciliation; releases a guard without replaying any effect. */
    fun reviewed(id: String, environment: String, verifiedFinished: Boolean): Boolean = synchronized(lock) {
        if (!verifiedFinished) return@synchronized false
        val record = find(id) ?: return@synchronized false
        val expected = record.optJSONObject("input")?.optString("environment")
            ?: record.optJSONObject("plan")?.optJSONObject("scope")?.optString("environment")
        if (expected != environment || record.getString("state") in TERMINAL) return@synchronized false
        transition(id, "reviewed")
        true
    }

    private fun readAll(): List<JSONObject> {
        if (!directory.exists()) return emptyList()
        val files = directory.listFiles { f -> f.extension == "json" } ?: error("git_journal_unavailable")
        return files.map { JSONObject(it.readText()) }
    }
    private fun write(id: String, value: JSONObject) {
        val target = file(id)
        val temporary = File(directory, target.name + ".tmp")
        FileOutputStream(temporary).use { stream -> stream.write(value.toString().toByteArray()); stream.fd.sync() }
        check(temporary.renameTo(target)) { "git_journal_unavailable" }
    }

    companion object {
        private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
        private val TERMINAL = setOf("complete", "failed", "reviewed")
        private val TRANSITIONS = mapOf(
            "unknown" to setOf("unknown", "pending", "complete", "failed", "superseded", "recovery_ready", "reviewed"),
            "pending" to setOf("pending", "complete", "failed", "superseded", "recovery_ready", "reviewed"),
            "superseded" to setOf("superseded", "reviewed"),
            "recovery_ready" to setOf("recovering", "reviewed"),
            "recovering" to setOf("complete", "recovery_failed", "reviewed"),
            "recovery_failed" to setOf("recovering", "reviewed"),
            "complete" to emptySet(), "failed" to emptySet(), "reviewed" to emptySet(),
        )
        private val STATES = TERMINAL + setOf("unknown", "pending", "superseded", "recovery_ready", "recovering", "recovery_failed")
        fun checkout(environment: String, cwd: String) = JSONArray().put(environment).put(cwd).toString()
        private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
