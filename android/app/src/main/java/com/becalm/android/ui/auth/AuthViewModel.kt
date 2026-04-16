package com.becalm.android.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.auth.AuthResult
import com.becalm.android.auth.SupabaseAuthService
import com.becalm.android.auth.TokenStore
import com.becalm.android.data.local.dao.PersonEnrichmentDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// spec: AUTH-001..AUTH-007 — authentication state management

sealed class AuthState {
    object Idle : AuthState()
    object Loading : AuthState()
    data class LoggedIn(val userId: String) : AuthState()
    data class Error(val message: String) : AuthState()
    object LoggedOut : AuthState()
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authService: SupabaseAuthService,
    private val tokenStore: TokenStore,
    private val personEnrichmentDao: PersonEnrichmentDao
) : ViewModel() {

    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    val authState: StateFlow<AuthState> = _authState

    init {
        // spec: ONB-006 — check if already logged in on startup
        if (tokenStore.isLoggedIn()) {
            val userId = tokenStore.getUserId() ?: ""
            _authState.value = AuthState.LoggedIn(userId)
        }
    }

    // spec: AUTH-001 — email+password login
    fun loginWithEmailPassword(email: String, password: String) {
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            when (val result = authService.loginWithEmailPassword(email, password)) {
                is AuthResult.Success -> {
                    tokenStore.saveTokens(result.accessToken, result.refreshToken, result.userId)
                    _authState.value = AuthState.LoggedIn(result.userId)
                }
                is AuthResult.Error -> {
                    // spec: AUTH-002 — 400 invalid_grant → error message
                    _authState.value = AuthState.Error(
                        if (result.code == 400) "이메일 또는 비밀번호가 올바르지 않습니다"
                        else result.message
                    )
                }
                AuthResult.NetworkError -> {
                    _authState.value = AuthState.Error("네트워크 연결을 확인해주세요")
                }
            }
        }
    }

    // spec: AUTH-003 — Google OAuth login
    fun loginWithGoogleIdToken(idToken: String) {
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            when (val result = authService.loginWithGoogleIdToken(idToken)) {
                is AuthResult.Success -> {
                    tokenStore.saveTokens(result.accessToken, result.refreshToken, result.userId)
                    _authState.value = AuthState.LoggedIn(result.userId)
                }
                is AuthResult.Error -> {
                    _authState.value = AuthState.Error(result.message)
                }
                AuthResult.NetworkError -> {
                    _authState.value = AuthState.Error("네트워크 연결을 확인해주세요")
                }
            }
        }
    }

    // spec: AUTH-005 — logout; Room DB preserved, persons_enrichment deleted (PIPA)
    fun logout() {
        viewModelScope.launch {
            val accessToken = tokenStore.getAccessToken()
            if (accessToken != null) {
                authService.logout(accessToken)
            }
            // spec: ENR-007 — delete persons_enrichment on logout (PIPA)
            personEnrichmentDao.deleteAll()
            tokenStore.clearTokens()
            _authState.value = AuthState.LoggedOut
        }
    }
}
