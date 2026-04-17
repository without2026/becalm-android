package com.becalm.android.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.core.util.Logger
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
 * The display order is determined by [OnboardingViewModel.STEPS].
 *
 * **Persistence note**: the current step is NOT persisted to DataStore as an ordinal or name;
 * only [UserPrefsStore.setOnboardingCompleted] is written at the very end. Adding or reordering
 * enum members is therefore safe for in-progress onboardings.
 *
 * **Step count**: the spec declares 12 steps (약관 → 로그인 → PIPA제3자제공 → 녹음폴더 → …).
 * [PIPA_CONSENT] is inserted at position 3 (after WELCOME/LOGIN, before RECORDING_FOLDER).
 */
public enum class OnboardingStep {
    WELCOME,
    PIPA_CONSENT,
    RECORDING_FOLDER,
    SMS_PERM,
    CALL_PERM,
    CONTACTS_PERM,
    CALENDAR_PERM,
    NOTIF_PERM,
    LINK_GMAIL,
    LINK_OUTLOOK,
    LINK_IMAP,
    SAMSUNG_DOZE,
    COMPLETE,
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
 * @param currentStepIndex Index into [OnboardingViewModel.STEPS] for the currently displayed step.
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
 * Navigation through the 11-step onboarding sequence is driven entirely by this
 * class. Permission result callbacks from the UI call [onMarkStepStatus] — no
 * Android framework APIs are used here.
 */
@HiltViewModel
public class OnboardingViewModel @Inject constructor(
    private val userPrefsStore: UserPrefsStore,
    private val logger: Logger,
) : ViewModel() {

    /** Canonical ordered list of onboarding steps. */
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
     * No-op when [currentStepIndex] is already at the last step.
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
     * No-op when [currentStepIndex] is already at the first step.
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
     * link-account result. This method is the only write path for [stepStates].
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

    // spec: ONB-PIPA
    /**
     * Called when the user taps [동의] on [com.becalm.android.ui.onboarding.PipaThirdPartyConsentScreen].
     *
     * Writes pipa_third_party_consent=true (+ timestamp) to DataStore, marks
     * [OnboardingStep.PIPA_CONSENT] as [StepStatus.GRANTED], and advances to
     * [OnboardingStep.RECORDING_FOLDER].
     *
     * The navigation itself is performed by the composable callback chain; this method
     * only owns the DataStore write and step-state update.
     */
    public fun onPipaConsentGranted() {
        viewModelScope.launch {
            try {
                userPrefsStore.setThirdPartyProvisionConsent(true)
                logger.i(TAG, "PIPA third-party provision consent GRANTED")
                // Update step state only after the write succeeds, then signal the screen to navigate.
                _uiState.update { state ->
                    val pipaIndex = steps.indexOf(OnboardingStep.PIPA_CONSENT)
                    val recordingFolderIndex = steps.indexOf(OnboardingStep.RECORDING_FOLDER)
                    logger.d(TAG, "onPipaConsentGranted: advancing to RECORDING_FOLDER (index=$recordingFolderIndex)")
                    state.copy(
                        currentStepIndex = recordingFolderIndex.coerceAtLeast(pipaIndex + 1),
                        stepStates = state.stepStates + (OnboardingStep.PIPA_CONSENT to StepStatus.GRANTED),
                        error = null,
                    )
                }
                _pipaConsentEvents.emit(PipaConsentEvent.PipaConsentSaved(granted = true))
            } catch (e: Exception) {
                logger.e(TAG, "onPipaConsentGranted: DataStore write failed", e)
                _uiState.update { it.copy(error = e.message ?: "consent write failed") }
                _pipaConsentEvents.emit(PipaConsentEvent.PipaConsentSaveFailed(e.message ?: "consent write failed"))
            }
        }
    }

    // spec: ONB-PIPA invariant: "동의 거부는 온보딩을 중단시키지 않는다 — 음성 기능만 비활성화"
    /**
     * Called when the user taps [동의 안 함] on [com.becalm.android.ui.onboarding.PipaThirdPartyConsentScreen].
     *
     * Writes pipa_third_party_consent=false to DataStore, marks
     * [OnboardingStep.PIPA_CONSENT] as [StepStatus.DENIED], and skips
     * [OnboardingStep.RECORDING_FOLDER] — advancing directly to [OnboardingStep.CONTACTS_PERM].
     *
     * Voice events recorded after this point will be stored with sync_status='awaiting_consent'
     * (enforced by VoiceUploadWorker VOI-004). The user can grant consent later via Settings.
     *
     * The navigation itself is performed by the composable callback chain; this method
     * only owns the DataStore write and step-state update.
     */
    public fun onPipaConsentDeclined() {
        viewModelScope.launch {
            try {
                userPrefsStore.setThirdPartyProvisionConsent(false)
                logger.i(TAG, "PIPA third-party provision consent DECLINED — voice auto-upload disabled")
                // Update step state only after the write succeeds, then signal the screen to navigate.
                _uiState.update { state ->
                    // Skip RECORDING_FOLDER — go straight to CONTACTS_PERM
                    val contactsIndex = steps.indexOf(OnboardingStep.CONTACTS_PERM)
                    val pipaIndex = steps.indexOf(OnboardingStep.PIPA_CONSENT)
                    val targetIndex = if (contactsIndex >= 0) contactsIndex else pipaIndex + 2
                    logger.d(TAG, "onPipaConsentDeclined: skipping RECORDING_FOLDER, advancing to index=$targetIndex")
                    // Mark PIPA_CONSENT as DENIED and RECORDING_FOLDER as SKIPPED so the
                    // onCompleteOnboarding() terminal-status gate does not block the user
                    // from finishing — leaving RECORDING_FOLDER as NOT_STARTED caused a
                    // lockout (PIPA compliance fix).
                    state.copy(
                        currentStepIndex = targetIndex.coerceAtMost(steps.lastIndex),
                        stepStates = state.stepStates +
                            (OnboardingStep.PIPA_CONSENT to StepStatus.DENIED) +
                            (OnboardingStep.RECORDING_FOLDER to StepStatus.SKIPPED),
                        error = null,
                    )
                }
                _pipaConsentEvents.emit(PipaConsentEvent.PipaConsentSaved(granted = false))
            } catch (e: Exception) {
                logger.e(TAG, "onPipaConsentDeclined: DataStore write failed", e)
                _uiState.update { it.copy(error = e.message ?: "consent write failed") }
                _pipaConsentEvents.emit(PipaConsentEvent.PipaConsentSaveFailed(e.message ?: "consent write failed"))
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
     */
    public fun onCompleteOnboarding() {
        viewModelScope.launch {
            _uiState.update { it.copy(isCompleting = true, error = null) }
            // spec: ONB-008 — gate completion on every non-COMPLETE step having reached a terminal
            // status. DENIED is accepted per the ONB-PIPA invariant (declining PIPA consent must
            // not block the user from finishing onboarding).
            val stepStates = _uiState.value.stepStates
            val terminalStatuses = setOf(
                StepStatus.GRANTED,
                StepStatus.COMPLETE,
                StepStatus.SKIPPED,
                StepStatus.DENIED,
            )
            val allStepsDone = OnboardingStep.entries
                .filter { it != OnboardingStep.COMPLETE }
                .all { step -> (stepStates[step] ?: StepStatus.NOT_STARTED) in terminalStatuses }
            if (!allStepsDone) {
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
                        stepStates = state.stepStates + (OnboardingStep.COMPLETE to StepStatus.COMPLETE),
                    )
                }
            } catch (e: Exception) {
                logger.e(TAG, "failed to persist onboarding completion", e)
                _uiState.update { it.copy(isCompleting = false, error = e.message ?: "Unknown error") }
            }
        }
    }
}
