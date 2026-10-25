// TEMPORARY until lib-t3-rust exposes the sidebar view (`sidebar.view` and `sidebar.status`,
// docs/LIB_REQUESTS.md phase 1 item 3). Minimal rules taken from ot3 (`ui/js/SidebarModel.js`,
// `src/sidebar.rs`); no snoozing, inbox returns, filters, search or subagent nesting. Do not
// grow it: new sidebar behaviour goes into the lib, and this file is deleted when the lib
// ships item 3.
package dev.avillagran.ot3android.core.shell

/** What a row says about its thread. */
enum class RowStatus {
    /** `pendingRuntimeRequest`: an approval or an answer is waiting for the user. */
    NeedsYou,

    /** A run is active. */
    Working,

    /** The thread failed or carries an error. */
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
        // Projects with the most recent thread first.
        val orderedGroups = groups.sortedWith(
            compareByDescending<ProjectGroup> { group -> group.rows.maxOf { it.thread.updatedAt.orEmpty() } }
                .thenBy { it.title.orEmpty() },
        )
        val settledRows = settled
            .sortedWith(compareByDescending<ShellThread> { it.settledAt ?: it.updatedAt.orEmpty() }.thenBy { it.id })
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

    fun status(thread: ShellThread): RowStatus = when {
        thread.pendingRuntimeRequest != null -> RowStatus.NeedsYou
        thread.activeRunId != null || thread.status == "running" -> RowStatus.Working
        thread.status == "failed" || thread.lastError != null -> RowStatus.Failed
        else -> RowStatus.Quiet
    }

    /** Pinned first (`pinOrderKey`, then `pinnedAt`), then the rest by `updatedAt`, newest first. */
    private val activeOrder: Comparator<ShellThread> = Comparator { a, b ->
        val aPinned = a.pinnedAt != null
        val bPinned = b.pinnedAt != null
        when {
            aPinned != bPinned -> if (aPinned) -1 else 1
            aPinned -> compareValuesBy(a, b, { it.pinOrderKey.orEmpty() }, { it.pinnedAt }, { it.id })
            else -> compareValuesBy(b, a, { it.updatedAt.orEmpty() }).takeIf { it != 0 } ?: a.id.compareTo(b.id)
        }
    }

    private fun row(thread: ShellThread) = SidebarRow(thread, status(thread), pinned = thread.pinnedAt != null)
}
