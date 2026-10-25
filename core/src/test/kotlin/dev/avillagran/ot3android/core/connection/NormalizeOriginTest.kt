package dev.avillagran.ot3android.core.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NormalizeOriginTest {

    @Test
    fun keepsOnlyTheOrigin() {
        assertEquals("https://t3.example.com", normalizeOrigin("  https://T3.example.com/some/path?x=1#y  "))
        assertEquals("http://127.0.0.1:8643", normalizeOrigin("http://127.0.0.1:8643/"))
    }

    @Test
    fun addsASchemeByHost() {
        assertEquals("http://192.168.1.20:3773", normalizeOrigin("192.168.1.20:3773"))
        assertEquals("http://localhost:3773", normalizeOrigin("localhost:3773"))
        assertEquals("http://box.local", normalizeOrigin("box.local"))
        assertEquals("http://100.101.102.103", normalizeOrigin("100.101.102.103"))
        assertEquals("https://t3.example.com", normalizeOrigin("t3.example.com"))
    }

    @Test
    fun blankIsNull() {
        assertNull(normalizeOrigin("   "))
    }

    @Test
    fun unparseableInputIsLeftForTheLibToReject() {
        assertEquals("ftp://", normalizeOrigin("ftp://"))
    }
}
