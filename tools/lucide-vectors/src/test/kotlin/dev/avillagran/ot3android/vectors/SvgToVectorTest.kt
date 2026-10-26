package dev.avillagran.ot3android.vectors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SvgToVectorTest {
    private fun lucide(body: String, viewBox: String = "0 0 24 24") =
        """<svg xmlns="http://www.w3.org/2000/svg" viewBox="$viewBox" fill="none" stroke="currentColor" """ +
            """stroke-width="2" stroke-linecap="round" stroke-linejoin="round">$body</svg>"""

    private fun pathData(vector: String): List<String> =
        Regex("""android:pathData="([^"]*)"""").findAll(vector).map { it.groupValues[1] }.toList()

    private fun convert(svg: String) = SvgToVector.convert(svg, "test.svg")

    @Test
    fun `path is kept with explicit commands`() {
        assertEquals(listOf("M12 5 L12 19 M5 12 h14"), pathData(convert(lucide("""<path d="M12 5 12 19M5 12h14"/>"""))))
    }

    @Test
    fun `circle becomes two half arcs`() {
        assertEquals(
            listOf("M10 12 A2 2 0 1 0 14 12 A2 2 0 1 0 10 12 Z"),
            pathData(convert(lucide("""<circle cx="12" cy="12" r="2"/>"""))),
        )
    }

    @Test
    fun `ellipse uses both radii`() {
        assertEquals(
            listOf("M2 12 A10 4 0 1 0 22 12 A10 4 0 1 0 2 12 Z"),
            pathData(convert(lucide("""<ellipse cx="12" cy="12" rx="10" ry="4"/>"""))),
        )
    }

    @Test
    fun `rect without radius is a closed box`() {
        assertEquals(listOf("M3 4 H21 V20 H3 Z"), pathData(convert(lucide("""<rect x="3" y="4" width="18" height="16"/>"""))))
    }

    @Test
    fun `rounded rect takes ry from rx and caps it at half the side`() {
        assertEquals(
            listOf("M5 3 H19 A2 2 0 0 1 21 5 V19 A2 2 0 0 1 19 21 H5 A2 2 0 0 1 3 19 V5 A2 2 0 0 1 5 3 Z"),
            pathData(convert(lucide("""<rect x="3" y="3" width="18" height="18" rx="2"/>"""))),
        )
        assertEquals(
            listOf("M3 2 H3 A1 1 0 0 1 4 3 V5 A1 1 0 0 1 3 6 H3 A1 1 0 0 1 2 5 V3 A1 1 0 0 1 3 2 Z"),
            pathData(convert(lucide("""<rect x="2" y="2" width="2" height="4" rx="9" ry="1"/>"""))),
        )
    }

    @Test
    fun `line is a move and a line`() {
        assertEquals(listOf("M12 8 L12 12"), pathData(convert(lucide("""<line x1="12" x2="12" y1="8" y2="12"/>"""))))
    }

    @Test
    fun `polyline stays open and polygon closes`() {
        assertEquals(listOf("M4 17 L10 11 L4 5"), pathData(convert(lucide("""<polyline points="4 17 10 11 4 5"/>"""))))
        assertEquals(listOf("M6 3 L20 12 L6 21 Z"), pathData(convert(lucide("""<polygon points="6,3 20,12 6,21"/>"""))))
    }

    @Test
    fun `strokes are drawn at ot3's 1_75 with round caps and white for tinting`() {
        val vector = convert(lucide("""<path d="M5 12h14"/>"""))
        assertTrue(vector.contains("""android:strokeWidth="1.75""""))
        assertTrue(vector.contains("""android:strokeLineCap="round" android:strokeLineJoin="round""""))
        assertTrue(vector.contains("""android:strokeColor="@android:color/white""""))
        assertFalse(vector.contains("fillColor"))
        assertTrue(vector.contains("""android:width="24dp""""))
        assertTrue(vector.contains("""android:viewportWidth="24""""))
        assertFalse(vector.contains("<group"))
    }

    @Test
    fun `filled marks fill white and keep evenodd`() {
        val vector = convert(
            """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 12 12" fill="currentColor" fill-rule="evenodd">""" +
                """<rect x="2" y="2" width="8" height="8"/></svg>""",
        )
        assertTrue(vector.contains("""android:fillColor="@android:color/white" android:fillType="evenOdd""""))
        assertFalse(vector.contains("strokeColor"))
    }

    @Test
    fun `an element overrides the inherited paint, through groups`() {
        val vector = convert(lucide("""<g fill="currentColor"><circle cx="12" cy="12" r="1" stroke="none"/></g>"""))
        assertTrue(vector.contains("fillColor"))
        assertFalse(vector.contains("strokeColor"))
    }

    @Test
    fun `offset and non-square view boxes are centred in a square viewport`() {
        val vector = convert(
            """<svg xmlns="http://www.w3.org/2000/svg" viewBox="10 20 40 20"><path d="M10 20h40v20h-40z"/></svg>""",
        )
        assertTrue(vector.contains("""android:viewportWidth="40""""))
        assertTrue(vector.contains("""android:viewportHeight="40""""))
        assertTrue(vector.contains("""<group android:translateX="-10" android:translateY="-10">"""))
    }

    @Test
    fun `stroke width scales with the viewport`() {
        val vector = convert(lucide("""<path d="M0 0h48"/>""", viewBox = "0 0 48 48"))
        assertTrue(vector.contains("""android:strokeWidth="3.5""""))
    }

    @Test
    fun `transforms and unknown elements fail`() {
        assertThrows(IllegalArgumentException::class.java) { convert(lucide("""<path d="M0 0h1" transform="rotate(45)"/>""")) }
        assertThrows(IllegalArgumentException::class.java) { convert(lucide("""<text x="1" y="1">a</text>""")) }
        assertThrows(IllegalArgumentException::class.java) { convert(lucide("")) }
    }

    @Test
    fun `drawable names follow the ot3 name`() {
        assertEquals("ic_git_pull_request_closed", drawableName("git-pull-request-closed.svg"))
        assertEquals("ic_t3_logo", drawableName("t3-logo.svg"))
        assertThrows(IllegalArgumentException::class.java) { drawableName("Bad_Name.svg") }
    }

    @Test
    fun `every icon source in the app converts`() {
        val dir = File(System.getProperty("ot3.svgDir") ?: error("ot3.svgDir not set"))
        val sources = dir.listFiles { file -> file.name.endsWith(".svg") }.orEmpty()
        assertTrue("no SVGs in $dir", sources.isNotEmpty())
        for (source in sources) {
            val vector = SvgToVector.convert(source.readText(), source.name)
            assertTrue(source.name, pathData(vector).isNotEmpty())
            pathData(vector).forEach { SvgPathData.parse(it) }
        }
    }
}
