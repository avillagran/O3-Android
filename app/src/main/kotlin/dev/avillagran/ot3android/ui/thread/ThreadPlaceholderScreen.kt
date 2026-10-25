package dev.avillagran.ot3android.ui.thread

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.avillagran.ot3android.R
import dev.avillagran.ot3android.core.connection.ConnectionManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

object ThreadScreenTags {
    const val ROOT = "thread_screen"
    const val TITLE = "thread_title"
}

/** The thread's title from the environment's live shell; the real thread view is the next PR. */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadPlaceholderViewModel(environmentId: String, threadId: String, connections: ConnectionManager) : ViewModel() {
    val title: StateFlow<String?> = connections.supervisors
        .flatMapLatest { it[environmentId]?.shell ?: flowOf(null) }
        .map { shell -> shell?.thread(threadId)?.title }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadPlaceholderScreen(viewModel: ThreadPlaceholderViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val title by viewModel.title.collectAsStateWithLifecycle()
    Scaffold(
        modifier = modifier.testTag(ThreadScreenTags.ROOT),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        title ?: stringResource(R.string.sidebar_untitled),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag(ThreadScreenTags.TITLE),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.thread_placeholder), style = MaterialTheme.typography.bodyLarge)
        }
    }
}
