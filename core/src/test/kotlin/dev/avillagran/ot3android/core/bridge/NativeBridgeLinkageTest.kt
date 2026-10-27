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
        // Exercises the actual native symbol without creating a peer, runtime or credential.
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { NativeBridge.request(JSONObject().put("op", "cached").put("handle", -1)) }
        }
        assertEquals("bridge_operation_failed", error.message)
    }
}
