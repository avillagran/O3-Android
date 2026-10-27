import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "dev.avillagran.ot3android.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // MigrationTestHelper reads the exported schemas from the test APK's assets.
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

ksp {
    arg("room.generateKotlin", "true")
}

// The desktop libt3_jni.so built by scripts/fetch-or-build-lib.sh, for JVM unit tests that
// drive the real Rust core; those tests are skipped when it is absent, and fail instead with
// OT3A_REQUIRE_HOST_JNI=1 (CI) or -Pot3a.requireHostJni=true. The library and both switches
// are test inputs, so adding, rebuilding or requiring it reruns the tests.
val hostJniDir = rootProject.file("libs/host/linux-x86_64")
val requireBridgeSdkProperty = providers.gradleProperty("ot3a.requireBridgeSdk").orElse("false")
val requireHostJniProperty = providers.gradleProperty("ot3a.requireHostJni").orElse("false")
tasks.withType<Test>().configureEach {
    systemProperty("java.library.path", hostJniDir.absolutePath)
    systemProperty("ot3a.requireHostJni", requireHostJniProperty.get())
    systemProperty("ot3a.requireBridgeSdk", requireBridgeSdkProperty.get())
    inputs.files(fileTree(hostJniDir) { include("*.so") })
        .withPropertyName("hostJni")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("requireHostJni", providers.environmentVariable("OT3A_REQUIRE_HOST_JNI").orElse(""))
}

// tools/import-omarchy-themes: the theme importer (OmarchyThemesImport) runs on the JVM with
// the host libt3_jni.so, because every colour comes from the lib's theme.tokens. Never part
// of the ordinary test run, never up to date.
val importOmarchyThemes by tasks.registering(Test::class) {
    group = "ot3"
    description = "Regenerates app/src/main/assets/themes/omarchy-themes.json from Omarchy's colors.toml files with theme.tokens."
    val unitTests = tasks.named<Test>("testDebugUnitTest")
    dependsOn("compileDebugUnitTestKotlin")
    testClassesDirs = files(unitTests.map { it.testClassesDirs })
    classpath = files(unitTests.map { it.classpath })
    filter { includeTestsMatching("dev.avillagran.ot3android.core.theme.OmarchyThemesImport") }
    systemProperty("ot3a.omarchyThemes", providers.gradleProperty("ot3a.omarchyThemes").getOrElse("/usr/share/omarchy/themes"))
    providers.gradleProperty("ot3a.omarchyVersion").orNull?.let { systemProperty("ot3a.omarchyVersion", it) }
    systemProperty("ot3a.themesCheck", providers.gradleProperty("ot3a.themesCheck").getOrElse("false"))
    systemProperty("ot3a.themesOutput", rootProject.file("app/src/main/assets/themes/omarchy-themes.json").absolutePath)
    systemProperty("ot3a.requireHostJni", "true")
    testLogging { showStandardStreams = true }
    outputs.upToDateWhen { false }
}

dependencies {
    api(project(":t3-lib"))
    implementation(libs.androidx.core.ktx)
    api(libs.kotlinx.coroutines.android)

    // api: Ot3Database (a RoomDatabase) is part of core's public surface; app builds the repository from it.
    api(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // android.jar only has org.json stubs; the lib's adapter needs a real implementation.
    testImplementation(libs.org.json)

    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

// Native bridge distribution is opt-in. Verify the source and bytes actually
// packaged; lab trust helpers can never enter a release artifact.
fun bridgeSdkVerification(release: Boolean) = tasks.register(if (release) "verifyReleaseBridgeSdk" else "verifyDebugBridgeSdk") {
    val pin = rootProject.file("libs/omabridge-native.lock")
    val artifacts = listOf("x86_64", "arm64-v8a").map { file("src/main/jniLibs/$it") }
    inputs.file(pin)
    inputs.files(artifacts.map { fileTree(it) })
    val required = providers.gradleProperty("ot3a.requireBridgeSdk").orElse("false").get().toBoolean()
    val allowLab = providers.gradleProperty("ot3a.bridgeLab").orElse("false").get().toBoolean()
    inputs.property("required", required)
    inputs.property("lab", allowLab)
    doLast {
        val included = artifacts.any { it.resolve("libomabridge_jni.so").exists() }
        if (!included) {
            check(!required) { "Bridge SDK required: run scripts/build-bridge-sdk.sh" }
            return@doLast
        }
        val expected = pin.readLines().single { it.startsWith("source=") }.substringAfter('=')
        for (directory in artifacts) {
            val library = directory.resolve("libomabridge_jni.so")
            val metadata = directory.resolve("omabridge-native.properties")
            check(library.isFile && metadata.isFile) { "Incomplete bridge SDK: $directory" }
            val lines = metadata.readLines()
            check("source=$expected" in lines && "abi=omabridge-mobile-1" in lines && "target=${directory.name}" in lines) { "Bridge SDK source/ABI/target mismatch" }
            val lab = "flavor=lab-tls" in lines
            check("flavor=production" in lines || lab) { "Missing bridge SDK flavor" }
            check(!lab || (!release && allowLab)) { "Lab JNI requires explicit debug opt-in and is forbidden in release" }
            val hash = MessageDigest.getInstance("SHA-256").digest(library.readBytes()).joinToString("") { "%02x".format(it) }
            check(lines.any { it.startsWith("$hash  ") }) { "Bridge SDK checksum mismatch" }
        }
    }
}
val verifyDebugBridgeSdk = bridgeSdkVerification(false)
val verifyReleaseBridgeSdk = bridgeSdkVerification(true)
tasks.matching { it.name == "mergeDebugJniLibFolders" }.configureEach { dependsOn(verifyDebugBridgeSdk) }
tasks.matching { it.name == "mergeReleaseJniLibFolders" }.configureEach { dependsOn(verifyReleaseBridgeSdk) }
