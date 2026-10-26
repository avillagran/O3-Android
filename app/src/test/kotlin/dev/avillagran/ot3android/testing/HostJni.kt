package dev.avillagran.ot3android.testing

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import java.io.File

/**
 * Skips a test unless the desktop libt3_jni (scripts/fetch-or-build-lib.sh) is on
 * java.library.path, as `core`'s tests do. With OT3A_REQUIRE_HOST_JNI=1 (CI) or
 * -Pot3a.requireHostJni=true a missing library fails instead.
 */
fun assumeHostJni() {
    val name = System.mapLibraryName("t3_jni")
    val found = System.getProperty("java.library.path").orEmpty()
        .split(File.pathSeparator)
        .any { File(it, name).isFile }
    val message = "$name not on java.library.path; run scripts/fetch-or-build-lib.sh"
    val required = System.getenv("OT3A_REQUIRE_HOST_JNI") == "1" || System.getProperty("ot3a.requireHostJni") == "true"
    if (required) assertTrue(message, found) else assumeTrue(message, found)
}
