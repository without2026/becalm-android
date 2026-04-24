import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
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
            "BECALM_API_BASE_URL, SUPABASE_URL, SUPABASE_ANON_KEY will be empty strings. " +
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

android {
    namespace = "com.becalm.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.becalm.android"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "BECALM_API_BASE_URL", "\"${localProp("BECALM_API_BASE_URL")}\"")
        buildConfigField("String", "SUPABASE_URL",        "\"${localProp("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY",   "\"${localProp("SUPABASE_ANON_KEY")}\"")
        // MSAL Microsoft Entra application (client) ID — consumed by AuthModule via
        // @Named("msalClientId"). Developer overrides via Gradle property `msal.client.id`
        // (typically set in ~/.gradle/gradle.properties or local.properties); CI supplies
        // the production value. Placeholder zero-GUID is a valid format for debug/test
        // builds that never hit the actual MSAL endpoint.
        // See docs/plans/repo-auth-msgraph-oauth-provider.md § 5.4.
        buildConfigField(
            "String",
            "MSAL_CLIENT_ID",
            "\"${findProperty("msal.client.id") ?: "00000000-0000-0000-0000-000000000000"}\"",
        )
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
            "\"${gradleOrLocalProp("google.web.client.id")}\"",
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
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
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
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

    // ─── Google Identity Services (Gmail OAuth — GoogleAuthTokenProviderImpl) ─
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.play.services.auth)

    // ─── MSAL (MS Graph OAuth — MsGraphTokenProviderImpl, ING-007) ───────────
    implementation(libs.msal)

    // ─── libphonenumber (E.164 normalization for call-recording person_ref) ──
    implementation(libs.libphonenumber)

    // ─── Gemini Nano (on-device LLM via AICore) ──────────────────────────────
    // Powers CommitmentExtractionWorker's email-source on-device commitment extraction.
    // Spec: EMAIL-001 / EMAIL-008 / KTR-GEMINI-NANO.
    implementation(libs.gemini.nano.aicore)

    // ─── Timber ──────────────────────────────────────────────────────────────
    implementation(libs.timber)

    // ─── Compose tooling (debug only) ────────────────────────────────────────
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

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
    // ApplicationProvider used by Robolectric unit tests (EmailPromptBuilderTest, CommitmentExtractionWorkerTest).
    testImplementation(libs.androidx.test.core)

    // ─── Instrumented Tests ───────────────────────────────────────────────────
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.hilt.android.testing)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
