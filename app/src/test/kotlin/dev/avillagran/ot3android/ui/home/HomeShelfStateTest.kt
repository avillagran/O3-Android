package dev.avillagran.ot3android.ui.home

import dev.avillagran.ot3android.core.home.*
import dev.avillagran.ot3android.ui.home.layouts.fixtureRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HomeShelfStateTest {
    @Test fun persistedFoldUpdatesWithoutAnotherDomainEmissionAndRetainsPaging() = runTest {
        val header = HomeItem.ShelfHeader(Shelf.Settled, 173, true)
        val more = HomeItem.ShowMoreSettled(163)
        val messages = HomeItem.MessagesHeader(false)
        val active = HomeItem.Row(fixtureRow("node", "active"))
        val closed = HomeItem.Row(fixtureRow("node", "closed", settled = true))
        val unfolded = HomeUiState(content = HomeContent.List, items = listOf(active, header, closed, more, messages))
        val domain = MutableStateFlow(unfolded)
        val saved = MutableStateFlow(ShelfState(settledExpanded = true))
        val results = mutableListOf<HomeUiState>()
        val job = backgroundScope.launch { domain.combine(saved) { state, shelves -> state.withShelfExpansion(shelves) }.collect { results.add(it) } }
        runCurrent()
        saved.value = ShelfState(settledExpanded = false)
        runCurrent()
        assertEquals(listOf(active, header.copy(expanded = false), messages), results.last().items)
        saved.value = ShelfState(settledExpanded = true)
        runCurrent()
        assertEquals(unfolded.items, results.last().items)
        assertEquals(3, results.size)
        job.cancel()
    }
}
