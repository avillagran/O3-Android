package dev.avillagran.ot3android.core.home

import dev.avillagran.ot3android.core.transport.T3Transport
import org.json.JSONObject
import dev.avillagran.ot3android.core.thread.MessageSender
import dev.avillagran.ot3android.core.thread.SendResult

/** Android supplies identities and intent; Rust validates and builds every command. */
object ThreadManagement {
    suspend fun access(transport: T3Transport): ActionContext {
        val config = transport.call("server.getConfig") as JSONObject
        val catalog = transport.domain("models.catalog", JSONObject().put("config", config)) as JSONObject
        return access(catalog, transport.authSession())
    }

    fun access(catalog: JSONObject, session: JSONObject): ActionContext {
        val scopes = session.optJSONArray("permissions") ?: session.optJSONArray("scopes")
        val canWrite = (session.opt("authenticated") == true) && scopes != null &&
            (0 until scopes.length()).any { scopes.optString(it) == "orchestration:operate" }
        val caps = catalog.optJSONObject("capabilities") ?: JSONObject()
        return ActionContext(capabilities = ServerCapability.entries.filter { capability ->
            caps.opt(capability.name.replaceFirstChar { it.lowercase() }) == true
        }.toSet(), canWrite = canWrite, buildersAvailable = true)
    }

    fun supported(action: ThreadAction) = action in setOf(ThreadAction.Pin, ThreadAction.Unpin,
        ThreadAction.MarkSettled, ThreadAction.MoveToActive, ThreadAction.Snooze, ThreadAction.WakeNow,
        ThreadAction.Rename, ThreadAction.RegenerateTitle, ThreadAction.MarkUnread,
        ThreadAction.AutoSettleEnabled, ThreadAction.AutoSettleDisabled, ThreadAction.Archive, ThreadAction.Delete)

    suspend fun execute(transport: T3Transport, row: HomeRow, action: ThreadAction,
        title: String? = null, until: String? = null, confirmed: Boolean = false, sender: MessageSender,
    ): SendResult {
        val context = access(transport)
        val menu = ThreadActions.menu(ThreadMenuTarget(row, false, row.working,
            autoSettleDisabled = row.autoSettleDisabled, canSnooze = row.canSnooze), context)
        val allowed = (menu + menu.flatMap { it.children }).any { it.action == action && it.available }
        check(supported(action) && allowed) { "action_unavailable" }
        check(action !in setOf(ThreadAction.Archive, ThreadAction.Delete) || confirmed) { "confirmation_required" }
        val lifecycle = when (action) {
            ThreadAction.MarkSettled -> "settle"
            ThreadAction.MoveToActive -> "unsettle"
            ThreadAction.Snooze -> "snooze"
            ThreadAction.WakeNow -> "unsnooze"
            else -> null
        }
        val args = if (lifecycle != null) JSONObject().put("action", lifecycle).put("threadId", row.threadId)
            .apply { until?.let { put("snoozedUntil", it) } }
        else JSONObject().put("thread", row.threadId).put("action", when (action) {
            ThreadAction.Pin -> "pin"
            ThreadAction.Unpin -> "unpin"
            ThreadAction.Rename -> "thread-rename"
            ThreadAction.RegenerateTitle -> "regenerate-title"
            ThreadAction.MarkUnread -> "mark-unread"
            ThreadAction.AutoSettleEnabled, ThreadAction.AutoSettleDisabled -> "auto-settle"
            ThreadAction.Archive -> "archive"
            ThreadAction.Delete -> "delete-thread"
            else -> error("action_unavailable")
        }).put("working", row.working).put("confirmed", confirmed)
            .put("enabled", action == ThreadAction.AutoSettleEnabled).apply { title?.let { put("title", it) } }
        // Persist identity and exact bytes before sending. Unknown outcomes stay in the
        // existing outbox for explicit same-id recovery, never automatic replay.
        return sender.sidebar(transport, row.environmentId, row.threadId,
            if (lifecycle != null) "sidebar.command" else "sidebar.threadAction", args, transport)
    }
}
