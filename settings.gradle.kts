pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Only for requery's sqlite-android, which is published on JitPack alone.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.requery") }
        }
    }
}

rootProject.name = "offline-research"
include(":app")
