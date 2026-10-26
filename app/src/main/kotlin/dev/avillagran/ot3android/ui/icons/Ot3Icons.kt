package dev.avillagran.ot3android.ui.icons

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import dev.avillagran.ot3android.R

/**
 * ot3's icon set by ot3 name (UI_SPEC 3.8): ot3's Lucide icons drawn at stroke 1.75, ot3's
 * `stop-filled` and the T3 mark. The drawables are generated at build time from
 * `app/src/main/svg/lucide/` (`tools/lucide-vectors`); they are white, so draw them with
 * `Icon(painter, tint = …)` in an ot3 colour role.
 *
 * To add an icon, copy its SVG into `app/src/main/svg/lucide/` from ot3 (`ui/assets/icons/`,
 * or `ui/js/IconAssets.js`), or from Lucide 0.564.0 if ot3 lacks it; record the source in
 * that folder's NOTICE.md and add the icon here.
 */
object Ot3Icons {
    @DrawableRes val ListTodo: Int = R.drawable.ic_list_todo
    @DrawableRes val Circle: Int = R.drawable.ic_circle
    @DrawableRes val CircleDot: Int = R.drawable.ic_circle_dot
    @DrawableRes val Star: Int = R.drawable.ic_star
    @DrawableRes val StarFilled: Int = R.drawable.ic_star_filled
    @DrawableRes val AlarmClock: Int = R.drawable.ic_alarm_clock
    @DrawableRes val ArrowUp: Int = R.drawable.ic_arrow_up
    @DrawableRes val Bot: Int = R.drawable.ic_bot
    @DrawableRes val ChartNoAxesColumn: Int = R.drawable.ic_chart_no_axes_column
    @DrawableRes val Check: Int = R.drawable.ic_check
    @DrawableRes val ChevronDown: Int = R.drawable.ic_chevron_down
    @DrawableRes val ChevronRight: Int = R.drawable.ic_chevron_right
    @DrawableRes val ChevronUp: Int = R.drawable.ic_chevron_up
    @DrawableRes val CircleAlert: Int = R.drawable.ic_circle_alert
    @DrawableRes val CircleCheck: Int = R.drawable.ic_circle_check
    @DrawableRes val CircleDashed: Int = R.drawable.ic_circle_dashed
    @DrawableRes val CircleDotDashed: Int = R.drawable.ic_circle_dot_dashed
    @DrawableRes val Clock: Int = R.drawable.ic_clock
    @DrawableRes val Cloud: Int = R.drawable.ic_cloud
    @DrawableRes val Copy: Int = R.drawable.ic_copy
    @DrawableRes val CornerUpRight: Int = R.drawable.ic_corner_up_right
    @DrawableRes val EllipsisVertical: Int = R.drawable.ic_ellipsis_vertical
    @DrawableRes val ExternalLink: Int = R.drawable.ic_external_link
    @DrawableRes val Eye: Int = R.drawable.ic_eye
    @DrawableRes val File: Int = R.drawable.ic_file
    @DrawableRes val FolderGit2: Int = R.drawable.ic_folder_git_2
    @DrawableRes val FolderPlus: Int = R.drawable.ic_folder_plus
    @DrawableRes val GitBranch: Int = R.drawable.ic_git_branch
    @DrawableRes val GitMerge: Int = R.drawable.ic_git_merge
    @DrawableRes val GitPullRequest: Int = R.drawable.ic_git_pull_request
    @DrawableRes val GitPullRequestClosed: Int = R.drawable.ic_git_pull_request_closed
    @DrawableRes val GitPullRequestDraft: Int = R.drawable.ic_git_pull_request_draft
    @DrawableRes val Laptop: Int = R.drawable.ic_laptop
    @DrawableRes val Layers: Int = R.drawable.ic_layers
    @DrawableRes val ListFilter: Int = R.drawable.ic_list_filter
    @DrawableRes val ListPlus: Int = R.drawable.ic_list_plus
    @DrawableRes val LoaderCircle: Int = R.drawable.ic_loader_circle
    @DrawableRes val MessageCircleQuestion: Int = R.drawable.ic_message_circle_question
    @DrawableRes val MessageSquare: Int = R.drawable.ic_message_square
    @DrawableRes val Monitor: Int = R.drawable.ic_monitor
    @DrawableRes val PanelLeft: Int = R.drawable.ic_panel_left
    @DrawableRes val PanelLeftClose: Int = R.drawable.ic_panel_left_close
    @DrawableRes val Paperclip: Int = R.drawable.ic_paperclip
    @DrawableRes val PenLine: Int = R.drawable.ic_pen_line
    @DrawableRes val Pin: Int = R.drawable.ic_pin
    @DrawableRes val PinOff: Int = R.drawable.ic_pin_off
    @DrawableRes val RefreshCw: Int = R.drawable.ic_refresh_cw
    @DrawableRes val Search: Int = R.drawable.ic_search
    @DrawableRes val Server: Int = R.drawable.ic_server
    @DrawableRes val Settings: Int = R.drawable.ic_settings
    @DrawableRes val ShieldQuestion: Int = R.drawable.ic_shield_question
    @DrawableRes val SlidersHorizontal: Int = R.drawable.ic_sliders_horizontal
    @DrawableRes val Smartphone: Int = R.drawable.ic_smartphone
    @DrawableRes val SquarePen: Int = R.drawable.ic_square_pen
    @DrawableRes val StopFilled: Int = R.drawable.ic_stop_filled
    @DrawableRes val T3Logo: Int = R.drawable.ic_t3_logo
    @DrawableRes val Undo2: Int = R.drawable.ic_undo_2
    @DrawableRes val X: Int = R.drawable.ic_x

    /** Every icon, keyed by its ot3 / Lucide name (`"git-pull-request"`). */
    val byName: Map<String, Int> = mapOf(
        "alarm-clock" to AlarmClock,
        "arrow-up" to ArrowUp,
        "bot" to Bot,
        "chart-no-axes-column" to ChartNoAxesColumn,
        "check" to Check,
        "chevron-down" to ChevronDown,
        "chevron-right" to ChevronRight,
        "chevron-up" to ChevronUp,
        "circle-alert" to CircleAlert,
        "circle-check" to CircleCheck,
        "circle-dashed" to CircleDashed,
        "circle-dot-dashed" to CircleDotDashed,
        "clock" to Clock,
        "cloud" to Cloud,
        "copy" to Copy,
        "corner-up-right" to CornerUpRight,
        "ellipsis-vertical" to EllipsisVertical,
        "external-link" to ExternalLink,
        "eye" to Eye,
        "file" to File,
        "folder-git-2" to FolderGit2,
        "folder-plus" to FolderPlus,
        "git-branch" to GitBranch,
        "git-merge" to GitMerge,
        "git-pull-request" to GitPullRequest,
        "git-pull-request-closed" to GitPullRequestClosed,
        "git-pull-request-draft" to GitPullRequestDraft,
        "laptop" to Laptop,
        "layers" to Layers,
        "list-filter" to ListFilter,
        "list-plus" to ListPlus,
        "loader-circle" to LoaderCircle,
        "message-circle-question" to MessageCircleQuestion,
        "message-square" to MessageSquare,
        "monitor" to Monitor,
        "panel-left" to PanelLeft,
        "panel-left-close" to PanelLeftClose,
        "paperclip" to Paperclip,
        "pen-line" to PenLine,
        "pin" to Pin,
        "pin-off" to PinOff,
        "refresh-cw" to RefreshCw,
        "search" to Search,
        "server" to Server,
        "settings" to Settings,
        "shield-question" to ShieldQuestion,
        "sliders-horizontal" to SlidersHorizontal,
        "smartphone" to Smartphone,
        "square-pen" to SquarePen,
        "stop-filled" to StopFilled,
        "star" to Star,
        "list-todo" to ListTodo,
        "circle" to Circle,
        "circle-dot" to CircleDot,
        "star-filled" to StarFilled,
        "t3-logo" to T3Logo,
        "undo-2" to Undo2,
        "x" to X,
    )

    @DrawableRes
    fun resource(name: String): Int = byName[name] ?: throw IllegalArgumentException("no ot3 icon named \"$name\"")

    @Composable
    fun painter(name: String): Painter = painterResource(resource(name))

    @Composable
    fun vector(name: String): ImageVector = ImageVector.vectorResource(resource(name))
}
