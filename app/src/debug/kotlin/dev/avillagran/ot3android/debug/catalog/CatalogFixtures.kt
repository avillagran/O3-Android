package dev.avillagran.ot3android.debug.catalog

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.ui.kit.PrBadgeModel
import dev.avillagran.ot3android.ui.kit.PrState
import dev.avillagran.ot3android.ui.kit.ProjectChipKind
import dev.avillagran.ot3android.ui.kit.ProjectChipModel
import dev.avillagran.ot3android.ui.kit.RowStatus
import dev.avillagran.ot3android.ui.kit.RowTime
import dev.avillagran.ot3android.ui.kit.StatusIcons
import dev.avillagran.ot3android.ui.kit.StatusRole
import dev.avillagran.ot3android.ui.kit.SubagentsModel
import dev.avillagran.ot3android.ui.kit.ThreadRowModel
import dev.avillagran.ot3android.ui.kit.TimeKind
import dev.avillagran.ot3android.ui.theme.Ot3Theme

/*
 * Catalog data: the projects and threads of ot3's reference capture
 * (docs/ui/reference/ot3-sidebar-and-thread.png), shaped as `sidebar.view` rows would be.
 */

/** A project of the capture: name, monogram and palette slot. */
internal data class CatalogProject(val name: String, val monogram: String, val slot: Int)

internal val CatalogProjects = listOf(
    CatalogProject("ohm-launcher", "OL", 0),
    CatalogProject("omarchy-animated-backgrounds", "OB", 1),
    CatalogProject("flea", "FA", 2),
    CatalogProject("openarena", "OA", 3),
    CatalogProject("omarchy-t3-code", "OC", 4),
    CatalogProject("ReportePolitico", "RO", 5),
    CatalogProject("AI DC", "AD", 6),
)

@Composable
internal fun CatalogProject.chip(): ProjectChipModel {
    val palette = Ot3Theme.colors.projectPalette
    return ProjectChipModel(monogram, palette[slot % palette.size])
}

@Composable
internal fun emojiChip(): ProjectChipModel = ProjectChipModel("🚀", Ot3Theme.colors.projectPalette[0], 0f, ProjectChipKind.EMOJI)

/** The ten details with ot3's labels and the lib's roles (`lib:src/sidebar/view.rs` `role`). */
@Composable
internal fun catalogStatuses(): List<Pair<String, RowStatus>> {
    fun role(detail: String) = when (detail) {
        "working", "goal" -> StatusRole.WORKING
        "done" -> StatusRole.DONE
        "failed" -> StatusRole.FAILED
        "limited", "woke", "approval" -> StatusRole.WARNING
        "input" -> StatusRole.INPUT
        "plan" -> StatusRole.WAITING
        else -> StatusRole.MUTED
    }
    val labels = mapOf(
        "approval" to stringResource(R.string.ot3_status_approval),
        "input" to stringResource(R.string.ot3_status_input),
        "plan" to stringResource(R.string.ot3_status_plan),
        "waiting" to stringResource(R.string.ot3_status_waiting),
        "goal" to stringResource(R.string.ot3_status_goal),
        "working" to stringResource(R.string.ot3_status_working),
        "limited" to stringResource(R.string.ot3_status_limited),
        "failed" to stringResource(R.string.ot3_status_failed),
        "woke" to stringResource(R.string.ot3_status_woke),
        "done" to stringResource(R.string.ot3_status_done),
    )
    return StatusIcons.DETAILS.map { detail -> detail to RowStatus(role(detail), labels.getValue(detail), StatusIcons.forDetail(detail)) }
}

private val Titles = listOf(
    "Continue Herdr/Omi safe sync work",
    "Menú de fondos para Omarchy 4.5",
    "PR Fix",
    "Integrar anuncios en port Android",
    "Migrar Omarchy Ares a Plugin",
    "Verificar uso de EmbeddingGemma 2",
    "Review banner and interstitial ad flow",
    "Port the sky header to AGSL",
    "Settle the shelf animation timings",
    "Answer the reviewer on the lib bump",
)

/** One card row per detail, in ot3's order, with the time each status would carry. */
@Composable
internal fun catalogCardRows(): List<ThreadRowModel> = catalogStatuses().mapIndexed { i, (detail, status) ->
    val project = CatalogProjects[i % CatalogProjects.size]
    ThreadRowModel(
        title = Titles[i],
        projectName = project.name,
        project = project.chip(),
        status = status,
        time = when (detail) {
            "working", "goal" -> RowTime(TimeKind.ELAPSED, "3m 5s")
            "done" -> RowTime(TimeKind.AGE, "11h")
            else -> RowTime(TimeKind.AGE, "5m")
        },
        branch = if (i % 3 == 2) null else "main",
        workspaceIcon = if (i % 3 == 0) "folder-git-2" else null,
        pullRequest = when (i) {
            1 -> PrBadgeModel(PrState.OPEN, "9639")
            9 -> PrBadgeModel(PrState.MERGED, "314")
            else -> null
        },
        machineIcon = if (i == 4) "server" else null,
        receding = detail == "working",
    )
}

/** Card rows showing each optional piece once. */
@Composable
internal fun catalogCardVariants(): List<Pair<String, ThreadRowModel>> {
    val statuses = catalogStatuses().toMap()
    val oc = CatalogProjects[4]
    val base = ThreadRowModel(
        title = "Migrar Omarchy Ares a Plugin",
        projectName = oc.name,
        project = oc.chip(),
        status = statuses.getValue("done"),
        time = RowTime(TimeKind.AGE, "now"),
        branch = "feat/ares-plugin",
        workspaceIcon = "folder-git-2",
    )
    return listOf(
        "draft" to base.copy(draft = true, title = "Draft: rename the plugin hooks"),
        "pinned" to base.copy(pinned = true, status = null),
        "pr" to base.copy(pullRequest = PrBadgeModel(PrState.CLOSED, "12"), machineIcon = "cloud"),
        "stack" to base.copy(pullRequest = PrBadgeModel(PrState.OPEN, "#41 +2", stack = true)),
        "environment" to base.copy(environmentName = "workstation", status = statuses.getValue("failed")),
        "subagents" to base.copy(
            status = statuses.getValue("working"),
            time = RowTime(TimeKind.ELAPSED, "12s"),
            subagents = SubagentsModel(count = 3, working = 1, expanded = false),
        ),
        "emoji" to base.copy(project = emojiChip(), projectName = "Project", branch = "main", workspaceIcon = null),
    )
}

/** Slim rows: settled, snoozed (countdown), with PR, pinned, draft, and subagents in all ten statuses. */
@Composable
internal fun catalogSlimRows(): List<ThreadRowModel> {
    val settled = listOf(
        ThreadRowModel("settled", "Project", CatalogProjects[0].chip(), time = RowTime(TimeKind.AGE, "now"), receding = true),
        ThreadRowModel("snoozed", "Project", CatalogProjects[1].chip(), time = RowTime(TimeKind.COUNTDOWN, "3h")),
        ThreadRowModel("Integrar anuncios", "openarena", CatalogProjects[3].chip(), time = RowTime(TimeKind.AGE, "2d"), pullRequest = PrBadgeModel(PrState.MERGED, "88")),
        ThreadRowModel("Pinned and settled", "flea", CatalogProjects[2].chip(), time = RowTime(TimeKind.AGE, "5m"), pinned = true),
        ThreadRowModel("With a draft", "flea", CatalogProjects[2].chip(), time = RowTime(TimeKind.AGE, "—"), draft = true),
    )
    val children = catalogStatuses().mapIndexed { i, (_, status) ->
        ThreadRowModel(
            title = "Subagent ${i + 1}",
            status = status,
            subagent = true,
            depth = if (i < 8) 1 else i - 6,
            time = RowTime(TimeKind.AGE, "1m"),
        )
    }
    return settled + children
}
