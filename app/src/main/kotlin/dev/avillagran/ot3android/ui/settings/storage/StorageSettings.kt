package dev.avillagran.ot3android.ui.settings.storage

import androidx.compose.runtime.Composable
import dev.avillagran.ot3android.core.maintenance.MaintenanceController
import dev.avillagran.ot3android.core.maintenance.MaintenancePage
import dev.avillagran.ot3android.ui.settings.maintenance.MaintenanceScreen

object StorageSettingsRoute {
    const val route = "settings/storage"
    @Composable fun Content(controller: MaintenanceController) {
        require(controller.page == MaintenancePage.STORAGE)
        MaintenanceScreen(controller)
    }
}
