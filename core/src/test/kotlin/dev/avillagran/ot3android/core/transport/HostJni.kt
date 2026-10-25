package dev.avillagran.ot3android.core.transport

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import java.io.File

/**
 * Skips a test unless the desktop libt3_jni (scripts/fetch-or-build-lib.sh) is on
 * java.library.path. With OT3A_REQUIRE_HOST_JNI=1 (CI) a missing library fails instead.
 */
internal fun assumeHostJni() {
    val name = System.mapLibraryName("t3_jni")
    val found = System.getProperty("java.library.path").orEmpty()
        .split(File.pathSeparator)
        .any { File(it, name).isFile }
    val message = "$name not on java.library.path; run scripts/fetch-or-build-lib.sh"
    if (System.getenv("OT3A_REQUIRE_HOST_JNI") == "1") assertTrue(message, found) else assumeTrue(message, found)
}
