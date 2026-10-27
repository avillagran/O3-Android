import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
    // Typed navigation routes (@Serializable destinations, UI_SPEC 2.1).
    alias(libs.plugins.kotlin.serialization)
}

val installedCatalogReadOnlyRunner = providers.gradleProperty("ot3a.installedCatalogReadOnlyRunner").orElse("false")

android {
    namespace = "dev.avillagran.ot3android"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.avillagran.ot3android"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = if (installedCatalogReadOnlyRunner.get() == "true")
            "dev.avillagran.ot3android.diagnostics.InstalledCatalogReadOnlyRunner"
        else "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    sourceSets.getByName("test").java.srcDir("src/sharedTest/kotlin")
    sourceSets.getByName("androidTest").java.srcDir("src/sharedTest/kotlin")

    // Robolectric reads the merged resources and assets (themes JSON, Fira Code).
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

// The desktop libt3_jni.so (scripts/fetch-or-build-lib.sh) for JVM tests that call the lib's
// domain functions (pairing links, Home), as in :core; skipped without it, failing instead with
// OT3A_REQUIRE_HOST_JNI=1 (CI) or -Pot3a.requireHostJni=true.
val hostJniDir = rootProject.file("libs/host/linux-x86_64")
val requireHostJniProperty = providers.gradleProperty("ot3a.requireHostJni").orElse("false")
tasks.withType<Test>().configureEach {
    providers.gradleProperty("ot3a.followLabDir").orNull?.let {
        systemProperty("ot3a.followLabDir", it)
        maxHeapSize = "256m"
        outputs.upToDateWhen { false } // Explicit external fixture, never a cached test result.
    }
    systemProperty("java.library.path", hostJniDir.absolutePath)
    systemProperty("ot3a.requireHostJni", requireHostJniProperty.get())
    inputs.files(fileTree(hostJniDir) { include("*.so") })
        .withPropertyName("hostJni")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("requireHostJni", providers.environmentVariable("OT3A_REQUIRE_HOST_JNI").orElse(""))
}

// Screenshot goldens (docs/UI_SPEC.md section 5): recordRoborazziDebug writes them,
// verifyRoborazziDebug fails on any difference.
roborazzi {
    outputDir.set(file("src/test/snapshots"))
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// ot3's icons: the SVGs in src/main/svg/lucide become res/drawable/ic_<name>.xml at build
// time, converted by tools/lucide-vectors (UI_SPEC 3.8; Ot3Icons lists them by name).
val lucideVectors: Configuration by configurations.creating {
    isCanBeConsumed = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
    }
}

abstract class GenerateOt3Icons : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val svgDir: DirectoryProperty

    @get:Classpath
    abstract val converter: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun generate() {
        exec.javaexec {
            classpath = converter
            mainClass.set("dev.avillagran.ot3android.vectors.MainKt")
            args(svgDir.get().asFile.absolutePath, outputDir.get().asFile.absolutePath)
        }
    }
}

val generateOt3Icons = tasks.register<GenerateOt3Icons>("generateOt3Icons") {
    svgDir.set(layout.projectDirectory.dir("src/main/svg/lucide"))
    converter.from(lucideVectors)
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)

    // QR scanning: CameraX preview and analysis, decoded with ZXing (pure Java, no native code,
    // no Play Services). See docs/DECISIONS.md.
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.compose)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // android.jar only has org.json stubs; the pairing service reads descriptors with it.
    testImplementation(libs.org.json)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)

    lucideVectors(project(":lucide-vectors"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(generateOt3Icons, GenerateOt3Icons::outputDir)
    }
}
