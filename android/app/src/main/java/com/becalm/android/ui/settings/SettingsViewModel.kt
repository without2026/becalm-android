package com.becalm.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.worker.WorkScheduler
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
 * @property pipaConsentEnabled    Whether the user has granted PIPA 제3자 제공 + 국외 이전 동의
 *                                 for voice auto-upload. Persisted under `pipa_third_party_consent`.
 *                                 False means voice events are stored as awaiting_consent.
 * @property storageMb             Local Room DB size in megabytes, or null when unavailable.
 *                                 Populated in future iteration; reserved for UI forward-compat.
 * @property loading               True while the initial load is in progress.
 * @property error                 Human-readable error from the last failed action, or null.
 */
public data class SettingsUiState(
    val userEmail: String? = null,
    val language: String = "",
    val notificationsEnabled: Boolean = true,
    val pipaConsentEnabled: Boolean = false,
    val storageMb: Long? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val signedOut: Boolean = false,
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
 * @param userPrefsStore          Persistent preferences store.
 * @param authRepository          Provides current session email, sign-out, and full PIPA wipe.
 * @param rawIngestionRepository  Used to release awaiting_consent voice rows when PIPA consent
 *                                is granted from Settings (VOI-004).
 * @param workScheduler           Used to re-enqueue [com.becalm.android.worker.VoiceUploadWorker]
 *                                for each released awaiting_consent row.
 * @param logger                  Structured log sink.
 */
@HiltViewModel
public class SettingsViewModel @Inject constructor(
    private val userPrefsStore: UserPrefsStore,
    private val authRepository: AuthRepository,
    private val rawIngestionRepository: RawIngestionRepository,
    private val workScheduler: WorkScheduler,
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
                val pipaConsentEnabled = userPrefsStore.observeThirdPartyProvisionConsent().first()
                _uiState.update { state ->
                    state.copy(
                        userEmail = session?.email?.maskEmail(),
                        language = localeTag,
                        notificationsEnabled = notificationsEnabled,
                        pipaConsentEnabled = pipaConsentEnabled,
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
     * Clears the current error from [SettingsUiState.error].
     */
    public fun onErrorDismissed() {
        _uiState.update { it.copy(error = null) }
    }

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

    // spec: ONB-PIPA / VOI-004
    /**
     * Called when the user toggles the PIPA 제3자 제공 동의 Switch in Settings.
     *
     * **Turning ON** ([enabled] = true):
     * 1. Writes pipa_third_party_consent=true to DataStore.
     * 2. Calls [RawIngestionRepository.releaseAwaitingConsentVoiceAndReturnIds] to atomically
     *    flip awaiting_consent voice rows → pending and obtain their exact IDs (finding #2 fix —
     *    avoids the generic findPendingSync over-query that could miss rows or re-enqueue
     *    already-pending rows).
     * 3. Re-enqueues [com.becalm.android.worker.VoiceUploadWorker] for each released ID by
     *    looking up its entity to obtain the content URI (voice sourceRef = content URI).
     *
     * **Turning OFF** ([enabled] = false):
     * 1. Writes pipa_third_party_consent=false to DataStore.
     * 2. Calls [RawIngestionRepository.parkAndCancelPendingVoice] to park all pending/queued
     *    voice rows back to awaiting_consent and collect their IDs (finding #1 fix).
     * 3. Cancels the WorkManager uniqueWork entry for each parked row via
     *    [WorkScheduler.cancelVoiceUpload] to prevent already-enqueued jobs from
     *    transmitting audio after consent is revoked.
     *    No retroactive deletion of already-uploaded data (per ONB-PIPA spec).
     *
     * Errors surface via [SettingsUiState.error]; the toggle state is updated optimistically
     * and may revert on DataStore write failure.
     *
     * @param enabled True to grant consent; false to withdraw.
     */
    public fun onTogglePipaConsent(enabled: Boolean) {
        _uiState.update { it.copy(pipaConsentEnabled = enabled, error = null) }
        viewModelScope.launch {
            try {
                userPrefsStore.setThirdPartyProvisionConsent(enabled)
                logger.i(TAG, "PIPA third-party provision consent toggled to $enabled")

                // Obtain userId for DAO scope — fall back gracefully if session is absent.
                val userId = userPrefsStore.observeCurrentUserId().first()
                if (userId.isNullOrBlank()) {
                    logger.w(TAG, "onTogglePipaConsent: userId absent — skipping post-toggle steps")
                    return@launch
                }

                if (enabled) {
                    // Atomically release awaiting_consent rows → pending and get exact IDs.
                    // Using the IDs-returning variant ensures we enqueue exactly the released
                    // rows — no chance of missing new rows or re-enqueueing existing pending ones
                    // (finding #2 fix).
                    val releaseResult = rawIngestionRepository.releaseAwaitingConsentVoiceAndReturnIds(userId)
                    val releasedIds = when (releaseResult) {
                        is BecalmResult.Success -> releaseResult.value
                        is BecalmResult.Failure -> {
                            logger.e(TAG, "releaseAwaitingConsentVoiceAndReturnIds failed: ${releaseResult.error}")
                            _uiState.update { it.copy(error = releaseResult.error.toString()) }
                            emptyList()
                        }
                    }

                    var enqueuedCount = 0
                    for (id in releasedIds) {
                        val entity = rawIngestionRepository.findById(id = id, userId = userId)
                        if (entity == null) {
                            logger.w(TAG, "released voice row id=$id not found for re-enqueue — skipping")
                            continue
                        }
                        val audioUri = entity.sourceRef
                        if (audioUri.isNullOrBlank()) {
                            logger.w(TAG, "voice row id=$id has null sourceRef — skipping enqueue")
                            continue
                        }
                        workScheduler.enqueueVoiceUpload(rawEventId = id, audioUri = audioUri)
                        enqueuedCount++
                    }
                    logger.d(TAG, "re-enqueued $enqueuedCount voice uploads after consent grant")
                } else {
                    // Consent withdrawn: park pending/queued voice rows and cancel their WorkManager
                    // jobs to prevent already-enqueued uploads from proceeding (finding #1 fix).
                    val parkResult = rawIngestionRepository.parkAndCancelPendingVoice(userId)
                    val parkedIds = when (parkResult) {
                        is BecalmResult.Success -> parkResult.value
                        is BecalmResult.Failure -> {
                            logger.e(TAG, "parkAndCancelPendingVoice failed: ${parkResult.error}")
                            _uiState.update { it.copy(error = parkResult.error.toString()) }
                            emptyList()
                        }
                    }
                    for (id in parkedIds) {
                        workScheduler.cancelVoiceUpload(rawEventId = id)
                    }
                    logger.d(TAG, "parked and cancelled ${parkedIds.size} voice uploads after consent withdrawal")
                    // No retroactive deletion of already-uploaded data (per ONB-PIPA spec).
                }
            } catch (e: Exception) {
                logger.e(TAG, "onTogglePipaConsent failed", e)
                // Revert optimistic UI update on failure.
                _uiState.update { it.copy(pipaConsentEnabled = !enabled, error = e.message ?: "consent toggle failed") }
            }
        }
    }

    /**
     * Signs out the current user.
     *
     * Per spec invariant "로그아웃 시 Room DB 데이터는 삭제하지 않는다", sign-out only invalidates
     * the current session (tokens, session store, current-user mirror, workers, content
     * observers) via [AuthRepository.invalidateSession] and preserves Room-persisted user data
     * so the user can resume locally cached content after signing back in. For the deliberate
     * full PIPA wipe use [onWipeLocalData] instead.
     *
     * On failure, surfaces the error string via [SettingsUiState.error].
     */
    public fun onSignOut() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            when (val result = authRepository.invalidateSession()) {
                is BecalmResult.Success -> {
                    logger.d(TAG, "sign-out completed (session invalidated, Room preserved)")
                    _uiState.update { it.copy(loading = false, signedOut = true) }
                }
                is BecalmResult.Failure -> {
                    logger.w(TAG, "sign-out failed: ${result.error}")
                    _uiState.update { it.copy(loading = false, error = result.error.toString()) }
                }
            }
        }
    }

    /**
     * Wipes all on-device personal data and signs the user out — this IS the full PIPA wipe.
     *
     * Unlike [onSignOut] (which per spec should preserve Room data), this action deliberately
     * deletes everything. Delegates to [AuthRepository.signOut], which performs the full
     * PIPA-compliant wipe required by PIPA Article 30 — including Room tables, encrypted
     * tokens, sync cursors, IMAP credentials, and all DataStore preferences. Signing out is
     * mandatory when wiping because the session is itself personal data and must be removed
     * together with all other locally held data.
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

// ─── Helpers ──────────────────────────────────────────────────────────────────

/**
 * Masks the local-part of an email for display safety (R6-09).
 *
 * Keeps the first character and the domain intact, replacing intermediate characters with
 * asterisks (capped at 5). Returns the original string if it has no '@' or the local-part is
 * too short to mask meaningfully.
 *
 * Examples:
 * - "alice@example.com" -> "a****@example.com"
 * - "bob@x.io"          -> "b**@x.io"
 * - "a@x.io"            -> "a@x.io" (local-part too short)
 */
private fun String.maskEmail(): String {
    val atIndex = indexOf('@')
    if (atIndex <= 1) return this
    return first() + "*".repeat((atIndex - 1).coerceAtMost(5)) + substring(atIndex)
}
