package com.becalm.android.ui.onboarding

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.core.observability.ObservabilityClient
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.EmailPipaProvider
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.secure.ImapCredentialStore
import com.becalm.android.data.local.secure.ImapCredentials
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.worker.AppRuntimeSyncCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

/**
 * One-shot outcome of an email-source connection attempt (S6-F/G/H).
 *
 * Scoped to the onboarding surface so downstream source-management UIs can share the
 * same [ObservabilityClient] / backend-managed mail OAuth plumbing without coupling
 * to this ViewModel — they will ship their own analog once they need it.
 */
public sealed class EmailConnectEvent {
    /** Identifies which provider the event belongs to so a screen can filter on its own. */
    public abstract val provider: EmailPipaProvider

    /**
     * Authorization completed and the credential is persisted. The caller should mark
     * the step [StepStatus.COMPLETE] and navigate to the next onboarding route.
     */
    public data class Connected(override val provider: EmailPipaProvider) : EmailConnectEvent()

    /**
     * First-time consent: launch the carried intent via an Activity-result launcher and
     * re-trigger [OnboardingViewModel.onConnectEmailProvider] once the user completes
     * the flow. Specific to Google's AuthorizationClient first-run path.
     */
    public data class PendingIntentRequired(
        override val provider: EmailPipaProvider,
        val pendingIntent: android.app.PendingIntent,
    ) : EmailConnectEvent()

    /**
     * Authorization failed or was cancelled. [errorCode] is a stable label
     * (`user_cancelled`, `scope_denied`, `network`, `play_services_unavailable`,
     * `unknown`) that already drove the `onboarding_step_failed` observability event.
     */
    public data class Failed(
        override val provider: EmailPipaProvider,
        val errorCode: String,
    ) : EmailConnectEvent()
}

/** One-shot outcome of a calendar-source connection attempt. */
public sealed class CalendarConnectEvent {
    public abstract val provider: CalendarOAuthProvider

    public data class Connected(
        override val provider: CalendarOAuthProvider,
    ) : CalendarConnectEvent()

    public data class Failed(
        override val provider: CalendarOAuthProvider,
        val errorCode: String,
    ) : CalendarConnectEvent()
}

/** One-shot effects for the contacts permission step (ENR-001 / ENR-002). */
public sealed interface ContactsPermissionEffect {
    public data object RequestSystemPermission : ContactsPermissionEffect
    public data class NavigateToEmailPipa(val provider: EmailPipaProvider) : ContactsPermissionEffect
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
    private val imapCredentialStore: ImapCredentialStore,
    private val emailOAuthConnector: EmailOAuthConnector,
    private val calendarOAuthConnector: CalendarOAuthConnector,
    private val appRuntimeSyncCoordinator: AppRuntimeSyncCoordinator,
) : ViewModel() {

    private val emailActionHandler: OnboardingEmailActionHandler = OnboardingEmailActionHandler(
        userPrefsStore = userPrefsStore,
        imapCredentialStore = imapCredentialStore,
        observability = observability,
        logger = logger,
    )

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

    // One-shot events for email OAuth / IMAP save outcomes (S6-F/G/H). replay=0 so a
    // ResolutionRequired intent is consumed exactly once; DROP_OLDEST yields the most
    // recent outcome when a rapid double-tap queues more than one.
    private val _emailConnectEvents: MutableSharedFlow<EmailConnectEvent> = MutableSharedFlow(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Collect in Gmail / Outlook / IMAP onboarding screens to drive result UX. */
    public val emailConnectEvents: SharedFlow<EmailConnectEvent> = _emailConnectEvents.asSharedFlow()

    private val _calendarConnectEvents: MutableSharedFlow<CalendarConnectEvent> = MutableSharedFlow(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Collect in calendar onboarding screens to drive connect / failure UX. */
    public val calendarConnectEvents: SharedFlow<CalendarConnectEvent> =
        _calendarConnectEvents.asSharedFlow()

    private val _contactsPermissionEffects: MutableSharedFlow<ContactsPermissionEffect> = MutableSharedFlow(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Collect in [ContactsPermissionScreen] to request the system dialog or navigate next. */
    public val contactsPermissionEffects: SharedFlow<ContactsPermissionEffect> =
        _contactsPermissionEffects.asSharedFlow()

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
            OnboardingStateReducer.next(state, steps)
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
            OnboardingStateReducer.previous(state, steps)
        }
    }

    // spec: ONB-003, ONB-008
    /**
     * Marks [step] as [StepStatus.SKIPPED] and re-anchors [OnboardingUiState.currentStepIndex]
     * on the next entry in [steps].
     *
     * Screen composables drive navigation directly (they know which step their Skip
     * button belongs to), so accepting an explicit [step] here instead of inferring it
     * from the index is required to keep the ONB-008 terminal gate honest. The earlier
     * index-driven variant (no argument) relied on [currentStepIndex] staying in sync
     * with the composable the user was viewing, which was never true: only
     * [onNext]/[onBack]/[setPipa] write the index, so a Skip tap on Gmail would mark
     * TERMS as SKIPPED and leave LINK_GMAIL `NOT_STARTED`, blocking
     * [onCompleteOnboarding].
     */
    public fun onSkipStep(step: OnboardingStep) {
        _uiState.update { state ->
            logger.d(TAG, "onSkipStep: $step skipped")
            OnboardingStateReducer.skipStep(state, step, steps)
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
            OnboardingStateReducer.markStepStatus(state, step, status)
        }
    }

    /**
     * Persists the current voice-source availability and records the recording-folder step
     * result using the same public state machine as the rest of onboarding.
     */
    public fun onRecordingFolderPermissionResult(granted: Boolean) {
        if (granted) return
        viewModelScope.launch {
            userPrefsStore.setSourceEnabled(SourceType.VOICE, false)
            userPrefsStore.setRecordingFolderTreeUri(null)
            onMarkStepStatus(OnboardingStep.RECORDING_FOLDER, StepStatus.DENIED)
            appRuntimeSyncCoordinator.refresh()
        }
    }

    /** Persists the SAF tree grant for the shared Recordings folder and enables voice capture. */
    public fun onRecordingFolderTreeGranted(uri: String) {
        viewModelScope.launch {
            userPrefsStore.setRecordingFolderTreeUri(uri)
            userPrefsStore.setSourceEnabled(SourceType.VOICE, true)
            onMarkStepStatus(OnboardingStep.RECORDING_FOLDER, StepStatus.GRANTED)
            appRuntimeSyncCoordinator.refresh()
        }
    }

    /** Explicit graceful-skip branch for the recording-folder step. */
    public fun onSkipRecordingFolder() {
        viewModelScope.launch {
            userPrefsStore.setRecordingFolderTreeUri(null)
            userPrefsStore.setSourceEnabled(SourceType.VOICE, false)
            onSkipStep(OnboardingStep.RECORDING_FOLDER)
            appRuntimeSyncCoordinator.refresh()
        }
    }

    /** Records an explicit skip for a calendar step and moves the flow forward. */
    public fun onSkipCalendarSource(provider: CalendarOAuthProvider) {
        viewModelScope.launch {
            userPrefsStore.setSourceEnabled(provider.sourceType, false)
            onSkipStep(provider.step)
        }
    }

    /**
     * Initiates an interactive calendar OAuth sign-in for [provider].
     *
     * The current production connector intentionally fails closed until the real provider SDK
     * is wired. This removes the previous fake-success path while keeping a stable unit-testable
     * contract for future external integration.
     */
    public fun onConnectCalendarProvider(provider: CalendarOAuthProvider, activity: Activity) {
        viewModelScope.launch {
            when (val result = calendarOAuthConnector.startSignIn(provider, activity)) {
                CalendarOAuthResult.Connected -> {
                    userPrefsStore.setSourceEnabled(provider.sourceType, true)
                    onMarkStepStatus(provider.step, StepStatus.COMPLETE)
                    _calendarConnectEvents.emit(CalendarConnectEvent.Connected(provider))
                }
                is CalendarOAuthResult.Failed -> {
                    reportOnboardingStepFailed(provider.step, result.errorCode)
                    _calendarConnectEvents.emit(
                        CalendarConnectEvent.Failed(
                            provider = provider,
                            errorCode = result.errorCode,
                        ),
                    )
                }
            }
        }
    }

    // spec: ONB-003, ENR-001, ENR-002
    /** Emits a one-shot request for the system READ_CONTACTS permission dialog. */
    public fun onAllowContacts() {
        _contactsPermissionEffects.tryEmit(ContactsPermissionEffect.RequestSystemPermission)
    }

    /** Records the system permission result and advances to the email PIPA step. */
    public fun onContactsPermissionResult(granted: Boolean) {
        val status = if (granted) StepStatus.GRANTED else StepStatus.DENIED
        onMarkStepStatus(OnboardingStep.CONTACTS_PERM, status)
        appRuntimeSyncCoordinator.refresh()
        _contactsPermissionEffects.tryEmit(
            ContactsPermissionEffect.NavigateToEmailPipa(EmailPipaProvider.GMAIL),
        )
    }

    /** Explicit graceful-skip branch for contacts permission. */
    public fun onSkipContacts() {
        onSkipStep(OnboardingStep.CONTACTS_PERM)
        appRuntimeSyncCoordinator.refresh()
        _contactsPermissionEffects.tryEmit(
            ContactsPermissionEffect.NavigateToEmailPipa(EmailPipaProvider.GMAIL),
        )
    }

    // spec: ONB-PIPA per-provider (S6-D) + PIPA Article 17
    /**
     * Persists email PIPA consent outcomes for the given [providers] atomically and
     * marks the matching OAuth / credential step [StepStatus.SKIPPED] when consent is
     * denied, so the terminal gate accepts the flow without forcing the user back to
     * the OAuth screen.
     *
     * Taking a list lets the IMAP onboarding screen — which shows a single combined
     * disclosure but must record consent for both Naver Corp and Kakao Corp per PIPA
     * Article 17 — update both recipients in one DataStore transaction-equivalent burst.
     * Gmail and Outlook callers pass a single-element list.
     *
     * Audit trail: the setter records a wall-clock timestamp alongside each flag, and
     * one structured `onboarding_pipa_email_consent` observability event is emitted per
     * recipient for downstream PIPA action-log correlation (W7 builds the full
     * user-facing log on top of these events).
     *
     * @return true once every write has completed; callers must await this before
     *   navigating so the consent record is durable before the user can reach a
     *   connectable provider screen.
     */
    public suspend fun onEmailPipaConsent(
        providers: List<EmailPipaProvider>,
        granted: Boolean,
    ): Boolean = emailActionHandler.persistEmailPipaConsent(
        providers = providers,
        granted = granted,
        updateState = _uiState::update,
        setError = { message -> _uiState.update { it.copy(error = message) } },
    )

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
    // spec: AUTH-002 + ONB-004 (S6-F/G/H)
    /**
     * Initiates an interactive OAuth sign-in for the given email [provider].
     *
     * Routes through [EmailOAuthConnector.startSignIn]; on success persists the
     * `<provider>_connected=true` flag, marks the appropriate step
     * [StepStatus.COMPLETE], and emits [EmailConnectEvent.Connected]. Failures emit
     * [EmailConnectEvent.Failed] and mark the step [StepStatus.SKIPPED] so the ONB-008
     * terminal gate accepts the flow.
     *
     * [provider] must be [EmailPipaProvider.GMAIL] or [EmailPipaProvider.OUTLOOK_MAIL]
     * — IMAP is credential-based and uses [saveImapCredentials] instead.
     *
     * @param provider Target email provider.
     * @param activity The foreground activity; required so [EmailOAuthConnector] can
     *   launch the external browser flow for the backend-managed OAuth callback path.
     */
    public fun onConnectEmailProvider(provider: EmailPipaProvider, activity: Activity) {
        require(provider == EmailPipaProvider.GMAIL || provider == EmailPipaProvider.OUTLOOK_MAIL) {
            "${provider.storageKey} uses saveImapCredentials(), not onConnectEmailProvider()"
        }
        viewModelScope.launch {
            val oauthProvider = when (provider) {
                EmailPipaProvider.GMAIL -> EmailOAuthProvider.GMAIL
                EmailPipaProvider.OUTLOOK_MAIL -> EmailOAuthProvider.OUTLOOK_MAIL
                EmailPipaProvider.NAVER_IMAP,
                EmailPipaProvider.DAUM_IMAP,
                -> error("unreachable")
            }
            val consented = userPrefsStore.observeEmailPipaConsent(provider).first()
            if (!consented) {
                reportOnboardingStepFailed(oauthProvider.step, "pipa_consent_missing")
                _uiState.update {
                    it.copy(stepStates = it.stepStates + (oauthProvider.step to StepStatus.SKIPPED))
                }
                _emailConnectEvents.emit(EmailConnectEvent.Failed(provider, "pipa_consent_missing"))
                return@launch
            }
            when (val result = emailOAuthConnector.startSignIn(oauthProvider, activity)) {
                EmailOAuthResult.Connected -> {
                    userPrefsStore.setEmailSourceConnected(provider, true)
                    userPrefsStore.setEmailSourceManagedByBackend(provider, true)
                    onMarkStepStatus(oauthProvider.step, StepStatus.COMPLETE)
                    observability.captureMessage(
                        message = "onboarding_email_connected",
                        tags = mapOf("provider" to provider.storageKey, "owner" to "backend"),
                    )
                    _emailConnectEvents.emit(EmailConnectEvent.Connected(provider))
                }
                is EmailOAuthResult.Failed -> {
                    reportOnboardingStepFailed(oauthProvider.step, result.errorCode)
                    _uiState.update {
                        it.copy(stepStates = it.stepStates + (oauthProvider.step to StepStatus.SKIPPED))
                    }
                    _emailConnectEvents.emit(EmailConnectEvent.Failed(provider, result.errorCode))
                }
            }
        }
    }

    // spec: ONB-004 + ING-011 (S6-H)
    /**
     * Persists IMAP [credentials] under the [sourceType] namespace
     * ([com.becalm.android.data.remote.dto.SourceType.NAVER_IMAP] or `DAUM_IMAP`) and
     * marks [OnboardingStep.LINK_IMAP] [StepStatus.COMPLETE] on success.
     *
     * On failure, [EmailConnectEvent.Failed] is emitted with a short `errorCode`
     * (`save_failed` / `network`) and the step is marked [StepStatus.SKIPPED] so the
     * terminal gate accepts the flow; the Snackbar copy drives user retry.
     *
     * Invalid sourceType values fail the provider's `require` guard and are reported as
     * `unknown_provider` without further persistence.
     */
    public fun saveImapCredentials(sourceType: String, credentials: ImapCredentials) {
        viewModelScope.launch {
            emailActionHandler.saveImapCredentials(
                sourceType = sourceType,
                credentials = credentials,
                updateState = _uiState::update,
                emitEvent = { event -> _emailConnectEvents.emit(event) },
                reportStepFailed = ::reportOnboardingStepFailed,
            )
        }
    }

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
        val nextState = OnboardingStateReducer.applyPipaConsent(state, granted, steps)
        if (granted) {
            logger.d(TAG, "$caller: advancing to RECORDING_FOLDER (index=${nextState.currentStepIndex})")
        } else {
            logger.d(TAG, "$caller: skipping RECORDING_FOLDER, advancing to index=${nextState.currentStepIndex}")
        }
        return nextState
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
        return OnboardingStateReducer.isTerminalGatePassed(stepStates)
    }
}
