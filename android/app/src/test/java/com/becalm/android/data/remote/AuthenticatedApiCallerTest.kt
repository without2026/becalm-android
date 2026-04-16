package com.becalm.android.data.remote

import com.becalm.android.auth.AuthResult
import com.becalm.android.auth.SupabaseAuthService
import com.becalm.android.auth.TokenStore
import com.becalm.android.data.remote.api.ApiCallResult
import com.becalm.android.data.remote.api.AuthenticatedApiCaller
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

// spec: AUTH-007 — 401 Railway response → refresh → 1 retry → Unauthorized if still 401
// spec: AUTH-004 — token update on successful refresh

class AuthenticatedApiCallerTest {

    private val tokenStore: TokenStore = mockk()
    private val authService: SupabaseAuthService = mockk()
    private lateinit var caller: AuthenticatedApiCaller

    @Before
    fun setUp() {
        caller = AuthenticatedApiCaller(tokenStore, authService)
    }

    // spec: AUTH-007 — successful API call returns data
    @Test
    fun `call returns Success on 200 response`() = runTest {
        every { tokenStore.getBearerHeader() } returns "Bearer valid_token"
        val result = caller.call<String> { _ ->
            Response.success("data")
        }
        assertTrue(result is ApiCallResult.Success)
        assertEquals("data", (result as ApiCallResult.Success).data)
    }

    // spec: AUTH-007 — 401 triggers refresh and retry
    @Test
    fun `call refreshes token on 401 and retries`() = runTest {
        every { tokenStore.getBearerHeader() } returns "Bearer expired_token"
        every { tokenStore.getRefreshToken() } returns "refresh_token"
        coEvery { authService.refreshToken("refresh_token") } returns
            AuthResult.Success("new_access", "new_refresh", "user123")
        every { tokenStore.updateTokens("new_access", "new_refresh") } returns Unit

        var callCount = 0
        val result = caller.call<String> { bearer ->
            callCount++
            if (callCount == 1) {
                // First call returns 401
                Response.error(401, "{}".toResponseBody())
            } else {
                // Second call (with new token) succeeds
                Response.success("retried_data")
            }
        }

        assertTrue(result is ApiCallResult.Success)
        assertEquals("retried_data", (result as ApiCallResult.Success).data)
        assertEquals(2, callCount)
        coVerify { tokenStore.updateTokens("new_access", "new_refresh") }
    }

    // spec: AUTH-007 — 401 on retry forces logout
    @Test
    fun `call returns Unauthorized when retry also returns 401`() = runTest {
        every { tokenStore.getBearerHeader() } returns "Bearer expired_token"
        every { tokenStore.getRefreshToken() } returns "refresh_token"
        coEvery { authService.refreshToken("refresh_token") } returns
            AuthResult.Success("new_access", "new_refresh", "user123")
        every { tokenStore.updateTokens(any(), any()) } returns Unit

        val result = caller.call<String> { _ ->
            // Both calls return 401
            Response.error(401, "{}".toResponseBody())
        }

        assertTrue(result is ApiCallResult.Unauthorized)
    }

    // spec: AUTH-007 — no token returns Unauthorized immediately
    @Test
    fun `call returns Unauthorized when no token stored`() = runTest {
        every { tokenStore.getBearerHeader() } returns null
        val result = caller.call<String> { _ -> Response.success("data") }
        assertTrue(result is ApiCallResult.Unauthorized)
    }

    // spec: AUTH-007 — refresh failure returns Unauthorized
    @Test
    fun `call returns Unauthorized when refresh fails`() = runTest {
        every { tokenStore.getBearerHeader() } returns "Bearer expired_token"
        every { tokenStore.getRefreshToken() } returns "bad_refresh"
        coEvery { authService.refreshToken("bad_refresh") } returns
            AuthResult.Error(400, "invalid_grant")

        val result = caller.call<String> { _ ->
            Response.error(401, "{}".toResponseBody())
        }

        assertTrue(result is ApiCallResult.Unauthorized)
    }

    // spec: SYNC-003 — 5xx returns HttpError
    @Test
    fun `call returns HttpError on 5xx`() = runTest {
        every { tokenStore.getBearerHeader() } returns "Bearer valid"
        val result = caller.call<String> { _ ->
            Response.error(500, "{}".toResponseBody())
        }
        assertTrue(result is ApiCallResult.HttpError)
        assertEquals(500, (result as ApiCallResult.HttpError).code)
    }

    // spec: network error returns NetworkError
    @Test
    fun `call returns NetworkError on exception`() = runTest {
        every { tokenStore.getBearerHeader() } returns "Bearer valid"
        val result = caller.call<String> { _ ->
            throw java.net.SocketTimeoutException("timeout")
        }
        assertTrue(result is ApiCallResult.NetworkError)
    }
}
