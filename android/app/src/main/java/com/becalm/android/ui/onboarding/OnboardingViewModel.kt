package com.becalm.android.ui.onboarding

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.R
import com.becalm.android.core.observability.ObservabilityClient
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.EmailPipaProvider
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.entity.SourceConnectionEntity
import com.becalm.android.data.local.secure.ImapCredentialStore
import com.becalm.android.data.local.secure.ImapCredentials
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.SelfIdentityRepository
import com.becalm.android.data.repository.SourceConnectionRepository
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.data.repository.UserProfileRepository
import com.becalm.android.ui.components.UiMessage
import com.becalm.android.ui.sources.sourceConnectionTitle
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

// ─── Enums ────────────────────────────────────────────────────────────────────

/**
 * Durable setup milestones used by first-run onboarding and settings recovery.
 *
 * The canonical first-run UI is [OnboardingSetupScreen], not one full screen per
 * enum value. The enum remains the persistence/status vocabulary because existing
 * source recovery screens and settings reconnect flows still need per-capability
 * terminal states.
 *
 * **Persistence note**: the current step is NOT persisted to DataStore as an ordinal or name;
 * only [UserPrefsStore.setOnboardingCompleted] is written at the very end. Adding or reordering
 * enum members is therefore safe for in-progress onboardings.
 *
 * **Canonical onboarding flow**:
 *
 * ```
 * 약관 → 로그인 → Setup(필수 요약 + 추천 권한 + 선택 출처) → Today
 * ```
 *
 * Completion is signalled by [UserPrefsStore.setOnboardingCompleted] `true` from
 * [onCompleteSetup]. Unfinished optional milestones are marked terminal so users can
 * reach the main person-first surface and reconnect later from Settings.
 */
public enum class OnboardingStep {
    /** Step 1 — [com.becalm.android.ui.auth.TermsScreen]. */
    TERMS,
    /** Step 2 — [com.becalm.android.ui.auth.LoginScreen]. */
    LOGIN,
    /** Voice-processing PIPA consent milestone. */
    PIPA_CONSENT,
    /** Recording-folder SAF/audio permission milestone. */
    RECORDING_FOLDER,
    /** Optional step — local CallLog matching consent for call-recording person refs. */
    CALL_LOG_MATCHING,
    /** Contacts permission milestone. */
    CONTACTS_PERM,
    /** Source connection page item: Gmail OAuth. */
    LINK_GMAIL,
    /** Source connection page item: Outlook Mail OAuth. */
    LINK_OUTLOOK_MAIL,
    /** Compatibility/manual source step. Skipped in first-run source connection. */
    LINK_IMAP,
    /** Source connection page item: Google Calendar OAuth. */
    LINK_GOOGLE_CALENDAR,
    /** Source connection page item: Outlook Calendar OAuth. */
    LINK_OUTLOOK_CALENDAR,
    /** Notification permission milestone. */
    NOTIFICATION_PERM,
    /** Background/battery recovery milestone retained for settings repair. */
    BATTERY_OPT,
    /** Legacy cold-sync milestone. First-run setup now lets runtime refresh continue behind Today. */
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
    public data object NavigateToSources : ContactsPermissionEffect
}

// ─── UI State ─────────────────────────────────────────────────────────────────

/**
 * Immutable snapshot of the onboarding screen state.
 *
 * @param currentStepIndex Index into [OnboardingViewModel.steps] for the currently displayed step.
 * @param stepStates       Per-step status map; defaults to [StepStatus.NOT_STARTED] for every step.
 * @param isCompleting     `true` while [OnboardingViewModel.onCompleteOnboarding] is in flight.
 * @param error            Non-null when the last action produced a resource-backed error to display.
 */
public data class OnboardingUiState(
    val currentStepIndex: Int = 0,
    val stepStates: Map<OnboardingStep, StepStatus> = OnboardingStep.entries.associateWith { StepStatus.NOT_STARTED },
    val selfDisplayName: String = "",
    val selfEmail: String = "",
    val selfPhone: String = "",
    val selfAlias: String = "",
    val selfIdentityConfirmed: Boolean = false,
    val isSavingSelfIdentity: Boolean = false,
    val sourceOwnerships: List<OnboardingSourceOwnershipUi> = emptyList(),
    val updatingSourceOwnershipId: String? = null,
    val isCompleting: Boolean = false,
    val error: UiMessage? = null,
)

// ─── ViewModel ────────────────────────────────────────────────────────────────

private const val TAG = "OnboardingViewModel"

/**
 * ViewModel for the onboarding flow.
 *
 * First-run onboarding is driven by the compact setup screen. [OnboardingStep]
 * remains the durable status vocabulary used to resume setup and expose
 * compatibility routes without putting Android framework APIs in this class.
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
    private val sourceStatusRepository: SourceStatusRepository,
    private val sourceConnectionRepository: SourceConnectionRepository,
    private val selfIdentityRepository: SelfIdentityRepository,
    private val userProfileRepository: UserProfileRepository,
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

    init {
        hydrateDurableProgress()
        hydrateSelfIdentity()
        hydrateSourceOwnerships()
    }

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
        persistStepStatus(step, StepStatus.SKIPPED)
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
        persistStepStatus(step, status)
    }

    public fun onSelfDisplayNameChange(value: String) {
        _uiState.update { it.copy(selfDisplayName = value, selfIdentityConfirmed = false) }
    }

    public fun onSelfEmailChange(value: String) {
        _uiState.update { it.copy(selfEmail = value, selfIdentityConfirmed = false) }
    }

    public fun onSelfPhoneChange(value: String) {
        _uiState.update { it.copy(selfPhone = value, selfIdentityConfirmed = false) }
    }

    public fun onSelfAliasChange(value: String) {
        _uiState.update { it.copy(selfAlias = value, selfIdentityConfirmed = false) }
    }

    public fun onSaveSelfIdentity() {
        viewModelScope.launch {
            val userId = userPrefsStore.observeCurrentUserId().first()
            if (userId.isNullOrBlank()) {
                _uiState.update { it.copy(error = UiMessage.resource(R.string.settings_identity_error_no_user)) }
                return@launch
            }
            val state = _uiState.value
            if (
                state.selfDisplayName.isBlank() &&
                state.selfEmail.isBlank() &&
                state.selfPhone.isBlank() &&
                state.selfAlias.isBlank()
            ) {
                _uiState.update { it.copy(error = UiMessage.resource(R.string.onb_error_self_identity_required)) }
                return@launch
            }
            _uiState.update { it.copy(isSavingSelfIdentity = true, error = null) }
            when (
                val result = userProfileRepository.updateRemote(
                    userId = userId,
                    displayName = state.selfDisplayName,
                    phoneE164Self = state.selfPhone,
                )
            ) {
                is BecalmResult.Success -> {
                    if (!createOptionalSelfAnchor(userId, anchorType = "email", value = state.selfEmail)) {
                        _uiState.update {
                            it.copy(
                                isSavingSelfIdentity = false,
                                error = UiMessage.resource(R.string.settings_identity_error_add_anchor),
                            )
                        }
                        return@launch
                    }
                    if (!createOptionalSelfAnchor(userId, anchorType = "alias", value = state.selfAlias)) {
                        _uiState.update {
                            it.copy(
                                isSavingSelfIdentity = false,
                                error = UiMessage.resource(R.string.settings_identity_error_add_anchor),
                            )
                        }
                        return@launch
                    }
                    selfIdentityRepository.refresh(userId)
                    _uiState.update {
                        it.copy(
                            selfDisplayName = result.value.displayNameOverride.orEmpty(),
                            selfEmail = state.selfEmail.trim(),
                            selfPhone = result.value.phoneE164Self.orEmpty(),
                            selfAlias = state.selfAlias.trim(),
                            selfIdentityConfirmed = true,
                            isSavingSelfIdentity = false,
                            error = null,
                        )
                    }
                }
                is BecalmResult.Failure -> _uiState.update {
                    it.copy(
                        isSavingSelfIdentity = false,
                        error = UiMessage.resource(R.string.settings_identity_error_save_profile),
                    )
                }
            }
        }
    }

    private suspend fun createOptionalSelfAnchor(
        userId: String,
        anchorType: String,
        value: String,
    ): Boolean {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return true
        return when (
            selfIdentityRepository.createAnchor(
                userId = userId,
                anchorType = anchorType,
                value = trimmed,
                displayValue = trimmed,
                source = "user_profile",
            )
        ) {
            is BecalmResult.Success -> true
            is BecalmResult.Failure -> false
        }
    }

    public fun onSetSourceConnectionOwnership(connectionId: String, ownership: String) {
        if (ownership !in SOURCE_OWNERSHIP_VALUES) return
        viewModelScope.launch {
            val userId = userPrefsStore.observeCurrentUserId().first()
            if (userId.isNullOrBlank()) {
                _uiState.update { it.copy(error = UiMessage.resource(R.string.settings_identity_error_no_user)) }
                return@launch
            }
            _uiState.update { it.copy(updatingSourceOwnershipId = connectionId, error = null) }
            when (sourceConnectionRepository.setOwnership(userId, connectionId, ownership)) {
                is BecalmResult.Success -> {
                    selfIdentityRepository.refresh(userId)
                    _uiState.update {
                        it.copy(
                            updatingSourceOwnershipId = null,
                            error = null,
                        )
                    }
                }
                is BecalmResult.Failure -> _uiState.update {
                    it.copy(
                        updatingSourceOwnershipId = null,
                        error = UiMessage.resource(R.string.settings_identity_error_update_connection),
                    )
                }
            }
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
            userPrefsStore.setSourceEnabled(SourceType.MEETING, false)
            userPrefsStore.setRecordingFolderTreeUri(null)
            onMarkStepStatus(OnboardingStep.RECORDING_FOLDER, StepStatus.DENIED)
            appRuntimeSyncCoordinator.refresh()
        }
    }

    /** Persists the shared Recordings SAF tree grant and enables voice/meeting capture. */
    public fun onRecordingFolderTreeGranted(uri: String) {
        viewModelScope.launch {
            userPrefsStore.setRecordingFolderTreeUri(uri)
            userPrefsStore.setSourceEnabled(SourceType.VOICE, true)
            userPrefsStore.setSourceEnabled(SourceType.MEETING, true)
            onMarkStepStatus(OnboardingStep.RECORDING_FOLDER, StepStatus.GRANTED)
            appRuntimeSyncCoordinator.refresh()
        }
    }

    /** Explicit graceful-skip branch for the recording-folder step. */
    public fun onSkipRecordingFolder() {
        viewModelScope.launch {
            userPrefsStore.setRecordingFolderTreeUri(null)
            userPrefsStore.setSourceEnabled(SourceType.VOICE, false)
            userPrefsStore.setSourceEnabled(SourceType.MEETING, false)
            onSkipStep(OnboardingStep.RECORDING_FOLDER)
            appRuntimeSyncCoordinator.refresh()
        }
    }

    /** Persists optional CallLog matching consent for call-recording person resolution. */
    public fun onCallLogMatchingConsentResult(granted: Boolean) {
        viewModelScope.launch {
            userPrefsStore.setCallLogMatchingConsent(granted)
            onMarkStepStatus(
                OnboardingStep.CALL_LOG_MATCHING,
                if (granted) StepStatus.GRANTED else StepStatus.DENIED,
            )
        }
    }

    /** Explicit graceful-skip branch for CallLog matching. */
    public fun onSkipCallLogMatching() {
        viewModelScope.launch {
            userPrefsStore.setCallLogMatchingConsent(false)
            onSkipStep(OnboardingStep.CALL_LOG_MATCHING)
        }
    }

    /** Records an explicit skip for a calendar step and moves the flow forward. */
    public fun onSkipCalendarSource(provider: CalendarOAuthProvider) {
        viewModelScope.launch {
            userPrefsStore.setSourceEnabled(provider.sourceType, false)
            onSkipStep(provider.step)
        }
    }

    /** Starts the OAuth flow for a source on the unified source-connection screen. */
    public fun onConnectSourceProvider(provider: OnboardingSourceProvider, activity: Activity) {
        provider.emailProvider?.let { emailProvider ->
            onConnectEmailProvider(emailProvider, activity)
            return
        }
        provider.calendarProvider?.let { calendarProvider ->
            onConnectCalendarProvider(calendarProvider, activity)
        }
    }

    /** Re-checks backend OAuth state after the unified source screen resumes. */
    public fun refreshSourceProviderConnection(provider: OnboardingSourceProvider) {
        provider.emailProvider?.let { emailProvider ->
            refreshEmailProviderConnection(emailProvider)
            return
        }
        provider.calendarProvider?.let { calendarProvider ->
            refreshCalendarProviderConnection(calendarProvider)
        }
    }

    /** Gracefully skips a source on the unified source-connection screen. */
    public fun onSkipSourceProvider(provider: OnboardingSourceProvider) {
        viewModelScope.launch {
            val emailProvider = provider.emailProvider
            val calendarProvider = provider.calendarProvider
            when {
                emailProvider != null -> {
                    userPrefsStore.setEmailSourceConnected(emailProvider, false)
                    userPrefsStore.setEmailSourceManagedByBackend(emailProvider, false)
                }
                calendarProvider != null -> userPrefsStore.setSourceEnabled(calendarProvider.sourceType, false)
            }
            onSkipStep(provider.step)
        }
    }

    /**
     * Marks every unfinished source step terminal before leaving the unified source page.
     * IMAP stays available from Settings but is skipped in first-run onboarding.
     */
    public fun onSkipRemainingSourceConnections() {
        val sourceSteps = setOf(
            OnboardingStep.LINK_GMAIL,
            OnboardingStep.LINK_OUTLOOK_MAIL,
            OnboardingStep.LINK_IMAP,
            OnboardingStep.LINK_GOOGLE_CALENDAR,
            OnboardingStep.LINK_OUTLOOK_CALENDAR,
        )
        val terminal = setOf(
            StepStatus.GRANTED,
            StepStatus.COMPLETE,
            StepStatus.SKIPPED,
            StepStatus.DENIED,
        )
        val updates = sourceSteps
            .filter { step -> (_uiState.value.stepStates[step] ?: StepStatus.NOT_STARTED) !in terminal }
            .associateWith { StepStatus.SKIPPED }
        if (updates.isEmpty()) return
        _uiState.update { state ->
            state.copy(stepStates = state.stepStates + updates)
        }
        persistStepStatuses(updates)
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
                CalendarOAuthResult.Connected -> markCalendarProviderConnected(provider)
                CalendarOAuthResult.NotConnected -> Unit
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

    /**
     * Recovers backend-managed calendar OAuth completion after returning from the external
     * browser callback. "Not connected" is ignored because screens call this on every resume.
     */
    public fun refreshCalendarProviderConnection(provider: CalendarOAuthProvider) {
        viewModelScope.launch {
            logger.i(TAG, "calendar OAuth resume refresh start provider=${provider.sourceType}")
            when (val result = calendarOAuthConnector.refreshConnectionStatus(provider)) {
                CalendarOAuthResult.Connected -> {
                    logger.i(TAG, "calendar OAuth resume refresh connected provider=${provider.sourceType}")
                    markCalendarProviderConnected(provider)
                }
                CalendarOAuthResult.NotConnected -> logger.i(
                    TAG,
                    "calendar OAuth resume refresh not connected provider=${provider.sourceType}",
                )
                is CalendarOAuthResult.Failed -> logger.w(
                    TAG,
                    "calendar OAuth status refresh failed provider=${provider.sourceType} error=${result.errorCode}",
                )
            }
        }
    }

    private suspend fun markCalendarProviderConnected(provider: CalendarOAuthProvider) {
        userPrefsStore.setSourceEnabled(provider.sourceType, true)
        onMarkStepStatus(provider.step, StepStatus.COMPLETE)
        appRuntimeSyncCoordinator.refresh()
        refreshSourceStatusAfterBackendSync(provider.sourceType)
        refreshIdentityMirrorsAfterBackendSync(provider.sourceType)
        _calendarConnectEvents.emit(CalendarConnectEvent.Connected(provider))
    }

    // spec: ONB-003, ENR-001, ENR-002
    /** Emits a one-shot request for the system READ_CONTACTS permission dialog. */
    public fun onAllowContacts() {
        _contactsPermissionEffects.tryEmit(ContactsPermissionEffect.RequestSystemPermission)
    }

    /** Records the system permission result and advances to the source connection step. */
    public fun onContactsPermissionResult(granted: Boolean) {
        val status = if (granted) StepStatus.GRANTED else StepStatus.DENIED
        onMarkStepStatus(OnboardingStep.CONTACTS_PERM, status)
        appRuntimeSyncCoordinator.refresh()
        _contactsPermissionEffects.tryEmit(ContactsPermissionEffect.NavigateToSources)
    }

    /** Explicit graceful-skip branch for contacts permission. */
    public fun onSkipContacts() {
        onSkipStep(OnboardingStep.CONTACTS_PERM)
        appRuntimeSyncCoordinator.refresh()
        _contactsPermissionEffects.tryEmit(ContactsPermissionEffect.NavigateToSources)
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

    // spec: ONB-007 — "온보딩 중 OAuth 인증 실패 또는 권한 거부 발생 시
    // onboarding_step_failed 관측 이벤트 전송됨 (step 이름, error 포함)"
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
                persistStepStatus(oauthProvider.step, StepStatus.SKIPPED)
                _emailConnectEvents.emit(EmailConnectEvent.Failed(provider, "pipa_consent_missing"))
                return@launch
            }
            when (val result = emailOAuthConnector.startSignIn(oauthProvider, activity)) {
                EmailOAuthResult.Connected -> markEmailProviderConnected(provider, oauthProvider)
                EmailOAuthResult.NotConnected -> Unit
                is EmailOAuthResult.Failed -> {
                    reportOnboardingStepFailed(oauthProvider.step, result.errorCode)
                    _uiState.update {
                        it.copy(stepStates = it.stepStates + (oauthProvider.step to StepStatus.SKIPPED))
                    }
                    persistStepStatus(oauthProvider.step, StepStatus.SKIPPED)
                    _emailConnectEvents.emit(EmailConnectEvent.Failed(provider, result.errorCode))
                }
            }
        }
    }

    /**
     * Recovers backend-managed email OAuth completion after returning from the external
     * browser callback. This intentionally does not emit failure events for "not connected"
     * because screens call it on every resume.
     */
    public fun refreshEmailProviderConnection(provider: EmailPipaProvider) {
        require(provider == EmailPipaProvider.GMAIL || provider == EmailPipaProvider.OUTLOOK_MAIL) {
            "${provider.storageKey} uses saveImapCredentials(), not refreshEmailProviderConnection()"
        }
        viewModelScope.launch {
            val oauthProvider = when (provider) {
                EmailPipaProvider.GMAIL -> EmailOAuthProvider.GMAIL
                EmailPipaProvider.OUTLOOK_MAIL -> EmailOAuthProvider.OUTLOOK_MAIL
                EmailPipaProvider.NAVER_IMAP,
                EmailPipaProvider.DAUM_IMAP,
                -> error("unreachable")
            }
            if (!userPrefsStore.observeEmailPipaConsent(provider).first()) {
                return@launch
            }
            logger.i(TAG, "email OAuth resume refresh start provider=${provider.storageKey}")
            when (val result = emailOAuthConnector.refreshConnectionStatus(oauthProvider)) {
                EmailOAuthResult.Connected -> {
                    logger.i(TAG, "email OAuth resume refresh connected provider=${provider.storageKey}")
                    markEmailProviderConnected(provider, oauthProvider)
                }
                EmailOAuthResult.NotConnected -> logger.i(
                    TAG,
                    "email OAuth resume refresh not connected provider=${provider.storageKey}",
                )
                is EmailOAuthResult.Failed -> logger.w(
                    TAG,
                    "email OAuth status refresh failed provider=${provider.storageKey} error=${result.errorCode}",
                )
            }
        }
    }

    private suspend fun markEmailProviderConnected(
        provider: EmailPipaProvider,
        oauthProvider: EmailOAuthProvider,
    ) {
        userPrefsStore.setEmailSourceConnected(provider, true)
        userPrefsStore.setEmailSourceManagedByBackend(provider, true)
        onMarkStepStatus(oauthProvider.step, StepStatus.COMPLETE)
        appRuntimeSyncCoordinator.refresh()
        refreshSourceStatusAfterBackendSync(oauthProvider.sourceType)
        refreshIdentityMirrorsAfterBackendSync(oauthProvider.sourceType)
        observability.captureMessage(
            message = "onboarding_email_connected",
            tags = mapOf("provider" to provider.storageKey, "owner" to "backend"),
        )
        _emailConnectEvents.emit(EmailConnectEvent.Connected(provider))
    }

    private suspend fun refreshSourceStatusAfterBackendSync(sourceType: String) {
        when (sourceStatusRepository.refreshFromServer()) {
            is BecalmResult.Success -> Unit
            is BecalmResult.Failure -> {
                logger.w(TAG, "source_status refresh failed after OAuth connect sourceType=$sourceType")
                sourceStatusRepository.recordSyncSuccess(sourceType, Clock.System.now())
            }
        }
    }

    private suspend fun refreshIdentityMirrorsAfterBackendSync(sourceType: String) {
        val userId = userPrefsStore.observeCurrentUserId().first()
        if (userId.isNullOrBlank()) {
            logger.w(TAG, "identity mirror refresh skipped without current user sourceType=$sourceType")
            return
        }
        if (sourceConnectionRepository.refresh(userId) is BecalmResult.Failure) {
            logger.w(TAG, "source_connections refresh failed after OAuth connect sourceType=$sourceType")
        }
        if (selfIdentityRepository.refresh(userId) is BecalmResult.Failure) {
            logger.w(TAG, "self_identity_anchors refresh failed after OAuth connect sourceType=$sourceType")
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
                persistStepStatuses(
                    if (granted) {
                        mapOf(OnboardingStep.PIPA_CONSENT to StepStatus.GRANTED)
                    } else {
                        mapOf(
                            OnboardingStep.PIPA_CONSENT to StepStatus.DENIED,
                            OnboardingStep.RECORDING_FOLDER to StepStatus.SKIPPED,
                            OnboardingStep.CALL_LOG_MATCHING to StepStatus.SKIPPED,
                        )
                    },
                )
                _pipaConsentEvents.emit(PipaConsentEvent.PipaConsentSaved(granted = granted))
            } catch (e: Exception) {
                logger.e(TAG, "$caller: DataStore write failed", e)
                _uiState.update { it.copy(error = UiMessage.resource(R.string.onb_error_consent_write_failed)) }
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
                    it.copy(isCompleting = false, error = UiMessage.resource(R.string.onb_error_complete_steps))
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
                persistStepStatus(OnboardingStep.COLD_SYNC, StepStatus.COMPLETE)
            } catch (e: Exception) {
                logger.e(TAG, "failed to persist onboarding completion", e)
                _uiState.update { it.copy(isCompleting = false, error = UiMessage.resource(R.string.onb_error_completion_failed)) }
            }
        }
    }

    /**
     * Completes the compact first-run setup surface.
     *
     * Terms and login are the only hard gates. Every post-login permission/source is
     * optional at first run and can be repaired from Settings, so unfinished steps are
     * made terminal here instead of forcing one screen per permission before Today.
     * Cold sync is intentionally marked complete so runtime refresh can happen behind
     * the main product surface.
     */
    public fun onCompleteSetup() {
        viewModelScope.launch {
            _uiState.update { it.copy(isCompleting = true, error = null) }
            if (!_uiState.value.selfIdentityConfirmed) {
                _uiState.update {
                    it.copy(
                        isCompleting = false,
                        error = UiMessage.resource(R.string.onb_error_self_identity_required),
                    )
                }
                return@launch
            }
            if (_uiState.value.sourceOwnerships.any { it.ownership == "unknown" }) {
                _uiState.update {
                    it.copy(
                        isCompleting = false,
                        error = UiMessage.resource(R.string.onb_error_source_ownership_required),
                    )
                }
                return@launch
            }
            val current = _uiState.value.stepStates
            val terminal = setOf(
                StepStatus.GRANTED,
                StepStatus.COMPLETE,
                StepStatus.SKIPPED,
                StepStatus.DENIED,
            )
            val updates = OnboardingStep.entries
                .filterNot { it == OnboardingStep.TERMS || it == OnboardingStep.LOGIN }
                .associateWith { step ->
                    when {
                        step == OnboardingStep.COLD_SYNC -> StepStatus.COMPLETE
                        (current[step] ?: StepStatus.NOT_STARTED) in terminal -> current.getValue(step)
                        else -> StepStatus.SKIPPED
                    }
                }
            val nextStates = current + updates
            try {
                _uiState.update { state ->
                    state.copy(stepStates = nextStates)
                }
                persistStepStatuses(updates)
                userPrefsStore.setOnboardingCompleted(true)
                appRuntimeSyncCoordinator.refresh()
                logger.i(TAG, "compact onboarding setup marked complete")
                _uiState.update { it.copy(isCompleting = false, error = null) }
            } catch (e: Exception) {
                logger.e(TAG, "failed to complete compact onboarding setup", e)
                _uiState.update { it.copy(isCompleting = false, error = UiMessage.resource(R.string.onb_error_completion_failed)) }
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

    private fun hydrateDurableProgress() {
        viewModelScope.launch {
            val restored = OnboardingProgressResolver.hydrateStepStates(
                persisted = userPrefsStore.observeOnboardingStepStatuses().first(),
                termsAccepted = userPrefsStore.observeTermsAccepted().first(),
                signedIn = userPrefsStore.observeCurrentUserId().first() != null,
            )
            val firstIncomplete = OnboardingProgressResolver.firstIncompleteStep(restored)
            _uiState.update { state ->
                val inMemoryProgress = state.stepStates.filterValues { it != StepStatus.NOT_STARTED }
                val merged = restored + inMemoryProgress
                val hasUserAction = inMemoryProgress.isNotEmpty()
                state.copy(
                    currentStepIndex = if (hasUserAction) {
                        state.currentStepIndex
                    } else {
                        steps.indexOf(firstIncomplete).coerceAtLeast(0)
                    },
                    stepStates = merged,
                )
            }
        }
    }

    private fun hydrateSelfIdentity() {
        viewModelScope.launch {
            val userId = userPrefsStore.observeCurrentUserId().first()
            if (userId.isNullOrBlank()) return@launch
            val profile = userProfileRepository.find(userId) ?: return@launch
            val anchors = selfIdentityRepository.observeAll(userId).first()
            val displayName = profile.displayNameOverride.orEmpty()
            val phone = profile.phoneE164Self.orEmpty()
            val email = anchors.firstActiveValue("email")
            val alias = anchors.firstActiveValue("alias")
            _uiState.update {
                it.copy(
                    selfDisplayName = displayName,
                    selfEmail = email,
                    selfPhone = phone,
                    selfAlias = alias,
                    selfIdentityConfirmed = listOf(displayName, email, phone, alias).any(String::isNotBlank),
                )
            }
        }
    }

    private fun hydrateSourceOwnerships() {
        viewModelScope.launch {
            val userId = userPrefsStore.observeCurrentUserId().first()
            if (userId.isNullOrBlank()) return@launch
            sourceConnectionRepository.observeAll(userId).collect { connections ->
                _uiState.update { state ->
                    state.copy(sourceOwnerships = connections.map(SourceConnectionEntity::toOnboardingOwnershipUi))
                }
            }
        }
    }

    private fun persistStepStatus(step: OnboardingStep, status: StepStatus) {
        persistStepStatuses(mapOf(step to status))
    }

    private fun persistStepStatuses(statuses: Map<OnboardingStep, StepStatus>) {
        viewModelScope.launch {
            userPrefsStore.setOnboardingStepStatuses(
                OnboardingProgressResolver.encodeStepStatuses(statuses),
            )
        }
    }
}

private fun SourceConnectionEntity.toOnboardingOwnershipUi(): OnboardingSourceOwnershipUi =
    OnboardingSourceOwnershipUi(
        id = id,
        title = sourceConnectionTitle(provider = provider, capability = capability),
        accountLabel = accountDisplayName ?: accountIdentifier ?: provider,
        ownership = ownership,
        status = status,
    )

private val SOURCE_OWNERSHIP_VALUES = setOf("self", "shared", "delegated", "unknown")

private fun List<com.becalm.android.data.local.db.entity.SelfIdentityAnchorEntity>.firstActiveValue(type: String): String =
    firstOrNull { it.anchorType == type && it.status == "active" }?.let { it.displayValue ?: it.normalizedValue }.orEmpty()
