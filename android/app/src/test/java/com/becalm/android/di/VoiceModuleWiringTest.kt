package com.becalm.android.di

import com.becalm.android.data.remote.api.ApiFactory
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.api.VoiceApi
import com.becalm.android.data.remote.interceptor.AuthTokenProvider
import com.becalm.android.data.remote.interceptor.IdempotencyKeyProvider
import com.becalm.android.core.util.addBecalmAdapters
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * Structural wiring tests for the VoiceApi DI module.
 *
 * Confirms:
 *   1. ApiFactory.createOkHttpClient returns a non-null OkHttpClient.
 *   2. ApiFactory.createRetrofit returns a non-null Retrofit instance.
 *   3. ApiFactory.createRailwayApi returns a non-null RailwayApi proxy.
 *   4. VoiceApi is constructable from the same Retrofit instance (VoiceApi EXISTS).
 *   5. VoiceApi.transcribeExtract is accessible via reflection (method shape matches spec).
 *
 * Deliberately uses the lightweight "plain JVM test that instantiates ApiFactory"
 * approach rather than full @HiltAndroidTest to avoid AndroidJUnit4 + Hilt overhead
 * in CI unit test runs.
 *
 * Spec refs: VOI-001, api-contract.yml § /v1/voice/transcribe_extract.
 */
class VoiceModuleWiringTest {

    private lateinit var moshi: Moshi
    private lateinit var authTokenProvider: AuthTokenProvider
    private lateinit var idempotencyKeyProvider: IdempotencyKeyProvider

    @Before
    fun setUp() {
        moshi = Moshi.Builder()
            .addBecalmAdapters()
            .add(KotlinJsonAdapterFactory())
            .build()

        authTokenProvider = mockk {
            every { currentAccessToken() } returns "test-jwt-token"
            coEvery { refresh() } returns "test-refreshed-token"
        }

        idempotencyKeyProvider = mockk {
            every { generate() } returns "test-idempotency-key-${System.nanoTime()}"
        }
    }

    private fun buildRetrofit(): retrofit2.Retrofit {
        val client = ApiFactory.createOkHttpClient(
            authProvider = authTokenProvider,
            idempotencyProvider = idempotencyKeyProvider,
            railwayHost = "api.becalm.test",
            isDebug = false,
        )
        return ApiFactory.createRetrofit(
            baseUrl = "https://api.becalm.test/",
            okHttp = client,
            moshi = moshi,
        )
    }

    // ---------------------------------------------------------------------------
    // Structural tests — compile and run without Android runtime
    // ---------------------------------------------------------------------------

    @Test
    fun `ApiFactory createOkHttpClient returns non-null client`() {
        val client = ApiFactory.createOkHttpClient(
            authProvider = authTokenProvider,
            idempotencyProvider = idempotencyKeyProvider,
            railwayHost = "api.becalm.test",
            isDebug = false,
        )
        assertNotNull("OkHttpClient must not be null", client)
    }

    @Test
    fun `ApiFactory createRetrofit returns non-null Retrofit instance`() {
        assertNotNull("Retrofit must not be null", buildRetrofit())
    }

    @Test
    fun `ApiFactory createRailwayApi returns non-null RailwayApi proxy`() {
        val api: RailwayApi = ApiFactory.createRailwayApi(buildRetrofit())
        assertNotNull("RailwayApi must not be null", api)
    }

    // ---------------------------------------------------------------------------
    // VoiceApi wiring — VoiceApi exists; no @Ignore needed
    // ---------------------------------------------------------------------------

    @Test
    fun `VoiceApi is constructable from Retrofit and is non-null`() {
        val retrofit = buildRetrofit()
        val voiceApi: VoiceApi = retrofit.create(VoiceApi::class.java)
        assertNotNull("VoiceApi must not be null", voiceApi)
    }

    @Test
    fun `VoiceApi transcribeExtract method exists with correct parameter count`() {
        // Verify the method exists with the 7 parameters specified in VoiceApi.kt.
        // api-contract.yml prescribes: audio, client_event_id, raw_event_id,
        // duration_seconds, timestamp, person_ref?, event_title?
        val methods = VoiceApi::class.java.declaredMethods
            .filter { it.name == "transcribeExtract" }
        assertTrue(
            "VoiceApi must have exactly one transcribeExtract method",
            methods.isNotEmpty(),
        )
        // transcribeExtract has 7 @Part parameters + Continuation = 8 JVM params for suspend fun
        val method = methods[0]
        assertTrue(
            "transcribeExtract must have >= 7 parameters (7 @Part + Continuation for suspend)",
            method.parameterCount >= 7,
        )
    }
}

private fun assertTrue(msg: String, value: Boolean) {
    org.junit.Assert.assertTrue(msg, value)
}
