package dev.avillagran.ot3android.vectors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SvgPathDataTest {
    @Test
    fun `compressed numbers and arc flags get separators`() {
        assertEquals(
            "a2 2 0 0 1 2 2 l.5 -.5 l-1 1".let { "M0 0 $it" },
            SvgPathData.normalize("M0 0a2 2 0 012 2l.5-.5-1 1"),
        )
    }

    @Test
    fun `coordinates repeated after a moveto are linetos`() {
        assertEquals("M1 2 L3 4 L5 6", SvgPathData.normalize("M1 2 3 4 5 6"))
        assertEquals("m1 2 l3 4", SvgPathData.normalize("m1,2,3,4"))
    }

    @Test
    fun `close path and exponents`() {
        assertEquals("M0 0 H1e2 V-2.5E-1 z", SvgPathData.normalize("M0 0H1e2V-2.5E-1z"))
    }

    @Test
    fun `malformed data fails`() {
        assertThrows(IllegalArgumentException::class.java) { SvgPathData.normalize("L1 1") }
        assertThrows(IllegalArgumentException::class.java) { SvgPathData.normalize("M1") }
        assertThrows(IllegalArgumentException::class.java) { SvgPathData.normalize("M0 0 a1 1 0 2 1 1 1") }
        assertThrows(IllegalArgumentException::class.java) { SvgPathData.normalize("") }
    }
}
