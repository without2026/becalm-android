package com.becalm.android.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.core.observability.ObservabilityClient
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.EmailPipaProvider
import com.becalm.android.data.local.datastore.UserPrefsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ─── Enums ────────────────────────────────────────────────────────────────────

/**
 * Ordered steps in the onboarding flow.
 *
 * The display order is determined by [OnboardingViewModel.steps].
 *
 * **Persistence note**: the current step is NOT persisted to DataStore as an ordinal or name;
 * only [UserPrefsStore.setOnboardingCompleted] is written at the very end. Adding or reordering
 * enum members is therefore safe for in-progress onboardings.
 *
 * **Canonical 12-step flow** (spec `onboarding.spec.yml` invariant line 110):
 *
 * ```
 * 약관 → 로그인 → PIPA제3자제공 → 녹음폴더 → 연락처
 *      → Gmail → Outlook메일 → IMAP
 *      → Google캘린더 → Outlook캘린더
 *      → 배터리최적화 → ColdSync
 * ```
 *
 * Each enum value maps to exactly one Composable screen; [COLD_SYNC] is the terminal
 * step. Completion is signalled by [UserPrefsStore.setOnboardingCompleted] `true`
 * after [ColdSyncScreen] is dismissed or cold-sync finishes.
 */
public enum class OnboardingStep {
    /** Step 1 — [com.becalm.android.ui.auth.TermsScreen]. */
    TERMS,
    /** Step 2 — [com.becalm.android.ui.auth.LoginScreen]. */
    LOGIN,
    /** Step 3 — [PipaThirdPartyConsentScreen] (ONB-PIPA). */
    PIPA_CONSENT,
    /** Step 4 — [RecordingFolderScreen]. Skipped if PIPA consent denied. */
    RECORDING_FOLDER,
    /** Step 5 — [ContactsPermissionScreen] (ONB-CONTACTS). */
    CONTACTS_PERM,
    /** Step 6 — [GmailOAuthScreen]. */
    LINK_GMAIL,
    /** Step 7 — [OutlookMailOAuthScreen]. */
    LINK_OUTLOOK_MAIL,
    /** Step 8 — [ImapSetupScreen]. */
    LINK_IMAP,
    /** Step 9 — [GoogleCalendarOAuthScreen]. */
    LINK_GOOGLE_CALENDAR,
    /** Step 10 — [OutlookCalendarOAuthScreen]. */
    LINK_OUTLOOK_CALENDAR,
    /**
     * Step 11 — [NotificationPermissionScreen] (S6-E).
     *
     * Requests POST_NOTIFICATIONS on API 33+ so commitment / reminder notifications
     * actually reach the user. On API 32 and below the screen is terminal
     * ([StepStatus.SKIPPED]) because the permission is implicitly granted at install time.
     * Inserted between [BATTERY_OPT] and [COLD_SYNC] in the canonical flow.
     */
    NOTIFICATION_PERM,
    /** Step 12 — [BatteryOptimizationScreen] (ONB-005). */
    BATTERY_OPT,
    /** Step 13 (terminal) — [ColdSyncScreen] (TDY-010, ONB-008). */
    COLD_SYNC,
}

/**
 * Lifecycle status of a single onboarding step.
 */
public enum class StepStatus {
    NOT_STARTED,
    IN_PROGRESS,
    GRANTED,
    DENIED,
    SKIPPED,
    COMPLETE,
}

// ─── Events ───────────────────────────────────────────────────────────────────

/**
 * One-shot events emitted by [OnboardingViewModel] after a PIPA DataStore write completes.
 * Collected by [PipaThirdPartyConsentScreen] to trigger navigation only after persistence
 * is confirmed (finding #2 fix — navigation must not race ahead of the DataStore write).
 */
public sealed class PipaConsentEvent {
    /** DataStore write succeeded; [granted] reflects the persisted value. */
    public data class PipaConsentSaved(val granted: Boolean) : PipaConsentEvent()
    /** DataStore write failed; [message] is suitable for display. */
    public data class PipaConsentSaveFailed(val message: String) : PipaConsentEvent()
}

// ─── UI State ─────────────────────────────────────────────────────────────────

/**
 * Immutable snapshot of the onboarding screen state.
 *
 * @param currentStepIndex Index into [OnboardingViewModel.steps] for the currently displayed step.
 * @param stepStates       Per-step status map; defaults to [StepStatus.NOT_STARTED] for every step.
 * @param isCompleting     `true` while [OnboardingViewModel.onCompleteOnboarding] is in flight.
 * @param error            Non-null when the last action produced an error message to display.
 */
public data class OnboardingUiState(
    val currentStepIndex: Int = 0,
    val stepStates: Map<OnboardingStep, StepStatus> = OnboardingStep.entries.associateWith { StepStatus.NOT_STARTED },
    val isCompleting: Boolean = false,
    val error: String? = null,
)

// ─── ViewModel ────────────────────────────────────────────────────────────────

private const val TAG = "OnboardingViewModel"

/**
 * ViewModel for the onboarding flow.
 *
 * Navigation through the canonical 12-step onboarding sequence (see [OnboardingStep])
 * is driven entirely by this class. Permission result callbacks from the UI call
 * [onMarkStepStatus] — no Android framework APIs are used here.
 */
@HiltViewModel
public class OnboardingViewModel @Inject constructor(
    private val userPrefsStore: UserPrefsStore,
    private val logger: Logger,
    private val observability: ObservabilityClient,
) : ViewModel() {

    /** Canonical ordered list of onboarding steps (12 entries, see [OnboardingStep]). */
    public val steps: List<OnboardingStep> = OnboardingStep.entries

    private val _uiState: MutableStateFlow<OnboardingUiState> = MutableStateFlow(OnboardingUiState())

    /** Current onboarding UI state. */
    public val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    // One-shot events emitted after the DataStore write completes (finding #2 fix).
    // replay=0 so late collectors don't receive stale events; DROP_OLDEST ensures
    // a rapid double-tap never blocks the coroutine.
    private val _pipaConsentEvents: MutableSharedFlow<PipaConsentEvent> = MutableSharedFlow(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Collect in [com.becalm.android.ui.onboarding.PipaThirdPartyConsentScreen] to navigate
     *  only after the DataStore write is confirmed. */
    public val pipaConsentEvents: SharedFlow<PipaConsentEvent> = _pipaConsentEvents.asSharedFlow()

    // ─── Navigation actions ───────────────────────────────────────────────────

    // spec: ONB-001
    /**
     * Advances to the next onboarding step.
     *
     * No-op when [OnboardingUiState.currentStepIndex] is already at the last step.
     */
    public fun onNext() {
        _uiState.update { state ->
            val next = (state.currentStepIndex + 1).coerceAtMost(steps.lastIndex)
            logger.d(TAG, "onNext: ${steps[state.currentStepIndex]} -> ${steps[next]}")
            state.copy(currentStepIndex = next, error = null)
        }
    }

    // spec: ONB-002
    /**
     * Returns to the previous onboarding step.
     *
     * No-op when [OnboardingUiState.currentStepIndex] is already at the first step.
     */
    public fun onBack() {
        _uiState.update { state ->
            val prev = (state.currentStepIndex - 1).coerceAtLeast(0)
            logger.d(TAG, "onBack: ${steps[state.currentStepIndex]} -> ${steps[prev]}")
            state.copy(currentStepIndex = prev, error = null)
        }
    }

    // spec: ONB-003
    /**
     * Marks the current step as [StepStatus.SKIPPED] and advances to the next step.
     *
     * No-op when already at the last step.
     */
    public fun onSkipStep() {
        _uiState.update { state ->
            val current = steps[state.currentStepIndex]
            val next = (state.currentStepIndex + 1).coerceAtMost(steps.lastIndex)
            logger.d(TAG, "onSkipStep: $current skipped")
            state.copy(
                currentStepIndex = next,
                stepStates = state.stepStates + (current to StepStatus.SKIPPED),
                error = null,
            )
        }
    }

    // spec: ONB-004, ONB-CONTACTS
    /**
     * Updates the status of a specific onboarding [step].
     *
     * Called by the screen after receiving an OS permission callback or a
     * link-account result. This method is the only write path for [OnboardingUiState.stepStates].
     *
     * @param step   The step whose status is being updated.
     * @param status The new [StepStatus] to record.
     */
    public fun onMarkStepStatus(step: OnboardingStep, status: StepStatus) {
        logger.d(TAG, "onMarkStepStatus: $step -> $status")
        _uiState.update { state ->
            state.copy(stepStates = state.stepStates + (step to status), error = null)
        }
    }

    // spec: ONB-PIPA per-provider (S6-D) + PIPA Article 17
    /**
     * Persists a per-provider email PIPA consent outcome (S6-D) and marks the
     * corresponding OAuth / credential step [StepStatus.SKIPPED] when consent is denied
     * so the onboarding terminal gate accepts the flow without forcing the user to
     * revisit the OAuth screen.
     *
     * On `granted=true`, no step-status change is made here — the downstream OAuth
     * screen marks its own step COMPLETE after a successful connection.
     *
     * Audit trail: the setter records a wall-clock timestamp alongside the flag, and
     * emits a structured `onboarding_pipa_email_consent` observability event for
     * downstream PIPA action-log correlation (W7 builds the full user-facing log on
     * top of these events).
     */
    public fun onEmailPipaConsent(provider: EmailPipaProvider, granted: Boolean) {
        viewModelScope.launch {
            try {
                userPrefsStore.setEmailPipaConsent(provider, granted)
                logger.i(TAG, "pipa email consent ${provider.storageKey}=${granted}")
                observability.captureMessage(
                    message = "onboarding_pipa_email_consent",
                    tags = mapOf(
                        "provider" to provider.storageKey,
                        "granted" to granted.toString(),
                    ),
                )
                if (!granted) {
                    val skippedStep = skippedStepForDeniedConsent(provider)
                    _uiState.update { state ->
                        state.copy(stepStates = state.stepStates + (skippedStep to StepStatus.SKIPPED))
                    }
                }
            } catch (e: Exception) {
                logger.e(TAG, "pipa email consent write failed for ${provider.storageKey}", e)
                _uiState.update { it.copy(error = e.message ?: "consent write failed") }
            }
        }
    }

    private fun skippedStepForDeniedConsent(provider: EmailPipaProvider): OnboardingStep =
        when (provider) {
            EmailPipaProvider.GMAIL -> OnboardingStep.LINK_GMAIL
            EmailPipaProvider.OUTLOOK_MAIL -> OnboardingStep.LINK_OUTLOOK_MAIL
            EmailPipaProvider.IMAP -> OnboardingStep.LINK_IMAP
        }

    // spec: ONB-007 — "온보딩 중 OAuth 인증 실패 또는 권한 거부 발생 시 Sentry 에
    // onboarding_step_failed 이벤트 전송됨 (step 이름, error 포함)"
    /**
     * Emits the `onboarding_step_failed` observability event used by downstream onboarding
     * screens (Gmail / Outlook / IMAP plans S6-F/G/H) when an OAuth launcher reports a
     * permission denial or transport failure.
     *
     * Tags carry only the step name and a compact [errorCode] — raw exception messages,
     * user emails, and OAuth tokens must be scrubbed upstream before calling this
     * method; [com.becalm.android.core.observability.LoggerObservabilityClient] performs
     * a second scrub as defence-in-depth.
     *
     * @param step      The onboarding step whose OAuth / permission launcher failed.
     * @param errorCode Vendor-neutral short code (e.g. `user_cancelled`,
     *   `msal_network`, `gis_no_credentials`) suitable for alerting / grouping.
     */
    public fun reportOnboardingStepFailed(step: OnboardingStep, errorCode: String) {
        logger.w(TAG, "onboarding_step_failed: step=$step errorCode=$errorCode")
        observability.captureMessage(
            message = "onboarding_step_failed",
            tags = mapOf(
                "step" to step.name,
                "error_code" to errorCode,
            ),
        )
    }

    // spec: ONB-PIPA / ONB-PIPA invariant: "동의 거부는 온보딩을 중단시키지 않는다 — 음성 기능만 비활성화"
    /**
     * Shared implementation for [onPipaConsentGranted] / [onPipaConsentDeclined].
     *
     * Writes pipa_third_party_consent=[granted] to DataStore. On success:
     *  - granted=true  → mark PIPA_CONSENT=GRANTED, advance to RECORDING_FOLDER
     *  - granted=false → mark PIPA_CONSENT=DENIED + RECORDING_FOLDER=SKIPPED, advance to CONTACTS_PERM
     *    (voice events stored with sync_status='awaiting_consent'; VOI-004)
     *
     * Emits [PipaConsentEvent] so [PipaThirdPartyConsentScreen] navigates only after the write.
     */
    private fun setPipa(granted: Boolean) {
        // 로그 태그는 분리돼 있던 onPipaConsentGranted / onPipaConsentDeclined 시절과 동일하게 유지한다.
        // (로그 파이프라인/필터가 함수명 prefix에 의존할 수 있으므로 drift 방지)
        val caller = if (granted) "onPipaConsentGranted" else "onPipaConsentDeclined"
        viewModelScope.launch {
            try {
                userPrefsStore.setThirdPartyProvisionConsent(granted)
                if (granted) {
                    logger.i(TAG, "PIPA third-party provision consent GRANTED")
                } else {
                    logger.i(TAG, "PIPA third-party provision consent DECLINED — voice auto-upload disabled")
                }
                _uiState.update { state -> computePipaStateAfterWrite(state, granted, caller) }
                _pipaConsentEvents.emit(PipaConsentEvent.PipaConsentSaved(granted = granted))
            } catch (e: Exception) {
                logger.e(TAG, "$caller: DataStore write failed", e)
                _uiState.update { it.copy(error = e.message ?: "consent write failed") }
                _pipaConsentEvents.emit(PipaConsentEvent.PipaConsentSaveFailed(e.message ?: "consent write failed"))
            }
        }
    }

    /**
     * Pure state reducer for [setPipa]'s granted/declined branches. Returns the new
     * [OnboardingUiState] produced by applying the PIPA write outcome to [state].
     *
     * Log strings are preserved byte-identical to the inlined form — [caller] is used as
     * the prefix so the log pipeline's function-name filter keeps working.
     */
    private fun computePipaStateAfterWrite(
        state: OnboardingUiState,
        granted: Boolean,
        caller: String,
    ): OnboardingUiState {
        val pipaIndex = steps.indexOf(OnboardingStep.PIPA_CONSENT)
        return if (granted) {
            val recordingFolderIndex = steps.indexOf(OnboardingStep.RECORDING_FOLDER)
            logger.d(TAG, "$caller: advancing to RECORDING_FOLDER (index=$recordingFolderIndex)")
            state.copy(
                currentStepIndex = recordingFolderIndex.coerceAtLeast(pipaIndex + 1),
                stepStates = state.stepStates + (OnboardingStep.PIPA_CONSENT to StepStatus.GRANTED),
                error = null,
            )
        } else {
            // Skip RECORDING_FOLDER — go straight to CONTACTS_PERM. Mark RECORDING_FOLDER
            // as SKIPPED so the onCompleteOnboarding() terminal-status gate doesn't lock
            // the user out (PIPA compliance fix).
            val contactsIndex = steps.indexOf(OnboardingStep.CONTACTS_PERM)
            val targetIndex = if (contactsIndex >= 0) contactsIndex else pipaIndex + 2
            logger.d(TAG, "$caller: skipping RECORDING_FOLDER, advancing to index=$targetIndex")
            state.copy(
                currentStepIndex = targetIndex.coerceAtMost(steps.lastIndex),
                stepStates = state.stepStates +
                    (OnboardingStep.PIPA_CONSENT to StepStatus.DENIED) +
                    (OnboardingStep.RECORDING_FOLDER to StepStatus.SKIPPED),
                error = null,
            )
        }
    }

    /**
     * Called when the user taps [동의] on [com.becalm.android.ui.onboarding.PipaThirdPartyConsentScreen].
     * See [setPipa] for behavior.
     */
    public fun onPipaConsentGranted() {
        setPipa(granted = true)
    }

    /**
     * Called when the user taps [동의 안 함] on [com.becalm.android.ui.onboarding.PipaThirdPartyConsentScreen].
     * See [setPipa] for behavior.
     */
    public fun onPipaConsentDeclined() {
        setPipa(granted = false)
    }

    // spec: ONB-001 — persist terms acceptance so restarting the app doesn't re-show terms.
    /**
     * Persists terms-of-service acceptance to DataStore.
     *
     * Called by [com.becalm.android.ui.auth.TermsScreen] before navigating to Login.
     */
    public fun onAcceptTerms() {
        viewModelScope.launch {
            try {
                userPrefsStore.setTermsAccepted(true)
                logger.i(TAG, "terms acceptance persisted")
            } catch (e: Exception) {
                logger.e(TAG, "failed to persist terms acceptance", e)
            }
        }
    }

    // spec: ONB-005, ONB-006, ONB-007, ONB-008
    /**
     * Persists onboarding completion and marks the flow as done.
     *
     * Writes [UserPrefsStore.setOnboardingCompleted] with `true`. The main navigation
     * graph observes [UserPrefsStore.observeOnboardingCompleted] and will route the user
     * to the home graph once this write is reflected.
     *
     * Terminal gate: every step in [steps] must be in a terminal [StepStatus]
     * (GRANTED / COMPLETE / SKIPPED / DENIED). DENIED is accepted for steps where the
     * spec explicitly tolerates denial (ONB-PIPA, ONB-CONTACTS).
     */
    public fun onCompleteOnboarding() {
        viewModelScope.launch {
            _uiState.update { it.copy(isCompleting = true, error = null) }
            val stepStates = _uiState.value.stepStates
            if (!isTerminalGatePassed(stepStates)) {
                logger.d(TAG, "onCompleteOnboarding: blocked — not all steps finished; stepStates=$stepStates")
                _uiState.update {
                    it.copy(isCompleting = false, error = "Please complete all steps before finishing")
                }
                return@launch
            }
            try {
                userPrefsStore.setOnboardingCompleted(true)
                logger.i(TAG, "onboarding marked complete")
                _uiState.update { state ->
                    state.copy(
                        isCompleting = false,
                        stepStates = state.stepStates + (OnboardingStep.COLD_SYNC to StepStatus.COMPLETE),
                    )
                }
            } catch (e: Exception) {
                logger.e(TAG, "failed to persist onboarding completion", e)
                _uiState.update { it.copy(isCompleting = false, error = e.message ?: "Unknown error") }
            }
        }
    }

    /**
     * spec: ONB-008 — every onboarding step must have reached a terminal status before
     * [onCompleteOnboarding] is allowed to persist `onboarding_completed=true`.
     *
     * [StepStatus.DENIED] is accepted per the ONB-PIPA / ONB-CONTACTS invariants
     * ("동의 거부는 온보딩을 중단시키지 않는다").
     *
     * Every enum value in [OnboardingStep] maps to an actual Composable screen
     * (post-Round 6B.4), so there is no screenless escape hatch.
     */
    private fun isTerminalGatePassed(stepStates: Map<OnboardingStep, StepStatus>): Boolean {
        val terminalStatuses = setOf(
            StepStatus.GRANTED,
            StepStatus.COMPLETE,
            StepStatus.SKIPPED,
            StepStatus.DENIED,
        )
        return OnboardingStep.entries
            .all { step -> (stepStates[step] ?: StepStatus.NOT_STARTED) in terminalStatuses }
    }
}
