package dev.avillagran.ot3android.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStoreException

class OpenWithOneResetTest {

    @Test
    fun successOnFirstTryDoesNotReset() {
        var resets = 0

        val opened = openWithOneReset(create = { "prefs" }, reset = { resets++ })

        assertEquals("prefs", opened.value)
        assertFalse(opened.wasReset)
        assertEquals(0, resets)
    }

    @Test
    fun securityFailureResetsAndRetriesOnce() {
        var attempts = 0
        var resets = 0

        val opened = openWithOneReset(
            create = { if (attempts++ == 0) throw KeyStoreException("bad key") else "prefs" },
            reset = { resets++ },
        )

        assertEquals("prefs", opened.value)
        assertTrue(opened.wasReset)
        assertEquals(1, resets)
        assertEquals(2, attempts)
    }

    @Test
    fun ioFailureResetsAndRetriesOnce() {
        var attempts = 0

        val opened = openWithOneReset(
            create = { if (attempts++ == 0) throw IOException("corrupt keyset") else "prefs" },
            reset = {},
        )

        assertTrue(opened.wasReset)
        assertEquals(2, attempts)
    }

    @Test
    fun secondFailurePropagates() {
        var resets = 0

        assertThrows(GeneralSecurityException::class.java) {
            openWithOneReset<String>(create = { throw GeneralSecurityException("still bad") }, reset = { resets++ })
        }
        assertEquals(1, resets)
    }

    @Test
    fun unrelatedFailureIsNotSwallowed() {
        var resets = 0

        assertThrows(IllegalStateException::class.java) {
            openWithOneReset<String>(create = { throw IllegalStateException("bug") }, reset = { resets++ })
        }
        assertEquals(0, resets)
    }
}
