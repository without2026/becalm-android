package com.becalm.android.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.AuthState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// ─── UI State ─────────────────────────────────────────────────────────────────

/**
 * Represents every possible UI state for the authentication screens.
 */
public sealed class AuthUiState {
    /** An async operation is in flight. */
    public data object Loading : AuthUiState()

    /** No session is present; sign-in UI should be shown. */
    public data object SignedOut : AuthUiState()

    /**
     * A valid session is present.
     *
     * @param userId Supabase `auth.users` UUID.
     * @param email  User's email address, or `null` when not available in the session.
     * @param onboardingCompleted Whether the user has already completed onboarding.
     */
    public data class SignedIn(
        val userId: String,
        val email: String?,
        val onboardingCompleted: Boolean = false,
    ) : AuthUiState()

    /**
     * An error occurred during a sign-in or sign-out operation.
     *
     * @param message Human-readable description of the error.
     */
    public data class Error(val message: String) : AuthUiState()
}

// ─── ViewModel ────────────────────────────────────────────────────────────────

private const val TAG = "AuthViewModel"

/** Maps a [BecalmError] from a sign-in failure to a human-readable message. */
private fun signInErrorMessage(error: BecalmError): String = when (error) {
    is BecalmError.Unauthorized -> "Invalid email or password"
    is BecalmError.Network -> "Network error. Please try again."
    else -> "Sign-in failed. Please try again."
}

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
    private val authRepository: AuthRepository,
    private val userPrefsStore: UserPrefsStore,
    private val logger: Logger,
) : ViewModel() {

    private val _uiState: MutableStateFlow<AuthUiState> = MutableStateFlow(AuthUiState.Loading)

    /** Current authentication UI state. Never null; starts as [AuthUiState.Loading]. */
    public val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        onObserveSession()
    }

    // ─── Actions ──────────────────────────────────────────────────────────────

    // spec: AUTH-001
    /**
     * Initiates an email/password sign-in.
     *
     * On success the session observer ([onObserveSession]) will update [uiState]
     * to [AuthUiState.SignedIn] automatically; this method only drives the
     * loading/error transitions around the network call itself.
     */
    public fun onEmailSignIn(email: String, password: String) {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            when (val result = authRepository.signInWithEmail(email, password)) {
                is BecalmResult.Success -> {
                    logger.d(TAG, "email sign-in succeeded")
                    // uiState will update via onObserveSession; no explicit transition needed.
                }
                is BecalmResult.Failure -> {
                    logger.w(TAG, "email sign-in failed")
                    _uiState.value = AuthUiState.Error(signInErrorMessage(result.error))
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
            when (val result = authRepository.signInWithGoogle(idToken)) {
                is BecalmResult.Success -> {
                    logger.d(TAG, "google sign-in succeeded")
                }
                is BecalmResult.Failure -> {
                    logger.w(TAG, "google sign-in failed")
                    _uiState.value = AuthUiState.Error(signInErrorMessage(result.error))
                }
            }
        }
    }

    // spec: AUTH-005
    /**
     * Signs out the current user and performs a PIPA-compliant local data wipe.
     *
     * On success the sign-out state is driven solely by [onObserveSession] collecting
     * [AuthRepository.observeAuthState], which will emit [AuthState.Unauthenticated] once
     * the repository completes the wipe. That collector is the single source of truth for
     * the signed-out transition; assigning [AuthUiState.SignedOut] here would race with it.
     */
    public fun onSignOut() {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            when (val result = authRepository.signOut()) {
                is BecalmResult.Success -> {
                    logger.d(TAG, "sign-out completed")
                    // State transitions to SignedOut via onObserveSession once the repository
                    // publishes AuthState.Unauthenticated.
                }
                is BecalmResult.Failure -> {
                    logger.w(TAG, "sign-out failed")
                    _uiState.value = AuthUiState.Error("Sign-out failed. Please try again.")
                }
            }
        }
    }

    // spec: AUTH-003, AUTH-004, AUTH-006, AUTH-007
    /**
     * Begins collecting [AuthRepository.observeAuthState] and maps each emission to
     * an [AuthUiState].
     *
     * Called automatically from `init`; may also be called explicitly to re-subscribe
     * after an error recovery.
     */
    public fun onObserveSession() {
        viewModelScope.launch {
            authRepository.observeAuthState().collect { authState ->
                _uiState.value = when (authState) {
                    is AuthState.Authenticated -> AuthUiState.SignedIn(
                        userId = authState.session.userId,
                        email = authState.session.email,
                        onboardingCompleted = userPrefsStore.observeOnboardingCompleted().first(),
                    )
                    is AuthState.Unauthenticated -> AuthUiState.SignedOut
                }
            }
        }
    }
}
