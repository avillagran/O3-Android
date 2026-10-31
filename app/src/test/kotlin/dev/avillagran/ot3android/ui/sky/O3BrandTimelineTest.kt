package dev.avillagran.ot3android.ui.sky

import org.junit.Assert.*
import org.junit.Test

class O3BrandTimelineTest {
    @Test fun desktopDefaults() {
        val c=O3BrandConfig()
        assertEquals(O3BrandGlyph.O,c.glyph)
        assertTrue(c.sober)
        assertEquals(1100L,c.morphMs)
        assertEquals(1400L,c.copy(glyph=O3BrandGlyph.OMARCHY).morphMs)
    }
    @Test fun desktopSceneBoundariesAndRestRepeat() {
        for(glyph in O3BrandGlyph.entries) {
            val c=O3BrandConfig(glyph=glyph)
            val spell=2400+c.morphMs+120
            val leave=spell+5165
            val back=leave+1050
            val rest=back+c.morphMs
            assertEquals(-1,brandFrame(2399,c).scene)
            assertEquals(0,brandFrame(2400,c).scene)
            assertEquals(1,brandFrame(spell,c).scene)
            assertEquals(2,brandFrame(leave,c).scene)
            assertEquals(3,brandFrame(back,c).scene)
            assertEquals(4,brandFrame(rest,c).scene)
            assertEquals(0,brandFrame(rest+7000,c).scene)
            assertEquals(1f,brandFrame(spell,c).morph,0f)
            assertEquals(0f,brandFrame(rest,c).morph,0f)
        }
    }
    @Test fun morphAnticipatesThenClosesTheCounter() {
        val c=O3BrandConfig()
        assertEquals(0f,brandFrame(2570,c).morph,0f)
        assertEquals(.5f,brandFrame(2880,c).morph,.0001f)
        assertEquals(1f,brandFrame(3190,c).morph,0f)
    }
    @Test fun paceChangesMotionButNotInitialRestOrHold() {
        val c=O3BrandConfig(pace=1.6f)
        assertEquals(-1,brandFrame(2399,c).scene)
        assertEquals(1760L,c.morphMs)
        val spell=2400+c.morphMs+120
        assertEquals(2,brandFrame(spell+c.ms(1565)+3600,c).scene)
        assertEquals(330L,c.copy(pace=.1f).morphMs)
    }
    @Test fun reducedAndStoppedStatesDoNotAdvance() {
        for(t in listOf(0L,2400L,9000L,Long.MAX_VALUE)) {
            val reduced=brandFrame(t,O3BrandConfig(reducedMotion=true))
            assertTrue(reduced.spelled)
            assertEquals(0f,reduced.morph,0f)
            val stopped=brandFrame(t,O3BrandConfig(running=false))
            assertFalse(stopped.spelled)
            assertEquals(-1,stopped.scene)
        }
    }
}
