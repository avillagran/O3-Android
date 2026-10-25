// TEMPORARY until lib-t3-rust exposes the sidebar view (`sidebar.view` and `sidebar.status`,
// docs/LIB_REQUESTS.md phase 1 item 3). An approximation of the lib's `src/sidebar.rs`
// (`sort_timestamps`, `sidebar_status`) and ot3's `ui/js/SidebarModel.js`: no snoozing, inbox
// returns, filters, search or subagent nesting. Do not grow it: new sidebar behaviour goes
// into the lib, and this file is deleted when the lib ships item 3.
package dev.avillagran.ot3android.core.shell

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

data class SidebarRow(
    val thread: ShellThread,
    val status: RowStatus,
    val pinned: Boolean,
)

/** Active rows of one project; [title] is null when the project is unknown to the shell. */
data class ProjectGroup(
    val projectId: String?,
    val title: String?,
    val rows: List<SidebarRow>,
)

data class Sidebar(
    val active: List<ProjectGroup>,
    val settled: List<SidebarRow>,
) {
    val isEmpty: Boolean get() = active.isEmpty() && settled.isEmpty()
}

object SidebarModel {

    fun build(shell: ShellState): Sidebar {
        val visible = shell.threads.filter(::isVisible)
        val (settled, active) = visible.partition(::isSettled)
        val titles = shell.projects.associate { it.id to it.title }

        val groups = active.groupBy { it.projectId }.map { (projectId, threads) ->
            val ordered = threads.sortedWith(activeOrder)
            ProjectGroup(projectId, projectId?.let { titles[it] }, ordered.map(::row))
        }
        // Projects with the most recent inbox activity first.
        val orderedGroups = groups.sortedWith(
            compareByDescending<ProjectGroup> { group -> group.rows.maxOf { inboxAt(it.thread) } }
                .thenBy { it.title.orEmpty() },
        )
        val settledRows = settled
            .sortedWith(compareByDescending<ShellThread> { settledSortAt(it) }.thenBy { it.id })
            .map(::row)
        return Sidebar(orderedGroups, settledRows)
    }

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

    /**
     * Pinned first (`pinOrderKey`, then `pinnedAt`). Then, as ot3, rows without an
     * `activeOrderKey` by [inboxAt] (newest first), then rows with one by that key (as text).
     */
    private val activeOrder: Comparator<ShellThread> = Comparator { a, b ->
        val aPinned = a.pinnedAt != null
        val bPinned = b.pinnedAt != null
        val aKey = a.activeOrderKey
        val bKey = b.activeOrderKey
        val order = when {
            aPinned != bPinned -> if (aPinned) -1 else 1
            aPinned -> compareValuesBy(a, b, { it.pinOrderKey.orEmpty() }, { it.pinnedAt })
            (aKey == null) != (bKey == null) -> if (aKey == null) -1 else 1
            aKey != null && bKey != null -> aKey.compareTo(bKey)
            else -> inboxAt(b).compareTo(inboxAt(a))
        }
        if (order != 0) order else a.id.compareTo(b.id)
    }

    private fun row(thread: ShellThread) = SidebarRow(thread, status(thread), pinned = thread.pinnedAt != null)
}
