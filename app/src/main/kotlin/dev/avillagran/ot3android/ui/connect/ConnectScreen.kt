package dev.avillagran.ot3android.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.ui.theme.Ot3Theme

object ConnectScreenTags {
    const val ROOT = "connect_screen"
    const val CONTINUE = "connect_continue"
}

/** Placeholder for the connect flow (pairing link, URL + token, OmaBridge). */
@Composable
fun ConnectScreen(onContinue: () -> Unit, modifier: Modifier = Modifier) {
    Scaffold(modifier = modifier.testTag(ConnectScreenTags.ROOT)) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displayMedium)
            Text(stringResource(R.string.connect_title), style = MaterialTheme.typography.titleMedium)
            Button(onClick = onContinue, modifier = Modifier.testTag(ConnectScreenTags.CONTINUE)) {
                Text(stringResource(R.string.connect_continue))
            }
        }
    }
}

@Preview
@Composable
private fun ConnectScreenPreview() {
    Ot3Theme { ConnectScreen(onContinue = {}) }
}
