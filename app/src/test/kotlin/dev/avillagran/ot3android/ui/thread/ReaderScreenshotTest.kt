package dev.avillagran.ot3android.ui.thread

import dev.avillagran.ot3android.testing.ScreenshotTest
import org.json.JSONObject
import org.junit.Test

class ReaderScreenshotTest : ScreenshotTest() {
    private val originalZone = java.util.TimeZone.getDefault()
    private val originalLocale = java.util.Locale.getDefault()
    @org.junit.Before fun stableClockLocale() {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Santiago"))
        java.util.Locale.setDefault(java.util.Locale.US)
    }
    @org.junit.After fun restoreClockLocale() {
        java.util.TimeZone.setDefault(originalZone)
        java.util.Locale.setDefault(originalLocale)
    }
    @Test fun markdownReader() {
        val view = JSONObject(checkNotNull(javaClass.getResource("/timeline/golden.view.json")).readText())
        snapshot("markdown") { TimelineReader(view, emptyList()) }
    }
}
