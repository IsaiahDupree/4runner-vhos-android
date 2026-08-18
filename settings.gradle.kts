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
    }
}

rootProject.name = "VHOSHeadUnit"

include(
    ":app",
    ":core:model",
    ":core:discovery",
    ":core:protocol",
    ":core:release",
    ":core:sync",
    ":data:store",
    ":transport:ble",
)
