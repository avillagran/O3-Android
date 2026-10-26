package dev.avillagran.ot3android.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/** The status bar icon is the bundled T3 mark, unchanged, as a single white alpha mask. */
class NotificationIconTest {
    private fun path(text: String, attribute: String) =
        Regex("""$attribute="([^"]+)"""").find(text)!!.groupValues[1].replace(',', ' ').trim()

    @Test
    fun statusIconIsTheBrandSvgPathInWhite() {
        val svg = File("src/main/svg/lucide/t3-logo.svg").readText()
        val vector = File("src/main/res/drawable/ic_stat_ot3.xml").readText()
        assertEquals(path(svg, "d"), path(vector, "android:pathData"))
        val fills = Regex("""android:fillColor="([^"]+)"""").findAll(vector).map { it.groupValues[1] }.toList()
        assertEquals(listOf("#FFFFFFFF"), fills)
        assertFalse(vector.contains("strokeColor"))
    }
}
