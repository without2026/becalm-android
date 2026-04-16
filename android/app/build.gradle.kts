import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
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

android {
    namespace = "com.becalm.android"
    compileSdk = 34

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

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.compose.compiler.get()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlin.RequiresOptIn"
        )
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
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
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    kapt(libs.hilt.work.compiler)

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
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.mockwebserver)

    // ─── Instrumented Tests ───────────────────────────────────────────────────
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.hilt.android.testing)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
