package com.becalm.android.ui.onboarding

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.R
import com.becalm.android.core.observability.ObservabilityClient
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.core.util.PhoneNumberUtils
import com.becalm.android.data.local.datastore.EmailPipaProvider
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.entity.CalendarEventEntity
import com.becalm.android.data.local.db.entity.SourceConnectionEntity
import com.becalm.android.data.local.secure.ImapCredentialStore
import com.becalm.android.data.local.secure.ImapCredentials
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.remote.supabase.SupabaseAuthProvider
import com.becalm.android.data.remote.supabase.SupabaseSessionStore
import com.becalm.android.data.repository.CalendarEventRepository
import com.becalm.android.data.repository.CommitmentParticipantRepository
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.FirstMemoryRepository
import com.becalm.android.data.repository.SelfIdentityRepository
import com.becalm.android.data.repository.OnboardingActivationPreview
import com.becalm.android.data.repository.OnboardingActivationScanSummary
import com.becalm.android.data.repository.OnboardingActivationProgress
import com.becalm.android.data.repository.OnboardingActivationPreviewRepository
import com.becalm.android.data.repository.OnboardingActivationPreviewResult
import com.becalm.android.data.repository.PersonEnrichmentRepository
import com.becalm.android.data.repository.PersonActionMutationSyncStats
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.ScheduleEventLinkRepository
import com.becalm.android.data.repository.SourceConnectionRepository
import com.becalm.android.data.repository.SourceEventParticipantRepository
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.data.repository.UserProfileRepository
import com.becalm.android.domain.onboarding.FirstMemoryDraft
import com.becalm.android.domain.onboarding.FirstMemoryInput
import com.becalm.android.domain.onboarding.FirstMemoryKind
import com.becalm.android.domain.onboarding.FirstMemoryOrigin
import com.becalm.android.domain.onboarding.FirstMemoryValidator
import com.becalm.android.ui.components.UiMessage
import com.becalm.android.ui.sources.sourceConnectionTitle
import com.becalm.android.worker.AppRuntimeSyncCoordinator
import com.becalm.android.worker.WorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

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
    /** Recording audio permission and app-owned path selection milestone. */
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

    /** Authorization completed and BeCalm is now waiting for backend sync + local mirror. */
    public data class Syncing(override val provider: EmailPipaProvider) : EmailConnectEvent()

    /** Browser/OAuth returned without a persisted connection. */
    public data class NotConnected(override val provider: EmailPipaProvider) : EmailConnectEvent()

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

    public data class Syncing(
        override val provider: CalendarOAuthProvider,
    ) : CalendarConnectEvent()

    public data class NotConnected(
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

/** One-shot effects for compact first-run setup completion. */
public sealed interface OnboardingSetupEffect {
    public data object NavigateToPeople : OnboardingSetupEffect
    public data class NavigateToSetupRoute(
        val route: String,
    ) : OnboardingSetupEffect

    public data class NavigateToCompletion(
        val personId: String = ONBOARDING_COMPLETE_GENERIC_PERSON_ID,
    ) : OnboardingSetupEffect
}

public enum class OnboardingSetupStage {
    INTRO,
    READY_TO_START,
    GMAIL_PREVIEW,
    FIRST_MEMORY,
}

public data class OnboardingContactsPreviewUi(
    val totalCount: Int = 0,
    val names: List<String> = emptyList(),
)

public data class OnboardingCalendarPreviewItemUi(
    val dayLabel: String,
    val timeLabel: String,
    val title: String,
)

public data class OnboardingCalendarPreviewUi(
    val loading: Boolean = false,
    val events: List<OnboardingCalendarPreviewItemUi> = emptyList(),
    val failed: Boolean = false,
)

public data class GmailActivationPreviewUi(
    val commitmentId: String,
    val personId: String?,
    val personName: String?,
    val participantId: String?,
    val participantName: String?,
    val participantEmail: String?,
    val participantPhone: String?,
    val contactMatched: Boolean,
    val title: String,
    val itemType: String,
    val direction: String?,
    val scheduleStatus: String?,
    val decisionStatus: String?,
    val dueHint: String?,
    val sourceType: String,
    val sourceTitle: String?,
    val actionItemId: String? = null,
    val actionKind: String? = null,
    val reasonCodes: List<String> = emptyList(),
)

public enum class GmailActivationPreviewStatus {
    Idle,
    Loading,
    Ready,
    Empty,
}

public enum class OnboardingActivationPreviewSourceSet {
    Gmail,
    GoogleCalendar,
    GmailAndGoogleCalendar,
}

public data class OnboardingActivationScanSummaryUi(
    val gmailCount: Int? = null,
    val calendarCount: Int? = null,
)

public data class GmailActivationPreviewUiState(
    val loading: Boolean = false,
    val preview: GmailActivationPreviewUi? = null,
    val previews: List<GmailActivationPreviewUi> = preview?.let { listOf(it) }.orEmpty(),
    val status: GmailActivationPreviewStatus = GmailActivationPreviewStatus.Idle,
    val progress: Float? = null,
    val progressMessage: String? = null,
    val progressStage: String? = null,
    val sourceSet: OnboardingActivationPreviewSourceSet = OnboardingActivationPreviewSourceSet.Gmail,
    val scanSummary: OnboardingActivationScanSummaryUi = OnboardingActivationScanSummaryUi(),
)

internal fun GmailActivationPreviewUiState.canReturnToActivationPreview(): Boolean =
    loading || preview != null || previews.isNotEmpty() || status != GmailActivationPreviewStatus.Idle

internal fun GmailActivationPreviewUiState.hasReadyPreview(): Boolean =
    preview != null || previews.isNotEmpty()

internal fun GmailActivationPreviewUiState.primaryPreview(): GmailActivationPreviewUi? =
    preview ?: previews.firstOrNull()

internal fun OnboardingActivationScanSummaryUi.hasCounts(): Boolean =
    gmailCount != null || calendarCount != null

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
    val setupStage: OnboardingSetupStage = OnboardingSetupStage.INTRO,
    val introPageIndex: Int = 0,
    val selfDisplayName: String = "",
    val selfEmail: String = "",
    val selfPhone: String = "",
    val selfAlias: String = "",
    val selfAuthProvider: SupabaseAuthProvider = SupabaseAuthProvider.EMAIL,
    val selfDisplayNameReadOnly: Boolean = false,
    val selfEmailReadOnly: Boolean = false,
    val selfPhoneReadOnly: Boolean = false,
    val selfPhoneVerified: Boolean = false,
    val selfIdentityConfirmed: Boolean = false,
    val isSavingSelfIdentity: Boolean = false,
    val sourceOwnerships: List<OnboardingSourceOwnershipUi> = emptyList(),
    val sourceOwnershipsLoaded: Boolean = false,
    val sourceOwnershipLoadFailed: Boolean = false,
    val sourceOwnershipActionInProgressIds: Set<String> = emptySet(),
    val callRecordingConnectionState: SourceConnectionState = SourceConnectionState.Idle,
    val contactsPreview: OnboardingContactsPreviewUi = OnboardingContactsPreviewUi(),
    val calendarPreview: OnboardingCalendarPreviewUi = OnboardingCalendarPreviewUi(),
    val gmailActivationPreview: GmailActivationPreviewUiState = GmailActivationPreviewUiState(),
    val firstMemory: FirstMemoryActivationUiState = FirstMemoryActivationUiState(),
    val firstMemoryExitPromptVisible: Boolean = false,
    val isCompleting: Boolean = false,
    val notice: UiMessage? = null,
    val error: UiMessage? = null,
)

// ─── ViewModel ────────────────────────────────────────────────────────────────

private const val TAG = "OnboardingViewModel"
internal const val ONBOARDING_COMPLETE_GENERIC_PERSON_ID = "ready"
internal const val ONBOARDING_INTRO_PAGE_COUNT = 5
internal const val ONBOARDING_READY_STEP_COUNT = ONBOARDING_INTRO_PAGE_COUNT + 1

private data class ConnectedActivationPreviewSources(
    val includeGmail: Boolean,
    val includeGoogleCalendar: Boolean,
) {
    val hasAny: Boolean = includeGmail || includeGoogleCalendar
}

private fun FirstMemoryActivationUiState.toDraft(): FirstMemoryDraft =
    FirstMemoryDraft(
        clientMemoryId = clientMemoryId,
        origin = origin,
        personName = personName,
        promiseText = promiseText,
        kind = kind,
        dueHint = dueHint,
    )

private fun FirstMemoryActivationUiState.hasRecoverableDraft(): Boolean =
    origin != null ||
        personName.isNotBlank() ||
        promiseText.isNotBlank() ||
        kind != null ||
        dueHint.isNotBlank()

private fun OnboardingActivationPreview.toUi(): GmailActivationPreviewUi =
    GmailActivationPreviewUi(
        commitmentId = commitmentId,
        personId = personId,
        personName = personName,
        participantId = participantId,
        participantName = participantName,
        participantEmail = participantEmail,
        participantPhone = participantPhone,
        contactMatched = contactMatched,
        title = title,
        itemType = itemType,
        direction = direction,
        scheduleStatus = scheduleStatus,
        decisionStatus = decisionStatus,
        dueHint = dueHint,
        sourceType = sourceType,
        sourceTitle = sourceTitle,
        actionItemId = actionItemId,
        actionKind = actionKind,
        reasonCodes = reasonCodes,
    )

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
    private val sessionStore: SupabaseSessionStore,
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
    private val calendarEventRepository: CalendarEventRepository,
    private val commitmentRepository: CommitmentRepository,
    private val sourceEventParticipantRepository: SourceEventParticipantRepository,
    private val commitmentParticipantRepository: CommitmentParticipantRepository,
    private val scheduleEventLinkRepository: ScheduleEventLinkRepository,
    private val personEnrichmentRepository: PersonEnrichmentRepository,
    private val firstMemoryRepository: FirstMemoryRepository,
    private val onboardingActivationPreviewRepository: OnboardingActivationPreviewRepository,
    private val rawIngestionRepository: RawIngestionRepository,
    private val workScheduler: WorkScheduler,
) : ViewModel() {

    private val emailActionHandler: OnboardingEmailActionHandler = OnboardingEmailActionHandler(
        userPrefsStore = userPrefsStore,
        imapCredentialStore = imapCredentialStore,
        observability = observability,
        logger = logger,
    )
    private val gmailActivationSyncMutex: Mutex = Mutex()

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

    private val _setupEffects: MutableSharedFlow<OnboardingSetupEffect> = MutableSharedFlow(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Collect in [OnboardingSetupScreen] and navigate only after completion is durable. */
    public val setupEffects: SharedFlow<OnboardingSetupEffect> = _setupEffects.asSharedFlow()

    init {
        hydrateDurableProgress()
        hydrateSelfIdentity()
        hydrateCallRecordingConnection()
        hydrateContactsPreview()
        hydrateCalendarPreview()
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
        if (_uiState.value.selfDisplayNameReadOnly) return
        _uiState.update { it.copy(selfDisplayName = value, selfIdentityConfirmed = false, notice = null) }
    }

    public fun onSelfEmailChange(value: String) {
        if (_uiState.value.selfEmailReadOnly) return
        _uiState.update { it.copy(selfEmail = value, selfIdentityConfirmed = false, notice = null) }
    }

    public fun onSelfPhoneChange(value: String) {
        if (_uiState.value.selfPhoneReadOnly) return
        _uiState.update { it.copy(selfPhone = value, selfIdentityConfirmed = false, notice = null) }
    }

    public fun onSelfAliasChange(value: String) {
        _uiState.update { it.copy(selfAlias = value, selfIdentityConfirmed = false, notice = null) }
    }

    public fun onNoticeDismissed() {
        _uiState.update { it.copy(notice = null) }
    }

    public fun onSetupRouteVisible(route: String?) {
        val destination = OnboardingSetupDestination.fromRoutePath(route) ?: return
        _uiState.update { state ->
            when (destination.setupStage) {
                OnboardingSetupStage.INTRO -> state.copy(
                    setupStage = OnboardingSetupStage.INTRO,
                    introPageIndex = destination.introPageIndex ?: state.introPageIndex,
                    firstMemoryExitPromptVisible = false,
                    notice = null,
                    error = null,
                )
                OnboardingSetupStage.READY_TO_START -> state.copy(
                    setupStage = OnboardingSetupStage.READY_TO_START,
                    introPageIndex = ONBOARDING_INTRO_PAGE_COUNT,
                    firstMemoryExitPromptVisible = false,
                    notice = null,
                    error = null,
                )
                OnboardingSetupStage.GMAIL_PREVIEW -> state.copy(
                    setupStage = OnboardingSetupStage.GMAIL_PREVIEW,
                    introPageIndex = ONBOARDING_INTRO_PAGE_COUNT,
                    firstMemoryExitPromptVisible = false,
                    notice = null,
                    error = null,
                )
                OnboardingSetupStage.FIRST_MEMORY -> state.copy(
                    setupStage = OnboardingSetupStage.FIRST_MEMORY,
                    introPageIndex = ONBOARDING_INTRO_PAGE_COUNT,
                    firstMemoryExitPromptVisible = false,
                    notice = null,
                    error = null,
                )
            }
        }
        persistSetupDestination(destination)
        if (destination.setupStage == OnboardingSetupStage.GMAIL_PREVIEW) {
            hydrateActivationPreviewRouteIfNeeded()
        }
    }

    public fun onIntroNext() {
        var resolvePostIntroDestination = false
        var nextDestination: OnboardingSetupDestination? = null
        _uiState.update { state ->
            if (state.setupStage != OnboardingSetupStage.INTRO) return@update state
            val nextPage = state.introPageIndex + 1
            if (nextPage >= ONBOARDING_INTRO_PAGE_COUNT) {
                resolvePostIntroDestination = true
                state.copy(
                    introPageIndex = ONBOARDING_INTRO_PAGE_COUNT,
                    notice = null,
                    error = null,
                )
            } else {
                nextDestination = OnboardingSetupDestination.fromIntroPageIndex(nextPage)
                state.copy(introPageIndex = nextPage, notice = null, error = null)
            }
        }
        when {
            resolvePostIntroDestination -> {
                routeToReadyToStart()
            }
            nextDestination != null -> {
                emitSetupDestination(requireNotNull(nextDestination))
            }
        }
    }

    public fun onIdentityNext() {
        viewModelScope.launch {
            if (_uiState.value.isSavingSelfIdentity || _uiState.value.isCompleting) return@launch
            val saved = saveSelfIdentityNow()
            if (saved) {
                onIntroNext()
            }
        }
    }

    private fun persistSetupDestination(destination: OnboardingSetupDestination) {
        viewModelScope.launch {
            userPrefsStore.setOnboardingSetupRoute(destination.routePath)
        }
    }

    private fun emitSetupDestination(destination: OnboardingSetupDestination) {
        viewModelScope.launch {
            userPrefsStore.setOnboardingSetupRoute(destination.routePath)
            _setupEffects.emit(OnboardingSetupEffect.NavigateToSetupRoute(destination.routePath))
        }
    }

    private fun emitCurrentSetupDestination() {
        emitSetupDestination(OnboardingSetupDestination.fromState(_uiState.value))
    }

    private fun routeToReadyToStart() {
        _uiState.update {
            it.copy(
                setupStage = OnboardingSetupStage.READY_TO_START,
                introPageIndex = ONBOARDING_INTRO_PAGE_COUNT,
                firstMemoryExitPromptVisible = false,
                notice = null,
                error = null,
            )
        }
        emitSetupDestination(OnboardingSetupDestination.ReadyToStart)
    }

    public fun onStartBeCalmSetup() {
        val activationPreviewSources = _uiState.value.connectedActivationPreviewSources()
        if (activationPreviewSources.hasAny) {
            routeToCachedActivationPreviewOrComplete(activationPreviewSources)
            return
        }
        completeAfterOptionalOnboardingSources()
    }

    private fun completeAfterOptionalOnboardingSources() {
        val state = _uiState.value
        if (!state.hasConnectedOnboardingSource()) {
            _uiState.update {
                it.copy(
                    setupStage = OnboardingSetupStage.FIRST_MEMORY,
                    introPageIndex = ONBOARDING_INTRO_PAGE_COUNT,
                    firstMemoryExitPromptVisible = false,
                    notice = null,
                    error = null,
                )
            }
            emitSetupDestination(OnboardingSetupDestination.FirstMemory)
            return
        }
        if (state.isCompleting) return
        _uiState.update {
            it.copy(
                isCompleting = true,
                error = null,
                notice = null,
            )
        }
        viewModelScope.launch {
            completeFirstMemorySetup {
                _setupEffects.emit(OnboardingSetupEffect.NavigateToPeople)
            }
        }
    }

    private fun routeToCachedActivationPreviewOrComplete(sources: ConnectedActivationPreviewSources) {
        if (_uiState.value.isCompleting || _uiState.value.gmailActivationPreview.loading) return
        viewModelScope.launch {
            if (!gmailActivationSyncMutex.tryLock()) {
                return@launch
            }
            try {
                _uiState.update {
                    val existingSummary = it.gmailActivationPreview.scanSummary
                    it.copy(
                        setupStage = OnboardingSetupStage.READY_TO_START,
                        introPageIndex = ONBOARDING_INTRO_PAGE_COUNT,
                        gmailActivationPreview = GmailActivationPreviewUiState(
                            loading = true,
                            status = GmailActivationPreviewStatus.Loading,
                            progress = 1f,
                            progressMessage = "연결한 기록에서 찾은 다음 행동을 준비하고 있습니다",
                            progressStage = "loading_cached",
                            sourceSet = sources.toPreviewSourceSet(),
                            scanSummary = existingSummary,
                        ),
                        firstMemoryExitPromptVisible = false,
                        notice = null,
                        error = null,
                    )
                }

                val userId = userPrefsStore.observeCurrentUserId().first()
                if (userId.isNullOrBlank()) {
                    completeAfterActivationPreviewUnavailable()
                    return@launch
                }
                val result = try {
                    onboardingActivationPreviewRepository.loadCachedPreview(
                        userId = userId,
                        includeGmail = sources.includeGmail,
                        includeGoogleCalendar = sources.includeGoogleCalendar,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.w(TAG, "cached activation preview load failed", e)
                    OnboardingActivationPreviewResult.Failed(retryable = true)
                }
                when (result) {
                    is OnboardingActivationPreviewResult.Ready -> {
                        val previews = result.previews.map { preview -> preview.toUi() }
                        if (previews.isEmpty()) {
                            completeAfterActivationPreviewUnavailable()
                            return@launch
                        }
                        _uiState.update {
                            val scanSummary = result.scanSummary.toUi()
                                .takeIf { summary -> summary.hasCounts() }
                                ?: it.gmailActivationPreview.scanSummary
                            it.copy(
                                setupStage = OnboardingSetupStage.GMAIL_PREVIEW,
                                gmailActivationPreview = GmailActivationPreviewUiState(
                                    loading = false,
                                    previews = previews,
                                    status = GmailActivationPreviewStatus.Ready,
                                    progress = 1f,
                                    progressMessage = "연결한 자료 확인을 마쳤습니다",
                                    progressStage = "complete",
                                    sourceSet = sources.toPreviewSourceSet(),
                                    scanSummary = scanSummary,
                                ),
                                notice = null,
                                error = null,
                            )
                        }
                        emitSetupDestination(OnboardingSetupDestination.GmailPreview)
                    }
                    OnboardingActivationPreviewResult.Empty,
                    is OnboardingActivationPreviewResult.Pending,
                    is OnboardingActivationPreviewResult.Failed,
                    -> completeAfterActivationPreviewUnavailable()
                }
            } finally {
                gmailActivationSyncMutex.unlock()
            }
        }
    }

    public fun onIntroBack() {
        var previousDestination: OnboardingSetupDestination? = null
        _uiState.update { state ->
            if (state.setupStage != OnboardingSetupStage.INTRO) return@update state
            val previousPage = (state.introPageIndex - 1).coerceAtLeast(0)
            previousDestination = OnboardingSetupDestination.fromIntroPageIndex(previousPage)
            state.copy(introPageIndex = previousPage, notice = null, error = null)
        }
        previousDestination?.let(::emitSetupDestination)
    }

    public fun onSetupBackRequested() {
        var destination: OnboardingSetupDestination? = null
        _uiState.update { state ->
            when (state.setupStage) {
                OnboardingSetupStage.INTRO -> {
                    val previousPage = (state.introPageIndex - 1).coerceAtLeast(0)
                    destination = OnboardingSetupDestination.fromIntroPageIndex(previousPage)
                    state.copy(
                        introPageIndex = previousPage,
                        firstMemoryExitPromptVisible = false,
                        notice = null,
                        error = null,
                    )
                }
                OnboardingSetupStage.READY_TO_START -> {
                    if (state.gmailActivationPreview.loading) {
                        state
                    } else {
                        destination = OnboardingSetupDestination.Email
                        state.copy(
                            setupStage = OnboardingSetupStage.INTRO,
                            introPageIndex = (ONBOARDING_INTRO_PAGE_COUNT - 1).coerceAtLeast(0),
                            firstMemoryExitPromptVisible = false,
                            notice = null,
                            error = null,
                        )
                    }
                }
                OnboardingSetupStage.GMAIL_PREVIEW -> {
                    destination = OnboardingSetupDestination.ReadyToStart
                    state.copy(
                        setupStage = OnboardingSetupStage.READY_TO_START,
                        introPageIndex = ONBOARDING_INTRO_PAGE_COUNT,
                        firstMemoryExitPromptVisible = false,
                        notice = null,
                        error = null,
                    )
                }
                OnboardingSetupStage.FIRST_MEMORY -> {
                    if (state.firstMemory.saving || state.isCompleting) {
                        state
                    } else if (state.firstMemory.hasRecoverableDraft()) {
                        state.copy(firstMemoryExitPromptVisible = true, notice = null, error = null)
                    } else {
                        destination = OnboardingSetupDestination.ReadyToStart
                        state.copy(
                            setupStage = OnboardingSetupStage.READY_TO_START,
                            introPageIndex = ONBOARDING_INTRO_PAGE_COUNT,
                            firstMemoryExitPromptVisible = false,
                            notice = null,
                            error = null,
                        )
                    }
                }
            }
        }
        destination?.let(::emitSetupDestination)
    }

    public fun onKeepFirstMemoryDraft() {
        _uiState.update { state ->
            state.copy(firstMemoryExitPromptVisible = false, notice = null, error = null)
        }
    }

    public fun onDiscardFirstMemoryDraft() {
        _uiState.update { state ->
            state.copy(
                setupStage = OnboardingSetupStage.READY_TO_START,
                introPageIndex = ONBOARDING_INTRO_PAGE_COUNT,
                firstMemory = FirstMemoryActivationUiState(),
                firstMemoryExitPromptVisible = false,
                notice = null,
                error = null,
            )
        }
        emitSetupDestination(OnboardingSetupDestination.ReadyToStart)
    }

    public fun onUseManualFirstMemory() {
        _uiState.update {
            it.copy(
                setupStage = OnboardingSetupStage.FIRST_MEMORY,
                introPageIndex = ONBOARDING_INTRO_PAGE_COUNT,
                gmailActivationPreview = GmailActivationPreviewUiState(),
                firstMemoryExitPromptVisible = false,
                notice = null,
                error = null,
            )
        }
        emitSetupDestination(OnboardingSetupDestination.FirstMemory)
    }

    public fun onReturnToGmailActivationPreview() {
        routeToGmailActivationPreviewIfAvailable()
    }

    private fun routeToGmailActivationPreviewIfAvailable() {
        if (!_uiState.value.gmailActivationPreview.hasReadyPreview()) return
        _uiState.update { state ->
            if (!state.gmailActivationPreview.hasReadyPreview()) {
                state
            } else {
                state.copy(
                    setupStage = OnboardingSetupStage.GMAIL_PREVIEW,
                    introPageIndex = ONBOARDING_INTRO_PAGE_COUNT,
                    firstMemoryExitPromptVisible = false,
                    notice = null,
                    error = null,
                )
            }
        }
        emitSetupDestination(OnboardingSetupDestination.GmailPreview)
    }

    private fun hydrateActivationPreviewRouteIfNeeded() {
        val state = _uiState.value
        if (state.gmailActivationPreview.loading) return
        val sources = state.connectedActivationPreviewSources().takeIf { it.hasAny }
            ?: ConnectedActivationPreviewSources(includeGmail = true, includeGoogleCalendar = true)
        val sourceSet = sources.toPreviewSourceSet()
        if (
            state.gmailActivationPreview.hasReadyPreview() &&
            state.gmailActivationPreview.sourceSet == sourceSet
        ) {
            return
        }
        viewModelScope.launch {
            if (!gmailActivationSyncMutex.tryLock()) return@launch
            try {
                _uiState.update {
                    it.copy(
                        gmailActivationPreview = it.gmailActivationPreview.copy(
                            loading = true,
                            status = GmailActivationPreviewStatus.Loading,
                            progress = 1f,
                            progressMessage = "연결한 기록에서 찾은 다음 행동을 준비하고 있습니다",
                            progressStage = "loading_cached",
                            sourceSet = sourceSet,
                        ),
                        notice = null,
                        error = null,
                    )
                }
                val userId = userPrefsStore.observeCurrentUserId().first()?.trim().orEmpty()
                val result = if (userId.isBlank()) {
                    OnboardingActivationPreviewResult.Empty
                } else {
                    try {
                        onboardingActivationPreviewRepository.loadCachedPreview(
                            userId = userId,
                            includeGmail = sources.includeGmail,
                            includeGoogleCalendar = sources.includeGoogleCalendar,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.w(TAG, "visible activation preview load failed", e)
                        OnboardingActivationPreviewResult.Failed(retryable = true)
                    }
                }
                applyActivationPreviewResult(
                    result = result,
                    sourceSet = sourceSet,
                    showLoadingWhenPending = false,
                )
            } finally {
                gmailActivationSyncMutex.unlock()
            }
        }
    }

    private fun OnboardingUiState.connectedActivationPreviewSources(): ConnectedActivationPreviewSources =
        ConnectedActivationPreviewSources(
            includeGmail = stepStates[OnboardingStep.LINK_GMAIL] == StepStatus.COMPLETE ||
                gmailActivationPreview.canReturnToActivationPreview(),
            includeGoogleCalendar = stepStates[OnboardingStep.LINK_GOOGLE_CALENDAR] == StepStatus.COMPLETE,
        )

    private fun OnboardingUiState.hasConnectedOnboardingSource(): Boolean =
        stepStates[OnboardingStep.LINK_GOOGLE_CALENDAR] == StepStatus.COMPLETE ||
            stepStates[OnboardingStep.LINK_GMAIL] == StepStatus.COMPLETE ||
            gmailActivationPreview.hasReadyPreview() ||
            callRecordingConnectionState == SourceConnectionState.Connected

    private fun ConnectedActivationPreviewSources.activationPreviewLoadingMessage(): String =
        when {
            includeGmail && includeGoogleCalendar -> "Gmail과 Google Calendar를 확인하고 있습니다"
            includeGoogleCalendar -> "Google Calendar 연결을 확인하고 있습니다"
            else -> "Gmail 연결을 확인하고 있습니다"
        }

    private fun ConnectedActivationPreviewSources.toPreviewSourceSet(): OnboardingActivationPreviewSourceSet =
        when {
            includeGmail && includeGoogleCalendar -> OnboardingActivationPreviewSourceSet.GmailAndGoogleCalendar
            includeGoogleCalendar -> OnboardingActivationPreviewSourceSet.GoogleCalendar
            else -> OnboardingActivationPreviewSourceSet.Gmail
        }

    private fun OnboardingActivationScanSummary.toUi(): OnboardingActivationScanSummaryUi =
        OnboardingActivationScanSummaryUi(
            gmailCount = gmailCount,
            calendarCount = calendarCount,
        )

    private fun updateGmailActivationProgress(progress: OnboardingActivationProgress) {
        _uiState.update { state ->
            state.copy(
                gmailActivationPreview = state.gmailActivationPreview.copy(
                    loading = true,
                    status = GmailActivationPreviewStatus.Loading,
                    progress = progress.progress?.toFloat()?.coerceIn(0f, 1f)
                        ?: state.gmailActivationPreview.progress,
                    progressMessage = progress.message,
                    progressStage = progress.stage,
                ),
                notice = null,
                error = null,
            )
        }
    }

    private suspend fun completeAfterActivationPreviewUnavailable() {
        if (_uiState.value.isCompleting) return
        _uiState.update {
            it.copy(
                isCompleting = true,
                gmailActivationPreview = it.gmailActivationPreview.copy(loading = false),
                notice = null,
                error = null,
            )
        }
        completeFirstMemorySetup {
            _setupEffects.emit(OnboardingSetupEffect.NavigateToPeople)
        }
    }

    public fun onUseGmailActivationPreview() {
        val state = _uiState.value
        if (state.isCompleting) return
        if (state.gmailActivationPreview.primaryPreview() == null) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isCompleting = true,
                    gmailActivationPreview = it.gmailActivationPreview.copy(loading = true),
                    error = null,
                    notice = null,
                )
            }
            completeFirstMemorySetup {
                _setupEffects.emit(OnboardingSetupEffect.NavigateToPeople)
            }
        }
    }

    public fun onAcceptGmailActivationPreview(actionItemId: String?) {
        mutateGmailActivationPreviewAction(
            actionItemId = actionItemId,
            successMessageRes = R.string.onb_activation_preview_accept_notice,
            removeOnSuccess = true,
            mutation = { userId, id ->
                onboardingActivationPreviewRepository.acceptPreviewAction(
                    userId = userId,
                    actionItemId = id,
                )
            },
        )
    }

    public fun onDismissGmailActivationPreview(actionItemId: String?) {
        mutateGmailActivationPreviewAction(
            actionItemId = actionItemId,
            successMessageRes = R.string.onb_activation_preview_dismiss_notice,
            removeOnSuccess = true,
            mutation = { userId, id ->
                onboardingActivationPreviewRepository.dismissPreviewAction(
                    userId = userId,
                    actionItemId = id,
                )
            },
        )
    }

    private fun mutateGmailActivationPreviewAction(
        actionItemId: String?,
        successMessageRes: Int,
        removeOnSuccess: Boolean,
        mutation: suspend (String, String) -> BecalmResult<PersonActionMutationSyncStats>,
    ) {
        val normalizedActionItemId = actionItemId?.trim().orEmpty()
        if (normalizedActionItemId.isBlank()) return
        viewModelScope.launch {
            val userId = userPrefsStore.observeCurrentUserId().first()?.trim().orEmpty()
            if (userId.isBlank()) {
                _uiState.update {
                    it.copy(error = UiMessage.resource(R.string.onb_activation_preview_action_failed))
                }
                return@launch
            }
            when (val result = mutation(userId, normalizedActionItemId)) {
                is BecalmResult.Success -> _uiState.update { state ->
                    val gmailActivationPreview = if (removeOnSuccess) {
                        state.gmailActivationPreview.withoutAction(normalizedActionItemId)
                    } else {
                        state.gmailActivationPreview
                    }
                    state.copy(
                        gmailActivationPreview = gmailActivationPreview,
                        notice = UiMessage.resource(successMessageRes),
                        error = null,
                    )
                }
                is BecalmResult.Failure -> {
                    logger.w(TAG, "gmail activation preview action mutation failed: ${result.error}")
                    _uiState.update {
                        it.copy(error = UiMessage.resource(R.string.onb_activation_preview_action_failed))
                    }
                }
            }
        }
    }

    private fun GmailActivationPreviewUiState.withoutAction(actionItemId: String): GmailActivationPreviewUiState {
        val nextPreviews = previews.filterNot { it.actionItemId == actionItemId }
        val nextPreview = preview?.takeUnless { it.actionItemId == actionItemId }
        val hasPreview = nextPreview != null || nextPreviews.isNotEmpty()
        return copy(
            preview = nextPreview,
            previews = nextPreviews,
            status = if (!hasPreview && status == GmailActivationPreviewStatus.Ready) {
                GmailActivationPreviewStatus.Empty
            } else {
                status
            },
        )
    }

    public fun onSaveSelfIdentity() {
        viewModelScope.launch {
            saveSelfIdentityNow()
        }
    }

    private suspend fun saveSelfIdentityNow(): Boolean {
        try {
            val userId = userPrefsStore.observeCurrentUserId().first()
            if (userId.isNullOrBlank()) {
                _uiState.update { it.copy(error = UiMessage.resource(R.string.settings_identity_error_no_user)) }
                return false
            }
            _uiState.update { it.copy(notice = null, error = null) }
            val state = _uiState.value
            if (!isSelfIdentityReady(state)) {
                _uiState.update { it.copy(error = UiMessage.resource(R.string.onb_error_self_identity_required)) }
                return false
            }
            val displayName = state.selfDisplayName.trim()
            val phone = normalizeSelfPhone(state.selfPhone)
            val email = state.selfEmail.trim()
            val alias = state.selfAlias.trim()
            _uiState.update { it.copy(isSavingSelfIdentity = true, error = null) }
            return when (
                val result = selfIdentityRepository.commitOnboardingSelfIdentity(
                    userId = userId,
                    displayName = displayName,
                    displayNameSource = authAwareDisplayNameSource(state),
                    displayNameReadOnly = state.selfDisplayNameReadOnly,
                    email = email.takeIf { it.isNotBlank() },
                    emailReadOnly = state.selfEmailReadOnly,
                    phoneE164 = phone.takeIf { it.isNotBlank() },
                    phoneReadOnly = state.selfPhoneReadOnly,
                    phoneVerified = state.selfPhoneVerified,
                    alias = alias.takeIf { it.isNotBlank() },
                    authProvider = state.selfAuthProvider.wireValue,
                )
            ) {
                is BecalmResult.Success -> {
                    val profile = result.value.profile
                    val anchors = result.value.anchors
                    _uiState.update {
                        it.copy(
                            selfDisplayName = profile.displayNameOverride.orEmpty().ifBlank { displayName },
                            selfEmail = anchors.firstActiveValue("email").ifBlank { email },
                            selfPhone = profile.phoneE164Self.orEmpty().ifBlank { anchors.firstActiveValue("phone").ifBlank { phone } },
                            selfAlias = anchors.firstActiveValue("alias").ifBlank { alias },
                            selfIdentityConfirmed = true,
                            isSavingSelfIdentity = false,
                            notice = UiMessage.resource(R.string.onb_setup_identity_saved),
                            error = null,
                        )
                    }
                    true
                }
                is BecalmResult.Failure -> {
                    logger.w(TAG, "self identity commit failed")
                    _uiState.update {
                        it.copy(
                            selfIdentityConfirmed = false,
                            isSavingSelfIdentity = false,
                            error = UiMessage.resource(R.string.settings_identity_error_save_profile),
                        )
                    }
                    false
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "failed to save onboarding self identity", e)
            _uiState.update {
                it.copy(
                    isSavingSelfIdentity = false,
                    error = UiMessage.resource(R.string.settings_identity_error_save_profile),
                )
            }
            return false
        }
    }

    private fun normalizeSelfPhone(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return ""
        return PhoneNumberUtils.toE164OrNull(trimmed) ?: trimmed
    }

    private fun authAwareDisplayNameSource(state: OnboardingUiState): String =
        when {
            state.selfAuthProvider == SupabaseAuthProvider.GOOGLE && state.selfDisplayNameReadOnly -> DISPLAY_NAME_SOURCE_GOOGLE_AUTH
            else -> DISPLAY_NAME_SOURCE_MANUAL
        }

    /**
     * Persists the current voice-source availability and records the recording-folder step
     * result using the same public state machine as the rest of onboarding.
     */
    public fun onRecordingFolderPermissionResult(
        granted: Boolean,
        targetSourceType: String? = null,
    ) {
        if (granted) return
        viewModelScope.launch {
            setRecordingSourcesEnabled(targetSourceType, enabled = false)
            clearRecordingPathSelections(targetSourceType)
            if (targetSourceType == null) {
                onMarkStepStatus(OnboardingStep.RECORDING_FOLDER, StepStatus.DENIED)
            } else if (targetSourceType == SourceType.CALL_RECORDING) {
                _uiState.update { it.copy(callRecordingConnectionState = SourceConnectionState.Skipped) }
            }
            appRuntimeSyncCoordinator.refresh()
        }
    }

    /** Persists the app-owned MediaStore path preset and enables recording capture. */
    public fun onRecordingPathSelected(targetSourceType: String? = null) {
        viewModelScope.launch {
            try {
                grantRecordingProcessingConsentAndReleaseAwaitingRows()
                if (targetSourceType == null) {
                    userPrefsStore.setRecordingFolderTreeUri(RecordingPathSelection.COMMON)
                    recordingSourceTypesFor(null).forEach { sourceType ->
                        userPrefsStore.setRecordingFolderTreeUri(
                            sourceType,
                            RecordingPathSelection.forSourceType(sourceType),
                        )
                    }
                } else {
                    userPrefsStore.setRecordingFolderTreeUri(
                        targetSourceType,
                        RecordingPathSelection.forSourceType(targetSourceType),
                    )
                }
                setRecordingSourcesEnabled(targetSourceType, enabled = true)
                _uiState.update { state ->
                    val updates = buildMap {
                        put(OnboardingStep.PIPA_CONSENT, StepStatus.GRANTED)
                        if (targetSourceType == null) {
                            put(OnboardingStep.RECORDING_FOLDER, StepStatus.GRANTED)
                        }
                    }
                    state.copy(
                        stepStates = state.stepStates + updates,
                        callRecordingConnectionState = if (targetSourceType == SourceType.CALL_RECORDING) {
                            SourceConnectionState.Connected
                        } else {
                            state.callRecordingConnectionState
                        },
                    )
                }
                persistStepStatuses(
                    buildMap {
                        put(OnboardingStep.PIPA_CONSENT, StepStatus.GRANTED)
                        if (targetSourceType == null) {
                            put(OnboardingStep.RECORDING_FOLDER, StepStatus.GRANTED)
                        }
                    },
                )
                appRuntimeSyncCoordinator.refresh()
                workScheduler.enqueueMediaStoreOneShotNow(RECORDING_GRANT_LOOKBACK_DAYS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(TAG, "recording path selection failed", e)
                _uiState.update { it.copy(error = UiMessage.resource(R.string.onb_error_consent_write_failed)) }
            }
        }
    }

    private suspend fun grantRecordingProcessingConsentAndReleaseAwaitingRows() {
        userPrefsStore.setThirdPartyProvisionConsent(true)
        val userId = userPrefsStore.observeCurrentUserId().first()
        if (userId.isNullOrBlank()) {
            logger.w(TAG, "recording consent grant skipped awaiting release without current user")
            return
        }
        when (val result = rawIngestionRepository.releaseAwaitingConsentVoiceAndReturnIds(userId)) {
            is BecalmResult.Failure -> {
                logger.e(TAG, "releaseAwaitingConsentVoiceAndReturnIds failed: ${result.error}")
                _uiState.update { it.copy(error = UiMessage.resource(R.string.settings_error_voice_release_failed)) }
            }
            is BecalmResult.Success -> {
                val enqueuedCount = reenqueueReleasedRecordingRows(userId, result.value)
                logger.d(TAG, "re-enqueued $enqueuedCount recording jobs after onboarding consent grant")
            }
        }
    }

    private suspend fun reenqueueReleasedRecordingRows(userId: String, releasedIds: List<String>): Int {
        var enqueuedCount = 0
        for (id in releasedIds) {
            val entity = rawIngestionRepository.findById(id = id, userId = userId) ?: continue
            val sourceRef = entity.sourceRef.takeUnless { it.isNullOrBlank() } ?: continue
            when (entity.sourceType) {
                SourceType.MESSAGE_SCREENSHOT -> {
                    workScheduler.enqueueMessageScreenshotUpload(rawEventId = id)
                }
                SourceType.CALL_RECORDING,
                SourceType.MEETING,
                -> {
                    workScheduler.enqueueMeetingSpeakerPreview(rawEventId = id, audioUri = sourceRef)
                }
                else -> {
                    workScheduler.enqueueVoiceUpload(rawEventId = id, audioUri = sourceRef)
                }
            }
            enqueuedCount++
        }
        return enqueuedCount
    }

    /** Explicit graceful-skip branch for the recording-folder step. */
    public fun onSkipRecordingFolder(targetSourceType: String? = null) {
        viewModelScope.launch {
            setRecordingSourcesEnabled(targetSourceType, enabled = false)
            clearRecordingPathSelections(targetSourceType)
            if (targetSourceType == null) {
                onSkipStep(OnboardingStep.RECORDING_FOLDER)
            } else if (targetSourceType == SourceType.CALL_RECORDING) {
                _uiState.update { it.copy(callRecordingConnectionState = SourceConnectionState.Skipped) }
            }
            appRuntimeSyncCoordinator.refresh()
        }
    }

    private suspend fun setRecordingSourcesEnabled(targetSourceType: String?, enabled: Boolean) {
        recordingSourceTypesFor(targetSourceType).forEach { sourceType ->
            userPrefsStore.setSourceEnabled(sourceType, enabled)
        }
    }

    private suspend fun clearRecordingPathSelections(targetSourceType: String?) {
        if (targetSourceType == null) {
            userPrefsStore.setRecordingFolderTreeUri(null)
        }
        recordingSourceTypesFor(targetSourceType).forEach { sourceType ->
            userPrefsStore.setRecordingFolderTreeUri(sourceType, null)
        }
    }

    private fun recordingSourceTypesFor(targetSourceType: String?): Set<String> =
        when (targetSourceType) {
            SourceType.VOICE,
            SourceType.CALL_RECORDING,
            SourceType.MEETING,
            -> setOf(targetSourceType)
            else -> RECORDING_SOURCE_TYPES
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
    public fun onConnectSourceProvider(
        provider: OnboardingSourceProvider,
        activity: Activity,
        sourceConnectionId: String? = null,
    ) {
        provider.emailProvider?.let { emailProvider ->
            onConnectEmailProvider(emailProvider, activity, sourceConnectionId = sourceConnectionId)
            return
        }
        provider.calendarProvider?.let { calendarProvider ->
            onConnectCalendarProvider(calendarProvider, activity, sourceConnectionId = sourceConnectionId)
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

    public fun onDeleteSourceConnection(connectionId: String) {
        val normalizedId = connectionId.trim()
        if (normalizedId.isBlank()) return
        if (normalizedId in _uiState.value.sourceOwnershipActionInProgressIds) return
        viewModelScope.launch {
            val userId = userPrefsStore.observeCurrentUserId().first()
            if (userId.isNullOrBlank()) {
                _uiState.update { it.copy(error = UiMessage.resource(R.string.settings_identity_error_no_user)) }
                return@launch
            }
            _uiState.update {
                it.copy(
                    sourceOwnershipActionInProgressIds = it.sourceOwnershipActionInProgressIds + normalizedId,
                    error = null,
                    notice = null,
                )
            }
            try {
                when (sourceConnectionRepository.deleteConnection(userId, normalizedId)) {
                    is BecalmResult.Success -> {
                        sourceStatusRepository.refreshFromServer()
                        _uiState.update {
                            it.copy(
                                sourceOwnerships = it.sourceOwnerships.filterNot { ownership -> ownership.id == normalizedId },
                                sourceOwnershipActionInProgressIds = it.sourceOwnershipActionInProgressIds - normalizedId,
                                error = null,
                            )
                        }
                    }
                    is BecalmResult.Failure -> {
                        _uiState.update {
                            it.copy(
                                sourceOwnershipActionInProgressIds = it.sourceOwnershipActionInProgressIds - normalizedId,
                                error = UiMessage.resource(R.string.settings_identity_error_delete_connection),
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w(TAG, "delete source connection failed", e)
                _uiState.update {
                    it.copy(
                        sourceOwnershipActionInProgressIds = it.sourceOwnershipActionInProgressIds - normalizedId,
                        error = UiMessage.resource(R.string.settings_identity_error_delete_connection),
                    )
                }
            }
        }
    }

    /**
     * Initiates an interactive calendar OAuth sign-in for [provider].
     *
     * The current production connector intentionally fails closed until the real provider SDK
     * is wired. This removes the previous fake-success path while keeping a stable unit-testable
     * contract for future external integration.
     */
    public fun onConnectCalendarProvider(
        provider: CalendarOAuthProvider,
        activity: Activity,
        sourceConnectionId: String? = null,
    ) {
        viewModelScope.launch {
            when (val result = calendarOAuthConnector.startSignIn(provider, activity, sourceConnectionId)) {
                CalendarOAuthResult.Connected -> markCalendarProviderConnected(provider)
                CalendarOAuthResult.NotConnected -> {
                    _calendarConnectEvents.emit(CalendarConnectEvent.NotConnected(provider))
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

    /**
     * Recovers backend-managed calendar OAuth completion after returning from the external
     * browser callback. "Not connected" clears the UI's pending external-auth state so an
     * OAuth cancel returns to the same onboarding step instead of staying in a spinner.
     */
    public fun refreshCalendarProviderConnection(provider: CalendarOAuthProvider) {
        viewModelScope.launch {
            logger.i(TAG, "calendar OAuth resume refresh start provider=${provider.sourceType}")
            when (val result = calendarOAuthConnector.refreshConnectionStatus(provider)) {
                CalendarOAuthResult.Connected -> {
                    logger.i(TAG, "calendar OAuth resume refresh connected provider=${provider.sourceType}")
                    markCalendarProviderConnected(provider)
                }
                CalendarOAuthResult.NotConnected -> {
                    if (_uiState.value.stepStates[provider.step] == StepStatus.IN_PROGRESS) {
                        onMarkStepStatus(provider.step, StepStatus.NOT_STARTED)
                    }
                    logger.i(
                        TAG,
                        "calendar OAuth resume refresh not connected provider=${provider.sourceType}",
                    )
                    _calendarConnectEvents.emit(CalendarConnectEvent.NotConnected(provider))
                }
                is CalendarOAuthResult.Failed -> {
                    logger.w(
                        TAG,
                        "calendar OAuth status refresh failed provider=${provider.sourceType} error=${result.errorCode}",
                    )
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

    private suspend fun markCalendarProviderConnected(provider: CalendarOAuthProvider) {
        userPrefsStore.setSourceEnabled(provider.sourceType, true)
        onMarkStepStatus(provider.step, StepStatus.IN_PROGRESS)
        _calendarConnectEvents.emit(CalendarConnectEvent.Syncing(provider))
        appRuntimeSyncCoordinator.refresh()
        when (refreshCalendarSourceDataFromServer(provider.sourceType)) {
            CalendarSourceRefreshResult.Ready -> {
                onMarkStepStatus(provider.step, StepStatus.COMPLETE)
                refreshCachedActivationPreviewAfterSourceSync()
                refreshSourceStatusAfterBackendSync(provider.sourceType)
                refreshIdentityMirrorsAfterBackendSync(provider.sourceType)
                _calendarConnectEvents.emit(CalendarConnectEvent.Connected(provider))
            }
            CalendarSourceRefreshResult.Pending -> {
                logger.i(TAG, "calendar source sync still pending provider=${provider.sourceType}")
                onMarkStepStatus(provider.step, StepStatus.COMPLETE)
                refreshSourceStatusAfterBackendSync(provider.sourceType)
                _calendarConnectEvents.emit(CalendarConnectEvent.Connected(provider))
            }
            CalendarSourceRefreshResult.Failed -> {
                onMarkStepStatus(provider.step, StepStatus.NOT_STARTED)
                reportOnboardingStepFailed(provider.step, "calendar_sync_failed")
                _calendarConnectEvents.emit(
                    CalendarConnectEvent.Failed(
                        provider = provider,
                        errorCode = "calendar_sync_failed",
                    ),
                )
            }
        }
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
        viewModelScope.launch {
            userPrefsStore.setContactsConsent(granted)
            if (granted) {
                workScheduler.enqueueEnrichment()
            }
            appRuntimeSyncCoordinator.refresh()
        }
        _contactsPermissionEffects.tryEmit(ContactsPermissionEffect.NavigateToSources)
    }

    /** Explicit graceful-skip branch for contacts permission. */
    public fun onSkipContacts() {
        onSkipStep(OnboardingStep.CONTACTS_PERM)
        viewModelScope.launch {
            userPrefsStore.setContactsConsent(false)
            appRuntimeSyncCoordinator.refresh()
        }
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
     * [StepStatus.COMPLETE], and emits [EmailConnectEvent.Connected]. Non-auth failures emit
     * [EmailConnectEvent.Failed] and mark the step [StepStatus.SKIPPED] so the ONB-008
     * terminal gate accepts the flow. Auth-boundary failures keep the step retryable because
     * they mean the session must be recovered, not that the user chose to skip the source.
     *
     * [provider] must be [EmailPipaProvider.GMAIL] or [EmailPipaProvider.OUTLOOK_MAIL]
     * — IMAP is credential-based and uses [saveImapCredentials] instead.
     *
     * @param provider Target email provider.
     * @param activity The foreground activity; required so [EmailOAuthConnector] can
     *   launch the external browser flow for the backend-managed OAuth callback path.
     */
    public fun onConnectEmailProvider(
        provider: EmailPipaProvider,
        activity: Activity,
        sourceConnectionId: String? = null,
    ) {
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
            when (val result = emailOAuthConnector.startSignIn(oauthProvider, activity, sourceConnectionId)) {
                EmailOAuthResult.Connected -> markEmailProviderConnected(provider, oauthProvider)
                EmailOAuthResult.NotConnected -> {
                    _emailConnectEvents.emit(EmailConnectEvent.NotConnected(provider))
                }
                is EmailOAuthResult.Failed -> {
                    reportOnboardingStepFailed(oauthProvider.step, result.errorCode)
                    val failureStatus = if (result.errorCode.isAuthBoundaryOAuthError()) {
                        StepStatus.NOT_STARTED
                    } else {
                        StepStatus.SKIPPED
                    }
                    _uiState.update {
                        it.copy(stepStates = it.stepStates + (oauthProvider.step to failureStatus))
                    }
                    persistStepStatus(oauthProvider.step, failureStatus)
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
                EmailOAuthResult.NotConnected -> {
                    logger.i(
                        TAG,
                        "email OAuth resume refresh not connected provider=${provider.storageKey}",
                    )
                    if (_uiState.value.stepStates[oauthProvider.step] == StepStatus.IN_PROGRESS) {
                        onMarkStepStatus(oauthProvider.step, StepStatus.NOT_STARTED)
                    }
                    _emailConnectEvents.emit(EmailConnectEvent.NotConnected(provider))
                }
                is EmailOAuthResult.Failed -> {
                    logger.w(
                        TAG,
                        "email OAuth status refresh failed provider=${provider.storageKey} error=${result.errorCode}",
                    )
                    reportOnboardingStepFailed(oauthProvider.step, result.errorCode)
                    _emailConnectEvents.emit(EmailConnectEvent.Failed(provider, result.errorCode))
                }
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
        if (provider == EmailPipaProvider.GMAIL) {
            _emailConnectEvents.emit(EmailConnectEvent.Syncing(provider))
        }
        _uiState.update {
            it.copy(
                gmailActivationPreview = if (provider == EmailPipaProvider.GMAIL &&
                    it.gmailActivationPreview.loading
                ) {
                    GmailActivationPreviewUiState()
                } else {
                    it.gmailActivationPreview
                },
                notice = null,
                error = null,
            )
        }
        appRuntimeSyncCoordinator.refresh()
        if (provider == EmailPipaProvider.GMAIL) {
            refreshGmailActivationPreviewFromServer()
        }
        refreshSourceStatusAfterBackendSync(oauthProvider.sourceType)
        refreshIdentityMirrorsAfterBackendSync(oauthProvider.sourceType)
        observability.captureMessage(
            message = "onboarding_email_connected",
            tags = mapOf("provider" to provider.storageKey, "owner" to "backend"),
        )
        _emailConnectEvents.emit(EmailConnectEvent.Connected(provider))
    }

    private suspend fun refreshGmailActivationPreviewFromServer() {
        val userId = userPrefsStore.observeCurrentUserId().first()?.trim().orEmpty()
        if (userId.isBlank()) return
        val sourceSet = _uiState.value.connectedActivationPreviewSources().toPreviewSourceSet()
        _uiState.update {
            it.copy(
                gmailActivationPreview = GmailActivationPreviewUiState(
                    loading = true,
                    status = GmailActivationPreviewStatus.Loading,
                    progress = 0.08f,
                    progressMessage = "최근 Gmail 메일을 정리하고 있습니다",
                    progressStage = "queued",
                    sourceSet = sourceSet,
                ),
                notice = null,
                error = null,
            )
        }
        val result = withTimeoutOrNull(ONBOARDING_PREVIEW_TIMEOUT_MS) {
            onboardingActivationPreviewRepository.syncConnectedSourcesAndLoadPreview(
                userId = userId,
                includeGmail = true,
                includeGoogleCalendar = false,
                onProgress = { progress -> updateGmailActivationProgress(progress) },
            )
        } ?: OnboardingActivationPreviewResult.Pending(
            progress = OnboardingActivationProgress(
                stage = "background_sync",
                progress = 0.65,
                message = "Gmail 정리가 백그라운드에서 이어지고 있습니다",
            ),
        )
        applyActivationPreviewResult(
            result = result,
            sourceSet = sourceSet,
            showLoadingWhenPending = true,
        )
    }

    private suspend fun refreshCachedActivationPreviewAfterSourceSync() {
        val userId = userPrefsStore.observeCurrentUserId().first()?.trim().orEmpty()
        if (userId.isBlank()) return
        val sourceSet = _uiState.value.connectedActivationPreviewSources().toPreviewSourceSet()
        val result = try {
            onboardingActivationPreviewRepository.refreshCachedPreview(
                userId = userId,
                includeGmail = sourceSet != OnboardingActivationPreviewSourceSet.GoogleCalendar,
                includeGoogleCalendar = sourceSet != OnboardingActivationPreviewSourceSet.Gmail,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "cached activation preview refresh failed", e)
            OnboardingActivationPreviewResult.Failed(retryable = true)
        }
        applyActivationPreviewResult(
            result = result,
            sourceSet = sourceSet,
            showLoadingWhenPending = false,
        )
    }

    private fun applyActivationPreviewResult(
        result: OnboardingActivationPreviewResult,
        sourceSet: OnboardingActivationPreviewSourceSet,
        showLoadingWhenPending: Boolean,
    ) {
        when (result) {
            is OnboardingActivationPreviewResult.Ready -> {
                val previews = result.previews.map { preview -> preview.toUi() }
                _uiState.update {
                    val scanSummary = result.scanSummary.toUi()
                        .takeIf { summary -> summary.hasCounts() }
                        ?: it.gmailActivationPreview.scanSummary
                    it.copy(
                        gmailActivationPreview = GmailActivationPreviewUiState(
                            loading = false,
                            previews = previews,
                            status = if (previews.isEmpty()) {
                                GmailActivationPreviewStatus.Empty
                            } else {
                                GmailActivationPreviewStatus.Ready
                            },
                            progress = 1f,
                            progressMessage = "연결한 자료 확인을 마쳤습니다",
                            progressStage = "complete",
                            sourceSet = sourceSet,
                            scanSummary = scanSummary,
                        ),
                        notice = null,
                        error = null,
                    )
                }
            }
            OnboardingActivationPreviewResult.Empty,
            is OnboardingActivationPreviewResult.Failed,
            -> _uiState.update {
                it.copy(
                    gmailActivationPreview = it.gmailActivationPreview.copy(
                        loading = false,
                        status = GmailActivationPreviewStatus.Empty,
                        progress = 1f,
                        progressMessage = null,
                        progressStage = "complete",
                        sourceSet = sourceSet,
                    ),
                    notice = null,
                    error = null,
                )
            }
            is OnboardingActivationPreviewResult.Pending -> _uiState.update {
                it.copy(
                    gmailActivationPreview = it.gmailActivationPreview.copy(
                        loading = showLoadingWhenPending,
                        status = if (showLoadingWhenPending) {
                            GmailActivationPreviewStatus.Loading
                        } else {
                            GmailActivationPreviewStatus.Empty
                        },
                        progress = result.progress?.progress?.toFloat()?.coerceIn(0f, 1f)
                            ?: it.gmailActivationPreview.progress,
                        progressMessage = result.progress?.message,
                        progressStage = result.progress?.stage,
                        sourceSet = sourceSet,
                        scanSummary = result.scanSummary.toUi()
                            .takeIf { summary -> summary.hasCounts() }
                            ?: it.gmailActivationPreview.scanSummary,
                    ),
                    notice = null,
                    error = null,
                )
            }
        }
    }

    private suspend fun refreshSourceStatusAfterBackendSync(sourceType: String) {
        when (sourceStatusRepository.refreshFromServer()) {
            is BecalmResult.Success -> Unit
            is BecalmResult.Failure -> {
                logger.w(TAG, "source_status refresh failed after OAuth connect sourceType=$sourceType")
            }
        }
        sourceStatusRepository.recordSyncSuccess(sourceType, Clock.System.now())
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
                onSaved = {
                    sourceStatusRepository.clear(sourceType)
                    appRuntimeSyncCoordinator.refresh()
                    workScheduler.enqueueExpedited(sourceType)
                },
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
                _uiState.update { state ->
                    state.copy(
                        isCompleting = false,
                        stepStates = state.stepStates + (OnboardingStep.COLD_SYNC to StepStatus.COMPLETE),
                    )
                }
                persistStepStatusesNow(mapOf(OnboardingStep.COLD_SYNC to StepStatus.COMPLETE))
                if (!markOnboardingCompletedNow()) {
                    _uiState.update {
                        it.copy(isCompleting = false, error = UiMessage.resource(R.string.onb_error_completion_failed))
                    }
                    return@launch
                }
                logger.i(TAG, "onboarding marked complete")
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
            if (!_uiState.value.selfIdentityConfirmed || !isSelfIdentityReady(_uiState.value)) {
                _uiState.update {
                    it.copy(
                        isCompleting = false,
                        error = UiMessage.resource(R.string.onb_error_self_identity_required),
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
                persistStepStatusesNow(updates)
                if (!markOnboardingCompletedNow()) {
                    _uiState.update {
                        it.copy(isCompleting = false, error = UiMessage.resource(R.string.onb_error_completion_failed))
                    }
                    return@launch
                }
                appRuntimeSyncCoordinator.refresh()
                logger.i(TAG, "compact onboarding setup marked complete")
                _uiState.update { it.copy(isCompleting = false, error = null) }
                _setupEffects.emit(OnboardingSetupEffect.NavigateToCompletion())
            } catch (e: Exception) {
                logger.e(TAG, "failed to complete compact onboarding setup", e)
                _uiState.update { it.copy(isCompleting = false, error = UiMessage.resource(R.string.onb_error_completion_failed)) }
            }
        }
    }

    public fun onFirstMemoryOriginChange(origin: FirstMemoryOrigin) {
        _uiState.update { state ->
            state.copy(
                firstMemory = state.firstMemory.copy(origin = origin, errorMessageRes = null),
                firstMemoryExitPromptVisible = false,
            )
        }
    }

    public fun onFirstMemoryPersonNameChange(value: String) {
        _uiState.update { state ->
            state.copy(
                firstMemory = state.firstMemory.copy(personName = value, errorMessageRes = null),
                firstMemoryExitPromptVisible = false,
            )
        }
    }

    public fun onFirstMemoryPromiseTextChange(value: String) {
        _uiState.update { state ->
            state.copy(
                firstMemory = state.firstMemory.copy(promiseText = value, errorMessageRes = null),
                firstMemoryExitPromptVisible = false,
            )
        }
    }

    public fun onFirstMemoryKindChange(kind: FirstMemoryKind) {
        _uiState.update { state ->
            val dueHint = if (kind == FirstMemoryKind.SHARED_SCHEDULE) state.firstMemory.dueHint else ""
            state.copy(
                firstMemory = state.firstMemory.copy(kind = kind, dueHint = dueHint, errorMessageRes = null),
                firstMemoryExitPromptVisible = false,
            )
        }
    }

    public fun onFirstMemoryDueHintChange(value: String) {
        _uiState.update { state ->
            state.copy(
                firstMemory = state.firstMemory.copy(dueHint = value, errorMessageRes = null),
                firstMemoryExitPromptVisible = false,
            )
        }
    }

    public fun onSaveFirstMemory() {
        if (_uiState.value.firstMemory.saving || _uiState.value.isCompleting) return
        val draft = _uiState.value.firstMemory.toDraft()
        when (val validation = FirstMemoryValidator.validate(draft)) {
            is FirstMemoryValidator.ValidationResult.Err -> {
                _uiState.update { state ->
                    state.copy(
                        firstMemory = state.firstMemory.copy(errorMessageRes = R.string.first_memory_error_required),
                    )
                }
            }
            is FirstMemoryValidator.ValidationResult.Ok -> saveFirstMemory(validation.input)
        }
    }

    public fun onSkipFirstMemory() {
        if (_uiState.value.firstMemory.saving || _uiState.value.isCompleting) return
        _uiState.update {
            it.copy(
                isCompleting = true,
                firstMemory = it.firstMemory.copy(saving = true, errorMessageRes = null),
                firstMemoryExitPromptVisible = false,
                error = null,
            )
        }
        viewModelScope.launch {
            completeFirstMemorySetup {
                _setupEffects.emit(OnboardingSetupEffect.NavigateToCompletion())
            }
        }
    }

    private fun saveFirstMemory(input: FirstMemoryInput) {
        _uiState.update {
            it.copy(
                isCompleting = true,
                firstMemory = it.firstMemory.copy(saving = true, errorMessageRes = null),
                firstMemoryExitPromptVisible = false,
                error = null,
            )
        }
        viewModelScope.launch {
            when (val result = firstMemoryRepository.save(input)) {
                is BecalmResult.Success -> {
                    completeFirstMemorySetup {
                        _setupEffects.emit(OnboardingSetupEffect.NavigateToCompletion(result.value.personId))
                    }
                }
                is BecalmResult.Failure -> {
                    val errorRes = when (result.error) {
                        is com.becalm.android.core.result.BecalmError.Unauthorized ->
                            R.string.first_memory_error_signed_out
                        else -> R.string.first_memory_error_save_failed
                    }
                    _uiState.update {
                        it.copy(
                            isCompleting = false,
                            firstMemory = it.firstMemory.copy(saving = false, errorMessageRes = errorRes),
                        )
                    }
                }
            }
        }
    }

    private suspend fun completeFirstMemorySetup(afterComplete: suspend () -> Unit) {
        val current = _uiState.value.stepStates
        val updates = OnboardingStep.entries
            .filterNot { it == OnboardingStep.TERMS || it == OnboardingStep.LOGIN }
            .associateWith { step ->
                when (step) {
                    OnboardingStep.COLD_SYNC -> StepStatus.COMPLETE
                    else -> current[step]?.takeIf { status ->
                        status in setOf(
                            StepStatus.GRANTED,
                            StepStatus.COMPLETE,
                            StepStatus.SKIPPED,
                            StepStatus.DENIED,
                        )
                    } ?: StepStatus.SKIPPED
                }
            }
        val nextStates = current + updates
        try {
            _uiState.update { state -> state.copy(stepStates = nextStates) }
            persistStepStatusesNow(updates)
            if (!markOnboardingCompletedNow()) {
                _uiState.update {
                    it.copy(
                        isCompleting = false,
                        firstMemory = it.firstMemory.copy(
                            saving = false,
                            errorMessageRes = R.string.first_memory_error_save_failed,
                        ),
                        error = UiMessage.resource(R.string.onb_error_completion_failed),
                    )
                }
                return
            }
            appRuntimeSyncCoordinator.refresh()
            logger.i(TAG, "first memory onboarding setup marked complete")
            _uiState.update {
                it.copy(
                    isCompleting = false,
                    firstMemory = it.firstMemory.copy(saving = false, errorMessageRes = null),
                    error = null,
                )
            }
            afterComplete()
        } catch (e: Exception) {
            logger.e(TAG, "failed to complete first memory onboarding setup", e)
            _uiState.update {
                it.copy(
                    isCompleting = false,
                    firstMemory = it.firstMemory.copy(
                        saving = false,
                        errorMessageRes = R.string.first_memory_error_save_failed,
                    ),
                )
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

    private fun isSelfIdentityReady(state: OnboardingUiState): Boolean =
        state.selfDisplayName.isNotBlank() &&
            listOf(state.selfEmail, state.selfPhone).any { it.isNotBlank() }

    private suspend fun markOnboardingCompletedNow(): Boolean {
        val userId = userPrefsStore.observeCurrentUserId().first()
        if (userId.isNullOrBlank()) {
            logger.w(TAG, "onboarding completion skipped without current user")
            return false
        }
        return when (userProfileRepository.markOnboardingCompleted(userId)) {
            is BecalmResult.Success -> {
                userPrefsStore.setOnboardingCompleted(true)
                true
            }
            is BecalmResult.Failure -> {
                logger.w(TAG, "server onboarding completion patch failed")
                false
            }
        }
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
            try {
                val userId = userPrefsStore.observeCurrentUserId().first()
                if (userId.isNullOrBlank()) return@launch
                val session = sessionStore.load()
                val profile = userProfileRepository.find(userId)
                val anchors = selfIdentityRepository.observeAll(userId).first()
                val authProvider = session?.authProvider ?: SupabaseAuthProvider.EMAIL
                val authEmail = session?.email?.trim().orEmpty()
                val authPhone = session?.phone?.trim().orEmpty()
                val authName = session?.profileName?.trim().orEmpty()
                val email = when {
                    authProvider in setOf(SupabaseAuthProvider.EMAIL, SupabaseAuthProvider.GOOGLE) &&
                        authEmail.isNotBlank() -> authEmail
                    else -> anchors.firstActiveValue("email")
                }
                val phone = when {
                    authProvider == SupabaseAuthProvider.PHONE && authPhone.isNotBlank() -> authPhone
                    else -> profile?.phoneE164Self?.takeIf { it.isNotBlank() } ?: anchors.firstActiveValue("phone")
                }
                val profileDisplayName = profile?.displayNameOverride?.takeIf { it.isNotBlank() }
                val displayNameReadOnly = authProvider == SupabaseAuthProvider.GOOGLE &&
                    authName.isNotBlank() &&
                    (profileDisplayName == null || profile?.displayNameSource == DISPLAY_NAME_SOURCE_GOOGLE_AUTH)
                val displayName = if (displayNameReadOnly) {
                    authName
                } else {
                    profileDisplayName ?: authName
                }
                val alias = anchors.firstActiveValue("alias")
                val emailReadOnly = authProvider in setOf(SupabaseAuthProvider.EMAIL, SupabaseAuthProvider.GOOGLE) &&
                    authEmail.isNotBlank()
                val phoneReadOnly = authProvider == SupabaseAuthProvider.PHONE && authPhone.isNotBlank()
                _uiState.update { current ->
                    val resolvedDisplayName = current.selfDisplayName.ifBlank { displayName }
                    val resolvedEmail = if (emailReadOnly) {
                        email
                    } else {
                        current.selfEmail.ifBlank { email }
                    }
                    val resolvedPhone = if (phoneReadOnly) {
                        phone
                    } else {
                        current.selfPhone.ifBlank { phone }
                    }
                    val resolvedAlias = current.selfAlias.ifBlank { alias }
                    val next = current.copy(
                        selfDisplayName = resolvedDisplayName,
                        selfEmail = resolvedEmail,
                        selfPhone = resolvedPhone,
                        selfAlias = resolvedAlias,
                        selfAuthProvider = authProvider,
                        selfDisplayNameReadOnly = displayNameReadOnly,
                        selfEmailReadOnly = emailReadOnly,
                        selfPhoneReadOnly = phoneReadOnly,
                        selfPhoneVerified = phoneReadOnly,
                    )
                    current.copy(
                        selfDisplayName = next.selfDisplayName,
                        selfEmail = next.selfEmail,
                        selfPhone = next.selfPhone,
                        selfAlias = resolvedAlias,
                        selfAuthProvider = next.selfAuthProvider,
                        selfDisplayNameReadOnly = next.selfDisplayNameReadOnly,
                        selfEmailReadOnly = next.selfEmailReadOnly,
                        selfPhoneReadOnly = next.selfPhoneReadOnly,
                        selfPhoneVerified = next.selfPhoneVerified,
                        selfIdentityConfirmed = profile?.displayNameSource in CONFIRMED_DISPLAY_NAME_SOURCES &&
                            isSelfIdentityReady(next),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(TAG, "failed to hydrate onboarding self identity", e)
                _uiState.update {
                    it.copy(error = UiMessage.resource(R.string.settings_identity_error_save_profile))
                }
            }
        }
    }

    private fun hydrateContactsPreview() {
        viewModelScope.launch {
            try {
                if (!userPrefsStore.observeContactsConsent().first()) {
                    _uiState.update { it.copy(contactsPreview = OnboardingContactsPreviewUi()) }
                    return@launch
                }
                personEnrichmentRepository.observeAll().collect { rows ->
                    val groups = rows.groupBy { row ->
                        row.sourceContactId?.takeIf { it.isNotBlank() }
                            ?: row.displayName?.takeIf { it.isNotBlank() }
                            ?: row.personRef
                    }
                    val names = groups.values
                        .map { group ->
                            group.firstNotNullOfOrNull { row -> row.displayName?.trim()?.takeIf { it.isNotEmpty() } }
                                ?: group.first().personRef
                        }
                        .distinct()
                        .sorted()
                    _uiState.update {
                        it.copy(
                            contactsPreview = OnboardingContactsPreviewUi(
                                totalCount = names.size,
                                names = names.take(3),
                            ),
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w(TAG, "failed to hydrate contacts preview", e)
            }
        }
    }

    private fun hydrateCalendarPreview() {
        viewModelScope.launch {
            try {
                val userId = userPrefsStore.observeCurrentUserId().first()
                if (userId.isNullOrBlank()) return@launch
                val now = Clock.System.now()
                val rangeEnd = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + CALENDAR_PREVIEW_RANGE_MILLIS)
                calendarEventRepository.observeForUser(userId, now, rangeEnd).collect { events ->
                    _uiState.update {
                        it.copy(
                            calendarPreview = it.calendarPreview.copy(
                                loading = false,
                                events = events.take(3).map { event -> event.toOnboardingCalendarPreviewItem(now) },
                            ),
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w(TAG, "failed to hydrate calendar preview", e)
                _uiState.update {
                    it.copy(calendarPreview = it.calendarPreview.copy(loading = false, failed = true))
                }
            }
        }
    }

    private suspend fun refreshCalendarSourceDataFromServer(sourceType: String): CalendarSourceRefreshResult {
        val userId = userPrefsStore.observeCurrentUserId().first()
        if (userId.isNullOrBlank()) return CalendarSourceRefreshResult.Failed
        _uiState.update { it.copy(calendarPreview = it.calendarPreview.copy(loading = true, failed = false)) }
        val syncResult = withTimeoutOrNull(ONBOARDING_PREVIEW_TIMEOUT_MS) {
            calendarEventRepository.triggerServerSync(
                sourceType = sourceType,
                mode = ONBOARDING_ACTIVATION_PREVIEW_MODE,
            )
        } ?: run {
            workScheduler.enqueueSourceRelationRefresh(
                sourceType,
                initialDelaySeconds = PENDING_BACKEND_REFRESH_DELAY_SECONDS,
                resetBeforeRefresh = true,
            )
            _uiState.update { it.copy(calendarPreview = it.calendarPreview.copy(loading = false, failed = false)) }
            return CalendarSourceRefreshResult.Pending
        }
        if (syncResult is BecalmResult.Failure) {
            logger.w(TAG, "calendar source sync trigger failed after OAuth connect sourceType=$sourceType")
            _uiState.update { it.copy(calendarPreview = it.calendarPreview.copy(loading = false, failed = true)) }
            return CalendarSourceRefreshResult.Failed
        }
        val syncResponse = (syncResult as BecalmResult.Success).value
        val syncStatus = syncResponse.status?.lowercase()
        if (syncResponse.accepted || syncStatus in setOf("pending", "running", "retry")) {
            workScheduler.enqueueSourceRelationRefresh(
                sourceType,
                initialDelaySeconds = (syncResponse.retryAfterSeconds ?: PENDING_BACKEND_REFRESH_DELAY_SECONDS)
                    .coerceAtLeast(PENDING_BACKEND_REFRESH_DELAY_SECONDS),
                resetBeforeRefresh = true,
            )
            return CalendarSourceRefreshResult.Pending
        }
        val refreshResult = calendarEventRepository.refreshSince(
            userId = userId,
            since = null,
            rangeStart = null,
            rangeEnd = null,
        )
        val commitmentResult = commitmentRepository.refreshSince(userId = userId, since = null)
        val sourceParticipantResult = sourceEventParticipantRepository.refreshSince(
            userId = userId,
            sourceType = sourceType,
            since = null,
        )
        val commitmentParticipantResult = commitmentParticipantRepository.refreshSince(
            userId = userId,
            since = null,
        )
        val scheduleLinkResult = scheduleEventLinkRepository.refreshSince(userId = userId, since = null)
        if (
            refreshResult is BecalmResult.Failure ||
            commitmentResult is BecalmResult.Failure ||
            sourceParticipantResult is BecalmResult.Failure ||
            commitmentParticipantResult is BecalmResult.Failure ||
            scheduleLinkResult is BecalmResult.Failure
        ) {
            logger.w(TAG, "calendar source mirror refresh failed after OAuth connect sourceType=$sourceType")
            _uiState.update { it.copy(calendarPreview = it.calendarPreview.copy(loading = false, failed = true)) }
            return CalendarSourceRefreshResult.Failed
        }
        _uiState.update { it.copy(calendarPreview = it.calendarPreview.copy(loading = false, failed = false)) }
        return CalendarSourceRefreshResult.Ready
    }

    private fun hydrateCallRecordingConnection() {
        viewModelScope.launch {
            combine(
                userPrefsStore.observeSourceEnabled(SourceType.CALL_RECORDING),
                userPrefsStore.observeRecordingFolderTreeUri(SourceType.CALL_RECORDING),
            ) { enabled, folderUri ->
                if (enabled && !folderUri.isNullOrBlank()) {
                    SourceConnectionState.Connected
                } else {
                    SourceConnectionState.Idle
                }
            }
                .distinctUntilChanged()
                .collect { connectionState ->
                    _uiState.update { state ->
                        if (state.callRecordingConnectionState == SourceConnectionState.Skipped &&
                            connectionState == SourceConnectionState.Idle
                        ) {
                            state
                        } else {
                            state.copy(callRecordingConnectionState = connectionState)
                        }
                    }
                }
        }
    }

    private fun hydrateSourceOwnerships() {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(sourceOwnershipsLoaded = false, sourceOwnershipLoadFailed = false) }
                val userId = userPrefsStore.observeCurrentUserId().first()
                if (userId.isNullOrBlank()) {
                    _uiState.update { it.copy(sourceOwnershipsLoaded = true, sourceOwnershipLoadFailed = false) }
                    return@launch
                }
                sourceConnectionRepository.observeAll(userId).collect { connections ->
                    val connectedStepUpdates = connections.connectedOnboardingStepUpdates()
                    var changedStepUpdates = emptyMap<OnboardingStep, StepStatus>()
                    _uiState.update { state ->
                        changedStepUpdates = connectedStepUpdates.filter { (step, status) ->
                            state.stepStates[step] != status
                        }
                        state.copy(
                            sourceOwnerships = connections.map(SourceConnectionEntity::toOnboardingOwnershipUi),
                            sourceOwnershipsLoaded = true,
                            sourceOwnershipLoadFailed = false,
                            stepStates = state.stepStates + connectedStepUpdates,
                        )
                    }
                    if (changedStepUpdates.isNotEmpty()) {
                        persistStepStatusesNow(changedStepUpdates)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(TAG, "failed to hydrate source ownerships", e)
                _uiState.update {
                    it.copy(
                        sourceOwnershipsLoaded = false,
                        sourceOwnershipLoadFailed = true,
                        error = UiMessage.resource(R.string.onb_error_source_ownership_required),
                    )
                }
            }
        }
    }

    private fun persistStepStatus(step: OnboardingStep, status: StepStatus) {
        persistStepStatuses(mapOf(step to status))
    }

    private fun persistStepStatuses(statuses: Map<OnboardingStep, StepStatus>) {
        viewModelScope.launch {
            persistStepStatusesNow(statuses)
        }
    }

    private suspend fun persistStepStatusesNow(statuses: Map<OnboardingStep, StepStatus>) {
        userPrefsStore.setOnboardingStepStatuses(
            OnboardingProgressResolver.encodeStepStatuses(statuses),
        )
    }
}

private fun SourceConnectionEntity.toOnboardingOwnershipUi(): OnboardingSourceOwnershipUi =
    OnboardingSourceOwnershipUi(
        id = id,
        title = sourceConnectionTitle(provider = provider, capability = capability),
        accountLabel = accountDisplayName ?: accountIdentifier ?: provider,
        status = status,
        provider = provider,
        capability = capability,
    )

private fun List<SourceConnectionEntity>.connectedOnboardingStepUpdates(): Map<OnboardingStep, StepStatus> =
    asSequence()
        .filter { sourceConnectionStateForStatus(it.status) == SourceConnectionState.Connected }
        .mapNotNull(SourceConnectionEntity::connectedOnboardingStep)
        .associateWith { StepStatus.COMPLETE }

private fun SourceConnectionEntity.connectedOnboardingStep(): OnboardingStep? =
    when {
        provider == "google" && capability == "mail" -> OnboardingStep.LINK_GMAIL
        provider == "google" && capability == "calendar" -> OnboardingStep.LINK_GOOGLE_CALENDAR
        provider == "outlook" && capability == "mail" -> OnboardingStep.LINK_OUTLOOK_MAIL
        provider == "outlook" && capability == "calendar" -> OnboardingStep.LINK_OUTLOOK_CALENDAR
        provider == SourceType.GMAIL -> OnboardingStep.LINK_GMAIL
        provider == SourceType.GOOGLE_CALENDAR -> OnboardingStep.LINK_GOOGLE_CALENDAR
        provider == SourceType.OUTLOOK_MAIL -> OnboardingStep.LINK_OUTLOOK_MAIL
        provider == SourceType.OUTLOOK_CALENDAR -> OnboardingStep.LINK_OUTLOOK_CALENDAR
        else -> null
    }

private val RECORDING_SOURCE_TYPES = setOf(
    SourceType.VOICE,
    SourceType.CALL_RECORDING,
    SourceType.MEETING,
)

private const val RECORDING_GRANT_LOOKBACK_DAYS = 30
private const val CALENDAR_PREVIEW_RANGE_MILLIS = 183L * 24L * 60L * 60L * 1000L
private const val PENDING_BACKEND_REFRESH_DELAY_SECONDS = 45L
private const val ONBOARDING_ACTIVATION_PREVIEW_MODE = "activation_preview"
private const val ONBOARDING_PREVIEW_TIMEOUT_MS = 30_000L

private fun String.isAuthBoundaryOAuthError(): Boolean =
    this == "auth_required" || this == "auth_invalid"

private enum class CalendarSourceRefreshResult {
    Ready,
    Pending,
    Failed,
}

private const val DISPLAY_NAME_SOURCE_MANUAL = "manual"
private const val DISPLAY_NAME_SOURCE_GOOGLE_AUTH = "google_auth"
private val CONFIRMED_DISPLAY_NAME_SOURCES = setOf(
    DISPLAY_NAME_SOURCE_MANUAL,
    DISPLAY_NAME_SOURCE_GOOGLE_AUTH,
)

private fun List<com.becalm.android.data.local.db.entity.SelfIdentityAnchorEntity>.firstActiveValue(type: String): String =
    firstOrNull { it.anchorType == type && it.status == "active" }?.let { it.displayValue ?: it.normalizedValue }.orEmpty()

private fun CalendarEventEntity.toOnboardingCalendarPreviewItem(now: Instant): OnboardingCalendarPreviewItemUi {
    val timeZone = TimeZone.currentSystemDefault()
    val local = startAt.toLocalDateTime(timeZone)
    val today = now.toLocalDateTime(timeZone).date
    val tomorrow = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + 24L * 60L * 60L * 1000L)
        .toLocalDateTime(timeZone)
        .date
    val dayLabel = when (local.date) {
        today -> "오늘"
        tomorrow -> "내일"
        else -> "${local.monthNumber}/${local.dayOfMonth}"
    }
    val timeLabel = if (isAllDay) {
        "종일"
    } else {
        "%02d:%02d".format(local.hour, local.minute)
    }
    return OnboardingCalendarPreviewItemUi(
        dayLabel = dayLabel,
        timeLabel = timeLabel,
        title = title.ifBlank { "제목 없는 일정" },
    )
}
