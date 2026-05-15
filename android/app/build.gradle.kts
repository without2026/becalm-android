import java.util.Properties
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.androidx.baselineprofile)
}

val googleServicesJson = project.file("google-services.json")
if (googleServicesJson.isFile) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
} else {
    logger.warn(
        "WARNING: app/google-services.json not found. Firebase Crashlytics Gradle tasks " +
            "will be disabled for this local build; protected release builds fail verification.",
    )
}

// Load local.properties for BuildConfig secrets.
// Missing keys are substituted with empty strings and a Gradle WARNING is emitted.
val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.exists()) {
    localProps.load(localPropsFile.inputStream())
} else {
    logger.warn(
        "WARNING: local.properties not found. " +
            "BECALM_API_BASE_URL, SUPABASE_URL, SUPABASE_ANON_KEY, AMPLITUDE_API_KEY will be empty strings. " +
            "Copy local.properties.sample → local.properties and fill in values."
    )
}

fun localProp(key: String): String {
    val value = localProps.getProperty(key)
    if (value.isNullOrBlank()) {
        logger.warn("WARNING: local.properties key '$key' is missing or empty.")
    }
    return value ?: ""
}

fun gradleOrLocalProp(key: String): String {
    val gradleValue = findProperty(key)?.toString()
    if (!gradleValue.isNullOrBlank()) {
        return gradleValue
    }
    return localProp(key)
}

fun requiredRuntimeConfig(key: String): String {
    val gradleValue = findProperty(key)?.toString()
    if (!gradleValue.isNullOrBlank()) return gradleValue
    val envValue = System.getenv(key)
    if (!envValue.isNullOrBlank()) return envValue
    return localProp(key)
}

fun String.asBuildConfigString(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

fun optionalGradleEnvOrLocalProp(gradleKey: String, envKey: String): String? {
    val gradleValue = findProperty(gradleKey)?.toString()
    if (!gradleValue.isNullOrBlank()) return gradleValue
    val gradleEnvAliasValue = findProperty(envKey)?.toString()
    if (!gradleEnvAliasValue.isNullOrBlank()) return gradleEnvAliasValue
    val envValue = System.getenv(envKey)
    if (!envValue.isNullOrBlank()) return envValue
    val localGradleValue = localProps.getProperty(gradleKey)
    if (!localGradleValue.isNullOrBlank()) return localGradleValue
    return localProps.getProperty(envKey)?.takeIf { it.isNotBlank() }
}

fun isJvmUnitTestInvocation(): Boolean =
    gradle.startParameter.taskNames.any { taskName ->
        val normalized = taskName.substringAfterLast(':')
        normalized == "test" || normalized.contains("UnitTest", ignoreCase = true)
    }

val releaseStoreFilePath = optionalGradleEnvOrLocalProp(
    gradleKey = "becalm.release.store.file",
    envKey = "BECALM_RELEASE_STORE_FILE",
)
val releaseStorePassword = optionalGradleEnvOrLocalProp(
    gradleKey = "becalm.release.store.password",
    envKey = "BECALM_RELEASE_STORE_PASSWORD",
)
val releaseKeyAlias = optionalGradleEnvOrLocalProp(
    gradleKey = "becalm.release.key.alias",
    envKey = "BECALM_RELEASE_KEY_ALIAS",
)
val releaseKeyPassword = optionalGradleEnvOrLocalProp(
    gradleKey = "becalm.release.key.password",
    envKey = "BECALM_RELEASE_KEY_PASSWORD",
)
val releaseSigningConfigured = listOf(
    releaseStoreFilePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }
val becalmApiBaseUrl = requiredRuntimeConfig("BECALM_API_BASE_URL")
val supabaseUrl = requiredRuntimeConfig("SUPABASE_URL")
val supabaseAnonKey = requiredRuntimeConfig("SUPABASE_ANON_KEY")
val googleWebClientId = optionalGradleEnvOrLocalProp(
    gradleKey = "google.web.client.id",
    envKey = "GOOGLE_WEB_CLIENT_ID",
).orEmpty()
val amplitudeApiKey = requiredRuntimeConfig("AMPLITUDE_API_KEY")
val configuredTelemetryEnabled = optionalGradleEnvOrLocalProp(
    gradleKey = "telemetry.enabled",
    envKey = "TELEMETRY_ENABLED",
)?.toBooleanStrictOrNull()
val telemetryEnabled = if (isJvmUnitTestInvocation()) false else configuredTelemetryEnabled ?: true
val requiredReleaseRuntimeConfig = mapOf(
    "BECALM_API_BASE_URL" to becalmApiBaseUrl,
    "SUPABASE_URL" to supabaseUrl,
    "SUPABASE_ANON_KEY" to supabaseAnonKey,
    "GOOGLE_WEB_CLIENT_ID" to googleWebClientId,
    "AMPLITUDE_API_KEY" to amplitudeApiKey,
)

android {
    namespace = "com.becalm.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.becalm.android"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "BECALM_API_BASE_URL", becalmApiBaseUrl.asBuildConfigString())
        buildConfigField("String", "SUPABASE_URL",        supabaseUrl.asBuildConfigString())
        buildConfigField("String", "SUPABASE_ANON_KEY",   supabaseAnonKey.asBuildConfigString())
        // Google Web OAuth 2.0 Client ID registered against the Supabase project's
        // Google provider (S6-C). Developer overrides via Gradle property
        // `google.web.client.id` (typically in ~/.gradle/gradle.properties or
        // local.properties); CI supplies the production value. An empty string is a
        // valid placeholder for debug builds that never attempt Google sign-in — the
        // LoginScreen CTA disables itself when this field is blank so a misconfigured
        // build cannot launch CredentialManager into a broken state.
        buildConfigField(
            "String",
            "GOOGLE_WEB_CLIENT_ID",
            googleWebClientId.asBuildConfigString(),
        )
        buildConfigField("String", "AMPLITUDE_API_KEY", amplitudeApiKey.asBuildConfigString())
        buildConfigField("boolean", "TELEMETRY_ENABLED", telemetryEnabled.toString())
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("releaseUpload") {
                storeFile = rootProject.file(requireNotNull(releaseStoreFilePath))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("releaseUpload")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("androidTest") {
            assets.srcDir("$projectDir/schemas")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlin.RequiresOptIn",
            // Align bytecode with kotlinx-serialization 1.8.0+ `all-compatibility` jvm-default mode.
            // Kotlin 2.2+ flips the default to `-jvm-default=enable`, but on 2.1.x we must opt in
            // explicitly or generated `$$serializer` classes throw AbstractMethodError when they
            // invoke interface-default methods like GeneratedSerializer.typeParametersSerializers().
            // See kotlinx.serialization CHANGELOG 1.8.0-RC (all-compatibility adoption) +
            // Kotlin compatibility-guide-22 (jvm-default default change).
            "-Xjvm-default=all-compatibility"
        )
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/NOTICE.md"
            excludes += "META-INF/LICENSE-notice.md"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    lint {
        // AGP 8.7.3 + Kotlin 2.1.x crashes this detector in lintVitalRelease with:
        // "Found class KaCallableMemberCall, but interface was expected".
        // BeCalm does not use MutableLiveData in production UI state, so disabling this
        // single detector keeps release validation unblocked without weakening ANR checks.
        disable += "NullSafeMutableLiveData"
    }
}

baselineProfile {
    automaticGenerationDuringBuild = false
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

tasks.register("verifyReleaseSigningConfigured") {
    group = "verification"
    description = "Fails protected release builds when Android upload signing is not configured."
    doLast {
        if (!releaseSigningConfigured) {
            throw GradleException(
                "Android release upload signing is not configured. Set " +
                    "BECALM_RELEASE_STORE_FILE, BECALM_RELEASE_STORE_PASSWORD, " +
                    "BECALM_RELEASE_KEY_ALIAS, and BECALM_RELEASE_KEY_PASSWORD " +
                    "(or matching becalm.release.* Gradle/local.properties keys).",
            )
        }
        val storeFile = rootProject.file(requireNotNull(releaseStoreFilePath))
        if (!storeFile.isFile) {
            throw GradleException("Android release upload keystore does not exist: ${storeFile.path}")
        }
    }
}

tasks.register("verifyReleaseRuntimeConfigured") {
    group = "verification"
    description = "Fails protected release builds when Android runtime API/Auth config is not configured."
    doLast {
        val missing = requiredReleaseRuntimeConfig
            .filterValues { it.isBlank() }
            .keys
            .sorted()
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Android release runtime configuration is incomplete. Set " +
                    missing.joinToString(", ") +
                    " via environment variables, Gradle properties, or local.properties before protected release builds.",
            )
        }
        if (telemetryEnabled && !googleServicesJson.isFile) {
            throw GradleException(
                "Android telemetry is enabled but app/google-services.json is missing. " +
                    "Generate it from CI secrets or disable telemetry for non-protected local builds.",
            )
        }
    }
}

dependencies {
    // ─── KotlinX ─────────────────────────────────────────────────────────────
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.datetime)

    // ─── Compose ─────────────────────────────────────────────────────────────
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material)
    implementation(libs.google.android.material)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)

    // ─── Lifecycle ───────────────────────────────────────────────────────────
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)

    // ─── Navigation ──────────────────────────────────────────────────────────
    implementation(libs.androidx.navigation.compose)

    // ─── Hilt ────────────────────────────────────────────────────────────────
    // Compiler runs via KSP (not KAPT) — KAPT can't read Kotlin 2.1 metadata (mv={2,1,0})
    // and throws "Unable to read Kotlin metadata due to unsupported metadata kind: null"
    // on @HiltWorker classes. Dagger Hilt supports KSP from 2.54+; Dagger #4303 closed.
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // ─── Room ────────────────────────────────────────────────────────────────
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // ─── WorkManager ─────────────────────────────────────────────────────────
    implementation(libs.androidx.work.runtime.ktx)

    // ─── DataStore ───────────────────────────────────────────────────────────
    implementation(libs.androidx.datastore.preferences)

    // ─── Security Crypto ─────────────────────────────────────────────────────
    implementation(libs.androidx.security.crypto)

    // ─── Retrofit + OkHttp ───────────────────────────────────────────────────
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.moshi)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // ─── Moshi ───────────────────────────────────────────────────────────────
    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)
    ksp(libs.moshi.kotlin.codegen)

    // ─── Supabase (BOM-managed) ───────────────────────────────────────────────
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)

    // ─── Ktor (Supabase transport) ────────────────────────────────────────────
    implementation(libs.ktor.client.okhttp)

    // ─── Jakarta Mail / Angus Mail (IMAPS fetch in ImapClient) ────────────────
    implementation(libs.jakarta.mail)
    implementation(libs.angus.mail)

    // ─── Jsoup (HTML → plain-text for EmailSnippetBuilder, EMAIL-003) ────────
    implementation(libs.jsoup)

    // ─── Google Identity Services (Supabase Google login) ───────────────────
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.play.services.auth)

    // ─── Product analytics + crash reporting ───────────────────────────────
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.crashlytics)
    implementation(libs.amplitude.analytics.android)

    // ─── libphonenumber (E.164 normalization for call-recording person_ref) ──
    implementation(libs.libphonenumber)

    // ─── Timber ──────────────────────────────────────────────────────────────
    implementation(libs.timber)
    implementation(libs.androidx.profileinstaller)

    // ─── Baseline Profile ───────────────────────────────────────────────────
    baselineProfile(project(":baselineprofile"))

    // ─── Compose tooling (debug only) ────────────────────────────────────────
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    add("benchmarkImplementation", libs.androidx.compose.ui.test.manifest)

    // ─── Unit Tests ──────────────────────────────────────────────────────────
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.mockwebserver)
    // kotlin-reflect powers the DTO-invariant reflection test
    // (DtoInvariantTest.rawIngestionEventDto_* asserts the wire shape never leaks EmailBody
    // fields). Unused at runtime — compileOnly on main would also work, but the reflection
    // is only exercised from src/test so it is declared as testImplementation.
    testImplementation(libs.kotlin.reflect)
    // ApplicationProvider used by Robolectric unit tests.
    testImplementation(libs.androidx.test.core)

    // ─── Instrumented Tests ───────────────────────────────────────────────────
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.hilt.android.testing)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

tasks.withType<Test>().configureEach {
    if (name == "testReleaseUnitTest") {
        // Compose UI tests need androidx.compose.ui:ui-test-manifest, which must stay out of
        // release artifacts. Run those UI suites on debug/benchmark variants and keep release
        // unit tests focused on non-UI logic.
        exclude("com/becalm/android/integration/local/ui/**")
    }
}
