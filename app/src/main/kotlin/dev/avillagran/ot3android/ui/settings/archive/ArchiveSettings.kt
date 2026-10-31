package dev.avillagran.ot3android.ui.settings.archive

import androidx.compose.runtime.Composable
import dev.avillagran.ot3android.core.maintenance.MaintenanceController
import dev.avillagran.ot3android.core.maintenance.MaintenancePage
import dev.avillagran.ot3android.ui.settings.maintenance.MaintenanceScreen

object ArchiveSettingsRoute {
    const val route = "settings/archive"
    @Composable fun Content(controller: MaintenanceController) {
        require(controller.page == MaintenancePage.ARCHIVE)
        MaintenanceScreen(controller)
    }
}
