package dev.avillagran.ot3android

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.avillagran.t3.T3Client
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Loads the APK's libt3_jni for the device ABI and reaches the Rust core through the adapter. */
@RunWith(AndroidJUnit4::class)
class JniSmokeTest {

    @Test
    fun nativeLibraryReportsAbi3() {
        val capabilities = T3Client().use { it.execute(JSONObject().put("op", "capabilities")) as JSONObject }

        assertEquals(3, capabilities.getInt("abiVersion"))
    }
}
