package dev.avillagran.ot3android.core.home

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import org.json.JSONObject

/** One match of `orchestration.searchThreads` (`{threadId, projectId, source, snippet, messageCreatedAt}`). */
data class MessageMatch(
    val environmentId: String,
    val threadId: String,
    val projectId: String?,
    val source: String?,
    val snippet: String,
    /** ISO text as the server sent it; compared as text, as ot3 does. */
    val messageCreatedAt: String?,
)

/** The "Messages" section of Home's search. */
sealed interface MessageSearchState {
    /** Fewer than [MessageSearch.MIN_CHARS] characters: no section. */
    data object Idle : MessageSearchState

    data class Loading(val query: String) : MessageSearchState

    data class Results(val query: String, val matches: List<MessageMatch>) : MessageSearchState
}

/**
 * The server's thread search, as ot3's palette and T3 Mobile's list run it
 * (`ot3:ui/qml/T3Panel.qml:917-919`, `ot3:src/thread_search.rs`;
 * `t3m:src/features/threads/threadListV2.ts:625-638`): from two characters, 250 ms after the
 * last keystroke, every connected environment at once, failures skipped.
 */
object MessageSearch {
    const val FUNCTION = "orchestration.searchThreads"
    const val MIN_CHARS = 2
    const val MAX_CHARS = 200
    const val DEBOUNCE_MS = 250L
    const val LIMIT = 20

    /** The `orchestration.searchThreads` arguments. */
    fun arguments(query: String): JSONObject = JSONObject().put("query", query).put("limit", LIMIT)

    /** Reads one environment's `call` result; anything unexpected is no match. */
    fun parse(environmentId: String, result: Any?): List<MessageMatch> {
        val matches = (result as? JSONObject)?.optJSONArray("matches") ?: return emptyList()
        return (0 until matches.length()).mapNotNull { index ->
            val match = matches.optJSONObject(index) ?: return@mapNotNull null
            val threadId = match.text("threadId") ?: return@mapNotNull null
            MessageMatch(
                environmentId = environmentId,
                threadId = threadId,
                projectId = match.text("projectId"),
                source = match.text("source"),
                snippet = match.text("snippet").orEmpty().replace(WHITESPACE, " ").trim(),
                messageCreatedAt = match.text("messageCreatedAt"),
            )
        }
    }

    /**
     * ot3's merge: one match per thread (the first), newest message first; title matches
     * (no message time) keep the server's order after them.
     */
    fun merge(perEnvironment: List<List<MessageMatch>>): List<MessageMatch> =
        perEnvironment.flatten()
            .distinctBy { it.environmentId to it.threadId }
            .sortedWith(compareByDescending(nullsFirst()) { it.messageCreatedAt })

    /**
     * Search states for [queries]: [MessageSearchState.Idle] under [MIN_CHARS] (at once),
     * otherwise [MessageSearchState.Loading] then [MessageSearchState.Results] after
     * [DEBOUNCE_MS]. A newer query cancels an older search.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun states(queries: Flow<String>, search: suspend (String) -> List<MessageMatch>): Flow<MessageSearchState> =
        queries.map { it.trim() }.distinctUntilChanged().transformLatest { query ->
            if (query.length < MIN_CHARS || query.length > MAX_CHARS) {
                emit(MessageSearchState.Idle)
                return@transformLatest
            }
            delay(DEBOUNCE_MS)
            emit(MessageSearchState.Loading(query))
            emit(MessageSearchState.Results(query, search(query)))
        }

    private val WHITESPACE = Regex("\\s+")

    private fun JSONObject.text(key: String): String? = if (isNull(key)) null else opt(key)?.toString()
}
