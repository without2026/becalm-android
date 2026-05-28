package com.becalm.android.unit.ui.auth

import app.cash.turbine.test
import com.becalm.android.R
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.EmailPipaProvider
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.remote.supabase.SupabaseSession
import com.becalm.android.data.remote.supabase.SupabaseSessionStore
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.UserProfileRepository
import com.becalm.android.ui.auth.AuthEffect
import com.becalm.android.ui.auth.AuthUiState
import com.becalm.android.ui.auth.AuthViewModel
import com.becalm.android.ui.auth.PhoneOtpUiState
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
    private val userProfileRepository: UserProfileRepository = mockk(relaxed = true)
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
        coEvery { userProfileRepository.refreshFromServer(any()) } returns
            BecalmResult.Failure(BecalmError.Network(0, "offline"))
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

        assertEquals(R.string.auth_error_invalid_credentials, (viewModel.uiState.value as AuthUiState.Error).message.resId)
    }

    @Test
    fun `AUTH-002 unconfirmed email maps to confirmation guidance`() = runTest {
        coEvery { authRepository.signInWithEmail("new@example.com", "ValidPass1!") } returns
            BecalmResult.Failure(BecalmError.Validation(field = "email", message = "email_not_confirmed"))

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onEmailSignIn("new@example.com", "ValidPass1!")
        advanceUntilIdle()

        assertEquals(
            R.string.auth_error_email_not_confirmed,
            (viewModel.uiState.value as AuthUiState.Error).message.resId,
        )
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
    fun `AUTH-001A email sign-up confirmation requirement shows waiting state`() = runTest {
        coEvery { authRepository.signUpWithEmail("new@example.com", "ValidPass1!") } returns
            BecalmResult.Failure(
                BecalmError.Validation(field = "email", message = "email_confirmation_required"),
            )

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onEmailSignUp("new@example.com", "ValidPass1!")
        advanceUntilIdle()

        assertEquals(
            AuthUiState.SignUpEmailConfirmationRequired(email = "new@example.com"),
            viewModel.uiState.value,
        )
        coVerify(exactly = 1) { authRepository.signUpWithEmail("new@example.com", "ValidPass1!") }
    }

    @Test
    fun `AUTH-001A email sign-up already-registered failure shows login-oriented copy`() = runTest {
        coEvery { authRepository.signUpWithEmail("new@example.com", "ValidPass1!") } returns
            BecalmResult.Failure(
                BecalmError.Validation(field = "email", message = "email_already_registered"),
            )

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onEmailSignUp("new@example.com", "ValidPass1!")
        advanceUntilIdle()

        assertEquals(
            R.string.auth_error_email_already_registered,
            (viewModel.uiState.value as AuthUiState.Error).message.resId,
        )
    }

    @Test
    fun `AUTH-001A email sign-up weak password failure shows password-specific copy`() = runTest {
        coEvery { authRepository.signUpWithEmail("new@example.com", "weakpass") } returns
            BecalmResult.Failure(
                BecalmError.Validation(field = "password", message = "weak_password"),
            )

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onEmailSignUp("new@example.com", "weakpass")
        advanceUntilIdle()

        assertEquals(
            R.string.auth_error_weak_password,
            (viewModel.uiState.value as AuthUiState.Error).message.resId,
        )
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
    fun `AUTH-010 bootstrap resumes incomplete onboarding to compact setup`() = runTest {
        coEvery { sessionStore.load() } returns session
        every { sessionStore.observe() } returns sessionEvents()
        every { userPrefsStore.observeOnboardingStepStatuses() } returns flowOf(
            mapOf(
                "TERMS" to "GRANTED",
                "LOGIN" to "GRANTED",
                "PIPA_CONSENT" to "GRANTED",
                "RECORDING_FOLDER" to "GRANTED",
                "CALL_LOG_MATCHING" to "GRANTED",
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
                onboardingResumeRoute = BecalmRoute.OnboardingSetupEmail.path,
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
    fun `AUTH phone otp request normalizes korean number and exposes code entry state`() = runTest {
        coEvery { authRepository.requestPhoneOtp("+821012345678") } returns BecalmResult.Success(Unit)

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.requestPhoneOtp("010-1234-5678")
        advanceUntilIdle()

        assertEquals(
            PhoneOtpUiState(
                normalizedPhone = "+821012345678",
                codeRequested = true,
            ),
            viewModel.phoneOtpState.value,
        )
        coVerify(exactly = 1) { authRepository.requestPhoneOtp("+821012345678") }
    }

    @Test
    fun `AUTH phone otp request failure maps rate limit message without exposing phone`() = runTest {
        coEvery { authRepository.requestPhoneOtp("+821012345678") } returns
            BecalmResult.Failure(BecalmError.RateLimited(retryAfterSeconds = null))

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.requestPhoneOtp("010-1234-5678")
        advanceUntilIdle()

        assertEquals("+821012345678", viewModel.phoneOtpState.value.normalizedPhone)
        assertEquals(R.string.auth_error_rate_limited, viewModel.phoneOtpState.value.error?.resId)
        coVerify(exactly = 1) { authRepository.requestPhoneOtp("+821012345678") }
    }

    @Test
    fun `AUTH phone otp verify success delegates token and maps signed in session`() = runTest {
        every { userPrefsStore.observeOnboardingCompleted() } returns flowOf(false)
        coEvery { authRepository.verifyPhoneOtp("+821012345678", "123456") } returns
            BecalmResult.Success(session.copy(phone = "+821012345678"))

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.verifyPhoneOtp("010-1234-5678", " 123456 ")
        advanceUntilIdle()

        assertEquals(
            AuthUiState.SignedIn(userId = "user-123", onboardingCompleted = false),
            viewModel.uiState.value,
        )
        coVerify(exactly = 1) { authRepository.verifyPhoneOtp("+821012345678", "123456") }
    }

    @Test
    fun `AUTH phone otp verify failure keeps code entry open and surfaces otp copy`() = runTest {
        coEvery { authRepository.verifyPhoneOtp("+821012345678", "000000") } returns
            BecalmResult.Failure(BecalmError.Validation(field = "phone", message = "phone_otp_failed"))

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.verifyPhoneOtp("010-1234-5678", "000000")
        advanceUntilIdle()

        assertEquals(R.string.auth_error_phone_otp_failed, viewModel.phoneOtpState.value.error?.resId)
        assertEquals(
            R.string.auth_error_phone_otp_failed,
            (viewModel.uiState.value as AuthUiState.Error).message.resId,
        )
        assertEquals(true, viewModel.phoneOtpState.value.codeRequested)
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

        assertEquals(R.string.auth_error_sign_out_failed, (viewModel.uiState.value as AuthUiState.Error).message.resId)
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
        advanceUntilIdle()
        assertEquals(AuthUiState.SignedOut(termsAccepted = true), viewModel.uiState.value)
    }

    @Test
    fun `AUTH recovery preserves terms flag when startup session restore fails`() = runTest {
        coEvery { sessionStore.load() } throws java.io.IOException("encrypted store unavailable")
        every { userPrefsStore.observeTermsAccepted() } returns flowOf(true)

        val viewModel = buildViewModel()
        advanceUntilIdle()

        assertEquals(
            AuthUiState.RecoveryRequired(
                message = com.becalm.android.ui.components.UiMessage.resource(
                    R.string.auth_error_session_restore_failed,
                ),
                termsAccepted = true,
            ),
            viewModel.uiState.value,
        )
    }

    @Test
    fun `AUTH google provider disabled surfaces setup-required message not network error`() = runTest {
        coEvery { authRepository.signInWithGoogle("id-token") } returns
            BecalmResult.Failure(
                BecalmError.Validation(
                    field = "auth_provider",
                    message = "google_provider_disabled",
                ),
            )

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onGoogleSignIn("id-token")
        advanceUntilIdle()

        assertEquals(
            R.string.login_google_setup_required,
            (viewModel.uiState.value as AuthUiState.Error).message.resId,
        )
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

    @Test
    fun `AUTH-011 accepting terms failure stays retryable with visible error state`() = runTest {
        coEvery { userPrefsStore.setTermsAccepted(true) } throws java.io.IOException("disk")
        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onAcceptTermsAndContinue()
        advanceUntilIdle()

        assertEquals(
            R.string.auth_error_terms_acceptance_failed,
            (viewModel.uiState.value as AuthUiState.Error).message.resId,
        )
        coVerify(exactly = 1) { userPrefsStore.setTermsAccepted(true) }
    }

    private fun buildViewModel(
        authRepositoryProvider: Provider<AuthRepository> = this.authRepositoryProvider,
    ): AuthViewModel = AuthViewModel(
        authRepositoryProvider = authRepositoryProvider,
        sessionStore = sessionStore,
        userPrefsStore = userPrefsStore,
        userProfileRepository = userProfileRepository,
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
