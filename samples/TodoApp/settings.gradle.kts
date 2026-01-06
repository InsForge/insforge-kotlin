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
        // Local InsForge SDK
        maven { url = uri("../../build/libs") }
    }
}

rootProject.name = "TodoApp"
include(":app")
