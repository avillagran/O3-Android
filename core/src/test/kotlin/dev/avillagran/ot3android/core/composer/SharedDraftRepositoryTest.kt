package dev.avillagran.ot3android.core.composer

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SharedDraftRepositoryTest {
    private val scope = JSONObject("""{"environment":"env","project":"project","kind":"thread","id":"thread"}""")
    private fun put(text: String) = JSONObject().put("op", "put").put("scope", scope)
        .put("expected", JSONObject.NULL).put("payload", JSONObject().put("schema", 1).put("text", text).put("attachments", JSONArray()))
    private fun page() = JSONObject().put("cursor", "owner:7").put("items", JSONArray()).put("nextOffset", JSONObject.NULL)

    @Test fun uncertainWriteRetainsExactCommandAcrossRestart() = runBlocking {
        var disk: String? = null
        var writes = 0
        var original: String? = null
        val remote: suspend (JSONObject) -> JSONObject = { command -> when (command.getString("op")) {
            "list" -> page()
            "watch" -> page()
            else -> {
                writes++
                if (original == null) original = command.toString() else assertEquals(original, command.toString())
                if (writes == 1) error("lost_receipt")
                JSONObject().put("status", "applied")
            }
        } }
        fun repo() = SharedDraftRepository("authority", { disk }, { disk = it }, remote)
        repo().synchronize()
        val id = repo().stage(put("offline"))
        assertTrue(id.startsWith("7."))
        try { repo().synchronize(); fail() } catch (_: IllegalStateException) { }
        assertEquals(1, repo().cached().getJSONArray("pending").length())
        repo().synchronize()
        assertEquals(2, writes)
        assertEquals(0, repo().cached().getJSONArray("pending").length())
    }

    @Test fun expiredCursorPreservesOfflineCreateAgainstAbsentSnapshot() = runBlocking {
        var disk: String? = null
        var expired = false
        var writes = 0
        val repo = SharedDraftRepository("authority", { disk }, { disk = it }) { command -> when (command.getString("op")) {
            "list" -> page()
            "watch" -> if (expired) JSONObject().put("error", "draft_resync_required") else page()
            else -> { writes++; JSONObject().put("status", "applied") }
        } }
        repo.synchronize()
        val id = repo.stage(put("keep me"))
        expired = true
        val result = repo.synchronize()
        assertEquals(0, writes)
        assertEquals(0, result.getJSONArray("pending").length())
        val branch = result.getJSONArray("conflicts").getJSONObject(0)
        assertEquals(id, branch.getString("mutationId"))
        assertEquals("keep me", branch.getJSONObject("local").getJSONObject("payload").getString("text"))
    }

    @Test fun snapshotDoesNotAdvanceOnIncompletePagination() = runBlocking {
        var disk: String? = null
        val repo = SharedDraftRepository("authority", { disk }, { disk = it }) { command ->
            if (command.has("offset")) error("offline") else page().put("nextOffset", 2)
        }
        try { repo.synchronize(); fail() } catch (_: IllegalStateException) { }
        assertNull(disk)
    }

    @Test fun queueScopeCannotEnterSharedStore() {
        try { SharedDraftRepository.scope(ComposerScope("env", "project", "thread", "queue")); fail() }
        catch (_: IllegalArgumentException) { }
    }
}
