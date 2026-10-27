pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ot3-android"

include(":app")
include(":core")
include(":t3-lib")
include(":lucide-vectors")
project(":lucide-vectors").projectDir = file("tools/lucide-vectors")

// Isolated opt-in instrumentation helper; never a dependency of the application.
if (providers.gradleProperty("ot3a.followLab").orNull == "true") include(":follow-lab")
