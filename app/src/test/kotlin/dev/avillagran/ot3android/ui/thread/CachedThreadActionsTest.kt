package dev.avillagran.ot3android.ui.thread

import dev.avillagran.ot3android.core.connection.ConnectionState
import dev.avillagran.ot3android.core.connection.ServerDescriptor
import dev.avillagran.ot3android.core.thread.ComposerState
import dev.avillagran.ot3android.core.thread.ThreadView
import org.junit.Assert.*
import org.junit.Test

class CachedThreadActionsTest {
    @Test fun cachedRunDoesNotEnableSendOrInterruptUntilValidated() {
        val cached = ThreadUiState(ThreadView("thread", loaded = true, working = true, activeRunId = "run", stale = true),
            connection = ConnectionState.Connected(ServerDescriptor(null, null, null, null, null)),
            composer = ComposerState(draft = "Keep this draft"))
        assertFalse(cached.canSend)
        assertFalse(cached.canInterrupt)
        val validated = cached.copy(view = cached.view.copy(stale = false))
        assertTrue(validated.canSend)
        assertTrue(validated.canInterrupt)
        assertEquals(cached.draft, validated.draft)
    }
}
