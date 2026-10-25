package dev.avillagran.ot3android.ui.icons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Ot3IconsTest {
    // UI_SPEC 3.7 and 3.8, by area, as ot3 names them.
    private val specIcons = listOf(
        // Home
        "search", "list-filter", "folder-plus", "square-pen", "git-pull-request", "chart-no-axes-column",
        // Rows
        "pin", "clock", "check", "undo-2", "folder-git-2", "laptop", "monitor", "server", "smartphone", "cloud",
        // Composer
        "paperclip", "sliders-horizontal", "arrow-up", "stop-filled", "list-plus", "corner-up-right", "refresh-cw",
        // Changes
        "chevron-down", "external-link", "file", "eye",
        // Pull requests
        "git-merge", "git-pull-request-closed", "git-pull-request-draft",
        // Row status
        "shield-question", "message-circle-question", "circle-dot-dashed", "circle-dashed", "circle-alert", "circle-check",
        // Brand
        "t3-logo",
    )

    @Test
    fun `every icon the spec lists resolves to a drawable`() {
        for (name in specIcons) assertNotEquals(name, 0, Ot3Icons.resource(name))
    }

    @Test
    fun `each icon has its own drawable`() {
        assertEquals(Ot3Icons.byName.size, Ot3Icons.byName.values.toSet().size)
    }

    @Test
    fun `the map lists exactly the SVG sources the build converts`() {
        val sources = File("src/main/svg/lucide").listFiles { file -> file.name.endsWith(".svg") }.orEmpty()
            .map { it.name.removeSuffix(".svg") }.toSet()
        assertTrue("no SVG sources found from ${File(".").absolutePath}", sources.isNotEmpty())
        assertEquals(sources, Ot3Icons.byName.keys)
    }

    @Test
    fun `unknown names fail`() {
        assertThrows(IllegalArgumentException::class.java) { Ot3Icons.resource("not-an-icon") }
    }
}
