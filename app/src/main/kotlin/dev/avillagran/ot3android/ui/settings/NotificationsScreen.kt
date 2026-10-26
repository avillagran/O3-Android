package dev.avillagran.ot3android.ui.settings

import android.Manifest
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.ui.kit.Ot3Switch
import dev.avillagran.ot3android.notifications.*
import dev.avillagran.ot3android.core.data.prefs.FollowPreferences
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(preferences: FollowPreferences, onBack: () -> Unit) {
    val context = LocalContext.current
    val enabled by preferences.enabled.collectAsStateWithLifecycle(false)
    val scope = rememberCoroutineScope()
    var denied by remember { mutableStateOf(false) }
    fun enable() { scope.launch { preferences.setEnabled(true); ThreadFollowService.start(context) } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) enable() else denied = true
    }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.follow_title)) },
        navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.follow_explanation))
            ListItem(headlineContent = { Text(stringResource(R.string.follow_enabled)) }, trailingContent = {
                Ot3Switch(enabled, { value ->
                    if (value) {
                        if (ThreadFollowService.allowed(context)) enable()
                        else if (android.os.Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else scope.launch {
                        preferences.setEnabled(false)
                        context.stopService(Intent(context, ThreadFollowService::class.java))
                    }
                }, modifier = Modifier.testTag("follow_enabled"), contentDescription = stringResource(R.string.follow_enabled))
            })
            if (denied) Text(stringResource(R.string.follow_permission_denied))
            Text(stringResource(when (LiveUpdates.support(context)) {
                LiveUpdates.Support.Allowed -> R.string.follow_live_allowed
                LiveUpdates.Support.NotAllowed -> R.string.follow_live_disabled
                LiveUpdates.Support.Unsupported -> R.string.follow_live_unsupported
            }))
            TextButton(onClick = { context.startActivity(LiveUpdates.settingsIntent(context)) }) {
                Text(stringResource(R.string.follow_live_settings))
            }
        }
    }
}
