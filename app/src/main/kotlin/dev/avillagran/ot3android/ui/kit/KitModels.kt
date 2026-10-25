package dev.avillagran.ot3android.ui.kit

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import dev.avillagran.ot3android.ui.theme.Ot3Blend
import dev.avillagran.ot3android.ui.theme.Ot3Colors
import dev.avillagran.ot3android.ui.theme.Ot3Sizes

/*
 * Presentation values the kit draws. Each one is what the lib's `sidebar.view` and
 * `projects.identity` already return (lib 0.3.3: `detail`, `statusLabel`, `statusRole`,
 * `time{kind,at,label}`; `monogram`, `colorSlot`, `color`, `fill`), so screens map JSON
 * fields one to one and the kit derives nothing (UI_SPEC rule 6).
 */

/**
 * A row status colour (`ot3:ui/qml/T3Palette.qml:30-34`, `lib:src/sidebar/view.rs` `role`):
 * the lib's `statusRole` names, plus [MUTED], ot3's neutral fg @ 0.6.
 */
enum class StatusRole(val libName: String) {
    WORKING("working"),
    DONE("done"),
    WAITING("waiting"),
    FAILED("failed"),
    WARNING("warning"),
    INPUT("input"),
    MUTED("muted");

    companion object {
        /** The lib's `statusRole`; anything unknown is [MUTED], as in ot3. */
        fun fromLib(name: String?): StatusRole = entries.firstOrNull { it.libName == name } ?: MUTED
    }
}

/** The colour of [role] in these colours; [StatusRole.MUTED] is fg @ 0.6 over the background. */
fun Ot3Colors.statusColor(role: StatusRole): Color = when (role) {
    StatusRole.WORKING -> working
    StatusRole.DONE -> done
    StatusRole.WAITING -> waiting
    StatusRole.FAILED -> failed
    StatusRole.WARNING -> warning
    StatusRole.INPUT -> input
    StatusRole.MUTED -> Ot3Blend.over(foreground, background, 600)
}

/**
 * ot3's ten row details and their icons (`ot3:ui/qml/T3ThreadRow.qml:260-277`). The lib
 * returns the detail; the icon is ot3's QML choice, so it lives here.
 */
object StatusIcons {
    const val APPROVAL = "shield-question"
    const val QUESTION = "message-circle-question"
    const val WAITING = "circle-dot-dashed"
    const val WORKING = "circle-dashed"
    const val ALERT = "circle-alert"
    const val DONE = "circle-check"
    const val WOKE = "alarm-clock"

    /** The ten details, in ot3's order (`ot3:ui/js/SidebarModel.js:5-17`). */
    val DETAILS = listOf("approval", "input", "plan", "waiting", "goal", "working", "limited", "failed", "woke", "done")

    /** The icon of a lib `detail`; ot3 falls back to `circle-alert`. */
    fun forDetail(detail: String): String = when (detail) {
        "approval" -> APPROVAL
        "input", "plan" -> QUESTION
        "waiting" -> WAITING
        "goal", "working" -> WORKING
        "woke" -> WOKE
        "done" -> DONE
        else -> ALERT
    }

    /** Icons that turn while animations are on (UI_SPEC 3.7). */
    fun spins(iconName: String): Boolean = iconName == WORKING
}

/** A row's status: colour role, ot3's label ("Working", "Plan ready") and ot3 icon name. */
@Immutable
data class RowStatus(val role: StatusRole, val label: String, val iconName: String) {
    companion object {
        /** From `sidebar.view` row fields: `detail`, `statusLabel` (translated by the caller), `statusRole`. */
        fun fromLib(detail: String, label: String, role: String?): RowStatus =
            RowStatus(StatusRole.fromLib(role), label, StatusIcons.forDetail(detail))
    }
}

/** The lib's `time.kind`: elapsed while working, countdown while snoozed, otherwise age. */
enum class TimeKind(val libName: String) {
    ELAPSED("elapsed"),
    COUNTDOWN("countdown"),
    AGE("age");

    companion object {
        fun fromLib(name: String?): TimeKind = entries.firstOrNull { it.libName == name } ?: AGE
    }
}

/** The lib's `time`: [label] is drawn as given ("12s", "3m 5s", "now", "3h", "—"). */
@Immutable
data class RowTime(val kind: TimeKind, val label: String)

/** How a project chip draws its label (`ot3:ui/qml/T3ProjectIcon.qml:9-19`). */
enum class ProjectChipKind { MONOGRAM, EMOJI }

/**
 * A project chip: [label] (the monogram, or an emoji), the project's slot [color] and the
 * tint's opacity [fill] behind it (0.14 for monograms, 0 for emoji).
 */
@Immutable
data class ProjectChipModel(
    val label: String,
    val color: Color,
    val fill: Float = MONOGRAM_FILL,
    val kind: ProjectChipKind = ProjectChipKind.MONOGRAM,
) {
    companion object {
        const val MONOGRAM_FILL = 0.14f

        /**
         * From a `projects.identity` entry: [color] (`#rrggbb`) when the lib resolved it, otherwise
         * slot [colorSlot] of [palette] (the theme's project palette), as ot3's `pick` does.
         */
        fun fromLib(
            label: String,
            kind: String?,
            color: String?,
            colorSlot: Int,
            fill: Double,
            palette: List<Color>,
        ): ProjectChipModel {
            val resolved = color?.takeIf { HEX.matches(it) }?.let { Color(0xFF000000 or it.substring(1).toLong(16)) }
                ?: palette[Math.floorMod(colorSlot, palette.size)]
            return ProjectChipModel(
                label = label,
                color = resolved,
                fill = fill.toFloat(),
                kind = if (kind == "emoji") ProjectChipKind.EMOJI else ProjectChipKind.MONOGRAM,
            )
        }

        private val HEX = Regex("#[0-9a-fA-F]{6}")
    }
}

/** A PR's state as `pull_requests::sidebar_badge` reports it. */
enum class PrState(val libName: String) {
    OPEN("open"),
    MERGED("merged"),
    CLOSED("closed"),
    DRAFT("draft"),
    UNKNOWN("");

    companion object {
        fun fromLib(name: String?): PrState = entries.firstOrNull { it.libName == name && it != UNKNOWN } ?: UNKNOWN
    }
}

/**
 * A PR badge (`ot3:ui/qml/T3PullRequestBadge.qml:20-31`): [text] as the lib writes it ("314",
 * "#12 +2"), [stack] when the badge stands for a stack of PRs.
 */
@Immutable
data class PrBadgeModel(val state: PrState, val text: String, val stack: Boolean = false)

/** ot3's PR icon for a state; a stack is `layers`. */
fun PrBadgeModel.iconName(): String = when {
    stack -> "layers"
    state == PrState.MERGED -> "git-merge"
    state == PrState.CLOSED -> "git-pull-request-closed"
    state == PrState.DRAFT -> "git-pull-request-draft"
    else -> "git-pull-request"
}

/** Open done, merged magenta (the `input` role is magenta, else accent, as ot3 picks), closed failed, else dim. */
fun Ot3Colors.prColor(state: PrState): Color = when (state) {
    PrState.OPEN -> done
    PrState.MERGED -> input
    PrState.CLOSED -> failed
    PrState.DRAFT, PrState.UNKNOWN -> dim
}

/** The Home count pills: the lib's `sidebar.view` `counts`. */
@Immutable
data class CountPillsModel(val working: Int, val done: Int, val waiting: Int)

/** ot3's pill digits (`ot3:ui/qml/T3StatusBadges.qml:19,26`). */
object CountPillFormat {
    fun text(count: Int): String = if (count > 99) "99+" else count.coerceAtLeast(0).toString()

    /** 16 / 21 / 27 dp for one, two and three characters (ot3's 14 / 18 / 23 px × 7/6). */
    fun width(count: Int): Dp = Ot3Sizes.countPillWidths[
        when {
            count > 99 -> 2
            count > 9 -> 1
            else -> 0
        },
    ]
}

/** A row's subagent toggle ("Show {count} subagents", `ot3:ui/qml/T3ThreadRow.qml:244-256`). */
@Immutable
data class SubagentsModel(val count: Int, val working: Int, val expanded: Boolean)

/**
 * Everything a thread row draws, as plain values (UI_SPEC 4.1.3). Card rows show the caption
 * line, the title and the workspace line; slim rows (settled, snoozed, subagent) show chip,
 * title, PR badge and the time.
 */
@Immutable
data class ThreadRowModel(
    val title: String,
    val projectName: String = "",
    val project: ProjectChipModel? = null,
    /** The status label and icon, or null to show [time] instead. */
    val status: RowStatus? = null,
    val time: RowTime? = null,
    /** Branch, or the environment name for a scratch thread. */
    val branch: String? = null,
    /** `folder-git-2` for a worktree, a machine icon for a scratch thread, or none. */
    val workspaceIcon: String? = null,
    /** Shown after the project name when more than one environment is saved. */
    val environmentName: String? = null,
    /** The remote machine icon (`server`, `cloud`, `laptop`…), absent for the local one. */
    val machineIcon: String? = null,
    val pullRequest: PrBadgeModel? = null,
    val subagents: SubagentsModel? = null,
    val draft: Boolean = false,
    val pinned: Boolean = false,
    /** A subagent row: the bot icon instead of the project chip, its status label on slim rows. */
    val subagent: Boolean = false,
    /** Nesting under the parent row, 0–4. */
    val depth: Int = 0,
    /** ot3's receding rows (working without input, settled): title in `dim`, normal weight. */
    val receding: Boolean = false,
)

/** What the end of a row's caption line says, and in which colour role. */
@Immutable
data class RowTrailing(val text: String, val role: TrailingRole, val medium: Boolean)

enum class TrailingRole { STATUS, WORKING, DIM }

/**
 * ot3's caption-line ending (`ot3:ui/qml/T3ThreadRow.qml:287-288`): the status label on card
 * rows and subagent rows, with the elapsed time after "Working" / "Goal"; otherwise the time,
 * in `working` while a snooze counts down and in `dim` otherwise.
 */
fun rowTrailing(row: ThreadRowModel, slim: Boolean): RowTrailing? {
    val status = row.status
    val time = row.time?.label?.takeIf { it.isNotEmpty() }
    val labelled = (!slim || row.subagent) && status != null && status.label.isNotEmpty()
    return when {
        labelled -> {
            val elapsed = if (row.time?.kind == TimeKind.ELAPSED && status!!.role == StatusRole.WORKING) time else null
            RowTrailing(listOfNotNull(status!!.label, elapsed).joinToString(" "), TrailingRole.STATUS, medium = true)
        }
        time != null -> RowTrailing(time, if (row.time.kind == TimeKind.COUNTDOWN) TrailingRole.WORKING else TrailingRole.DIM, medium = false)
        else -> null
    }
}

/** The age under "Done" on card rows (`ot3:ui/qml/T3ThreadRow.qml:313-314`). */
fun rowDoneAge(row: ThreadRowModel): String? =
    row.time?.label?.takeIf { it.isNotEmpty() && row.status?.role == StatusRole.DONE && row.time.kind == TimeKind.AGE }

/** ot3's row name for accessibility: title · project · status (`ot3:ui/qml/T3ThreadRow.qml:93`). */
fun rowAccessibilityName(row: ThreadRowModel): String =
    listOf(row.title, row.projectName, row.status?.label.orEmpty()).filter { it.isNotEmpty() }.joinToString(" · ")
