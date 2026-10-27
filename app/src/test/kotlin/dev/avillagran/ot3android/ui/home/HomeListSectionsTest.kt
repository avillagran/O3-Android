package dev.avillagran.ot3android.ui.home

import dev.avillagran.ot3android.core.home.HomeItem
import dev.avillagran.ot3android.core.home.Shelf
import org.junit.Assert.*
import org.junit.Test

class HomeListSectionsTest {
    @Test fun searchResultsReturnToMainAndPagingStaysInSettledShelf() {
        val working = HomeItem.ShelfHeader(Shelf.Working, 1, false)
        val snoozed = HomeItem.ShelfHeader(Shelf.Snoozed, 4, false)
        val settled = HomeItem.ShelfHeader(Shelf.Settled, 30, true)
        val more = HomeItem.ShowMoreSettled(10)
        val messages = HomeItem.MessagesHeader(false)
        val sections = homeListSections(listOf(working, snoozed, settled, more, messages))
        assertEquals(listOf(working, messages), sections.main)
        assertEquals(listOf(snoozed to emptyList<HomeItem>(), settled to listOf(more)), sections.bottom)
    }
}
