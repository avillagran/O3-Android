package dev.avillagran.ot3android.ui.sidebar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.avillagran.ot3android.R

object SidebarScreenTags {
    const val ROOT = "sidebar_screen"
}

/** Placeholder for the environments / projects / threads sidebar. */
@Composable
fun SidebarScreen(modifier: Modifier = Modifier) {
    Scaffold(modifier = modifier.testTag(SidebarScreenTags.ROOT)) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.sidebar_empty), style = MaterialTheme.typography.titleMedium)
        }
    }
}
