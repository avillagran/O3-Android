package dev.avillagran.ot3android.core.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CredentialStoreTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun tokenRoundTripAndDelete() = runBlocking {
        val store = CredentialStore.open(context)

        store.setToken("env-test", "secret")
        assertEquals("secret", CredentialStore.open(context).token("env-test"))

        store.delete("env-test")
        assertNull(store.token("env-test"))
    }

    @Test
    fun unreadableFileIsWipedAndReported() = runBlocking {
        CredentialStore.open(context).setToken("env-test", "secret")
        // Overwrite the encrypted value keyset with bytes that are valid hex but not a
        // keyset, as after a restore from another device or a Keystore reset.
        context.getSharedPreferences(CredentialStore.FILE_NAME, 0).edit()
            .putString("__androidx_security_crypto_encrypted_prefs_value_keyset__", "deadbeef")
            .commit()

        val store = CredentialStore.open(context)

        assertEquals(true, store.wasReset)
        assertNull(store.token("env-test"))
    }
}
