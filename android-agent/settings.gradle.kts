pluginManagement {
    repositories {
        // Project-local fallback for development hosts where certificate
        // revocation endpoints are unavailable to the JDK. Only reviewed,
        // pinned artifacts belong here; normal resolution still uses Google/Maven.
        maven { url = uri("local-maven") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("local-maven") }
        google()
        mavenCentral()
    }
}

rootProject.name = "ecosystem-agent"
include(":app")
