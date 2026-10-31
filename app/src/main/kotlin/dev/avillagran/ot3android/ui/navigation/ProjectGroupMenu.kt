package dev.avillagran.ot3android.ui.navigation

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.ui.settings.projects.NamedGroupsContent

@Composable
internal fun ProjectGroupMenu(names: List<String>, selected: String?, enabled: Boolean, onSelect: (String?) -> Unit) {
    var opened by remember { mutableStateOf(false) }
    TextButton(onClick = { opened = true }, enabled = enabled) { Text(stringResource(R.string.thread_menus_groups)) }
    if (opened) AlertDialog(onDismissRequest = { opened = false }, confirmButton = {
        TextButton(onClick = { opened = false }) { Text(stringResource(R.string.ot3_dialog_close)) }
    }, text = {
        androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) {
            NamedGroupsContent(names, selected, enabled) { onSelect(it); opened = false }
        }
    })
}
