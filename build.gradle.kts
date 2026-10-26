plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.roborazzi) apply false
}

// CI's first-boot launcher ANR can own window focus while the test Activity is RESUMED.
// Keep the existing workflow command: both modules depend on this single preparation.
val prepareInstrumentedEmulator = tasks.register<Exec>("prepareInstrumentedEmulator") {
    val onGitHubActions = providers.environmentVariable("GITHUB_ACTIONS").map { it == "true" }.orElse(false).get()
    val serial = providers.environmentVariable("ANDROID_SERIAL").orElse("emulator-5554").get()
    group = "verification"
    description = "Prepare the GitHub Actions emulator for focus-dependent instrumented tests."
    onlyIf("Runs only on GitHub Actions, never on a developer's phone") { onGitHubActions }
    commandLine("bash", layout.projectDirectory.file("scripts/emulator-ready.sh").asFile.absolutePath, serial)
}
subprojects {
    tasks.matching { it.name == "connectedDebugAndroidTest" }.configureEach {
        dependsOn(prepareInstrumentedEmulator)
    }
}
