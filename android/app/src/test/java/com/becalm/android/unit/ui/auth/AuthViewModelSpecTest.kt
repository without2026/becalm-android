package com.becalm.android.unit.ui.auth

import app.cash.turbine.test
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.EmailPipaProvider
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.remote.supabase.SupabaseSession
import com.becalm.android.data.remote.supabase.SupabaseSessionStore
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.ui.auth.AuthEffect
import com.becalm.android.ui.auth.AuthUiState
import com.becalm.android.ui.auth.AuthViewModel
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.worker.AuthenticatedRuntimeBootstrap
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import javax.inject.Provider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelSpecTest {

    private val testDispatcher = StandardTestDispatcher()
    private val authRepository: AuthRepository = mockk(relaxed = true)
    private val authRepositoryProvider: Provider<AuthRepository> = Provider { authRepository }
    private val sessionStore: SupabaseSessionStore = mockk(relaxed = true)
    private val userPrefsStore: UserPrefsStore = mockk(relaxed = true)
    private val runtimeBootstrap: AuthenticatedRuntimeBootstrap = mockk(relaxed = true)
    private val runtimeBootstrapProvider: Provider<AuthenticatedRuntimeBootstrap> =
        Provider { runtimeBootstrap }
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
        coEvery { sessionStore.load() } returns null
        every { sessionStore.observe() } returns sessionEvents()
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-123")
        every { userPrefsStore.observeTermsAccepted() } returns flowOf(true)
        every { userPrefsStore.observeOnboardingCompleted() } returns flowOf(false)
        every { userPrefsStore.observeOnboardingStepStatuses() } returns flowOf(emptyMap())
        EmailPipaProvider.entries.forEach { provider ->
            every { userPrefsStore.observeEmailPipaConsent(provider) } returns flowOf(false)
        }
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
        coVerify(exactly = 0) { runtimeBootstrap.startForUser(any()) }
    }

    @Test
    fun `ONB-006 authenticated session with completed onboarding resolves to SignedIn shell state`() = runTest {
        coEvery { sessionStore.load() } returns session
        every { sessionStore.observe() } returns sessionEvents(session)
        every { userPrefsStore.observeOnboardingCompleted() } returns flowOf(true)

        val viewModel = buildViewModel()
        advanceUntilIdle()

        assertEquals(
            AuthUiState.SignedIn(userId = "user-123", onboardingCompleted = true),
            viewModel.uiState.value,
        )
        coVerify(exactly = 1) { runtimeBootstrap.startForUser("user-123") }
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
    fun `AUTH-001 email sign-in success maps returned session without waiting for observer`() = runTest {
        every { userPrefsStore.observeOnboardingCompleted() } returns flowOf(false)
        coEvery { authRepository.signInWithEmail("user@example.com", "ValidPass1!") } coAnswers {
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
    fun `AUTH-001A email sign-up success maps returned session without waiting for observer`() = runTest {
        every { userPrefsStore.observeOnboardingCompleted() } returns flowOf(false)
        coEvery { authRepository.signUpWithEmail("new@example.com", "ValidPass1!") } coAnswers {
            BecalmResult.Success(session.copy(email = "new@example.com"))
        }

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onEmailSignUp("new@example.com", "ValidPass1!")
        advanceUntilIdle()

        assertEquals(
            AuthUiState.SignedIn(userId = "user-123", onboardingCompleted = false),
            viewModel.uiState.value,
        )
        coVerify(exactly = 1) { authRepository.signUpWithEmail("new@example.com", "ValidPass1!") }
    }

    @Test
    fun `AUTH-001A email sign-up confirmation requirement stays on login with stable message`() = runTest {
        coEvery { authRepository.signUpWithEmail("new@example.com", "ValidPass1!") } returns
            BecalmResult.Failure(
                BecalmError.Validation(field = "email", message = "email_confirmation_required"),
            )

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onEmailSignUp("new@example.com", "ValidPass1!")
        advanceUntilIdle()

        assertEquals(
            AuthUiState.Error("Check your email to confirm your account, then sign in."),
            viewModel.uiState.value,
        )
        coVerify(exactly = 1) { authRepository.signUpWithEmail("new@example.com", "ValidPass1!") }
    }

    @Test
    fun `AUTH-010 splash bootstrap does not instantiate network auth repository graph`() = runTest {
        val forbiddenProvider = Provider<AuthRepository> {
            error("AuthRepository should stay lazy during splash bootstrap")
        }

        val viewModel = buildViewModel(authRepositoryProvider = forbiddenProvider)
        advanceUntilIdle()

        assertEquals(AuthUiState.SignedOut(termsAccepted = true), viewModel.uiState.value)
        coVerify(exactly = 1) { sessionStore.load() }
    }

    @Test
    fun `AUTH-010 bootstrap resolves signed out route from persisted terms before observer emits`() = runTest {
        every { userPrefsStore.observeTermsAccepted() } returns flowOf(false)
        every { sessionStore.observe() } returns sessionEvents()

        val viewModel = buildViewModel()
        advanceUntilIdle()

        assertEquals(AuthUiState.SignedOut(termsAccepted = false), viewModel.uiState.value)
        coVerify(exactly = 1) { sessionStore.load() }
    }

    @Test
    fun `AUTH-010 bootstrap resolves signed in route from persisted session before observer emits`() = runTest {
        coEvery { sessionStore.load() } returns session
        every { userPrefsStore.observeOnboardingCompleted() } returns flowOf(true)
        every { sessionStore.observe() } returns sessionEvents()

        val viewModel = buildViewModel()
        advanceUntilIdle()

        assertEquals(
            AuthUiState.SignedIn(userId = "user-123", onboardingCompleted = true),
            viewModel.uiState.value,
        )
        coVerify(exactly = 1) { sessionStore.load() }
        coVerify(exactly = 1) { runtimeBootstrap.startForUser("user-123") }
    }

    @Test
    fun `AUTH-009 signed-in bootstrap starts runtime once for duplicate same-user emissions`() = runTest {
        coEvery { sessionStore.load() } returns session
        every { userPrefsStore.observeOnboardingCompleted() } returns flowOf(true)
        every { sessionStore.observe() } returns sessionEvents(session)

        val viewModel = buildViewModel()
        advanceUntilIdle()

        assertEquals(
            AuthUiState.SignedIn(userId = "user-123", onboardingCompleted = true),
            viewModel.uiState.value,
        )
        coVerify(exactly = 1) { runtimeBootstrap.startForUser("user-123") }
    }

    @Test
    fun `AUTH-010 bootstrap resumes first incomplete onboarding provider after email PIPA consent`() = runTest {
        coEvery { sessionStore.load() } returns session
        every { sessionStore.observe() } returns sessionEvents()
        every { userPrefsStore.observeOnboardingStepStatuses() } returns flowOf(
            mapOf(
                "TERMS" to "GRANTED",
                "LOGIN" to "GRANTED",
                "PIPA_CONSENT" to "GRANTED",
                "RECORDING_FOLDER" to "GRANTED",
                "CONTACTS_PERM" to "DENIED",
            ),
        )
        every { userPrefsStore.observeEmailPipaConsent(EmailPipaProvider.GMAIL) } returns flowOf(true)

        val viewModel = buildViewModel()
        advanceUntilIdle()

        assertEquals(
            AuthUiState.SignedIn(
                userId = "user-123",
                onboardingCompleted = false,
                onboardingResumeRoute = BecalmRoute.OnboardingGmail.path,
            ),
            viewModel.uiState.value,
        )
    }

    @Test
    fun `AUTH-003 google sign-in success delegates once and maps onboarding completion`() = runTest {
        every { userPrefsStore.observeOnboardingCompleted() } returns flowOf(true)
        coEvery { authRepository.signInWithGoogle("id-token") } coAnswers {
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
        val sessionEvents = MutableSharedFlow<SupabaseSession?>()
        coEvery { sessionStore.load() } returns session
        every { sessionStore.observe() } returns sessionEvents
        every { userPrefsStore.observeOnboardingCompleted() } returns flowOf(true)
        every { userPrefsStore.observeTermsAccepted() } returns flowOf(true)
        coEvery { authRepository.invalidateSession() } coAnswers {
            sessionEvents.emit(null)
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

    private fun buildViewModel(
        authRepositoryProvider: Provider<AuthRepository> = this.authRepositoryProvider,
    ): AuthViewModel = AuthViewModel(
        authRepositoryProvider = authRepositoryProvider,
        sessionStore = sessionStore,
        userPrefsStore = userPrefsStore,
        runtimeBootstrapProvider = runtimeBootstrapProvider,
        runtimeBootstrapDispatcher = testDispatcher,
        logger = logger,
    )

    private fun sessionEvents(
        vararg sessions: SupabaseSession?,
    ): MutableSharedFlow<SupabaseSession?> =
        MutableSharedFlow<SupabaseSession?>(replay = sessions.size).also { events ->
            sessions.forEach { session -> events.tryEmit(session) }
        }
}
