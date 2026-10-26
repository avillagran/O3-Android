package dev.avillagran.ot3android.debug.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.ui.kit.ActivityText
import dev.avillagran.ot3android.ui.kit.ConnectionDot
import dev.avillagran.ot3android.ui.kit.CopyButton
import dev.avillagran.ot3android.ui.kit.CopyState
import dev.avillagran.ot3android.ui.kit.CountPills
import dev.avillagran.ot3android.ui.kit.CountPillsModel
import dev.avillagran.ot3android.ui.kit.Ot3Button
import dev.avillagran.ot3android.ui.kit.Ot3ButtonStyle
import dev.avillagran.ot3android.ui.kit.Ot3Dialog
import dev.avillagran.ot3android.ui.kit.Ot3DialogSurface
import dev.avillagran.ot3android.ui.kit.Ot3IconButton
import dev.avillagran.ot3android.ui.kit.Ot3SelectField
import dev.avillagran.ot3android.ui.kit.Ot3SettingsGroup
import dev.avillagran.ot3android.ui.kit.Ot3SettingsRow
import dev.avillagran.ot3android.ui.kit.Ot3Sheet
import dev.avillagran.ot3android.ui.kit.Ot3SheetItem
import dev.avillagran.ot3android.ui.kit.Ot3Spinner
import dev.avillagran.ot3android.ui.kit.Ot3Switch
import dev.avillagran.ot3android.ui.kit.Ot3TextField
import dev.avillagran.ot3android.ui.kit.Ot3Tooltip
import dev.avillagran.ot3android.ui.kit.PrBadge
import dev.avillagran.ot3android.ui.kit.PrBadgeModel
import dev.avillagran.ot3android.ui.kit.PrState
import dev.avillagran.ot3android.ui.kit.ProjectChip
import dev.avillagran.ot3android.ui.kit.ShelfContent
import dev.avillagran.ot3android.ui.kit.ShelfHeader
import dev.avillagran.ot3android.ui.kit.ShelfTone
import dev.avillagran.ot3android.ui.kit.StatusIcon
import dev.avillagran.ot3android.ui.kit.ThreadCardRow
import dev.avillagran.ot3android.ui.kit.ThreadSlimRow
import dev.avillagran.ot3android.ui.theme.Ot3Spacing
import dev.avillagran.ot3android.ui.theme.Ot3Theme

/*
 * The catalog's sections. Each one is a screenshot sheet of its own (CatalogScreenshotTest),
 * so each fits a 411 × 891 dp phone.
 */

/** A section title: titleSmall in `dim`, with the phone gutter. */
@Composable
internal fun CatalogHeading(text: String) {
    Text(
        text,
        style = Ot3Theme.typography.material.titleSmall,
        color = Ot3Theme.colors.dim,
        modifier = Modifier.padding(horizontal = Ot3Spacing.phoneGutter, vertical = Ot3Spacing.lg).semantics { heading() },
    )
}

/** A row of samples with the gutter. */
@Composable
private fun Samples(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Ot3Spacing.phoneGutter),
        horizontalArrangement = Arrangement.spacedBy(Ot3Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/** Buttons, icon buttons, copy states, fields, select, switches and a settings group. */
@Composable
internal fun CatalogControls() {
    Column(verticalArrangement = Arrangement.spacedBy(Ot3Spacing.xs)) {
        CatalogHeading(stringResource(R.string.catalog_controls))
        Samples {
            Ot3Button(stringResource(R.string.ot3_dialog_save), {})
            Ot3Button(stringResource(R.string.ot3_dialog_cancel), {}, style = Ot3ButtonStyle.SECONDARY)
            Ot3Button(stringResource(R.string.ot3_home_refresh), {}, style = Ot3ButtonStyle.OUTLINE, iconName = "refresh-cw")
        }
        Samples {
            Ot3Button(stringResource(R.string.ot3_dialog_save), {}, enabled = false)
            Ot3Button(stringResource(R.string.ot3_action_delete), {}, style = Ot3ButtonStyle.OUTLINE, destructive = true)
            Ot3Button(stringResource(R.string.ot3_action_delete), {}, destructive = true)
        }
        Samples {
            Ot3IconButton("list-filter", stringResource(R.string.ot3_filter_button), {})
            Ot3IconButton("folder-plus", stringResource(R.string.ot3_home_add_project), {})
            Ot3IconButton("square-pen", stringResource(R.string.ot3_home_new_thread), {})
            Ot3IconButton("refresh-cw", stringResource(R.string.ot3_home_refresh), {}, enabled = false)
        }
        Samples {
            for (state in CopyState.entries) CopyButton(state = state, onClick = {})
            CopyButton(state = CopyState.IDLE, onClick = {}, enabled = false, label = stringResource(R.string.ot3_kit_copy_code))
        }
        var query by remember { mutableStateOf("") }
        var name by remember { mutableStateOf("omarchy-t3-code") }
        Column(
            Modifier.padding(horizontal = Ot3Spacing.phoneGutter),
            verticalArrangement = Arrangement.spacedBy(Ot3Spacing.lg),
        ) {
            Ot3TextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = stringResource(R.string.ot3_search_threads_placeholder), leadingIcon = "search")
            Ot3TextField(name, { name = it }, Modifier.fillMaxWidth(), label = stringResource(R.string.ot3_project_name))
            Row(horizontalArrangement = Arrangement.spacedBy(Ot3Spacing.lg)) {
                Ot3SelectField(stringResource(R.string.ot3_filter_all_projects), stringResource(R.string.ot3_filter_project), {}, Modifier.weight(1f))
                Ot3SelectField(stringResource(R.string.ot3_project_keep_separate), stringResource(R.string.ot3_project_group_into), {}, Modifier.weight(1f), enabled = false)
            }
        }
        Samples {
            Ot3Switch(checked = true, onCheckedChange = {})
            Ot3Switch(checked = false, onCheckedChange = {})
            Ot3Switch(checked = true, onCheckedChange = {}, enabled = false)
            Ot3Switch(checked = false, onCheckedChange = {}, enabled = false)
        }
        Box(Modifier.padding(horizontal = Ot3Spacing.phoneGutter, vertical = Ot3Spacing.sm)) {
            var autoPull by remember { mutableStateOf(true) }
            Ot3SettingsGroup(stringResource(R.string.ot3_action_project_settings), iconName = "folder-git-2") {
                Ot3SettingsRow(
                    stringResource(R.string.ot3_project_auto_pull),
                    onClick = { autoPull = !autoPull },
                ) { Ot3Switch(autoPull, onCheckedChange = null) }
                Ot3SettingsRow(stringResource(R.string.ot3_project_default_model), divided = true) {
                    Ot3SelectField(stringResource(R.string.ot3_project_use_global_default), stringResource(R.string.ot3_project_default_model), {})
                }
                Ot3SettingsRow(
                    stringResource(R.string.ot3_project_delete_threads),
                    description = stringResource(R.string.ot3_project_remove_body),
                    divided = true,
                ) { Ot3Switch(false, onCheckedChange = null) }
            }
        }
        Samples {
            Ot3Spinner()
            Ot3Spinner(size = 20.dp, tint = Ot3Theme.colors.working)
            ActivityText("Running gradle :app:testDebugUnitTest", Modifier.weight(1f))
            ConnectionDot(live = true)
            ConnectionDot(live = false)
        }
    }
}

/** The ten statuses as card rows. */
@Composable
internal fun CatalogCardRows() {
    Column {
        CatalogHeading(stringResource(R.string.catalog_card_rows))
        Column(Modifier.padding(horizontal = Ot3Spacing.lg)) {
            catalogCardRows().forEach { ThreadCardRow(it, onClick = {}, onLongClick = {}) }
        }
    }
}

/** Draft, pinned, PR, stack, environment, subagents and emoji card rows, then a selected one. */
@Composable
internal fun CatalogCardVariants() {
    Column {
        CatalogHeading(stringResource(R.string.catalog_card_variants))
        Column(Modifier.padding(horizontal = Ot3Spacing.lg)) {
            val variants = catalogCardVariants()
            variants.forEach { (_, row) -> ThreadCardRow(row, onClick = {}, onUnpin = {}, onSubagentsClick = {}, onPullRequestClick = {}) }
            ThreadCardRow(variants.first().second.copy(draft = false, title = "selected"), onClick = {}, selected = true)
        }
    }
}

/** Settled, snoozed and subagent slim rows. */
@Composable
internal fun CatalogSlimRows() {
    Column {
        CatalogHeading(stringResource(R.string.catalog_slim_rows))
        Column(Modifier.padding(horizontal = Ot3Spacing.lg)) {
            catalogSlimRows().forEach { ThreadSlimRow(it, onClick = {}, onPullRequestClick = {}) }
        }
    }
}

/** ot3's three shelves, expanded and collapsed, with rows under the expanded ones. */
@Composable
internal fun CatalogShelves() {
    val slim = catalogSlimRows()
    val card = catalogCardRows()
    var working by remember { mutableStateOf(true) }
    var snoozed by remember { mutableStateOf(true) }
    var settled by remember { mutableStateOf(false) }
    Column {
        CatalogHeading(stringResource(R.string.catalog_shelves))
        Column(Modifier.padding(horizontal = Ot3Spacing.lg)) {
            ShelfHeader(stringResource(R.string.ot3_shelf_working, "1"), working, { working = !working }, tone = ShelfTone.WORKING)
            ShelfContent(working) { ThreadCardRow(card[5], onClick = {}) }
            ShelfHeader(stringResource(R.string.ot3_shelf_snoozed, "1"), snoozed, { snoozed = !snoozed })
            ShelfContent(snoozed) { ThreadSlimRow(slim[1], onClick = {}) }
            ShelfHeader(stringResource(R.string.ot3_shelf_settled, "5"), settled, { settled = !settled })
            ShelfContent(settled) { slim.take(5).forEach { ThreadSlimRow(it, onClick = {}) } }
            ShelfHeader(stringResource(R.string.ot3_shelf_working, "0"), expanded = false, onToggle = {}, tone = ShelfTone.WORKING)
            ShelfHeader(stringResource(R.string.ot3_shelf_snoozed, "0"), expanded = false, onToggle = {})
            ShelfHeader(stringResource(R.string.ot3_shelf_settled, "5"), expanded = true, onToggle = {})
            slim.take(1).forEach { ThreadSlimRow(it, onClick = {}) }
        }
    }
}

/** Project chips, PR badges, count pills, status icons and connection dots. */
@Composable
internal fun CatalogBadges() {
    val colors = Ot3Theme.colors
    val type = Ot3Theme.typography.material
    Column(verticalArrangement = Arrangement.spacedBy(Ot3Spacing.md)) {
        CatalogHeading(stringResource(R.string.catalog_badges))
        Samples {
            CatalogProjects.forEach { ProjectChip(it.chip()) }
            ProjectChip(emojiChip())
        }
        Samples {
            CatalogProjects.take(4).forEach { ProjectChip(it.chip(), size = 32.dp) }
        }
        Samples {
            PrBadge(PrBadgeModel(PrState.OPEN, "314"), onClick = {})
            PrBadge(PrBadgeModel(PrState.MERGED, "9639"), onClick = {})
            PrBadge(PrBadgeModel(PrState.CLOSED, "12"), onClick = {})
            PrBadge(PrBadgeModel(PrState.DRAFT, "7"), onClick = {})
            PrBadge(PrBadgeModel(PrState.OPEN, "#41 +2", stack = true), onClick = {})
            PrBadge(PrBadgeModel(PrState.UNKNOWN, "3"))
        }
        Samples {
            CountPills(CountPillsModel(working = 0, done = 9, waiting = 0))
            CountPills(CountPillsModel(working = 3, done = 2, waiting = 1))
            CountPills(CountPillsModel(working = 42, done = 7, waiting = 12))
            CountPills(CountPillsModel(working = 120, done = 5, waiting = 340))
        }
        Column(
            Modifier.padding(horizontal = Ot3Spacing.phoneGutter),
            verticalArrangement = Arrangement.spacedBy(Ot3Spacing.sm),
        ) {
            catalogStatuses().chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(Ot3Spacing.huge)) {
                    pair.forEach { (detail, status) ->
                        Row(
                            Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(Ot3Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            StatusIcon(status)
                            Text(status.label, style = type.bodySmall, color = colors.foreground)
                            Text(detail, style = Ot3Theme.typography.markdownCode, color = colors.dim)
                        }
                    }
                }
            }
        }
        Samples {
            ConnectionDot(live = true)
            Text(stringResource(R.string.ot3_env_connected), style = type.bodySmall, color = colors.dim)
            ConnectionDot(live = false)
            Text(stringResource(R.string.ot3_env_offline), style = type.bodySmall, color = colors.dim)
            Ot3Spinner(active = false)
            ActivityText(stringResource(R.string.ot3_loading), active = false)
        }
    }
}

/** The dialog cards inline (Rename with its field, Delete), without a window. */
@Composable
internal fun CatalogDialogCards() {
    Column(Modifier.padding(horizontal = Ot3Spacing.phoneGutter), verticalArrangement = Arrangement.spacedBy(Ot3Spacing.lg)) {
        RenameDialogCard()
        DeleteDialogCard()
    }
}

/** The overlays' section page: heading and the inline dialog cards. */
@Composable
internal fun CatalogOverlayCards() {
    Column(verticalArrangement = Arrangement.spacedBy(Ot3Spacing.sm)) {
        CatalogHeading(stringResource(R.string.catalog_overlays))
        CatalogDialogCards()
    }
}

@Composable
private fun RenameDialogCard(onDismiss: () -> Unit = {}) {
    var title by remember { mutableStateOf("Migrar Omarchy Ares a Plugin") }
    Ot3DialogSurface(
        title = stringResource(R.string.ot3_action_rename),
        subtitle = "Migrar Omarchy Ares a Plugin",
        confirmText = stringResource(R.string.ot3_dialog_save),
        onConfirm = onDismiss,
        dismissText = stringResource(R.string.ot3_dialog_cancel),
        onDismiss = onDismiss,
    ) {
        Ot3TextField(title, { title = it }, Modifier.fillMaxWidth(), label = stringResource(R.string.ot3_action_rename))
    }
}

@Composable
private fun DeleteDialogCard(onDismiss: () -> Unit = {}) {
    Ot3DialogSurface(
        title = stringResource(R.string.ot3_action_delete),
        subtitle = "Verificar uso de EmbeddingGemma 2",
        message = stringResource(R.string.ot3_dialog_delete_thread_body) + "\n" + stringResource(R.string.ot3_dialog_cannot_undo),
        confirmText = stringResource(R.string.ot3_action_delete),
        onConfirm = onDismiss,
        dismissText = stringResource(R.string.ot3_dialog_cancel),
        onDismiss = onDismiss,
        destructive = true,
    )
}

/** A page under the overlays, so the scrim has something to dim. */
@Composable
internal fun OverlayBackdrop(content: @Composable ColumnScope.() -> Unit = {}) {
    Column(Modifier.fillMaxSize()) {
        CatalogCardRows()
        content()
    }
}

/** The select's sheet, open: the theme list of the Project filter. */
@Composable
internal fun CatalogSheetOpen(onDismiss: () -> Unit = {}) {
    val projects = listOf(stringResource(R.string.ot3_filter_all_projects)) + CatalogProjects.map { it.name }
    OverlayBackdrop()
    Ot3Sheet(onDismissRequest = onDismiss, title = stringResource(R.string.ot3_filter_project)) {
        projects.forEachIndexed { i, name ->
            Ot3SheetItem(name, onClick = onDismiss, selected = i == 4, iconName = if (i == 0) "list-filter" else null)
        }
        Ot3SheetItem(stringResource(R.string.ot3_project_remove), onClick = onDismiss, iconName = "x", destructive = true)
    }
}

/**
 * ot3's dialog, open in its window: Archive thread, or Delete when [destructive]. Rename,
 * with its field, is drawn inline by [CatalogDialogCards]: under Robolectric a text field in
 * a dialog window never lets Compose go idle.
 */
@Composable
internal fun CatalogDialogOpen(destructive: Boolean, onDismiss: () -> Unit = {}) {
    OverlayBackdrop()
    if (destructive) {
        Ot3Dialog(
            onDismissRequest = onDismiss,
            title = stringResource(R.string.ot3_action_delete),
            subtitle = "Verificar uso de EmbeddingGemma 2",
            message = stringResource(R.string.ot3_dialog_delete_thread_body) + "\n" + stringResource(R.string.ot3_dialog_cannot_undo),
            confirmText = stringResource(R.string.ot3_action_delete),
            onConfirm = onDismiss,
            dismissText = stringResource(R.string.ot3_dialog_cancel),
            destructive = true,
        )
    } else {
        Ot3Dialog(
            onDismissRequest = onDismiss,
            title = stringResource(R.string.ot3_action_archive_thread),
            subtitle = "Migrar Omarchy Ares a Plugin",
            confirmText = stringResource(R.string.ot3_action_archive_thread),
            onConfirm = onDismiss,
            dismissText = stringResource(R.string.ot3_dialog_cancel),
        )
    }
}

/** Home's toolbar icon buttons, the filter's long-press tooltip showing. */
@Composable
internal fun CatalogTooltipOpen() {
    Column {
        CatalogHeading(stringResource(R.string.catalog_overlays))
        Box(Modifier.padding(top = 96.dp)) {
            Samples {
                Ot3IconButton("search", stringResource(R.string.ot3_search_threads_placeholder), {})
                Ot3Tooltip(stringResource(R.string.ot3_filter_button), initiallyVisible = true) {
                    Ot3IconButton("list-filter", stringResource(R.string.ot3_filter_button), {})
                }
                Ot3IconButton("folder-plus", stringResource(R.string.ot3_home_add_project), {})
                Ot3IconButton("square-pen", stringResource(R.string.ot3_home_new_thread), {})
            }
        }
    }
}
