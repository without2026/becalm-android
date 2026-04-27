package com.becalm.android.unit.core.di

import com.becalm.android.core.di.DefaultAuthTokenProvider
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.remote.interceptor.AuthTokenProvider
import com.becalm.android.data.remote.supabase.SupabaseAuthClient
import com.becalm.android.data.remote.supabase.SupabaseSession
import com.becalm.android.data.remote.supabase.SupabaseSessionStore
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import javax.inject.Provider

class DefaultAuthTokenProviderSpecTest {

    private val logger: Logger = mockk(relaxed = true)
    private val session = SupabaseSession(
        accessToken = "access-1",
        refreshToken = "refresh-1",
        userId = "user-1",
        email = "user@example.com",
        expiresAt = Instant.parse("2026-04-27T00:00:00Z"),
    )

    @Test
    fun `AUTH-009 current token read does not instantiate Supabase auth client`() = runTest {
        val store = FakeSessionStore(initial = session)
        val provider = DefaultAuthTokenProvider(
            authClientProvider = Provider { error("SupabaseAuthClient must stay lazy until refresh") },
            sessionStore = store,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            logger = logger,
        )

        assertEquals("access-1", provider.currentAccessToken())
        assertEquals(1, store.loadCount)
    }

    @Test
    fun `AUTH-004 refresh instantiates Supabase auth client only on demand`() = runTest {
        val store = FakeSessionStore(initial = session)
        val refreshed = session.copy(accessToken = "access-2", refreshToken = "refresh-2")
        val authClient = StubSupabaseAuthClient(refreshResult = BecalmResult.Success(refreshed))
        var providerCalls = 0
        val provider = DefaultAuthTokenProvider(
            authClientProvider = Provider {
                providerCalls += 1
                authClient
            },
            sessionStore = store,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            logger = logger,
        )

        val result = provider.refresh(previousAccessToken = "access-1")

        assertEquals(AuthTokenProvider.RefreshResult.Refreshed("access-2"), result)
        assertEquals(1, providerCalls)
        assertEquals(1, authClient.refreshCalls)
        assertSame(refreshed, store.saved)
    }

    private class FakeSessionStore(initial: SupabaseSession?) : SupabaseSessionStore {
        private val changes = MutableSharedFlow<SupabaseSession?>()
        private var current = initial
        var loadCount = 0
            private set
        var saved: SupabaseSession? = null
            private set

        override suspend fun save(session: SupabaseSession) {
            current = session
            saved = session
            changes.emit(session)
        }

        override suspend fun load(): SupabaseSession? {
            loadCount += 1
            return current
        }

        override suspend fun clear() {
            current = null
            changes.emit(null)
        }

        override fun observe(): SharedFlow<SupabaseSession?> = changes
    }

    private class StubSupabaseAuthClient(
        private val refreshResult: BecalmResult<SupabaseSession>? = null,
    ) : SupabaseAuthClient {
        var refreshCalls = 0
            private set

        override suspend fun signInWithEmail(
            email: String,
            password: String,
        ): BecalmResult<SupabaseSession> = error("not expected")

        override suspend fun signUpWithEmail(
            email: String,
            password: String,
        ): BecalmResult<SupabaseSession> = error("not expected")

        override suspend fun signInWithGoogleIdToken(idToken: String): BecalmResult<SupabaseSession> =
            error("not expected")

        override suspend fun refresh(refreshToken: String): BecalmResult<SupabaseSession> {
            refreshCalls += 1
            return refreshResult ?: error("refresh not expected")
        }

        override suspend fun signOut(accessToken: String): BecalmResult<Unit> = error("not expected")
    }
}
