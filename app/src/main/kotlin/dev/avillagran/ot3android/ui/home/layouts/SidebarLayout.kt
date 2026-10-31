package dev.avillagran.ot3android.ui.home.layouts

import dev.avillagran.ot3android.core.home.*

/** Optional authoritative metadata omitted by the current HomeRow projection. P20 supplies it.
 * No timestamps are inferred from localized age labels. Keys always include the host. */
data class SidebarMetadata(
    val clocks: Map<String, ReplyClock> = emptyMap(),
    val threadGroups: Map<String, String> = emptyMap(),
    val projectGroups: Map<String, String> = emptyMap(),
    val hostStyles: Map<String, HostStyle> = emptyMap(),
)
data class ReplyClock(val repliedAt: Long = 0, val settledSortAt: Long = 0, val inboxAt: Long = 0,
    val updatedAt: Long = 0, val startedAt: Long = 0) {
    fun time(row: HomeRow): Long {
        val reply = repliedAt.takeIf { it != 0L } ?: settledSortAt.takeIf { row.settled && it != 0L }
            ?: inboxAt.takeIf { it != 0L } ?: updatedAt
        return if (row.working) maxOf(reply, updatedAt, startedAt) else reply
    }
}
data class HostStyle(val icon: String = "server", val color: String = "", val showName: Boolean = false) {
    // The existing Android kit has no platform-specific glyph assets.
    val displayIcon: String get() = when (icon) {
        "linux" -> "terminal"
        "mac-mini" -> "server"
        "mac-studio" -> "monitor"
        else -> icon
    }
}
data class SidebarChip(val key: String, val title: String, val count: Int, val group: Boolean = false)
data class SidebarSection(val key: String, val title: String, val rows: List<HomeRow>)

fun SidebarMetadata.group(row: HomeRow): String = threadGroups[row.key] ?: projectGroups[row.projectKey].orEmpty()

fun sidebarChips(rows: List<HomeRow>, metadata: SidebarMetadata): List<SidebarChip> {
    val top = rows.filterNot { it.subagent || it.settled }
    val groups = top.filter { metadata.group(it).isNotEmpty() }.groupBy { metadata.group(it) }
        .toSortedMap().map { (name, members) -> SidebarChip("group:$name", name, members.size, true) }
    val projects = top.groupBy { it.projectKey ?: "host:${it.environmentId}" }.map { (key, members) ->
        SidebarChip(key, members.first().projectTitle ?: members.first().environmentLabel.orEmpty(), members.size)
    }.sortedWith(compareByDescending<SidebarChip> { it.count }.thenBy { it.title }.thenBy { it.key })
    return groups + if (projects.size > 1) projects else emptyList()
}

/** Presentation only; membership/search/capability policy stays in sidebar.view. */
fun sidebarSections(rows: List<HomeRow>, layout: String, grouped: Boolean, metadata: SidebarMetadata,
    byReply: Boolean): List<SidebarSection> {
    // Keep each visible child next to its parent while sorting roots. Home's native nesting wins.
    val families = mutableListOf<MutableList<HomeRow>>()
    rows.forEach { row -> if (row.depth > 0 && families.isNotEmpty()) families.last().add(row) else families.add(mutableListOf(row)) }
    val order = if (byReply && families.all { metadata.clocks.containsKey(it.first().key) })
        families.sortedWith(compareByDescending<List<HomeRow>> { metadata.clocks.getValue(it.first().key).time(it.first()) }
            .thenBy { it.first().threadId }.thenBy { it.first().environmentId }) else families
    fun bucket(row: HomeRow): String = when {
        row.snoozed -> "snoozed"
        row.state == "waiting" -> "waiting"
        row.state == "working" -> "working"
        row.state == "failed" -> "failed"
        row.settled -> "settled"
        row.pinned && row.detail != "done" -> "pinned"
        row.detail == "done" -> "done"
        else -> "idle"
    }
    if (layout == "projects") return order.groupBy { it.first().projectKey ?: "host:${it.first().environmentId}" }
        .entries.sortedWith(compareBy<Map.Entry<String, List<List<HomeRow>>>> {
            it.value.first().first().projectTitle.orEmpty().lowercase() }.thenBy { it.key }).flatMap { (project, members) ->
            val title = members.first().first().projectTitle ?: members.first().first().environmentLabel.orEmpty()
            val sorted = members.sortedWith(compareBy<List<HomeRow>> { it.first().title.orEmpty().lowercase() }.thenBy { it.first().key })
            listOf(SidebarSection("project:$project", title, emptyList())) +
                (if (grouped) listOf("waiting", "working", "failed", "idle", "done", "snoozed", "settled").mapNotNull { state ->
                    sorted.filter { (if (bucket(it.first()) == "pinned") "idle" else bucket(it.first())) == state }.takeIf { it.isNotEmpty() }?.let {
                        SidebarSection("project:$project/$state", state, it.flatten()) }
                } else listOf(SidebarSection("project:$project/rows", "", sorted.flatten())))
        }
    if (layout == "focus") return listOf("pending", "working", "waiting", "done").mapNotNull { state ->
        order.filter { family -> val row = family.first(); state == when {
            row.state == "waiting" || row.state == "failed" -> "waiting"
            row.state == "working" -> "working"
            row.settled || row.detail == "done" -> "done"
            else -> "pending"
        } }.takeIf { it.isNotEmpty() }?.let { SidebarSection("focus:$state", state, it.flatten()) }
    }
    if (!grouped) return listOf(SidebarSection("active", "", order.flatten()))
    return listOf("pinned", "idle", "done", "working", "waiting", "failed", "snoozed", "settled").mapNotNull { state ->
        order.filter { bucket(it.first()) == state }.takeIf { it.isNotEmpty() }?.let {
            SidebarSection(state, if (state == "idle") "" else state, it.flatten()) }
    }
}
