// TEMPORARY until lib-t3-rust exposes the sidebar view (`sidebar.view` over several
// environments' shells, docs/UI_SPEC.md §6 U2, docs/LIB_REQUESTS.md phase 1 item 3). An
// approximation of the lib's `src/sidebar.rs` (`sort_timestamps`, `sidebar_status`,
// `metadata`'s snooze rule) and ot3's `ui/js/SidebarModel.js` (shelves and order): no inbox
// returns, no plan / done / woke / limited states, no subagent nesting, no project grouping by
// repository. This is the one temporary sidebar file: Home's state builder reads only what it
// returns. Do not grow it: new sidebar behaviour goes into the lib, and this file is deleted
// when the lib ships U2.
package dev.avillagran.ot3android.core.shell

import org.json.JSONObject

/** What a row says about its thread. */
enum class RowStatus {
    /** `pendingRuntimeRequest`: an approval or an answer is waiting for the user. */
    NeedsYou,

    /** A run is active. */
    Working,

    /** The thread's status is `failed` or `error`. */
    Failed,

    /** Nothing to say. */
    Quiet,
}

/** One top-level thread of one environment, keyed like ot3 (`<environment>/<thread>`). */
data class SidebarRow(
    val environmentId: String,
    val thread: ShellThread,
    val status: RowStatus,
    val pinned: Boolean,
    val settled: Boolean,
    /** The lib's `metadata.snoozed`: snoozed until a future time and nothing raised it since. */
    val snoozed: Boolean,
) {
    val key: String get() = "$environmentId/${thread.id}"
}

/** ot3's sections, each in its own order. [working] is empty unless grouping is on. */
data class Sidebar(
    val active: List<SidebarRow>,
    val working: List<SidebarRow>,
    val snoozed: List<SidebarRow>,
    val settled: List<SidebarRow>,
)

object SidebarModel {

    /** The visible top-level rows of one environment's shell, unordered. */
    fun rows(environmentId: String, shell: ShellState, now: Long): List<SidebarRow> =
        shell.threads.filter(::isVisible).map { thread ->
            SidebarRow(
                environmentId = environmentId,
                thread = thread,
                status = status(thread),
                pinned = thread.pinnedAt != null,
                settled = isSettled(thread),
                snoozed = isSnoozed(thread, now),
            )
        }

    /**
     * ot3's panel split (`ui/qml/T3Panel.qml:552-561`): snoozed rows leave every other
     * section; settled rows sort by [settledSortAt]; with [grouped], unpinned working rows
     * fold into the Working shelf; the rest is the active list, pinned first. Observed
     * returns from Working (`SidebarModel.js` `observeReturns`) are not tracked: U2.
     */
    fun sections(rows: List<SidebarRow>, grouped: Boolean): Sidebar {
        val (snoozed, awake) = rows.partition { it.snoozed }
        val (settled, inbox) = awake.partition { it.settled }
        val (working, active) = if (grouped) inbox.partition(::inWorkingShelf) else emptyList<SidebarRow>() to inbox
        return Sidebar(
            active = active.sortedWith(activeOrder(grouped)),
            working = working.sortedWith(compareByDescending<SidebarRow> { workingSortAt(it.thread) }.then(tie)),
            // ot3 keeps the shell's order here: updated, newest first (`ot3:src/lib.rs:715-719`).
            snoozed = snoozed.sortedWith(compareByDescending<SidebarRow> { it.thread.updatedAt ?: 0 }.then(tie)),
            settled = settled.sortedWith(compareByDescending<SidebarRow> { settledSortAt(it.thread) }.then(tie)),
        )
    }

    /** ot3 `inWorkingShelf`: work that needs no decision; pinned rows stay in place. */
    fun inWorkingShelf(row: SidebarRow): Boolean = row.status == RowStatus.Working && !row.pinned

    /** Archived, deleted and subagent threads stay out of the top level. */
    fun isVisible(thread: ShellThread): Boolean =
        thread.archivedAt == null && thread.deletedAt == null && thread.relationshipToParent != "subagent"

    fun isSettled(thread: ShellThread): Boolean = when (thread.settledOverride) {
        "settled" -> true
        "active" -> false
        else -> thread.settledAt != null
    }

    /**
     * A request that holds the thread (`blocking` not false, and not a background
     * `auth_refresh`) needs the user; then a run in progress; then a failed status.
     * `lastError` alone is not a failure: it outlives the run that set it.
     */
    fun status(thread: ShellThread): RowStatus {
        val request = thread.pendingRuntimeRequest
        return when {
            request != null && request.blocking != false && request.kind != "auth_refresh" -> RowStatus.NeedsYou
            thread.activeRunId != null || thread.status == "running" -> RowStatus.Working
            thread.status == "failed" || thread.status == "error" -> RowStatus.Failed
            else -> RowStatus.Quiet
        }
    }

    /**
     * The lib's `metadata.snoozed` (`lib:src/sidebar.rs:4-12`): `snoozedUntil` is still ahead
     * and nothing raised the thread since it was snoozed (a pending request, a runtime error
     * after the snooze, or a run completed after it).
     */
    fun isSnoozed(thread: ShellThread, now: Long): Boolean {
        val raw = thread.raw
        val until = snoozedUntil(thread) ?: return false
        if (until <= now) return false
        val runtime = raw.optJSONObject("runtime") ?: raw.optJSONObject("session")
        val run = raw.optJSONObject("latestRun") ?: raw.optJSONObject("latestTurn")
        val snoozedAt = stamp(raw, "snoozedAt")
        val blocked = raw.opt("hasPendingApprovals") == true || raw.opt("hasPendingUserInput") == true ||
            raw.optJSONObject("pendingSubagentRequest") != null || raw.optJSONObject("pendingRuntimeRequest") != null
        val runtimeStatus = runtime?.optString("status")
        val failedSince = (runtimeStatus == "error" || runtimeStatus == "failed") &&
            (snoozedAt == 0L || stamp(runtime, "updatedAt") > snoozedAt)
        val completedSince = snoozedAt > 0 && run != null &&
            (run.optString("state") == "completed" || run.optString("status") == "completed") &&
            stamp(run, "completedAt") > snoozedAt
        return !(blocked || failedSince || completedSince)
    }

    /** `snoozedUntil` in epoch ms, or null. */
    fun snoozedUntil(thread: ShellThread): Long? = stamp(thread.raw, "snoozedUntil").takeIf { it > 0 }

    /** ot3's `startedAt` for the elapsed time (`ot3:src/lib.rs:796`), or null. */
    fun runStartedAt(thread: ShellThread): Long? =
        maxOf(stamp(thread.raw, "activityRunStartedAt"), stamp(thread.raw, "latestRunStartedAt")).takeIf { it > 0 }

    /** The thread's worktree, when it runs in one (`worktreePath`). */
    fun worktreePath(thread: ShellThread): String? =
        thread.raw.optString("worktreePath").takeIf { it.isNotEmpty() && !thread.raw.isNull("worktreePath") }

    /**
     * The lib's `inboxAt`: the latest of created (or unsettled), run requested and run
     * completed. Never `updatedAt`, which also moves on metadata writes.
     */
    fun inboxAt(thread: ShellThread): Long = maxOf(
        maxOf(thread.createdAt ?: 0, thread.unsettledAt ?: 0),
        thread.latestRunRequestedAt ?: 0,
        thread.latestRunCompletedAt ?: 0,
    )

    /** The lib's `settledSortAt`, simplified: settled, else last run, else last message, else updated. */
    fun settledSortAt(thread: ShellThread): Long =
        thread.settledAt ?: thread.latestRunCompletedAt ?: thread.latestUserMessageAt ?: thread.updatedAt ?: 0

    /** ot3's Working shelf order: last sent (`lastSentAt`), else run start, else updated. */
    fun workingSortAt(thread: ShellThread): Long {
        val sent = maxOf(thread.latestUserMessageAt ?: thread.latestRunRequestedAt ?: 0, thread.createdAt ?: 0)
        return sent.takeIf { it > 0 } ?: runStartedAt(thread) ?: thread.updatedAt ?: 0
    }

    /** ot3 `tie`: thread id, then environment. */
    private val tie: Comparator<SidebarRow> = compareBy<SidebarRow> { it.thread.id }.thenBy { it.environmentId }

    /**
     * ot3 `compareActive`: pinned first (`pinOrderKey`, then `pinnedAt`). Then, grouped, by
     * [inboxAt] newest first; ungrouped, rows without an `activeOrderKey` by [inboxAt]
     * (newest first), then rows with one by that key (as text).
     */
    fun activeOrder(grouped: Boolean): Comparator<SidebarRow> = Comparator<SidebarRow> { ra, rb ->
        val a = ra.thread
        val b = rb.thread
        val aKey = a.activeOrderKey
        val bKey = b.activeOrderKey
        when {
            ra.pinned != rb.pinned -> if (ra.pinned) -1 else 1
            ra.pinned -> compareValuesBy(a, b, { it.pinOrderKey.orEmpty() }, { it.pinnedAt })
            grouped -> inboxAt(b).compareTo(inboxAt(a))
            (aKey == null) != (bKey == null) -> if (aKey == null) -1 else 1
            aKey != null && bKey != null -> aKey.compareTo(bKey)
            else -> inboxAt(b).compareTo(inboxAt(a))
        }
    }.then(tie)

    /** A timestamp field of [json] in epoch ms (ISO text or a number), 0 when absent or unreadable. */
    private fun stamp(json: JSONObject?, key: String): Long {
        if (json == null || json.isNull(key)) return 0
        return when (val value = json.opt(key)) {
            is Number -> value.toLong()
            is String -> ShellReducer.parseTimestamp(value) ?: 0
            else -> 0
        }
    }
}
