package com.becalm.android.ui.sources

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.core.util.Logger
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.worker.WorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.Instant
import kotlinx.coroutines.launch
import javax.inject.Inject

// ─── Navigation argument key ──────────────────────────────────────────────────

/** Nav-graph argument key used to pass the source type string to this screen. */
public const val ARG_SOURCE_TYPE: String = "source_id"

// ─── UI types ─────────────────────────────────────────────────────────────────

/**
 * PII-safe summary row for the recent-events list on SourceDetailScreen.
 *
 * Excludes [com.becalm.android.data.local.db.entity.RawIngestionEventEntity.eventSnippet]
 * and [com.becalm.android.data.local.db.entity.RawIngestionEventEntity.personRef]
 * so that Compose state snapshots cannot leak email-body content or raw counterparty
 * identifiers into Crashlytics or LeakCanary retained-object reports.
 *
 * @property id        Primary-key UUID of the source entity.
 * @property timestamp When the event was recorded.
 * @property title     Event title when available; null otherwise.
 */
public data class RecentEventSummary(
    val id: String,
    val timestamp: Instant,
    val title: String?,
)

/**
 * UI state for the source detail screen (SMG-003..005).
 *
 * @param sourceType The [com.becalm.android.data.remote.dto.SourceType] string for this screen.
 * @param status Human-readable connection status label.
 * @param recentEvents Up to 50 most-recent raw ingestion events for this source, newest-first.
 * @param error Non-null when the sourceType argument is absent or blank.
 */
public data class SourceDetailUiState(
    val sourceType: String = "",
    val status: String = "",
    val lastSyncAt: Instant? = null,
    val eventsSyncedCount: Int? = null,
    val lastError: String? = null,
    val showReconnectButton: Boolean = false,
    val showDisconnectButton: Boolean = false,
    val showManualSyncButton: Boolean = false,
    val showDisconnectConfirmDialog: Boolean = false,
    val disconnectOutcome: SourceDisconnectOutcome? = null,
    val actionError: String? = null,
    val recentEvents: List<RecentEventSummary> = emptyList(),
    val error: String? = null,
)

/** UI-neutral reconnect destinations emitted by [SourceDetailViewModel]. */
public enum class SourceReconnectDestination {
    RECORDING_FOLDER,
    GMAIL,
    OUTLOOK_MAIL,
    IMAP,
    GOOGLE_CALENDAR,
    OUTLOOK_CALENDAR,
}

/** One-shot effects for settings source detail actions. */
public sealed interface SourceDetailEffect {
    /** Open the reconnect flow appropriate for the current source type. */
    public data class OpenReconnect(
        val destination: SourceReconnectDestination,
    ) : SourceDetailEffect
}

// ─── ViewModel ────────────────────────────────────────────────────────────────

private const val TAG = "SourceDetailViewModel"

/** Maximum number of recent events shown in the detail view (SMG-005). */
private const val RECENT_EVENTS_LIMIT = 50

/**
 * ViewModel for the source detail screen (SMG-003..005).
 *
 * Reads [sourceType] from [SavedStateHandle] (nav-graph argument [ARG_SOURCE_TYPE]),
 * observes sync health for that source, and streams the 50 most-recent raw ingestion
 * events that match [sourceType].
 *
 * ## userId
 * The SP-41 spec does not inject [com.becalm.android.data.repository.AuthRepository] into
 * this ViewModel. The userId is therefore supplied by the host composable via [setUserId].
 * Until [setUserId] is called the events list remains empty.
 */
@HiltViewModel
public class SourceDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sourceStatusRepository: SourceStatusRepository,
    private val rawIngestionRepository: RawIngestionRepository,
    private val sourceAdministrationPort: SourceAdministrationPort,
    private val workScheduler: WorkScheduler,
    private val logger: Logger,
) : ViewModel() {

    private val sourceType: String = savedStateHandle[ARG_SOURCE_TYPE] ?: ""
    private val actionHandler: SourceDetailActionHandler = SourceDetailActionHandler(
        sourceAdministrationPort = sourceAdministrationPort,
        workScheduler = workScheduler,
        logger = logger,
    )

    /**
     * Must be populated by the host screen with the authenticated userId before events
     * are emitted. Required because [com.becalm.android.data.repository.AuthRepository]
     * is outside this ViewModel's scope.
     */
    private val _userId = MutableStateFlow<String?>(null)
    private val _effects: MutableSharedFlow<SourceDetailEffect> =
        MutableSharedFlow(extraBufferCapacity = 1)

    /** Provide the authenticated userId so that event queries are scoped per-user. */
    public fun setUserId(userId: String) {
        _userId.value = userId
    }

    /** One-shot UI effects for reconnect navigation. */
    public val effects: SharedFlow<SourceDetailEffect> = _effects.asSharedFlow()

    /** Opens the reconnect flow appropriate for this source type. */
    public fun onReconnect() {
        val destination = SourceDetailActionResolver.reconnectDestinationFor(sourceType)
        if (destination == null) {
            logger.w(TAG, "onReconnect ignored for unsupported sourceType=$sourceType")
            return
        }
        _effects.tryEmit(SourceDetailEffect.OpenReconnect(destination))
    }

    /** Enqueues immediate sync work for this source. */
    public fun onManualSync() {
        actionHandler.requestManualSync(sourceType = sourceType, hasValidSourceType = hasValidSourceType)
    }

    /** Opens the confirmation dialog for disconnecting this source. */
    public fun onDisconnectClick() {
        if (!hasValidSourceType) return
        _dialogState.value = true
    }

    /** Dismisses the confirmation dialog without performing any side effects. */
    public fun onDisconnectDismiss() {
        _dialogState.value = false
    }

    /**
     * Performs the disconnect flow through [SourceAdministrationPort].
     *
     * The concrete implementation owns DataStore / Keystore / Room details; the VM exposes
     * the returned [SourceDisconnectOutcome] so tests can lock the contract without peeking
     * into those subsystems.
     */
    public fun onDisconnectConfirm() {
        if (!hasValidSourceType) return
        viewModelScope.launch {
            _dialogState.value = false
            actionHandler.disconnect(
                sourceType = sourceType,
                onSuccess = { outcome ->
                    _disconnectOutcome.value = outcome
                    _actionError.value = null
                },
                onFailure = { message ->
                    _disconnectOutcome.value = null
                    _actionError.value = message
                },
            )
        }
    }

    private val eventsFlow = _userId.flatMapLatest { userId ->
        if (userId == null) return@flatMapLatest flowOf(emptyList())
        rawIngestionRepository.observeForSourceType(userId, sourceType, limit = RECENT_EVENTS_LIMIT)
    }

    private val hasValidSourceType: Boolean =
        sourceType.isNotBlank() && sourceType in SourceType.ALL

    private val statusFlow = if (!hasValidSourceType) {
        flowOf(null)
    } else {
        sourceStatusRepository.observeFor(sourceType)
    }

    private val _dialogState = MutableStateFlow(false)
    private val _disconnectOutcome = MutableStateFlow<SourceDisconnectOutcome?>(null)
    private val _actionError = MutableStateFlow<String?>(null)

    /**
     * Observable state consumed by the source detail composable.
     *
     * When [sourceType] is blank (missing nav argument), the state immediately shows
     * an error and no repository flows are subscribed.
     */
    public val state: StateFlow<SourceDetailUiState> = if (sourceType.isBlank()) {
        MutableStateFlow(
            SourceDetailUiState(error = "sourceType argument is missing or blank"),
        )
    } else if (!hasValidSourceType) {
        // Schema-level guard — any value not declared in data-model.yml's source_type
        // enum is rejected. We use ALL (not PRODUCT_SOURCES) so deep links to VOICE or
        // (future) CALL_RECORDING detail routes remain navigable once their UI ships.
        MutableStateFlow(
            SourceDetailUiState(error = "Invalid source type"),
        )
    } else {
        combine(
            statusFlow,
            eventsFlow,
            _dialogState,
            _disconnectOutcome,
            _actionError,
        ) { status, sourceEvents, showDisconnectConfirmDialog, disconnectOutcome, actionError ->
            buildUiState(
                status = status,
                sourceEvents = sourceEvents,
                showDisconnectConfirmDialog = showDisconnectConfirmDialog,
                disconnectOutcome = disconnectOutcome,
                actionError = actionError,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SourceDetailUiState(sourceType = sourceType),
        )
    }

    init {
        logger.d(TAG, "init sourceType=$sourceType")
        if (sourceType.isBlank()) {
            logger.e(TAG, "sourceType argument missing from SavedStateHandle")
        } else if (!hasValidSourceType) {
            logger.w(TAG, "rejected unknown sourceType")
        }
    }

    override fun onCleared() {
        super.onCleared()
        logger.d(TAG, "cleared sourceType=$sourceType")
    }

    private fun buildUiState(
        status: com.becalm.android.data.repository.SourceStatus?,
        sourceEvents: List<com.becalm.android.data.local.db.entity.RawIngestionEventEntity>,
        showDisconnectConfirmDialog: Boolean,
        disconnectOutcome: SourceDisconnectOutcome?,
        actionError: String?,
    ): SourceDetailUiState = SourceDetailProjector.buildUiState(
        sourceType = sourceType,
        status = status,
        sourceEvents = sourceEvents,
        showDisconnectConfirmDialog = showDisconnectConfirmDialog,
        disconnectOutcome = disconnectOutcome,
        actionError = actionError,
    )
}
