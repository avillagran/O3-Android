package dev.avillagran.ot3android.ui.home.layouts

import dev.avillagran.ot3android.core.home.HomeRow

internal data class SidebarDescendants(val count: Int = 0, val working: Int = 0)

/** Indexa subárboles contiguos en una pasada. Un encabezado corta el árbol anterior. */
internal fun sidebarTreeIndex(rows: List<HomeRow?>): List<SidebarDescendants> {
    val result = MutableList(rows.size) { SidebarDescendants() }
    val stack = ArrayDeque<Int>()
    val workingBefore = IntArray(rows.size + 1)
    for (index in 0..rows.size) {
        val row = rows.getOrNull(index)
        while (stack.isNotEmpty() && (row == null || rows[stack.last()]!!.depth >= row.depth)) {
            val parent = stack.removeLast()
            result[parent] = SidebarDescendants(index - parent - 1, workingBefore[index] - workingBefore[parent + 1])
        }
        if (index < rows.size) {
            workingBefore[index + 1] = workingBefore[index] + if (row?.working == true) 1 else 0
            if (row != null) stack.addLast(index)
        }
    }
    return result
}

internal fun sidebarSectionHidden(section: String, collapsed: Set<String>, includeSelf: Boolean = true): Boolean {
    var key = if (includeSelf) section else section.substringBeforeLast('/', "")
    while (key.isNotEmpty()) {
        if (key in collapsed) return true
        key = key.substringBeforeLast('/', "")
    }
    return false
}
