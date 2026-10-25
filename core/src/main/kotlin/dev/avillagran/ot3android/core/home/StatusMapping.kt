package dev.avillagran.ot3android.core.home

import dev.avillagran.ot3android.core.shell.RowStatus
import dev.avillagran.ot3android.core.shell.SidebarRow

/**
 * ot3's ten-state row detail (`ot3:ui/js/SidebarModel.js:5-17`, labels at
 * `ot3:ui/qml/T3Panel.qml:745`), plus [None] for "nothing to say" (the row shows its time).
 */
enum class RowStatusDetail {
    Approval,
    Input,
    PlanReady,
    Waiting,
    Goal,
    Working,
    Limited,
    Failed,
    Woke,
    Done,
    None,
}

/** ot3's shelf and filter bucket (`SidebarModel.js` `state`). */
enum class StatusBucket { Waiting, Working, Failed, Done, Woke, Idle }

object StatusMapping {

    /**
     * Interim (UI_SPEC §4.1.3, until U2 ships `sidebar.view`): today's [RowStatus] mapped to
     * Approval or Input (by the pending request's `kind`), Working and Failed. PlanReady,
     * Waiting, Goal, Limited, Woke and Done need the lib's refinements and never appear yet.
     */
    fun detail(row: SidebarRow): RowStatusDetail = when (row.status) {
        RowStatus.NeedsYou ->
            if (row.thread.pendingRuntimeRequest?.kind == "approval") RowStatusDetail.Approval else RowStatusDetail.Input
        RowStatus.Working -> RowStatusDetail.Working
        RowStatus.Failed -> RowStatusDetail.Failed
        RowStatus.Quiet -> RowStatusDetail.None
    }

    /** ot3 `state(t)`: the bucket of a detail. */
    fun bucket(detail: RowStatusDetail): StatusBucket = when (detail) {
        RowStatusDetail.Approval, RowStatusDetail.Input, RowStatusDetail.PlanReady, RowStatusDetail.Waiting -> StatusBucket.Waiting
        RowStatusDetail.Goal, RowStatusDetail.Working -> StatusBucket.Working
        RowStatusDetail.Limited, RowStatusDetail.Failed -> StatusBucket.Failed
        RowStatusDetail.Done -> StatusBucket.Done
        RowStatusDetail.Woke -> StatusBucket.Woke
        RowStatusDetail.None -> StatusBucket.Idle
    }

    /**
     * ot3's `pending`: something waits for the user and the thread is not snoozed. In ot3 a
     * completion to review counts too; that needs U2, so interim only the waiting bucket does.
     */
    fun pending(row: SidebarRow, detail: RowStatusDetail = detail(row)): Boolean =
        !row.snoozed && bucket(detail) == StatusBucket.Waiting
}
