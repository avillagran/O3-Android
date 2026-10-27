package dev.avillagran.ot3android.core.bridge

import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class NativeBridgeTest {
    @Test fun actualJniRedactsInvalidHandleError() {
        val present = System.getProperty("java.library.path").orEmpty().split(File.pathSeparator)
            .any { File(it, System.mapLibraryName("omabridge_jni")).isFile }
        if (System.getProperty("ot3a.requireBridgeSdk") == "true") assertTrue("Bridge host JNI is required", present)
        else assumeTrue("Bridge host JNI not installed", present)
        // No peer, network runtime, identity or credential is created by this invalid request.
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { NativeBridge.request(JSONObject().put("op", "cached").put("handle", -1)) }
        }
        assertEquals("bridge_operation_failed", error.message)
    }

    @Test fun clockDiagnosticsArePreservedButArbitraryBodiesAreRedacted() {
        for (code in listOf("bridge_catalog_invalid_window", "bridge_catalog_ttl_exceeded",
            "bridge_catalog_from_future", "bridge_catalog_revision_reused",
            "authority session exceeds enrollment", "authority returned unbounded session",
            "authority returned excessive scopes")) assertEquals(code, bridgeErrorCode(code))
        assertEquals("bridge_operation_failed", bridgeErrorCode("fixture-private-arbitrary-body"))
        assertEquals("bridge_operation_failed", bridgeErrorCode("bridge_catalog_from_future: fixture-body"))
    }
}
