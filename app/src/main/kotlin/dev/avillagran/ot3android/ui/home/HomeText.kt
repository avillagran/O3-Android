package dev.avillagran.ot3android.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.core.home.HeaderStatus
import dev.avillagran.ot3android.core.home.HomeRow
import dev.avillagran.ot3android.core.home.ProjectAction
import dev.avillagran.ot3android.core.home.RowStatusDetail
import dev.avillagran.ot3android.core.home.RowTime
import dev.avillagran.ot3android.core.home.Shelf
import dev.avillagran.ot3android.core.home.SnoozeOption
import dev.avillagran.ot3android.core.home.StatusFilter
import dev.avillagran.ot3android.core.home.SwipeAction
import dev.avillagran.ot3android.core.home.ThreadAction
import dev.avillagran.ot3android.ui.common.errorText

/** Texts of Home's state. Plain resources until ui-1b's generated ot3 catalog replaces them. */

@Composable
fun statusText(detail: RowStatusDetail): String = when (detail) {
    RowStatusDetail.Approval -> stringResource(R.string.ot3_status_approval)
    RowStatusDetail.Input -> stringResource(R.string.ot3_status_input)
    RowStatusDetail.PlanReady -> stringResource(R.string.ot3_status_plan)
    RowStatusDetail.Waiting -> stringResource(R.string.ot3_status_waiting)
    RowStatusDetail.Goal -> stringResource(R.string.ot3_status_goal)
    RowStatusDetail.Working -> stringResource(R.string.ot3_status_working)
    RowStatusDetail.Limited -> stringResource(R.string.ot3_status_limited)
    RowStatusDetail.Failed -> stringResource(R.string.ot3_status_failed)
    RowStatusDetail.Woke -> stringResource(R.string.ot3_status_woke)
    RowStatusDetail.Done -> stringResource(R.string.ot3_status_done)
    RowStatusDetail.None -> ""
}

@Composable
fun timeText(time: RowTime): String = time.text(stringResource(R.string.ot3_row_time_now))

/** The header text; [HeaderStatus.Hidden] is empty. */
@Composable
fun headerText(status: HeaderStatus): String = when (status) {
    HeaderStatus.Hidden -> ""
    HeaderStatus.Offline -> stringResource(R.string.mobile_you_are_offline)
    HeaderStatus.Syncing -> stringResource(R.string.mobile_syncing_threads)
    HeaderStatus.Loading -> stringResource(R.string.mobile_loading_threads)
    is HeaderStatus.Reconnecting -> stringResource(R.string.mobile_reconnecting_env, status.environmentLabel)
    is HeaderStatus.ReconnectingMany -> stringResource(R.string.mobile_reconnecting_envs, status.count)
    is HeaderStatus.Error -> errorText(status.code)
    HeaderStatus.NotConnected -> stringResource(R.string.state_disconnected)
}

@Composable
fun shelfText(shelf: Shelf, count: Int): String = when (shelf) {
    Shelf.Working -> stringResource(R.string.ot3_shelf_working, count)
    Shelf.Snoozed -> stringResource(R.string.ot3_shelf_snoozed, count)
    Shelf.Settled -> stringResource(R.string.ot3_shelf_settled, count)
}

@Composable
fun statusFilterText(filter: StatusFilter): String = when (filter) {
    StatusFilter.All -> stringResource(R.string.ot3_filter_all)
    StatusFilter.Working -> stringResource(R.string.ot3_status_working)
    StatusFilter.Done -> stringResource(R.string.ot3_status_done)
    StatusFilter.Failed -> stringResource(R.string.ot3_status_failed)
    StatusFilter.Pending -> stringResource(R.string.ot3_filter_pending)
    StatusFilter.Settled -> stringResource(R.string.ot3_filter_settled)
}

@Composable
fun threadActionText(action: ThreadAction, row: HomeRow): String = when (action) {
    ThreadAction.NewThreadOnBranch -> stringResource(R.string.ot3_action_new_thread_on_branch, row.branch.orEmpty())
    ThreadAction.Pin -> stringResource(R.string.ot3_action_pin)
    ThreadAction.Unpin -> stringResource(R.string.ot3_action_unpin)
    ThreadAction.MoveUp -> stringResource(R.string.ot3_action_move_up)
    ThreadAction.MoveDown -> stringResource(R.string.ot3_action_move_down)
    ThreadAction.MarkSettled -> stringResource(R.string.ot3_action_mark_settled)
    ThreadAction.MoveToActive -> stringResource(R.string.ot3_action_move_to_active)
    ThreadAction.Snooze -> stringResource(R.string.ot3_action_snooze)
    ThreadAction.WakeNow -> stringResource(R.string.ot3_action_wake)
    ThreadAction.Rename -> stringResource(R.string.ot3_action_rename)
    ThreadAction.RegenerateTitle -> stringResource(R.string.ot3_action_regenerate_title)
    ThreadAction.MarkUnread -> stringResource(R.string.ot3_action_mark_unread)
    ThreadAction.FilterByProject -> stringResource(R.string.ot3_action_filter_by_project, row.projectTitle ?: row.projectId.orEmpty())
    ThreadAction.AllProjects -> stringResource(R.string.ot3_filter_all_projects)
    ThreadAction.AutoSettle -> stringResource(R.string.ot3_action_auto_settle)
    ThreadAction.AutoSettleEnabled -> stringResource(R.string.ot3_action_enabled)
    ThreadAction.AutoSettleDisabled -> stringResource(R.string.ot3_action_disabled)
    ThreadAction.Copy -> stringResource(R.string.ot3_action_copy)
    ThreadAction.CopyPath -> stringResource(R.string.ot3_action_copy_path)
    ThreadAction.CopyBranch -> stringResource(R.string.ot3_action_copy_branch)
    ThreadAction.CopyThreadId -> stringResource(R.string.ot3_action_copy_thread_id)
    ThreadAction.ProjectSettings -> stringResource(R.string.ot3_action_project_settings)
    ThreadAction.Archive -> stringResource(R.string.ot3_action_archive_thread)
    ThreadAction.Delete -> stringResource(R.string.ot3_action_delete)
}

@Composable
fun projectActionText(action: ProjectAction): String = when (action) {
    ProjectAction.Rename -> stringResource(R.string.ot3_project_rename)
    ProjectAction.GroupInto -> stringResource(R.string.ot3_project_group_into)
    ProjectAction.CopyPath -> stringResource(R.string.ot3_project_copy_path)
    ProjectAction.ProjectSettings -> stringResource(R.string.ot3_action_project_settings)
    ProjectAction.Remove -> stringResource(R.string.ot3_project_remove)
}

@Composable
fun snoozeText(option: SnoozeOption): String = when (option) {
    SnoozeOption.InOneHour -> stringResource(R.string.ot3_snooze_in_1_hour)
    SnoozeOption.InThreeHours -> stringResource(R.string.ot3_snooze_in_3_hours)
    SnoozeOption.ThisEvening -> stringResource(R.string.ot3_snooze_this_evening)
    SnoozeOption.Tomorrow -> stringResource(R.string.ot3_snooze_tomorrow)
    SnoozeOption.NextWeek -> stringResource(R.string.ot3_snooze_next_week)
    SnoozeOption.Custom -> stringResource(R.string.ot3_snooze_custom)
}

@Composable
fun swipeText(action: SwipeAction): String = when (action) {
    SwipeAction.Settle -> stringResource(R.string.ot3_action_settle)
    SwipeAction.MoveToActive -> stringResource(R.string.ot3_action_move_to_active)
    SwipeAction.Wake -> stringResource(R.string.ot3_action_wake)
    SwipeAction.Archive -> stringResource(R.string.ot3_action_archive)
    SwipeAction.Snooze -> stringResource(R.string.ot3_action_snooze)
}
