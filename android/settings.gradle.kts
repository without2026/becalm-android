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
        // supabase-kt is published on Maven Central
        // gemini nano aicore is distributed via Google's Maven repository (included above)
    }
}
rootProject.name = "becalm-android"
include(":app")
