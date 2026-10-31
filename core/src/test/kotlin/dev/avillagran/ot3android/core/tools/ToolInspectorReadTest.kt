package dev.avillagran.ot3android.core.tools

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ToolInspectorReadTest {
    private fun reference() = JSONObject().put("sourceThreadId", "source").put("sourceItemId", "item")
        .put("type", "command_execution").put("revision", "live").put("runId", JSONObject.NULL).put("providerTurnId", JSONObject.NULL)
    private val owner = ToolInspectorOwner("host", "viewer")
    private fun response(ref: JSONObject = reference()) = JSONObject().put("available", true)
        .put("scope", JSONObject(ref.toString()).put("environment", owner.environment).put("threadId", owner.threadId))

    @Test fun acceptsInheritedSourceAndExactViewer() = runTest {
        assertTrue(ToolInspectorRead.read(reference(), owner) { response() }!!.getBoolean("available"))
    }
    @Test fun rejectsEveryStaleScopeField() = runTest {
        for (field in listOf("environment", "threadId", "sourceThreadId", "sourceItemId", "type", "revision", "runId", "providerTurnId")) {
            val stale = response().apply { getJSONObject("scope").put(field, "other") }
            try { ToolInspectorRead.read(reference(), owner) { stale }; fail(field) }
            catch (expected: IllegalArgumentException) { assertEquals("tool_scope_mismatch", expected.message) }
        }
    }
    @Test fun explicitOwnerRequiresScope() = runTest {
        try { ToolInspectorRead.read(reference(), owner) { JSONObject() }; fail() }
        catch (expected: IllegalArgumentException) { assertEquals("tool_scope_mismatch", expected.message) }
    }
    @Test fun offlineRemainsDistinctFromMalformedResponse() = runTest {
        assertNull(ToolInspectorRead.read(reference(), owner) { null })
    }
    @Test fun readerCannotMutateFrozenReference() = runTest {
        val ref = reference()
        val result = ToolInspectorRead.read(ref, owner) { it.put("revision", "changed"); response() }
        assertNotNull(result)
        assertEquals("live", ref.getString("revision"))
    }
    @Test fun cancelledReadRejectsEvenNonCooperativeCompletion() = runTest {
        val entered = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Unit>()
        var published = false
        val job = launch {
            ToolInspectorRead.read(reference(), owner) {
                entered.complete(Unit)
                withContext(NonCancellable) { completed.await(); response() }
            }
            published = true
        }
        entered.await()
        job.cancel()
        completed.complete(Unit)
        job.join()
        assertFalse(published)
    }
    @Test fun largeAnsiStaticTextIsBoundedWithoutInterpretingControlSequences() {
        val input = "\u001b[31mprogress\r\n".repeat(100000)
        val shown = ToolInspectorRead.display(input)
        assertTrue(shown.truncated)
        assertTrue(shown.text.toByteArray(Charsets.UTF_8).size <= 65536)
        assertTrue(input.startsWith(shown.text))
    }
    @Test fun multibyteBoundaryNeverSplitsSurrogatePair() {
        val shown = ToolInspectorRead.display("a" + "😀".repeat(100000), 65536)
        assertEquals(65533, shown.text.toByteArray(Charsets.UTF_8).size)
        assertFalse(Character.isHighSurrogate(shown.text.last()))
        assertTrue(shown.truncated)
    }
    @Test fun exactLimitEmptyAndRawBudget() {
        assertFalse(ToolInspectorRead.display("").truncated)
        assertFalse(ToolInspectorRead.display("abcd", 4).truncated)
        assertEquals("", ToolInspectorRead.display("x", 0).text)
        assertEquals(131072, ToolInspectorRead.display("x".repeat(200000), 128 * 1024).text.length)
    }
}
