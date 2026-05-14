// Root project build file — plugin declarations only; no apply.
// All module-level configuration lives in app/build.gradle.kts.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.kapt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
    alias(libs.plugins.owasp.dependencycheck)
}

dependencyCheck {
    failBuildOnCVSS = 7.0F
    formats = listOf("HTML", "JSON")
    outputDirectory.set(layout.buildDirectory.dir("reports/dependency-check"))
    suppressionFile = rootProject.file("config/dependency-check-suppression.xml").absolutePath
    analyzers.assemblyEnabled = false
    nvd.apiKey = providers.environmentVariable("NVD_API_KEY").orNull
}
