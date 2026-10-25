package dev.avillagran.ot3android.core.home

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessageSearchTest {

    private fun match(thread: String, at: String?, snippet: String = "hit") =
        JSONObject().put("threadId", thread).put("projectId", "p").put("source", "message").put("snippet", snippet)
            .put("messageCreatedAt", at ?: JSONObject.NULL)

    @Test
    fun parsesTheTypedResult() {
        val result = JSONObject().put("matches", JSONArray().put(match("t1", "2026-10-25T10:00:00Z", "  two\n lines ")).put(JSONObject().put("snippet", "no thread")))
        assertEquals(listOf(MessageMatch("e1", "t1", "p", "message", "two lines", "2026-10-25T10:00:00Z")), MessageSearch.parse("e1", result))
        assertTrue(MessageSearch.parse("e1", null).isEmpty())
        assertTrue(MessageSearch.parse("e1", "not json").isEmpty())
        assertEquals("""{"query":"ab","limit":20}""", MessageSearch.arguments("ab").toString())
    }

    @Test
    fun mergeKeepsOneMatchPerThreadNewestFirst() {
        val e1 = MessageSearch.parse("e1", JSONObject().put("matches", JSONArray().put(match("t1", "2026-10-25T10:00:00Z")).put(match("t1", "2026-10-25T12:00:00Z")).put(match("title", null))))
        val e2 = MessageSearch.parse("e2", JSONObject().put("matches", JSONArray().put(match("t1", "2026-10-25T11:00:00Z"))))
        val merged = MessageSearch.merge(listOf(e1, e2))
        assertEquals(listOf("e2/t1", "e1/t1", "e1/title"), merged.map { "${it.environmentId}/${it.threadId}" })
        assertEquals("the first match of a thread wins", "2026-10-25T10:00:00Z", merged[1].messageCreatedAt)
    }

    @Test
    fun debouncesFromTwoCharacters() = runTest {
        val queries = MutableStateFlow("")
        val searched = mutableListOf<String>()
        val states = mutableListOf<MessageSearchState>()
        val job = launch {
            MessageSearch.states(queries) { q -> searched += q; listOf(MessageMatch("e", "t", null, null, q, null)) }.collect { states += it }
        }
        runCurrent()
        assertEquals(listOf<MessageSearchState>(MessageSearchState.Idle), states)

        queries.value = "a"
        runCurrent()
        queries.value = "ab"
        advanceTimeBy(100)
        queries.value = "abc "
        advanceTimeBy(249)
        assertTrue("nothing sent inside the debounce", searched.isEmpty())
        advanceTimeBy(2)
        assertEquals(listOf("abc"), searched)
        assertEquals(MessageSearchState.Loading("abc"), states[states.size - 2])
        assertEquals("abc", (states.last() as MessageSearchState.Results).matches.single().snippet)

        queries.value = "x"
        runCurrent()
        assertEquals(MessageSearchState.Idle, states.last())
        job.cancel()
    }
}
