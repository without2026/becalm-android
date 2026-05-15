package com.becalm.android.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.R
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.remote.supabase.SupabaseSession
import com.becalm.android.data.remote.supabase.SupabaseSessionStore
import com.becalm.android.ui.components.UiMessage
import com.becalm.android.ui.onboarding.OnboardingProgressResolver
import com.becalm.android.worker.AuthenticatedRuntimeBootstrap
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ─── UI State ─────────────────────────────────────────────────────────────────

/**
 * Represents every possible UI state for the authentication screens.
 */
public sealed class AuthUiState {
    /** An async operation is in flight. */
    public data object Loading : AuthUiState()

    /**
     * No session is present; sign-in UI should be shown.
     *
     * @param termsAccepted Whether the user has already accepted terms on a prior launch.
     *   When `true`, [SplashScreen] can skip directly to [BecalmRoute.Login].
     */
    public data class SignedOut(val termsAccepted: Boolean = false) : AuthUiState()

    /**
     * A valid session is present.
     *
     * @param userId Supabase `auth.users` UUID.
     * @param onboardingCompleted Whether the user has already completed onboarding.
     */
    public data class SignedIn(
        val userId: String,
        val onboardingCompleted: Boolean = false,
        val onboardingResumeRoute: String = com.becalm.android.ui.navigation.BecalmRoute.OnboardingSetup.path,
    ) : AuthUiState()

    /**
     * An error occurred during a sign-in or sign-out operation.
     *
     * @param message Resource-backed description of the error.
     */
    public data class Error(val message: UiMessage) : AuthUiState()
}

/** One-shot effects emitted by [AuthViewModel]. */
public sealed interface AuthEffect {
    public data object FinishApp : AuthEffect
    public data object NavigateToLogin : AuthEffect
}

// ─── ViewModel ────────────────────────────────────────────────────────────────

private const val TAG = "AuthViewModel"
/**
 * ViewModel for the authentication flow.
 *
 * Exposes [uiState] as a [StateFlow] so the composable screen can collect it
 * without managing coroutine lifetimes itself.
 *
 * On init the current auth state is observed immediately, so the UI never
 * renders in a stale `SignedOut` state when a valid session is already present.
 */
@HiltViewModel
public class AuthViewModel @Inject constructor(
    private val authRepositoryProvider: Provider<AuthRepository>,
    private val sessionStore: SupabaseSessionStore,
    private val userPrefsStore: UserPrefsStore,
    private val runtimeBootstrapProvider: Provider<AuthenticatedRuntimeBootstrap>,
    @IoDispatcher private val runtimeBootstrapDispatcher: CoroutineDispatcher,
    private val logger: Logger,
) : ViewModel() {

    private val _uiState: MutableStateFlow<AuthUiState> = MutableStateFlow(AuthUiState.Loading)
    private val _effects: MutableSharedFlow<AuthEffect> = MutableSharedFlow(extraBufferCapacity = 1)

    /** Current authentication UI state. Never null; starts as [AuthUiState.Loading]. */
    public val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    /** One-shot authentication effects such as AUTH-006 app finish. */
    public val effects: SharedFlow<AuthEffect> = _effects.asSharedFlow()

    private var sessionObservationJob: Job? = null
    private var runtimeBootstrapUserId: String? = null

    init {
        onObserveSession()
    }

    // ─── Actions ──────────────────────────────────────────────────────────────

    // spec: AUTH-001
    /**
     * Initiates an email/password sign-in.
     *
     * Splash/bootstrap keeps the network auth graph lazy, so this method is the
     * first place that resolves [AuthRepository]. On success it maps the returned
     * session immediately; [onObserveSession] remains the source for later session
     * clear/save events.
     */
    public fun onEmailSignIn(email: String, password: String) {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            when (val result = authRepository().signInWithEmail(email, password)) {
                is BecalmResult.Success -> {
                    logger.d(TAG, "email sign-in succeeded")
                    setUiState(result.value.toUiState())
                }
                is BecalmResult.Failure -> {
                    logger.w(TAG, "email sign-in failed")
                    _uiState.value = AuthUiState.Error(result.error.toAuthMessage())
                }
            }
        }
    }

    // spec: AUTH-001A
    /**
     * Creates an email/password account from the public Login shell.
     *
     * If Supabase returns a session immediately, the returned session drives the
     * transition to [AuthUiState.SignedIn]. If email confirmation is required, the
     * screen stays on Login and shows a stable confirmation-required message.
     */
    public fun onEmailSignUp(email: String, password: String) {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            when (val result = authRepository().signUpWithEmail(email, password)) {
                is BecalmResult.Success -> {
                    logger.d(TAG, "email sign-up succeeded")
                    setUiState(result.value.toUiState())
                }
                is BecalmResult.Failure -> {
                    logger.w(TAG, "email sign-up failed")
                    val message = when (val error = result.error) {
                        is BecalmError.Validation ->
                            if (error.message == "email_confirmation_required") {
                                UiMessage.resource(R.string.auth_error_email_confirmation_required)
                            } else {
                                UiMessage.resource(R.string.auth_error_sign_up_failed)
                            }
                        else -> error.toAuthMessage()
                    }
                    _uiState.value = AuthUiState.Error(message)
                }
            }
        }
    }

    // spec: AUTH-002
    /**
     * Initiates a Google ID-token sign-in.
     *
     * @param idToken Raw JWT returned by the Google Sign-In SDK.
     */
    public fun onGoogleSignIn(idToken: String) {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            when (val result = authRepository().signInWithGoogle(idToken)) {
                is BecalmResult.Success -> {
                    logger.d(TAG, "google sign-in succeeded")
                    setUiState(result.value.toUiState())
                }
                is BecalmResult.Failure -> {
                    logger.w(TAG, "google sign-in failed")
                    _uiState.value = AuthUiState.Error(result.error.toAuthMessage())
                }
            }
        }
    }

    // spec: AUTH-005 — routine sign-out preserves local Room data per
    // `AuthRepository.invalidateSession` KDoc ("로그아웃 시 Room DB 데이터는 삭제하지 않는다").
    /**
     * Routine sign-out: revokes the active session without wiping the local Room database.
     *
     * Delegates to [AuthRepository.invalidateSession], which cancels running workers,
     * clears the encrypted session / IMAP / Gmail-OAuth credential stores, and drops
     * the in-memory token cache — but deliberately **does not** call
     * `database.clearAllTables()`. Combined with the per-user SQLite file introduced in
     * S6-A, this lets the user sign back in and resume from their cached commitments
     * and person-enrichment state while guaranteeing that a different account on the
     * same device cannot observe the prior user's data (a separate file is opened).
     *
     * The "로컬 데이터 전체 삭제" UX (full PIPA wipe via [AuthRepository.signOut]) is
     * reached from Settings → Privacy and is intentionally not this method's concern.
     *
     * On success the sign-out state is driven solely by [onObserveSession] collecting
     * [SupabaseSessionStore.observe], which will emit `null` once the repository completes
     * the session-only cleanup. That collector is the single
     * source of truth for the signed-out transition; assigning [AuthUiState.SignedOut]
     * here would race with it.
     */
    public fun onSignOut() {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            when (val result = authRepository().invalidateSession()) {
                is BecalmResult.Success -> {
                    logger.d(TAG, "routine sign-out completed (Room data preserved)")
                    // State transitions to SignedOut via onObserveSession once the repository
                    // clears the encrypted session store.
                }
                is BecalmResult.Failure -> {
                    logger.w(TAG, "routine sign-out failed")
                    _uiState.value = AuthUiState.Error(UiMessage.resource(R.string.auth_error_sign_out_failed))
                }
            }
        }
    }

    // spec: AUTH-006
    /**
     * Explicit decline path from the Terms screen.
     *
     * Emits a one-shot [AuthEffect.FinishApp] so the screen layer can close the
     * current activity without baking `finish()` calls into the composable itself.
     */
    public fun onDeclineTerms() {
        _effects.tryEmit(AuthEffect.FinishApp)
    }

    /**
     * Persists terms acceptance from the public auth shell and then emits a one-shot
     * navigation to Login. This keeps TermsScreen independent from onboarding/runtime
     * owners so the public auth shell can render pre-auth without that graph.
     */
    public fun onAcceptTermsAndContinue() {
        viewModelScope.launch {
            try {
                userPrefsStore.setTermsAccepted(true)
                _effects.emit(AuthEffect.NavigateToLogin)
            } catch (e: Exception) {
                logger.e(TAG, "failed to persist terms acceptance", e)
                _uiState.value = AuthUiState.Error(UiMessage.resource(R.string.auth_error_terms_acceptance_failed))
            }
        }
    }

    // spec: AUTH-003, AUTH-004, AUTH-006, AUTH-007
    /**
     * Begins collecting [SupabaseSessionStore.observe] and maps each emission to
     * an [AuthUiState].
     *
     * Called automatically from `init`; may also be called explicitly to re-subscribe
     * after an error recovery.
     */
    public fun onObserveSession() {
        sessionObservationJob?.cancel()
        sessionObservationJob = viewModelScope.launch {
            try {
                setUiState(bootstrapUiState())
                logger.d(TAG, "startup auth bootstrap resolved to ${uiState.value}")
            } catch (e: Exception) {
                logger.w(TAG, "startup auth bootstrap failed")
                _uiState.value = AuthUiState.Error(UiMessage.resource(R.string.auth_error_session_restore_failed))
            }

            try {
                sessionStore.observe().collect { session ->
                    setUiState(session.toUiState())
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.value = AuthUiState.Error(UiMessage.resource(R.string.auth_error_session_restore_failed))
            }
        }
    }

    /**
     * Clears an [AuthUiState.Error] state so the UI can dismiss error dialogs
     * and return to the sign-in screen.
     */
    public fun onErrorDismissed() {
        _uiState.value = AuthUiState.SignedOut()
    }

    private fun setUiState(state: AuthUiState) {
        _uiState.value = state
        when (state) {
            is AuthUiState.SignedIn -> startRuntimeBootstrap(state.userId)
            is AuthUiState.SignedOut -> runtimeBootstrapUserId = null
            AuthUiState.Loading,
            is AuthUiState.Error -> Unit
        }
    }

    private fun startRuntimeBootstrap(userId: String) {
        if (runtimeBootstrapUserId == userId) return
        runtimeBootstrapUserId = userId
        viewModelScope.launch {
            runCatching {
                withContext(runtimeBootstrapDispatcher) {
                    runtimeBootstrapProvider.get().startForUser(userId)
                }
            }.onFailure { error ->
                if (runtimeBootstrapUserId == userId) {
                    runtimeBootstrapUserId = null
                }
                logger.e(TAG, "runtime bootstrap failed", error)
            }
        }
    }

    private suspend fun bootstrapUiState(): AuthUiState {
        return sessionStore.load().toUiState()
    }

    private suspend fun SupabaseSession?.toUiState(): AuthUiState =
        if (this != null) {
            val onboardingCompleted = userPrefsStore.observeOnboardingCompleted().first()
            AuthUiState.SignedIn(
                userId = userId,
                onboardingCompleted = onboardingCompleted,
                onboardingResumeRoute = if (onboardingCompleted) {
                    com.becalm.android.ui.navigation.BecalmRoute.OnboardingSetup.path
                } else {
                    onboardingResumeRoute()
                },
            )
        } else {
            AuthUiState.SignedOut(
                termsAccepted = userPrefsStore.observeTermsAccepted().first(),
            )
        }

    private suspend fun onboardingResumeRoute(): String {
        val stepStates = OnboardingProgressResolver.hydrateStepStates(
            persisted = userPrefsStore.observeOnboardingStepStatuses().first(),
            termsAccepted = userPrefsStore.observeTermsAccepted().first(),
            signedIn = true,
        )
        return OnboardingProgressResolver.resumeRoute(stepStates)
    }

    private fun authRepository(): AuthRepository = authRepositoryProvider.get()
}

private fun BecalmError.toAuthMessage(): UiMessage = when (this) {
    is BecalmError.Network -> UiMessage.resource(R.string.auth_error_network)
    is BecalmError.Unauthorized -> UiMessage.resource(R.string.auth_error_invalid_credentials)
    is BecalmError.RateLimited -> UiMessage.resource(R.string.auth_error_rate_limited)
    is BecalmError.ServerError -> UiMessage.resource(R.string.auth_error_server)
    is BecalmError.Validation -> if (message == "google_provider_disabled") {
        UiMessage.resource(R.string.login_google_setup_required)
    } else {
        UiMessage.resource(R.string.auth_error_validation)
    }
    is BecalmError.Io -> UiMessage.resource(R.string.auth_error_local_io)
    is BecalmError.Permission -> UiMessage.resource(R.string.auth_error_permission)
    is BecalmError.NotFound -> UiMessage.resource(R.string.auth_error_not_found)
    is BecalmError.Cancelled -> UiMessage.resource(R.string.auth_error_cancelled)
    is BecalmError.ExtractorUnavailable,
    is BecalmError.Unknown,
    -> UiMessage.resource(R.string.auth_error_unknown)
}
