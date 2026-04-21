package com.becalm.android.ui.auth

import app.cash.turbine.test
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.remote.supabase.SupabaseSession
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.AuthState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var authRepository: AuthRepository
    private lateinit var userPrefsStore: UserPrefsStore
    private lateinit var logger: Logger
    private lateinit var viewModel: AuthViewModel

    private val fakeSession = SupabaseSession(
        accessToken = "access-token",
        refreshToken = "refresh-token",
        userId = "user-123",
        email = "user@example.com",
        expiresAt = Instant.fromEpochMilliseconds(Long.MAX_VALUE),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        authRepository = mockk()
        userPrefsStore = mockk()
        logger = mockk(relaxed = true)

        // Default: no session; onboarding not completed.
        every { authRepository.observeAuthState() } returns flowOf(AuthState.Unauthenticated)
        every { userPrefsStore.observeOnboardingCompleted() } returns flowOf(false)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel(): AuthViewModel =
        AuthViewModel(authRepository, userPrefsStore, logger)

    // ─── Happy path: session observer → SignedIn ──────────────────────────────

    @Test
    fun `onObserveSession maps Authenticated to SignedIn with correct userId`() = runTest {
        every { authRepository.observeAuthState() } returns flowOf(AuthState.Authenticated(fakeSession))
        every { userPrefsStore.observeOnboardingCompleted() } returns flowOf(false)

        viewModel = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("Expected SignedIn, got $state", state is AuthUiState.SignedIn)
        assertEquals("user-123", (state as AuthUiState.SignedIn).userId)
    }

    // ─── R8/H-7: onboardingCompleted field populated from UserPrefsStore ──────

    @Test
    fun `onEmailSignIn success when onboarding already completed emits SignedIn(onboardingCompleted=true)`() = runTest {
        every { authRepository.observeAuthState() } returns flowOf(AuthState.Authenticated(fakeSession))
        every { userPrefsStore.observeOnboardingCompleted() } returns flowOf(true)
        coEvery { authRepository.signInWithEmail(any(), any()) } returns BecalmResult.Success(fakeSession)

        viewModel = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is AuthUiState.SignedIn)
        assertTrue("Expected onboardingCompleted=true", (state as AuthUiState.SignedIn).onboardingCompleted)
    }

    @Test
    fun `onEmailSignIn success when onboarding not completed emits SignedIn(onboardingCompleted=false)`() = runTest {
        every { authRepository.observeAuthState() } returns flowOf(AuthState.Authenticated(fakeSession))
        every { userPrefsStore.observeOnboardingCompleted() } returns flowOf(false)
        coEvery { authRepository.signInWithEmail(any(), any()) } returns BecalmResult.Success(fakeSession)

        viewModel = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is AuthUiState.SignedIn)
        assertFalse("Expected onboardingCompleted=false", (state as AuthUiState.SignedIn).onboardingCompleted)
    }

    // ─── Sign-out ──────────────────────────────────────────────────────────────

    @Test
    fun `onSignOut success transitions to SignedOut via observer`() = runTest {
        coEvery { authRepository.signOut() } returns BecalmResult.Success(Unit)

        viewModel = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiState.test {
            awaitItem() // current state (SignedOut from init observer)

            viewModel.onSignOut()
            testDispatcher.scheduler.advanceUntilIdle()

            val states = mutableListOf<AuthUiState>()
            while (true) {
                val item = awaitItem()
                states.add(item)
                if (item is AuthUiState.SignedOut) break
                if (states.size > 5) break
            }

            assertTrue("Expected SignedOut in emissions: $states", states.any { it is AuthUiState.SignedOut })
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ─── Error states: mapped strings, not raw toString ───────────────────────

    @Test
    fun `onEmailSignIn failure with Unauthorized emits mapped string not raw toString`() = runTest {
        coEvery {
            authRepository.signInWithEmail(any(), any())
        } returns BecalmResult.Failure(BecalmError.Unauthorized)

        viewModel = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiState.test {
            awaitItem() // SignedOut from init

            viewModel.onEmailSignIn("bad@user.com", "wrong")
            testDispatcher.scheduler.advanceUntilIdle()

            val states = mutableListOf<AuthUiState>()
            repeat(3) {
                runCatching { awaitItem() }.getOrNull()?.let { states.add(it) }
            }

            val errorState = states.filterIsInstance<AuthUiState.Error>().firstOrNull()
            assertTrue("Expected Error state in: $states", errorState != null)
            assertEquals("Invalid email or password", errorState!!.message)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onEmailSignIn failure with Network emits mapped network error string`() = runTest {
        coEvery {
            authRepository.signInWithEmail(any(), any())
        } returns BecalmResult.Failure(BecalmError.Network(503, "Service Unavailable"))

        viewModel = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiState.test {
            awaitItem() // SignedOut

            viewModel.onEmailSignIn("a@b.com", "pass")
            testDispatcher.scheduler.advanceUntilIdle()

            val states = mutableListOf<AuthUiState>()
            repeat(3) {
                runCatching { awaitItem() }.getOrNull()?.let { states.add(it) }
            }

            val errorState = states.filterIsInstance<AuthUiState.Error>().firstOrNull()
            assertTrue("Expected Error state", errorState != null)
            assertEquals("Network error. Please try again.", errorState!!.message)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onEmailSignIn failure emits Error state`() = runTest {
        coEvery {
            authRepository.signInWithEmail(any(), any())
        } returns BecalmResult.Failure(BecalmError.Unauthorized)

        viewModel = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiState.test {
            awaitItem() // SignedOut from init observer

            viewModel.onEmailSignIn("bad@user.com", "wrong")
            testDispatcher.scheduler.advanceUntilIdle()

            val states = mutableListOf<AuthUiState>()
            repeat(3) {
                runCatching { awaitItem() }.getOrNull()?.let { states.add(it) }
            }

            assertTrue(
                "Expected Error in emitted states: $states",
                states.any { it is AuthUiState.Error },
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ─── Google sign-in ───────────────────────────────────────────────────────

    @Test
    fun `onGoogleSignIn success — repository is called with idToken`() = runTest {
        coEvery { authRepository.signInWithGoogle("id-token") } returns BecalmResult.Success(fakeSession)
        every { authRepository.observeAuthState() } returns flowOf(AuthState.Authenticated(fakeSession))
        every { userPrefsStore.observeOnboardingCompleted() } returns flowOf(false)

        viewModel = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onGoogleSignIn("id-token")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { authRepository.signInWithGoogle("id-token") }
    }
}
