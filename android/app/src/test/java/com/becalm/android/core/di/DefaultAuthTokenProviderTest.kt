package com.becalm.android.core.di

import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.data.remote.supabase.AuthResult
import com.becalm.android.data.remote.supabase.SupabaseAuthClient
import com.becalm.android.data.remote.supabase.SupabaseSession
import com.becalm.android.data.remote.supabase.SupabaseSessionStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Tests for the H3 token-cache + refresh-coalescing behaviour of [DefaultAuthTokenProvider].
 *
 * Design notes:
 * - [DefaultAuthTokenProvider] subscribes to [SupabaseSessionStore.observe] in its init block
 *   on an `Unconfined` dispatcher, so emissions propagate to the cache synchronously on the
 *   emit thread. Tests therefore `tryEmit` into a real [MutableSharedFlow] returned from the
 *   mock store and assert against the cache on the next line.
 * - The store mock is wired so that `save(x)` and `clear()` forward to the shared flow,
 *   mimicking the real [com.becalm.android.data.local.secure.EncryptedTokenStore] behaviour.
 */
class DefaultAuthTokenProviderTest {

    private lateinit var authClient: SupabaseAuthClient
    private lateinit var sessionStore: SupabaseSessionStore
    private lateinit var sessionChanges: MutableSharedFlow<SupabaseSession?>

    @Before
    fun setUp() {
        authClient = mockk()
        sessionChanges = MutableSharedFlow(
            replay = 0,
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        sessionStore = mockk {
            every { observe() } returns sessionChanges.asSharedFlow()
            coEvery { load() } returns null
            coEvery { save(any()) } coAnswers {
                sessionChanges.tryEmit(firstArg())
                Unit
            }
            coEvery { clear() } coAnswers {
                sessionChanges.tryEmit(null)
                Unit
            }
        }
    }

    @Test
    fun `currentAccessToken returns null when no session exists`() {
        val provider = DefaultAuthTokenProvider(authClient, sessionStore)

        assertNull(provider.currentAccessToken())
    }

    @Test
    fun `currentAccessToken cold path loads from store exactly once and seeds cache`() {
        coEvery { sessionStore.load() } returns session("at_v1")

        val provider = DefaultAuthTokenProvider(authClient, sessionStore)

        assertEquals("at_v1", provider.currentAccessToken())
        assertEquals("at_v1", provider.currentAccessToken())
        coVerify(exactly = 1) { sessionStore.load() }
    }

    @Test
    fun `observer updates cache when store emits a new session`() {
        val provider = DefaultAuthTokenProvider(authClient, sessionStore)

        sessionChanges.tryEmit(session("at_from_observer"))

        assertEquals("at_from_observer", provider.currentAccessToken())
    }

    @Test
    fun `observer clears cache when store emits null`() {
        val provider = DefaultAuthTokenProvider(authClient, sessionStore)
        sessionChanges.tryEmit(session("at_v1"))
        assertEquals("at_v1", provider.currentAccessToken())

        sessionChanges.tryEmit(null)

        assertNull(provider.currentAccessToken())
    }

    @Test
    fun `refresh returns null when no session is persisted`() = runTest {
        val provider = DefaultAuthTokenProvider(authClient, sessionStore)

        assertNull(provider.refresh(previousAccessToken = "stale_token"))
    }

    @Test
    fun `refresh success saves new session and caches new access token`() = runTest {
        coEvery { sessionStore.load() } returns session("at_v1", refreshToken = "rt_v1")
        val newSession = session("at_v2", refreshToken = "rt_v2")
        coEvery { authClient.refresh("rt_v1") } returns BecalmResult.Success(AuthResult(newSession))

        val provider = DefaultAuthTokenProvider(authClient, sessionStore)
        val result = provider.refresh(previousAccessToken = "at_v1")

        assertEquals("at_v2", result)
        coVerify { sessionStore.save(newSession) }
        assertEquals("at_v2", provider.currentAccessToken())
    }

    @Test
    fun `refresh returns null when Supabase refresh fails, cache preserved`() = runTest {
        coEvery { sessionStore.load() } returns session("at_v1", refreshToken = "rt_v1")
        coEvery { authClient.refresh("rt_v1") } returns BecalmResult.Failure(
            BecalmError.Network(code = -1, message = "bad net"),
        )

        val provider = DefaultAuthTokenProvider(authClient, sessionStore)
        sessionChanges.tryEmit(session("at_v1"))

        val result = provider.refresh(previousAccessToken = "at_v1")

        assertNull(result)
        assertEquals("at_v1", provider.currentAccessToken())
    }

    @Test
    fun `refresh coalesces when cache is already newer than previousAccessToken`() = runTest {
        coEvery { sessionStore.load() } returns session("at_v2", refreshToken = "rt_v2")
        sessionChanges.tryEmit(session("at_v2"))

        val provider = DefaultAuthTokenProvider(authClient, sessionStore)
        val result = provider.refresh(previousAccessToken = "at_v1")

        assertEquals("at_v2", result)
        // Critical: Supabase refresh endpoint was NOT called.
        coVerify(exactly = 0) { authClient.refresh(any()) }
    }

    @Test
    fun `refresh hits Supabase when cache equals previousAccessToken`() = runTest {
        coEvery { sessionStore.load() } returns session("at_v1", refreshToken = "rt_v1")
        sessionChanges.tryEmit(session("at_v1"))
        val newSession = session("at_v2", refreshToken = "rt_v2")
        coEvery { authClient.refresh("rt_v1") } returns BecalmResult.Success(AuthResult(newSession))

        val provider = DefaultAuthTokenProvider(authClient, sessionStore)
        val result = provider.refresh(previousAccessToken = "at_v1")

        assertEquals("at_v2", result)
        coVerify(exactly = 1) { authClient.refresh("rt_v1") }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private fun session(
        accessToken: String,
        refreshToken: String = "rt_default",
    ): SupabaseSession = SupabaseSession(
        accessToken = accessToken,
        refreshToken = refreshToken,
        userId = "user-123",
        email = "test@test.com",
        expiresAt = Instant.fromEpochSeconds(1_000_000),
    )
}
