package com.becalm.android.ui.sources

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.R
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.entity.SourceConnectionEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.MeetingImportRepository
import com.becalm.android.data.repository.ProcessingPhase
import com.becalm.android.data.repository.ProcessingSourceState
import com.becalm.android.data.repository.ProcessingStatusRepository
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.SourceConnectionRepository
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.ui.components.SourceSyncStatus
import com.becalm.android.ui.components.UiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
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
 * @param status Typed UI status used by shared source-status indicators.
 * @param recentEvents Up to 50 most-recent raw ingestion events for this source, newest-first.
 * @param error Non-null when the sourceType argument is absent or blank.
 */
public data class SourceDetailUiState(
    val sourceType: String = "",
    val status: SourceSyncStatus = SourceSyncStatus.Unknown,
    val lastSyncAt: Instant? = null,
    val eventsSyncedCount: Int? = null,
    val processingPhase: ProcessingPhase = ProcessingPhase.IDLE,
    val processingMessage: String? = null,
    val hasError: Boolean = false,
    val showReconnectButton: Boolean = false,
    val showDisconnectButton: Boolean = false,
    val showManualSyncButton: Boolean = false,
    val showMeetingAudioAddButton: Boolean = false,
    val showDisconnectConfirmDialog: Boolean = false,
    val disconnectOutcome: SourceDisconnectOutcome? = null,
    val actionError: UiMessage? = null,
    val actionMessage: UiMessage? = null,
    val manualSyncLoading: Boolean = false,
    val recentEvents: List<RecentEventSummary> = emptyList(),
    val error: UiMessage? = null,
)

/** UI-neutral reconnect destinations emitted by [SourceDetailViewModel]. */
public enum class SourceReconnectDestination {
    RECORDING_FOLDER,
    GMAIL,
    OUTLOOK_MAIL,
    NAVER_IMAP,
    DAUM_IMAP,
    GOOGLE_CALENDAR,
    OUTLOOK_CALENDAR,
}

/** One-shot effects for settings source detail actions. */
public sealed interface SourceDetailEffect {
    /** Open the reconnect flow appropriate for the current source type. */
    public data class OpenReconnect(
        val destination: SourceReconnectDestination,
        val sourceType: String? = null,
        val sourceConnectionId: String? = null,
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
 * The default user scope is loaded from [AuthRepository] so the production screen can show
 * recent imported evidence without host wiring. [setUserId] remains available for tests and
 * future explicit host scopes.
 */
@HiltViewModel
public class SourceDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sourceStatusRepository: SourceStatusRepository,
    private val processingStatusRepository: ProcessingStatusRepository,
    private val rawIngestionRepository: RawIngestionRepository,
    private val authRepository: AuthRepository,
    private val sourceConnectionRepository: SourceConnectionRepository,
    private val sourceAdministrationPort: SourceAdministrationPort,
    private val sourceSyncPort: SourceSyncPort,
    private val meetingImportRepository: MeetingImportRepository,
    private val logger: Logger,
) : ViewModel() {

    private val sourceType: String = savedStateHandle[ARG_SOURCE_TYPE] ?: ""
    private val actionHandler: SourceDetailActionHandler = SourceDetailActionHandler(
        sourceAdministrationPort = sourceAdministrationPort,
        sourceSyncPort = sourceSyncPort,
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

    private val hasValidSourceType: Boolean =
        sourceType.isNotBlank() && sourceType in SourceType.ALL

    init {
        seedUserIdFromCurrentSession()
        refreshSourceStatusFromServerOnOpen()
    }

    /** Provide or override the authenticated userId so that event queries are scoped per-user. */
    public fun setUserId(userId: String) {
        _userId.value = userId
    }

    private fun seedUserIdFromCurrentSession() {
        if (!hasValidSourceType) return
        viewModelScope.launch {
            try {
                val userId = authRepository.currentSession()?.userId?.takeIf { it.isNotBlank() }
                    ?: return@launch
                if (_userId.value == null) {
                    _userId.value = userId
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w(TAG, "failed to load source detail session scope: ${e.message}")
            }
        }
    }

    private fun refreshSourceStatusFromServerOnOpen() {
        if (!hasValidSourceType) return
        viewModelScope.launch {
            when (sourceStatusRepository.refreshFromServer()) {
                is BecalmResult.Success -> logger.d(TAG, "source detail status refreshed sourceType=$sourceType")
                is BecalmResult.Failure -> logger.w(TAG, "source detail status refresh failed sourceType=$sourceType")
            }
        }
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
        _effects.tryEmit(
            SourceDetailEffect.OpenReconnect(
                destination = destination,
                sourceType = sourceType,
                sourceConnectionId = reconnectTargetConnectionId.value,
            ),
        )
    }

    /** Triggers an immediate manual sync through the active source owner for this source. */
    public fun onManualSync() {
        if (_manualSyncLoading.value) return
        if (!state.value.status.allowsManualSync()) {
            _disconnectOutcome.value = null
            _actionMessage.value = null
            _actionError.value = UiMessage.resource(R.string.source_detail_error_manual_sync_requires_connection)
            logger.w(TAG, "manual sync blocked because source is not connected sourceType=$sourceType")
            return
        }
        viewModelScope.launch {
            _manualSyncLoading.value = true
            _actionError.value = null
            _actionMessage.value = null
            try {
                when (
                    val result = actionHandler.requestManualSync(
                        sourceType = sourceType,
                        hasValidSourceType = hasValidSourceType,
                    )
                ) {
                    is BecalmResult.Success -> {
                        _actionError.value = null
                        _actionMessage.value = UiMessage.resource(R.string.source_detail_manual_sync_started)
                    }
                    is BecalmResult.Failure -> {
                        _disconnectOutcome.value = null
                        _actionMessage.value = null
                        _actionError.value = UiMessage.resource(R.string.source_detail_error_manual_sync_failed)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w(TAG, "manual sync failed: ${e.message}")
                _disconnectOutcome.value = null
                _actionMessage.value = null
                _actionError.value = UiMessage.resource(R.string.source_detail_error_manual_sync_failed)
            } finally {
                _manualSyncLoading.value = false
            }
        }
    }

    /** Imports a user-selected meeting audio file into app-private storage for speaker review. */
    public fun onMeetingAudioSelected(uri: Uri?) {
        if (uri == null || sourceType != SourceType.MEETING) return
        viewModelScope.launch {
            when (val result = meetingImportRepository.stageAudioForSpeakerPreview(uri)) {
                is BecalmResult.Success -> {
                    _actionError.value = null
                    _actionMessage.value = null
                }
                is BecalmResult.Failure -> {
                    _actionMessage.value = null
                    _actionError.value = UiMessage.resource(R.string.source_detail_error_meeting_audio_import_failed)
                }
            }
        }
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
                    _actionMessage.value = null
                },
                onFailure = { message ->
                    _disconnectOutcome.value = null
                    _actionMessage.value = null
                    _actionError.value = message
                },
            )
        }
    }

    private val eventsFlow = _userId.flatMapLatest { userId ->
        if (userId == null) return@flatMapLatest flowOf(emptyList())
        rawIngestionRepository.observeForSourceType(userId, sourceType, limit = RECENT_EVENTS_LIMIT)
    }
    private val sourceConnectionsFlow = _userId.flatMapLatest { userId ->
        if (userId == null) return@flatMapLatest flowOf(emptyList())
        sourceConnectionRepository.observeAll(userId)
    }
    private val reconnectTargetConnectionId: StateFlow<String?> = sourceConnectionsFlow
        .map { rows -> SourceReconnectTargetResolver.targetConnectionId(rows, sourceType) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )

    private val statusFlow = if (!hasValidSourceType) {
        flowOf(null)
    } else {
        sourceStatusRepository.observeFor(sourceType)
    }
    private val processingStateFlow = if (!hasValidSourceType) {
        flowOf(null)
    } else {
        processingStatusRepository.observeAll().map { states ->
            states.firstOrNull { state -> state.sourceType == sourceType }
        }
    }
    private val _dialogState = MutableStateFlow(false)
    private val _disconnectOutcome = MutableStateFlow<SourceDisconnectOutcome?>(null)
    private val _actionError = MutableStateFlow<UiMessage?>(null)
    private val _actionMessage = MutableStateFlow<UiMessage?>(null)
    private val _manualSyncLoading = MutableStateFlow(false)
    private val transientStateFlow = combine(
        _actionError,
        _actionMessage,
        _manualSyncLoading,
    ) { actionError, actionMessage, manualSyncLoading ->
        SourceDetailTransientState(
            actionError = actionError,
            actionMessage = actionMessage,
            manualSyncLoading = manualSyncLoading,
        )
    }
    private val stableStateFlow = combine(
        statusFlow,
        processingStateFlow,
        eventsFlow,
        _dialogState,
        _disconnectOutcome,
    ) { status, processingState, sourceEvents, showDisconnectConfirmDialog, disconnectOutcome ->
        SourceDetailStableState(
            status = status,
            processingState = processingState,
            sourceEvents = sourceEvents,
            showDisconnectConfirmDialog = showDisconnectConfirmDialog,
            disconnectOutcome = disconnectOutcome,
        )
    }

    /**
     * Observable state consumed by the source detail composable.
     *
     * When [sourceType] is blank (missing nav argument), the state immediately shows
     * an error and no repository flows are subscribed.
     */
    public val state: StateFlow<SourceDetailUiState> = if (sourceType.isBlank()) {
        MutableStateFlow(
            SourceDetailUiState(error = UiMessage.resource(R.string.source_detail_error_missing_source_message)),
        )
    } else if (!hasValidSourceType) {
        // Schema-level guard — any value not declared in data-model.yml's source_type
        // enum is rejected. We use ALL (not PRODUCT_SOURCES) so deep links to VOICE or
        // (future) CALL_RECORDING detail routes remain navigable once their UI ships.
        MutableStateFlow(
            SourceDetailUiState(error = UiMessage.resource(R.string.source_detail_error_invalid_source)),
        )
    } else {
            combine(
                stableStateFlow,
                transientStateFlow,
            ) { stableState, transientState ->
                buildUiState(
                    status = stableState.status,
                    processingState = stableState.processingState,
                    sourceEvents = stableState.sourceEvents,
                    showDisconnectConfirmDialog = stableState.showDisconnectConfirmDialog,
                    disconnectOutcome = stableState.disconnectOutcome,
                    transientState = transientState,
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
        processingState: ProcessingSourceState?,
        sourceEvents: List<com.becalm.android.data.local.db.entity.RawIngestionEventEntity>,
        showDisconnectConfirmDialog: Boolean,
        disconnectOutcome: SourceDisconnectOutcome?,
        transientState: SourceDetailTransientState,
    ): SourceDetailUiState {
        return SourceDetailProjector.buildUiState(
            sourceType = sourceType,
            status = status,
            processingState = processingState,
            sourceEvents = sourceEvents,
            showDisconnectConfirmDialog = showDisconnectConfirmDialog,
            disconnectOutcome = disconnectOutcome,
            actionError = transientState.actionError,
            actionMessage = transientState.actionMessage,
            manualSyncLoading = transientState.manualSyncLoading,
        )
    }

    private data class SourceDetailTransientState(
        val actionError: UiMessage?,
        val actionMessage: UiMessage?,
        val manualSyncLoading: Boolean,
    )

    private data class SourceDetailStableState(
        val status: com.becalm.android.data.repository.SourceStatus?,
        val processingState: ProcessingSourceState?,
        val sourceEvents: List<com.becalm.android.data.local.db.entity.RawIngestionEventEntity>,
        val showDisconnectConfirmDialog: Boolean,
        val disconnectOutcome: SourceDisconnectOutcome?,
    )

}

private fun SourceSyncStatus.allowsManualSync(): Boolean =
    this == SourceSyncStatus.Connected ||
        this == SourceSyncStatus.Syncing ||
        this == SourceSyncStatus.Error

internal object SourceReconnectTargetResolver {
    fun targetConnectionId(
        connections: List<SourceConnectionEntity>,
        sourceType: String,
    ): String? {
        val candidates = connections
            .filter { connection ->
                connection.sourceType() == sourceType && connection.status != "disconnected"
            }
        val reconnectable = candidates.filter { connection ->
            connection.status in reconnectableStatuses
        }
        if (reconnectable.isEmpty()) return null
        if (candidates.mapNotNull { it.accountKey() }.distinct().size > 1) {
            return null
        }
        return reconnectable
            .sortedWith(
                compareByDescending<SourceConnectionEntity> { it.status == "needs_reauth" }
                    .thenByDescending { it.status == "failed" }
                    .thenByDescending { it.lastSyncAt?.toEpochMilliseconds() ?: Long.MIN_VALUE },
            )
            .firstOrNull()
            ?.id
    }

    private fun SourceConnectionEntity.sourceType(): String? =
        when {
            provider == "google" && capability == "mail" -> SourceType.GMAIL
            provider == "outlook" && capability == "mail" -> SourceType.OUTLOOK_MAIL
            provider == "google" && capability == "calendar" -> SourceType.GOOGLE_CALENDAR
            provider == "outlook" && capability == "calendar" -> SourceType.OUTLOOK_CALENDAR
            else -> null
        }

    private fun SourceConnectionEntity.accountKey(): String? =
        accountIdentifier
            ?.trim()
            ?.lowercase()
            ?.takeIf(String::isNotBlank)

    private val reconnectableStatuses = setOf("needs_reauth", "failed")
}
