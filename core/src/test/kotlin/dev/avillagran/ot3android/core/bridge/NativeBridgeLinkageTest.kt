package dev.avillagran.ot3android.core.bridge

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class NativeBridgeLinkageTest {
    @Test fun pinnedSdkExportsTheConsumerJniEntryPoint() {
        val library = System.getProperty("java.library.path").orEmpty().split(File.pathSeparator)
            .map { File(it, System.mapLibraryName("omabridge_jni")) }
            .firstOrNull { it.isFile }
        val present = library != null
        if (System.getProperty("ot3a.requireBridgeSdk") == "true") assertTrue("Bridge host JNI is required", present)
        else assumeTrue("Bridge host JNI not installed", present)
        assertBuiltFromThePin(checkNotNull(library))
        val capabilities = runBlocking { NativeBridge.request(JSONObject().put("op", "capabilities")) }
        assertEquals(1, capabilities.getInt("abi"))
        val features = capabilities.getJSONArray("features")
        assertTrue((0 until features.length()).any { features.getString(it) == "shared-drafts-v1" })
        assertTrue((0 until features.length()).any { features.getString(it) == "mobile-admin-v1" })
        // Exercises the actual native symbol without creating a peer, runtime or credential.
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { NativeBridge.request(JSONObject().put("op", "cached").put("handle", -1)) }
        }
        assertEquals("bridge_operation_failed", error.message)
    }

    /** build-jni.sh records the commit and bytes it installed; a leftover library fails here by name. */
    private fun assertBuiltFromThePin(library: File) {
        val pin = File(checkNotNull(System.getProperty("ot3a.bridgeLock"))).readLines().single { it.startsWith("source=") }
        val metadata = File(library.parentFile, "omabridge-native.properties")
        assertTrue("$metadata is missing: run scripts/build-bridge-sdk.sh", metadata.isFile)
        val lines = metadata.readLines()
        assertTrue("$library is not from libs/omabridge-native.lock ($pin): run scripts/build-bridge-sdk.sh", pin in lines)
        val hash = MessageDigest.getInstance("SHA-256").digest(library.readBytes()).joinToString("") { "%02x".format(it) }
        assertTrue("$library does not match $metadata: run scripts/build-bridge-sdk.sh", lines.any { it.startsWith("$hash  ") })
    }
}
