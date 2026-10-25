package dev.avillagran.ot3android.debug.catalog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.ui.kit.Ot3Button
import dev.avillagran.ot3android.ui.kit.Ot3ButtonStyle
import dev.avillagran.ot3android.ui.kit.Ot3Select
import dev.avillagran.ot3android.ui.kit.Ot3SettingsRow
import dev.avillagran.ot3android.ui.kit.Ot3Switch
import dev.avillagran.ot3android.ui.theme.OmarchyThemeCatalog
import dev.avillagran.ot3android.ui.theme.Ot3Colors
import dev.avillagran.ot3android.ui.theme.Ot3MotionPolicy
import dev.avillagran.ot3android.ui.theme.Ot3Spacing
import dev.avillagran.ot3android.ui.theme.Ot3Theme

/**
 * Debug builds only: every kit component in every state (UI_SPEC 3.9), under any of the 22
 * Omarchy themes, as T3 Mobile's dev catalog does. Its sections are also the screenshot
 * fixtures (CatalogScreenshotTest).
 */
class ComponentCatalogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ComponentCatalogScreen() }
    }
}

@Composable
internal fun ComponentCatalogScreen() {
    val catalog = OmarchyThemeCatalog.loadBlocking(LocalContext.current)
    val themes = catalog.themes
    var themeIndex by rememberSaveable { mutableIntStateOf(themes.indexOfFirst { it.id == "tokyo-night" }.coerceAtLeast(0)) }
    var animate by rememberSaveable { mutableStateOf(true) }
    val colors = remember(themeIndex) { Ot3Colors.fromTheme(themes[themeIndex]) }
    Ot3Theme(colors = colors, motion = if (animate) Ot3MotionPolicy.Full else Ot3MotionPolicy.Off) {
        Surface(Modifier.fillMaxSize()) {
            var overlay by remember { mutableStateOf<Overlay?>(null) }
            LazyColumn(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                item {
                    Column(Modifier.padding(horizontal = Ot3Spacing.phoneGutter, vertical = Ot3Spacing.lg)) {
                        Text(stringResource(R.string.mobile_component_catalog), style = Ot3Theme.typography.material.titleMedium)
                        Row(
                            Modifier.fillMaxWidth().padding(top = Ot3Spacing.lg),
                            horizontalArrangement = Arrangement.spacedBy(Ot3Spacing.lg),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(R.string.ot3_catalog_theme), style = Ot3Theme.typography.material.bodyMedium)
                            Ot3Select(
                                label = stringResource(R.string.ot3_catalog_theme),
                                options = themes.map { it.name },
                                selectedIndex = themeIndex,
                                onSelect = { themeIndex = it },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Ot3SettingsRow(stringResource(R.string.catalog_animations), onClick = { animate = !animate }) {
                            Ot3Switch(animate, onCheckedChange = null)
                        }
                    }
                }
                item { CatalogControls() }
                item { CatalogBadges() }
                item { CatalogCardRows() }
                item { CatalogCardVariants() }
                item { CatalogSlimRows() }
                item { CatalogShelves() }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(Ot3Spacing.sm)) {
                        CatalogHeading(stringResource(R.string.catalog_overlays))
                        Row(Modifier.padding(horizontal = Ot3Spacing.phoneGutter), horizontalArrangement = Arrangement.spacedBy(Ot3Spacing.sm)) {
                            Ot3Button(stringResource(R.string.catalog_open_sheet), { overlay = Overlay.SHEET }, style = Ot3ButtonStyle.OUTLINE)
                            Ot3Button(stringResource(R.string.catalog_open_dialog), { overlay = Overlay.DIALOG }, style = Ot3ButtonStyle.OUTLINE)
                        }
                        Row(Modifier.padding(horizontal = Ot3Spacing.phoneGutter)) {
                            Ot3Button(stringResource(R.string.catalog_open_destructive), { overlay = Overlay.DELETE }, style = Ot3ButtonStyle.OUTLINE, destructive = true)
                        }
                        CatalogDialogCards()
                    }
                }
            }
            when (overlay) {
                Overlay.SHEET -> CatalogSheetOpen(onDismiss = { overlay = null })
                Overlay.DIALOG -> CatalogDialogOpen(destructive = false, onDismiss = { overlay = null })
                Overlay.DELETE -> CatalogDialogOpen(destructive = true, onDismiss = { overlay = null })
                null -> Unit
            }
        }
    }
}

private enum class Overlay { SHEET, DIALOG, DELETE }
