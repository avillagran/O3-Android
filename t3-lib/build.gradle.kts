// Exposes the lib-t3-rust AAR (classes.jar, JNI libraries, consumer rules) as a project
// dependency. A library module cannot depend on a local .aar file directly, so the AAR is
// published here as the artifact of a plain "default" configuration, the pattern AGP's
// own error message recommends. The file is a build output of scripts/fetch-or-build-lib.sh
// and is never committed (docs/LIB_BUILD.md).
val aar = rootProject.file("libs/lib-t3-rust-android.aar")

if (!aar.isFile) {
    throw GradleException(
        "Missing ${aar.relativeTo(rootDir)}. Run scripts/fetch-or-build-lib.sh " +
            "(or scripts/fetch-or-build-lib.sh --release) first; see docs/LIB_BUILD.md.",
    )
}

configurations.create("default")
artifacts.add("default", aar)
