package com.becalm.android.ui.auth

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.becalm.android.auth.AuthResult
import com.becalm.android.auth.SupabaseAuthService
import com.becalm.android.auth.TokenStore
import com.becalm.android.data.local.dao.PersonEnrichmentDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

// spec: AUTH-001 — email+password login success
// spec: AUTH-002 — invalid password error
// spec: AUTH-003 — Google OAuth
// spec: AUTH-005 — logout: tokens cleared, persons_enrichment deleted
// spec: ENR-007 — persons_enrichment deleted on logout

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val authService: SupabaseAuthService = mockk()
    private val tokenStore: TokenStore = mockk(relaxed = true)
    private val personEnrichmentDao: PersonEnrichmentDao = mockk(relaxed = true)

    // spec: AUTH-001 — successful login
    @Test
    fun `loginWithEmailPassword success saves tokens and sets LoggedIn state`() = runTest {
        every { tokenStore.isLoggedIn() } returns false
        coEvery { authService.loginWithEmailPassword("test@becalm.com", "ValidPass1!") } returns
            AuthResult.Success("access123", "refresh456", "user789")

        val vm = AuthViewModel(authService, tokenStore, personEnrichmentDao)
        vm.loginWithEmailPassword("test@becalm.com", "ValidPass1!")
        advanceUntilIdle()

        assertTrue(vm.authState.value is AuthState.LoggedIn)
        verify { tokenStore.saveTokens("access123", "refresh456", "user789") }
    }

    // spec: AUTH-002 — invalid password shows error
    @Test
    fun `loginWithEmailPassword 400 invalid_grant shows Korean error message`() = runTest {
        every { tokenStore.isLoggedIn() } returns false
        coEvery { authService.loginWithEmailPassword(any(), any()) } returns
            AuthResult.Error(400, "invalid_grant")

        val vm = AuthViewModel(authService, tokenStore, personEnrichmentDao)
        vm.loginWithEmailPassword("test@becalm.com", "wrongpass")
        advanceUntilIdle()

        val state = vm.authState.value
        assertTrue(state is AuthState.Error)
        assertEquals("이메일 또는 비밀번호가 올바르지 않습니다", (state as AuthState.Error).message)
    }

    // spec: AUTH-001 — network error shows error state
    @Test
    fun `loginWithEmailPassword network error shows network error message`() = runTest {
        every { tokenStore.isLoggedIn() } returns false
        coEvery { authService.loginWithEmailPassword(any(), any()) } returns AuthResult.NetworkError

        val vm = AuthViewModel(authService, tokenStore, personEnrichmentDao)
        vm.loginWithEmailPassword("test@becalm.com", "ValidPass1!")
        advanceUntilIdle()

        assertTrue(vm.authState.value is AuthState.Error)
    }

    // spec: AUTH-003 — Google OAuth success
    @Test
    fun `loginWithGoogleIdToken success saves tokens and sets LoggedIn state`() = runTest {
        every { tokenStore.isLoggedIn() } returns false
        coEvery { authService.loginWithGoogleIdToken("google_id_token") } returns
            AuthResult.Success("access_g", "refresh_g", "user_g")

        val vm = AuthViewModel(authService, tokenStore, personEnrichmentDao)
        vm.loginWithGoogleIdToken("google_id_token")
        advanceUntilIdle()

        assertTrue(vm.authState.value is AuthState.LoggedIn)
    }

    // spec: AUTH-005 — logout clears tokens
    @Test
    fun `logout clears tokens and sets LoggedOut state`() = runTest {
        every { tokenStore.isLoggedIn() } returns true
        every { tokenStore.getUserId() } returns "user123"
        every { tokenStore.getAccessToken() } returns "access_token"
        coEvery { authService.logout(any()) } returns true

        val vm = AuthViewModel(authService, tokenStore, personEnrichmentDao)
        vm.logout()
        advanceUntilIdle()

        verify { tokenStore.clearTokens() }
        assertTrue(vm.authState.value is AuthState.LoggedOut)
    }

    // spec: ENR-007 — persons_enrichment deleted on logout (PIPA)
    @Test
    fun `logout deletes persons_enrichment for PIPA compliance`() = runTest {
        every { tokenStore.isLoggedIn() } returns true
        every { tokenStore.getUserId() } returns "user123"
        every { tokenStore.getAccessToken() } returns "access_token"
        coEvery { authService.logout(any()) } returns true

        val vm = AuthViewModel(authService, tokenStore, personEnrichmentDao)
        vm.logout()
        advanceUntilIdle()

        coVerify { personEnrichmentDao.deleteAll() }
    }

    // spec: ONB-006 — already logged in on init → LoggedIn state immediately
    @Test
    fun `init sets LoggedIn state when token already exists`() {
        every { tokenStore.isLoggedIn() } returns true
        every { tokenStore.getUserId() } returns "user_existing"

        val vm = AuthViewModel(authService, tokenStore, personEnrichmentDao)
        assertTrue(vm.authState.value is AuthState.LoggedIn)
    }
}
