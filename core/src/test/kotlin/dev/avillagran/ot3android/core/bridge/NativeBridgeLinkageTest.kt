package dev.avillagran.ot3android.core.bridge

import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class NativeBridgeLinkageTest {
    @Test fun pinnedSdkExportsTheConsumerJniEntryPoint() {
        val present = System.getProperty("java.library.path").orEmpty().split(File.pathSeparator)
            .any { File(it, System.mapLibraryName("omabridge_jni")).isFile }
        if (System.getProperty("ot3a.requireBridgeSdk") == "true") assertTrue("Bridge host JNI is required", present)
        else assumeTrue("Bridge host JNI not installed", present)
        val capabilities = runBlocking { NativeBridge.request(JSONObject().put("op", "capabilities")) }
        assertEquals(1, capabilities.getInt("abi"))
        val features = capabilities.getJSONArray("features")
        assertTrue((0 until features.length()).any { features.getString(it) == "shared-drafts-v1" })
        assertTrue((0 until features.length()).any { features.getString(it) == "mobile-admin-v1" })
        assertTrue((0 until features.length()).any { features.getString(it) == "host-refresh-v1" })
        assertTrue((0 until features.length()).any { features.getString(it) == "route-fallback-v1" })
        // Exercises the actual native symbol without creating a peer, runtime or credential.
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { NativeBridge.request(JSONObject().put("op", "cached").put("handle", -1)) }
        }
        assertEquals("bridge_operation_failed", error.message)
    }
}
