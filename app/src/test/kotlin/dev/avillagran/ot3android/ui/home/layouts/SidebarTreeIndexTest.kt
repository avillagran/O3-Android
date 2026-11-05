package dev.avillagran.ot3android.ui.home.layouts

import org.junit.Assert.*
import org.junit.Test

class SidebarTreeIndexTest {
    @Test fun nestedCountsIncludeWorkingDescendantsAndStopAtHeadersAndSiblings() {
        val rows = listOf(fixtureRow("a", "root"), fixtureRow("a", "child", state = "working").copy(depth = 1),
            fixtureRow("a", "grandchild", state = "working").copy(depth = 2), fixtureRow("a", "sibling").copy(depth = 1),
            null, fixtureRow("b", "next"), fixtureRow("b", "child").copy(depth = 1))
        val index = sidebarTreeIndex(rows)
        assertEquals(SidebarDescendants(3, 2), index[0])
        assertEquals(SidebarDescendants(1, 1), index[1])
        assertEquals(SidebarDescendants(), index[2])
        assertEquals(SidebarDescendants(), index[3])
        assertEquals(SidebarDescendants(1, 0), index[5])
    }
    @Test fun sectionFoldsUseWholeAncestorKeys() {
        val collapsed = setOf("project:node/app", "thread:node/chat")
        assertTrue(sidebarSectionHidden("project:node/app/working", collapsed))
        assertTrue(sidebarSectionHidden("project:node/app", collapsed))
        assertFalse(sidebarSectionHidden("project:node/app", collapsed, includeSelf = false))
        assertFalse(sidebarSectionHidden("project:node/application/working", collapsed))
    }
    @Test fun largeFlatListDoesNotInventDescendants() {
        val rows = (0 until 3_000).map { fixtureRow("node", "thread-$it") }
        // Compara el trabajo anterior y el índice sin imponer un umbral dependiente de la máquina.
        val before = System.nanoTime()
        val previous = rows.mapIndexed { index, row -> rows.drop(index + 1).takeWhile { it.depth > row.depth }.size }
        val middle = System.nanoTime()
        val result = sidebarTreeIndex(rows)
        val end = System.nanoTime()
        assertEquals(previous, result.map { it.count })
        assertTrue(result.all { it.working == 0 })
        println("sidebar-tree 3000 rows: previous=${(middle - before) / 1_000_000.0}ms indexed=${(end - middle) / 1_000_000.0}ms")
    }
}
