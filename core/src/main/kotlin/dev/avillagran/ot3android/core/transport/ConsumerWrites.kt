package dev.avillagran.ot3android.core.transport

import dev.avillagran.t3.T3Exception
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** Exact project/pin batches, checkpointed after each receipt. Never replay on construction. */
class ConsumerWrites(private val directory: File) {
    private val mutex = Mutex()
    private val revisions = MutableStateFlow(0L)
    val changes = revisions.asStateFlow()
    private fun file(environment: String): File {
        val key = java.security.MessageDigest.getInstance("SHA-256").digest(environment.toByteArray()).joinToString("") { "%02x".format(it) }
        return directory.resolve("$key.json")
    }
    fun pending(environment: String): JSONObject? = file(environment).takeIf(File::exists)?.readText()?.let(::JSONObject)
    private fun persist(environment: String, state: JSONObject) {
        directory.mkdirs()
        val target = file(environment); val temporary = directory.resolve(target.name + ".tmp")
        FileOutputStream(temporary).use { it.write(state.toString().toByteArray()); it.fd.sync() }
        check(temporary.renameTo(target))
        revisions.value++
    }
    suspend fun submit(environment: String, transport: T3Transport, function: String, commands: JSONArray) = mutex.withLock {
        require(function in setOf("projects.mutate", "orchestration.dispatchCommand"))
        check(pending(environment) == null) { "command_pending" }
        require(commands.length() > 0)
        for (i in 0 until commands.length()) require(commands.getJSONObject(i).getString("commandId").isNotEmpty())
        val state = JSONObject().put("function", function).put("commands", commands).put("next", 0)
        persist(environment, state)
        send(environment, transport, state)
    }
    suspend fun retry(environment: String, transport: T3Transport) = mutex.withLock {
        pending(environment)?.let { send(environment, transport, it) }
    }
    private suspend fun send(environment: String, transport: T3Transport, state: JSONObject) {
        val commands = state.getJSONArray("commands")
        try {
            while (state.getInt("next") < commands.length()) {
                val index = state.getInt("next")
                transport.call(state.getString("function"), commands.getJSONObject(index))
                state.put("next", index + 1)
                persist(environment, state)
            }
            check(file(environment).delete()); revisions.value++
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: T3Exception) {
            // Definitive refusal releases this batch; unknown keeps its exact suffix.
            if (error.code != "rpc_outcome_unknown") { check(file(environment).delete()); revisions.value++ }
            throw error
        }
    }
}
