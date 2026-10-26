import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Build-time converter from icon SVGs to Android VectorDrawables, run by :app's
// generateOt3Icons task (UI_SPEC 3.8). Plain JVM, so its tests run without Android.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
}

// The test that converts every icon the app ships reads them from :app's sources.
val appIcons = rootProject.layout.projectDirectory.dir("app/src/main/svg/lucide")
tasks.test {
    systemProperty("ot3.svgDir", appIcons.asFile.absolutePath)
    inputs.dir(appIcons).withPropertyName("appIcons").withPathSensitivity(PathSensitivity.RELATIVE)
}
