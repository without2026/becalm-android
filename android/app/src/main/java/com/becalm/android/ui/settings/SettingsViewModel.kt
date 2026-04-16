package com.becalm.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ─── UI state ─────────────────────────────────────────────────────────────────

/**
 * Full UI state for SettingsScreen.
 *
 * @property userEmail             Email of the currently signed-in user, or null when not
 *                                 available (session absent or load not yet complete).
 * @property language              IETF BCP 47 locale tag (e.g. "ko", "en"), or an empty
 *                                 string when [UserPrefsStore.observeLocaleTag] returns null
 *                                 (device locale fallback). Placeholder default: "".
 * @property notificationsEnabled  Whether the user has enabled notifications. Persisted in
 *                                 [UserPrefsStore] under the `notifications_enabled` key;
 *                                 defaults to `true` on first install.
 * @property storageMb             Local Room DB size in megabytes, or null when unavailable.
 *                                 Populated in future iteration; reserved for UI forward-compat.
 * @property loading               True while the initial load is in progress.
 * @property error                 Human-readable error from the last failed action, or null.
 */
public data class SettingsUiState(
    val userEmail: String? = null,
    val language: String = "",
    val notificationsEnabled: Boolean = true,
    val storageMb: Long? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

// ─── ViewModel ────────────────────────────────────────────────────────────────

private const val TAG = "SettingsViewModel"

/**
 * ViewModel for SettingsScreen.
 *
 * Reads initial state from [AuthRepository.currentSession] (for the user email) and from
 * [UserPrefsStore] (for locale tag and notifications flag). All writes are delegated back to
 * those stores.
 *
 * @param userPrefsStore  Persistent preferences store.
 * @param authRepository  Provides current session email, sign-out, and full PIPA wipe.
 * @param logger          Structured log sink.
 */
@HiltViewModel
public class SettingsViewModel @Inject constructor(
    private val userPrefsStore: UserPrefsStore,
    private val authRepository: AuthRepository,
    private val logger: Logger,
) : ViewModel() {

    private val _uiState: MutableStateFlow<SettingsUiState> =
        MutableStateFlow(SettingsUiState())

    /** Current settings UI state. Starts loading; settles after [loadSettings] completes. */
    public val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
    }

    // ─── Init load ────────────────────────────────────────────────────────────

    /**
     * Reads the current session email, stored locale tag, and stored notifications flag,
     * then populates [uiState].
     *
     * Failures from [AuthRepository.currentSession] are non-fatal: the email is simply left
     * null, which the composable interprets as "signed out or loading". The locale tag
     * defaults to "" (empty string) when [UserPrefsStore.observeLocaleTag] returns null so
     * the UI can show a "follow device locale" label.
     */
    private fun loadSettings() {
        viewModelScope.launch {
            try {
                val session = authRepository.currentSession()
                // Take the first value from each preference flow without subscribing forever.
                val localeTag = userPrefsStore.observeLocaleTag().first() ?: ""
                val notificationsEnabled = userPrefsStore.observeNotificationsEnabled().first()
                _uiState.update { state ->
                    state.copy(
                        userEmail = session?.email,
                        language = localeTag,
                        notificationsEnabled = notificationsEnabled,
                        loading = false,
                    )
                }
                logger.d(TAG, "settings loaded, email present=${session != null}")
            } catch (e: Exception) {
                logger.e(TAG, "loadSettings failed", e)
                _uiState.update { it.copy(loading = false, error = e.message ?: "load failed") }
            }
        }
    }

    // ─── Actions ──────────────────────────────────────────────────────────────

    /**
     * Persists [lang] as the preferred locale tag via [UserPrefsStore.setLocaleTag].
     *
     * An empty string is treated as "follow system locale" and stored as null. Valid
     * non-empty values follow IETF BCP 47 (e.g. "ko", "en", "ko-KR", "en-US").
     *
     * @param lang Locale tag string, or "" to follow the system locale.
     */
    public fun onChangeLanguage(lang: String) {
        viewModelScope.launch {
            try {
                userPrefsStore.setLocaleTag(lang.ifEmpty { null })
                _uiState.update { it.copy(language = lang, error = null) }
                logger.d(TAG, "language changed to '${lang.ifEmpty { "system" }}'")
            } catch (e: Exception) {
                logger.e(TAG, "onChangeLanguage failed", e)
                _uiState.update { it.copy(error = e.message ?: "language change failed") }
            }
        }
    }

    /**
     * Persists the notifications [enabled] preference and updates [uiState].
     *
     * @param enabled True to enable, false to disable notifications.
     */
    public fun onToggleNotifications(enabled: Boolean) {
        viewModelScope.launch {
            try {
                userPrefsStore.setNotificationsEnabled(enabled)
                _uiState.update { it.copy(notificationsEnabled = enabled, error = null) }
                logger.d(TAG, "notifications toggled to $enabled")
            } catch (e: Exception) {
                logger.e(TAG, "onToggleNotifications failed", e)
                _uiState.update { it.copy(error = e.message ?: "notifications toggle failed") }
            }
        }
    }

    /**
     * Signs out the current user via [AuthRepository.signOut].
     *
     * [AuthRepository.signOut] performs a full PIPA-compliant wipe (AUTH-005) including
     * clearing the Room database, encrypted token store, and all prefs. No additional wipe
     * step is needed here.
     *
     * On failure, surfaces the error string via [SettingsUiState.error].
     */
    public fun onSignOut() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            when (val result = authRepository.signOut()) {
                is BecalmResult.Success -> {
                    logger.d(TAG, "sign-out completed")
                    _uiState.update { it.copy(loading = false) }
                }
                is BecalmResult.Failure -> {
                    logger.w(TAG, "sign-out failed: ${result.error}")
                    _uiState.update { it.copy(loading = false, error = result.error.toString()) }
                }
            }
        }
    }

    /**
     * Wipes all on-device personal data and signs the user out.
     *
     * Delegates to [AuthRepository.signOut], which performs the full PIPA-compliant wipe
     * required by PIPA Article 30 — including Room tables, encrypted tokens, sync cursors,
     * IMAP credentials, and all DataStore preferences. Signing out is mandatory when wiping
     * because the session is itself personal data and must be removed together with all other
     * locally held data.
     *
     * On failure, surfaces the error via [SettingsUiState.error].
     */
    public fun onWipeLocalData() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            when (val result = authRepository.signOut()) {
                is BecalmResult.Success -> {
                    logger.d(TAG, "PIPA wipe and sign-out completed")
                    _uiState.update { it.copy(loading = false, error = null) }
                }
                is BecalmResult.Failure -> {
                    logger.w(TAG, "PIPA wipe failed: ${result.error}")
                    _uiState.update { it.copy(loading = false, error = result.error.toString()) }
                }
            }
        }
    }
}
