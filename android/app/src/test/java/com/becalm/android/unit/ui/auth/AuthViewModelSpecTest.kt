package com.becalm.android.unit.ui.auth

import app.cash.turbine.test
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.remote.supabase.SupabaseSession
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.AuthState
import com.becalm.android.ui.auth.AuthEffect
import com.becalm.android.ui.auth.AuthUiState
import com.becalm.android.ui.auth.AuthViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelSpecTest {

    private val testDispatcher = StandardTestDispatcher()
    private val authRepository: AuthRepository = mockk(relaxed = true)
    private val userPrefsStore: UserPrefsStore = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)

    private val session = SupabaseSession(
        accessToken = "a",
        refreshToken = "r",
        userId = "user-123",
        email = "user@example.com",
        expiresAt = Instant.parse("2026-05-01T00:00:00Z"),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        coEvery { authRepository.currentSession() } returns null
        every { authRepository.observeAuthState() } returns flowOf(AuthState.Unauthenticated)
        every { userPrefsStore.observeTermsAccepted() } returns flowOf(true)
        every { userPrefsStore.observeOnboardingCompleted() } returns flowOf(false)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `AUTH observer maps unauthenticated state to SignedOut with persisted terms flag`() = runTest {
        every { userPrefsStore.observeTermsAccepted() } returns flowOf(false)

        val viewModel = buildViewModel()
        advanceUntilIdle()

        assertEquals(AuthUiState.SignedOut(termsAccepted = false), viewModel.uiState.value)
    }

    @Test
    fun `ONB-006 authenticated session with completed onboarding resolves to SignedIn shell state`() = runTest {
        coEvery { authRepository.currentSession() } returns session
        every { authRepository.observeAuthState() } returns flowOf(AuthState.Authenticated(session))
        every { userPrefsStore.observeOnboardingCompleted() } returns flowOf(true)

        val viewModel = buildViewModel()
        advanceUntilIdle()

        assertEquals(
            AuthUiState.SignedIn(userId = "user-123", onboardingCompleted = true),
            viewModel.uiState.value,
        )
    }

    @Test
    fun `AUTH-002 email unauthorized maps to product error string`() = runTest {
        coEvery { authRepository.signInWithEmail("bad@user.com", "wrong") } returns
            BecalmResult.Failure(BecalmError.Unauthorized)

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onEmailSignIn("bad@user.com", "wrong")
        advanceUntilIdle()

        assertEquals(AuthUiState.Error("Invalid email or password"), viewModel.uiState.value)
    }

    @Test
    fun `AUTH-001 email sign-in success is reflected through observer transition`() = runTest {
        val states = MutableStateFlow<AuthState>(AuthState.Unauthenticated)
        every { authRepository.observeAuthState() } returns states
        every { userPrefsStore.observeOnboardingCompleted() } returns flowOf(false)
        coEvery { authRepository.signInWithEmail("user@example.com", "ValidPass1!") } coAnswers {
            states.value = AuthState.Authenticated(session)
            BecalmResult.Success(session)
        }

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onEmailSignIn("user@example.com", "ValidPass1!")
        advanceUntilIdle()

        assertEquals(
            AuthUiState.SignedIn(userId = "user-123", onboardingCompleted = false),
            viewModel.uiState.value,
        )
        coVerify(exactly = 1) { authRepository.signInWithEmail("user@example.com", "ValidPass1!") }
    }

    @Test
    fun `AUTH-010 bootstrap resolves signed out route from persisted terms before observer emits`() = runTest {
        every { userPrefsStore.observeTermsAccepted() } returns flowOf(false)
        every { authRepository.observeAuthState() } returns emptyFlow()

        val viewModel = buildViewModel()
        advanceUntilIdle()

        assertEquals(AuthUiState.SignedOut(termsAccepted = false), viewModel.uiState.value)
        coVerify(exactly = 1) { authRepository.currentSession() }
    }

    @Test
    fun `AUTH-010 bootstrap resolves signed in route from persisted session before observer emits`() = runTest {
        coEvery { authRepository.currentSession() } returns session
        every { userPrefsStore.observeOnboardingCompleted() } returns flowOf(true)
        every { authRepository.observeAuthState() } returns emptyFlow()

        val viewModel = buildViewModel()
        advanceUntilIdle()

        assertEquals(
            AuthUiState.SignedIn(userId = "user-123", onboardingCompleted = true),
            viewModel.uiState.value,
        )
        coVerify(exactly = 1) { authRepository.currentSession() }
    }

    @Test
    fun `AUTH-003 google sign-in success delegates once and maps onboarding completion`() = runTest {
        val states = MutableStateFlow<AuthState>(AuthState.Unauthenticated)
        every { authRepository.observeAuthState() } returns states
        every { userPrefsStore.observeOnboardingCompleted() } returns flowOf(true)
        coEvery { authRepository.signInWithGoogle("id-token") } coAnswers {
            states.value = AuthState.Authenticated(session)
            BecalmResult.Success(session)
        }

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onGoogleSignIn("id-token")
        advanceUntilIdle()

        assertEquals(
            AuthUiState.SignedIn(userId = "user-123", onboardingCompleted = true),
            viewModel.uiState.value,
        )
        coVerify(exactly = 1) { authRepository.signInWithGoogle("id-token") }
    }

    @Test
    fun `AUTH-005 signout success is driven by observer transition not direct state write`() = runTest {
        val states = MutableStateFlow<AuthState>(AuthState.Authenticated(session))
        every { authRepository.observeAuthState() } returns states
        every { userPrefsStore.observeOnboardingCompleted() } returns flowOf(true)
        every { userPrefsStore.observeTermsAccepted() } returns flowOf(true)
        coEvery { authRepository.invalidateSession() } coAnswers {
            states.value = AuthState.Unauthenticated
            BecalmResult.Success(Unit)
        }

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onSignOut()
        advanceUntilIdle()

        assertEquals(AuthUiState.SignedOut(termsAccepted = true), viewModel.uiState.value)
        coVerify(exactly = 1) { authRepository.invalidateSession() }
    }

    @Test
    fun `AUTH-005 signout failure surfaces safe mapped message`() = runTest {
        coEvery { authRepository.invalidateSession() } returns
            BecalmResult.Failure(BecalmError.Network(503, "service unavailable"))

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onSignOut()
        advanceUntilIdle()

        assertEquals(AuthUiState.Error("Network error (503)"), viewModel.uiState.value)
    }

    @Test
    fun `AUTH clear error returns to default SignedOut state`() = runTest {
        coEvery { authRepository.signInWithGoogle("bad-token") } returns
            BecalmResult.Failure(BecalmError.Network(500, "boom"))

        val viewModel = buildViewModel()
        advanceUntilIdle()
        viewModel.onGoogleSignIn("bad-token")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is AuthUiState.Error)

        viewModel.onErrorDismissed()
        assertEquals(AuthUiState.SignedOut(), viewModel.uiState.value)
    }

    @Test
    fun `AUTH-006 declining terms emits FinishApp effect`() = runTest {
        val viewModel = buildViewModel()

        viewModel.effects.test {
            viewModel.onDeclineTerms()

            assertEquals(AuthEffect.FinishApp, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `AUTH-011 accepting terms persists flag and emits NavigateToLogin`() = runTest {
        val viewModel = buildViewModel()

        viewModel.effects.test {
            viewModel.onAcceptTermsAndContinue()
            advanceUntilIdle()

            assertEquals(AuthEffect.NavigateToLogin, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) { userPrefsStore.setTermsAccepted(true) }
    }

    private fun buildViewModel(): AuthViewModel = AuthViewModel(
        authRepository = authRepository,
        userPrefsStore = userPrefsStore,
        logger = logger,
    )
}
