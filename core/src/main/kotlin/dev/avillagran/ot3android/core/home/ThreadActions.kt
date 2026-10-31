package dev.avillagran.ot3android.core.home

import java.util.Calendar
import java.util.TimeZone

/** Server capabilities that gate thread actions, as ot3 reads them from the environment's catalog. */
enum class ServerCapability { ThreadPinning, ThreadSettlement, ThreadSnooze, ThreadTitleRegeneration, ThreadAutoSettleOptOut }

/** Server-advertised capabilities and authenticated session permissions, scoped to one connection. */
data class ActionContext(
    val capabilities: Set<ServerCapability> = emptySet(),
    /** The token may write (ot3 `canWrite`). */
    val canWrite: Boolean = false,
    /** `sidebar::command` and `sidebar::thread_action` are reachable. */
    val buildersAvailable: Boolean = false,
    /** The New thread flow exists (UI-5). */
    val newThreadAvailable: Boolean = false,
    /** Settings → Projects exists (UI-6). */
    val projectSettingsAvailable: Boolean = false,
    /** A management request is in flight. */
    val busy: Boolean = false,
    /** A released native project mutation builder and its integration are both present. */
    val projectMutationsAvailable: Boolean = false,
    /** Local grouping controls and persistence have been registered by the shell. */
    val projectGroupingAvailable: Boolean = false,
) {
    /** ot3 `actionCanOperate && !busy`, plus the builders that send it. */
    val canOperate: Boolean get() = canWrite && buildersAvailable && !busy

    fun has(capability: ServerCapability): Boolean = capability in capabilities

    companion object {
        /** What UI-1 knows: nothing that mutates. */
        val Interim = ActionContext()
    }
}

/** Every item of ot3's thread menu (`ot3:ui/qml/T3ThreadRow.qml:378-410`). */
enum class ThreadAction(val mutates: Boolean) {
    NewThreadOnBranch(true),
    Pin(true),
    Unpin(true),
    MoveUp(true),
    MoveDown(true),
    MarkSettled(true),
    MoveToActive(true),
    Snooze(true),
    WakeNow(true),
    Rename(true),
    RegenerateTitle(true),
    MarkUnread(true),
    FilterByProject(false),
    AllProjects(false),
    AutoSettle(true),
    AutoSettleEnabled(true),
    AutoSettleDisabled(true),
    Copy(false),
    CopyPath(false),
    CopyBranch(false),
    CopyThreadId(false),
    ProjectSettings(false),
    Archive(true),
    Delete(true),
}

/**
 * One menu item: [visible] and [enabled] follow ot3's rules for [ActionContext]; [group]
 * changes where ot3 draws a separator. [checked] is set on the auto-settle choices.
 */
data class MenuEntry(
    val action: ThreadAction,
    val visible: Boolean,
    val enabled: Boolean,
    val group: Int,
    val checked: Boolean? = null,
    val children: List<MenuEntry> = emptyList(),
) {
    /** Shown in the sheet: ot3 shows disabled items greyed out; UI-1 lists only usable ones. */
    val available: Boolean get() = visible && enabled && (children.isEmpty() || children.any { it.available })
}

/** Inputs of the thread menu that are not on the row. */
data class ThreadMenuTarget(
    val row: HomeRow,
    /** The Project filter is on this row's project: offer "All projects". */
    val projectScoped: Boolean,
    /** A run is active (`working` or `activeRunId`): Archive is off. */
    val working: Boolean,
    val autoSettleDisabled: Boolean = false,
    val titleRegenerating: Boolean = false,
    /** The lib's `metadata.canSnooze`, on the `sidebar.view` row. */
    val canSnooze: Boolean = false,
)

object ThreadActions {

    /** ot3's thread menu in ot3's order, with its visibility and enablement rules. */
    fun menu(target: ThreadMenuTarget, context: ActionContext): List<MenuEntry> {
        val row = target.row
        val operate = context.canOperate
        val pinning = context.has(ServerCapability.ThreadPinning)
        val settlement = context.has(ServerCapability.ThreadSettlement)
        val snooze = context.has(ServerCapability.ThreadSnooze)
        val autoSettle = context.has(ServerCapability.ThreadAutoSettleOptOut)
        val branch = row.branch?.takeIf { it.isNotBlank() }
        return listOf(
            MenuEntry(ThreadAction.NewThreadOnBranch, branch != null, operate && context.newThreadAvailable, 0),
            MenuEntry(if (row.pinned) ThreadAction.Unpin else ThreadAction.Pin, pinning, operate && pinning, 0),
            // Fractional pin-order generation is not exposed by the domain API yet.
            MenuEntry(ThreadAction.MoveUp, row.pinned && pinning, false, 0),
            MenuEntry(ThreadAction.MoveDown, row.pinned && pinning, false, 0),
            MenuEntry(if (row.settled) ThreadAction.MoveToActive else ThreadAction.MarkSettled, settlement, operate && settlement, 0),
            MenuEntry(
                if (row.snoozed) ThreadAction.WakeNow else ThreadAction.Snooze,
                snooze,
                operate && ((snooze && target.canSnooze) || row.snoozed),
                0,
            ),
            MenuEntry(ThreadAction.Rename, true, operate, 1),
            MenuEntry(
                ThreadAction.RegenerateTitle,
                context.has(ServerCapability.ThreadTitleRegeneration),
                operate && context.has(ServerCapability.ThreadTitleRegeneration) && !target.titleRegenerating,
                1,
            ),
            MenuEntry(ThreadAction.MarkUnread, true, operate, 1),
            MenuEntry(if (target.projectScoped) ThreadAction.AllProjects else ThreadAction.FilterByProject, row.projectKey != null, true, 1),
            MenuEntry(
                ThreadAction.AutoSettle,
                autoSettle,
                operate && autoSettle,
                2,
                children = listOf(
                    MenuEntry(ThreadAction.AutoSettleEnabled, true, operate && autoSettle, 0, checked = !target.autoSettleDisabled),
                    MenuEntry(ThreadAction.AutoSettleDisabled, true, operate && autoSettle, 0, checked = target.autoSettleDisabled),
                ),
            ),
            MenuEntry(
                ThreadAction.Copy,
                true,
                true,
                2,
                children = listOf(
                    MenuEntry(ThreadAction.CopyPath, true, !row.path.isNullOrEmpty(), 0),
                    MenuEntry(ThreadAction.CopyBranch, branch != null, true, 0),
                    MenuEntry(ThreadAction.CopyThreadId, true, true, 0),
                ),
            ),
            MenuEntry(ThreadAction.ProjectSettings, true, row.projectKey != null && context.projectSettingsAvailable, 2),
            MenuEntry(ThreadAction.Archive, true, operate && !target.working && !row.working, 3),
            MenuEntry(ThreadAction.Delete, true, operate, 3),
        )
    }

    /**
     * T3 Mobile's swipe (`t3m:src/features/threads/threadListV2.ts:95-118`): Wake on snoozed
     * rows, Move to active on settled ones, Settle on the rest when the server supports
     * settlement, else Archive; Snooze as the secondary action. Every swipe mutates, so they
     * require current write permission and native builders.
     */
    fun swipe(row: HomeRow, context: ActionContext): SwipeActions {
        val primary = when {
            row.snoozed -> SwipeAction.Wake
            row.settled -> SwipeAction.MoveToActive
            context.has(ServerCapability.ThreadSettlement) -> SwipeAction.Settle
            else -> SwipeAction.Archive
        }
        val secondary = SwipeAction.Snooze.takeIf { !row.snoozed && row.canSnooze && context.has(ServerCapability.ThreadSnooze) }
        return SwipeActions(primary, secondary, enabled = context.canOperate && !row.subagent && when (primary) {
            SwipeAction.Wake -> context.has(ServerCapability.ThreadSnooze)
            SwipeAction.MoveToActive -> context.has(ServerCapability.ThreadSettlement)
            SwipeAction.Archive -> !row.working
            else -> true
        })
    }

    /** ot3's project menu (`ot3:ui/qml/T3ProjectMenu.qml:62-72`). */
    fun projectMenu(workspaceRoot: String?, context: ActionContext): List<ProjectMenuEntry> = listOf(
        ProjectMenuEntry(ProjectAction.Rename, context.canOperate && context.projectMutationsAvailable, 0),
        // Grouping is ot3's local project model (`ui/js/ProjectModel.js`), U2.
        ProjectMenuEntry(ProjectAction.GroupInto, context.projectGroupingAvailable && !context.busy, 0),
        ProjectMenuEntry(ProjectAction.CopyPath, !workspaceRoot.isNullOrEmpty(), 0),
        ProjectMenuEntry(ProjectAction.ProjectSettings, context.projectSettingsAvailable, 0),
        ProjectMenuEntry(ProjectAction.Remove, context.canOperate && context.projectMutationsAvailable, 1),
    )

    /**
     * ot3's snooze presets (`ot3:ui/qml/T3ThreadRow.qml:104-110`): in 1 and 3 hours, this
     * evening at 18:00 when more than an hour away, tomorrow at 9:00, next Monday at 9:00
     * unless that is tomorrow; then Custom.
     */
    fun snoozePresets(now: Long, zone: TimeZone = TimeZone.getDefault()): List<SnoozePreset> {
        val hour = 3_600_000L
        val result = mutableListOf(SnoozePreset(SnoozeOption.InOneHour, now + hour), SnoozePreset(SnoozeOption.InThreeHours, now + 3 * hour))
        fun at(daysAhead: Int, hourOfDay: Int): Long = Calendar.getInstance(zone).apply {
            timeInMillis = now
            add(Calendar.DAY_OF_MONTH, daysAhead)
            set(Calendar.HOUR_OF_DAY, hourOfDay)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val evening = at(0, 18)
        if (evening - now > hour) result += SnoozePreset(SnoozeOption.ThisEvening, evening)
        val tomorrow = at(1, 9)
        result += SnoozePreset(SnoozeOption.Tomorrow, tomorrow)
        val weekday = Calendar.getInstance(zone).apply { timeInMillis = now }.get(Calendar.DAY_OF_WEEK) - 1 // Sunday = 0, as JS
        val toMonday = ((1 - weekday + 7) % 7).takeIf { it != 0 } ?: 7
        val monday = at(toMonday, 9)
        if (monday != tomorrow) result += SnoozePreset(SnoozeOption.NextWeek, monday)
        result += SnoozePreset(SnoozeOption.Custom, null)
        return result
    }
}

enum class SwipeAction { Settle, MoveToActive, Wake, Archive, Snooze }

/** A row's swipe: [primary] commits on a full swipe, [secondary] opens Snooze until. */
data class SwipeActions(val primary: SwipeAction?, val secondary: SwipeAction?, val enabled: Boolean) {
    companion object {
        val None = SwipeActions(null, null, false)
    }
}

enum class ProjectAction { Rename, GroupInto, CopyPath, ProjectSettings, Remove }

data class ProjectMenuEntry(val action: ProjectAction, val enabled: Boolean, val group: Int)

enum class SnoozeOption { InOneHour, InThreeHours, ThisEvening, Tomorrow, NextWeek, Custom }

/** A snooze choice and when it wakes (null for Custom). */
data class SnoozePreset(val option: SnoozeOption, val at: Long?)
