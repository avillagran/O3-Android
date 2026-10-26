package dev.avillagran.ot3android.ui.thread

import dev.avillagran.ot3android.core.transport.DomainEngine
import dev.avillagran.ot3android.testing.assumeHostJni
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Actual queue.derive row -> documented UI inputs -> strict native builder, not a hand-built row. */
class QueueEditContractTest {
    @Test fun derivedMetadataIsExcludedWhileOriginalAttachmentsAndContextSurvive() = runBlocking {
        assumeHostJni()
        for (context in listOf(JSONObject("""{"filePaths":["/fixture/notes.txt"]}"""), JSONObject.NULL)) {
            val projection = JSONObject("""{"thread":{"id":"chat"},"runs":[{"id":"q1","status":"queued","userMessageId":"m1","queuePosition":1}],"messages":[{"id":"m1","runId":"q1","text":"Original","attachments":[{"type":"file","name":"notes.txt","url":"https://fixture.invalid/notes"}]}]}""")
            projection.getJSONArray("messages").getJSONObject(0).put("context", context)
            val derived = DomainEngine.shared.domain("queue.derive", JSONObject().put("projection", projection)) as JSONObject
            val row = derived.getJSONArray("items").getJSONObject(0).put("pending", false).put("text", "Revised")
            assertTrue(row.getBoolean("available")); assertEquals("m1", row.getString("messageId"))
            val args = queueEditArguments(row).put("thread", "chat")
            assertFalse(args.has("available")); assertFalse(args.has("messageId")); assertFalse(args.has("pending"))
            val command = DomainEngine.shared.domain("queue.command", args) as JSONObject
            assertEquals("queued-run.edit", command.getString("type")); assertEquals("q1", command.getString("runId"))
            assertEquals(row.getJSONArray("attachments").toString(), command.getJSONArray("attachments").toString())
            if (context is JSONObject) assertEquals(context.toString(), command.getJSONObject("context").toString())
            else assertFalse(command.has("context"))
        }
    }
}
