import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
val requireHostJniProperty = providers.gradleProperty("ot3a.requireHostJni").orElse("false")
tasks.withType<Test>().configureEach {
    systemProperty("java.library.path", hostJniDir.absolutePath)
    systemProperty("ot3a.requireHostJni", requireHostJniProperty.get())
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
