package dev.avillagran.ot3android.debug.catalog

import dev.avillagran.ot3android.testing.ScreenshotTest
import org.junit.Test

/**
 * The component kit's goldens (UI_SPEC 3.9 and 5): one sheet per group of the debug
 * catalog, in tokyo-night and catppuccin-latte; docs/ui/compare/ui-1/kit/ puts them next to
 * ot3's captures. The catalog lives in the debug source set, so these run in
 * `testDebugUnitTest` only.
 */
class CatalogScreenshotTest : ScreenshotTest() {
    @Test
    fun controls() = snapshot("kit-controls") { CatalogControls() }

    @Test
    fun badges() = snapshot("kit-badges") { CatalogBadges() }

    @Test
    fun cardRows() = snapshot("kit-rows-card") { CatalogCardRows() }

    @Test
    fun cardVariants() = snapshot("kit-rows-card-variants") { CatalogCardVariants() }

    @Test
    fun slimRows() = snapshot("kit-rows-slim") { CatalogSlimRows() }

    @Test
    fun shelves() = snapshot("kit-shelves") { CatalogShelves() }

    @Test
    fun sheet() = snapshot("kit-sheet", screen = true) { CatalogSheetOpen() }

    @Test
    fun dialog() = snapshot("kit-dialog", screen = true) { CatalogDialogOpen(destructive = false) }

    @Test
    fun destructiveDialog() = snapshot("kit-dialog-destructive", screen = true) { CatalogDialogOpen(destructive = true) }

    @Test
    fun dialogCards() = snapshot("kit-dialog-cards") { CatalogOverlayCards() }

    @Test
    fun tooltip() = snapshot("kit-tooltip", screen = true) { CatalogTooltipOpen() }
}
