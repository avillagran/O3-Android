package dev.avillagran.ot3android.core.transport

import org.junit.Assume.assumeTrue
import java.io.File

/** Skips a test unless the desktop libt3_jni (scripts/fetch-or-build-lib.sh) is on java.library.path. */
internal fun assumeHostJni() {
    val name = System.mapLibraryName("t3_jni")
    val found = System.getProperty("java.library.path").orEmpty()
        .split(File.pathSeparator)
        .any { File(it, name).isFile }
    assumeTrue("$name not on java.library.path; run scripts/fetch-or-build-lib.sh", found)
}
