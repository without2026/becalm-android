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
        // Microsoft DuoSDK feed — required transitively by MSAL's `common` artifact
        // (display-mask:0.3.0 lives here, not on Maven Central). See
        // docs/plans/repo-auth-msgraph-oauth-provider.md § 5.1 for MSAL rationale.
        maven {
            url = uri("https://pkgs.dev.azure.com/MicrosoftDeviceSDK/DuoSDK-Public/_packaging/Duo-SDK-Feed/maven/v1")
            // Scope the repository to the one dependency that actually lives here so a
            // typo or a compromised feed cannot service unrelated artifacts.
            content {
                includeGroup("com.microsoft.device.display")
            }
        }
    }
}
rootProject.name = "becalm-android"
include(":app")
include(":baselineprofile")
