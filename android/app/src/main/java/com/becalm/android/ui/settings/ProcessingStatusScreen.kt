package com.becalm.android.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.becalm.android.R
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.local.db.entity.RawIngestionSyncStatus
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.AudioProcessingConfirmationRepository
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.ProcessingPhase
import com.becalm.android.data.repository.ProcessingSourceState
import com.becalm.android.data.repository.ProcessingStatusRepository
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.SourceConnectionRepository
import com.becalm.android.data.repository.SourceConnectionStatus
import com.becalm.android.data.repository.SourceStatus
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.data.repository.isActive
import com.becalm.android.ui.components.BecalmButton
import com.becalm.android.ui.components.BecalmButtonVariant
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.components.CollectFlowEffect
import com.becalm.android.ui.components.EmptyState
import com.becalm.android.ui.components.EvidenceCard
import com.becalm.android.ui.components.localizedProcessingStatusMessage
import com.becalm.android.ui.components.sourcePresentationFor
import com.becalm.android.ui.components.uiMessageStringResource
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.navigation.dispatchSourceDetailEffect
import com.becalm.android.ui.sources.SourceDetailActionResolver
import com.becalm.android.ui.sources.SourceDetailEffect
import com.becalm.android.ui.sources.SourceReconnectDestination
import com.becalm.android.ui.sources.SourceReconnectTargetResolver
import com.becalm.android.ui.sources.SourceSyncPort
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Immutable
public data class ProcessingStatusUiState(
    val rows: List<ProcessingStatusRow> = emptyList(),
)

@Immutable
public data class ProcessingStatusRow(
    val sourceType: String,
    val phase: ProcessingPhase,
    val itemCount: Int,
    val message: String?,
    val updatedAt: Instant?,
    val items: List<ProcessingStatusItem> = emptyList(),
    val opensSourceDetail: Boolean = true,
    val recoveryAction: ProcessingStatusRecoveryAction? = null,
    val recoveryInProgress: Boolean = false,
)

@Immutable
public data class ProcessingStatusItem(
    val id: String,
    val sourceType: String,
    val title: String?,
    val sourceRef: String?,
    val durationSeconds: Int?,
    val syncStatus: String,
    val updatedAt: Instant?,
)

public enum class ProcessingStatusRecoveryActionType {
    RETRY_SOURCE_SYNC,
    RECONNECT_SOURCE,
    OPEN_RECORDING_SETUP,
    OPEN_CONSENT_SETTINGS,
}

@Immutable
public data class ProcessingStatusRecoveryAction(
    val type: ProcessingStatusRecoveryActionType,
    @StringRes val labelRes: Int,
)

public sealed interface ProcessingStatusEffect {
    public data class OpenReconnect(
        val destination: SourceReconnectDestination,
        val sourceType: String,
        val sourceConnectionId: String? = null,
    ) : ProcessingStatusEffect

    public data object OpenConsentSettings : ProcessingStatusEffect
}

@HiltViewModel
public class ProcessingStatusViewModel @Inject constructor(
    processingStatusRepository: ProcessingStatusRepository,
    private val sourceStatusRepository: SourceStatusRepository,
    rawIngestionRepository: RawIngestionRepository,
    private val audioProcessingConfirmationRepository: AudioProcessingConfirmationRepository,
    private val sourceSyncPort: SourceSyncPort,
    private val sourceConnectionRepository: SourceConnectionRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {
    private val actionInProgressSourceType: MutableStateFlow<String?> = MutableStateFlow(null)
    private val _effects: MutableSharedFlow<ProcessingStatusEffect> =
        MutableSharedFlow(extraBufferCapacity = 1)

    public val effects: SharedFlow<ProcessingStatusEffect> = _effects.asSharedFlow()

    init {
        viewModelScope.launch {
            sourceStatusRepository.refreshFromServer()
        }
    }

    private val processingItemsFlow = flow {
        val userId = authRepository.currentSession()?.userId
        if (userId.isNullOrBlank()) {
            emit(emptyList())
        } else {
            emitAll(rawIngestionRepository.observeActiveProcessingItems(userId))
        }
    }

    public val state: StateFlow<ProcessingStatusUiState> =
        combine(
            processingStatusRepository.observeAll(),
            sourceStatusRepository.observeSources(),
            processingItemsFlow,
            actionInProgressSourceType,
        ) { states, sourceStatuses, activeItems, inProgressSourceType ->
            val itemsBySource = activeItems
                .map(RawIngestionEventEntity::toProcessingStatusItem)
                .groupBy { item -> item.sourceType }
            ProcessingStatusUiState(
                rows = states.mapNotNull { state ->
                    toRow(
                        state = state.withSourceStatusOverlay(sourceStatuses[state.sourceType]),
                        sourceStatus = sourceStatuses[state.sourceType],
                        items = itemsBySource[state.sourceType].orEmpty(),
                        inProgressSourceType = inProgressSourceType,
                    )
                },
            )
        }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = ProcessingStatusUiState(),
            )

    private fun toRow(
        state: ProcessingSourceState,
        sourceStatus: SourceStatus?,
        items: List<ProcessingStatusItem>,
        inProgressSourceType: String?,
    ): ProcessingStatusRow? {
        val isManualEvidence = state.sourceType == SourceType.MESSAGE_SCREENSHOT
        if (
            !isManualEvidence &&
            state.phase != ProcessingPhase.IDLE &&
            sourceStatus?.status == SourceConnectionStatus.NEVER_CONNECTED
        ) {
            return null
        }
        return ProcessingStatusRow(
            sourceType = state.sourceType,
            phase = state.phase,
            itemCount = state.itemCount,
            message = state.message,
            updatedAt = state.updatedAt,
            items = items,
            opensSourceDetail = !isManualEvidence && state.phase != ProcessingPhase.AWAITING_CONFIRMATION,
            recoveryAction = recoveryActionFor(state, sourceStatus),
            recoveryInProgress = inProgressSourceType == state.sourceType,
        )
    }

    public fun onConfirmAudioItem(rawEventId: String) {
        viewModelScope.launch {
            audioProcessingConfirmationRepository.confirm(rawEventId)
        }
    }

    public fun onSkipAudioItem(rawEventId: String) {
        viewModelScope.launch {
            audioProcessingConfirmationRepository.skip(rawEventId)
        }
    }

    public fun onRecoveryAction(sourceType: String, actionType: ProcessingStatusRecoveryActionType) {
        when (actionType) {
            ProcessingStatusRecoveryActionType.RETRY_SOURCE_SYNC -> retrySourceSync(sourceType)
            ProcessingStatusRecoveryActionType.RECONNECT_SOURCE,
            ProcessingStatusRecoveryActionType.OPEN_RECORDING_SETUP,
            -> openReconnect(sourceType)
            ProcessingStatusRecoveryActionType.OPEN_CONSENT_SETTINGS ->
                _effects.tryEmit(ProcessingStatusEffect.OpenConsentSettings)
        }
    }

    private fun retrySourceSync(sourceType: String) {
        if (actionInProgressSourceType.value == sourceType) return
        viewModelScope.launch {
            actionInProgressSourceType.value = sourceType
            try {
                sourceSyncPort.requestManualSync(sourceType)
                sourceStatusRepository.refreshFromServer()
            } finally {
                actionInProgressSourceType.value = null
            }
        }
    }

    private fun openReconnect(sourceType: String) {
        val destination = SourceDetailActionResolver.reconnectDestinationFor(sourceType) ?: return
        viewModelScope.launch {
            _effects.emit(
                ProcessingStatusEffect.OpenReconnect(
                    destination = destination,
                    sourceType = sourceType,
                    sourceConnectionId = reconnectTargetConnectionId(sourceType),
                ),
            )
        }
    }

    private suspend fun reconnectTargetConnectionId(sourceType: String): String? {
        return try {
            val userId = authRepository.currentSession()?.userId?.takeIf { it.isNotBlank() } ?: return null
            val connections = sourceConnectionRepository.observeAll(userId).first()
            SourceReconnectTargetResolver.targetConnectionId(connections, sourceType)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun recoveryActionFor(
        state: ProcessingSourceState,
        sourceStatus: SourceStatus?,
    ): ProcessingStatusRecoveryAction? {
        if (state.sourceType == SourceType.MESSAGE_SCREENSHOT) return null
        if (state.phase == ProcessingPhase.AWAITING_CONFIRMATION) return null

        val message = state.message.normalizedRecoveryMessage()
        return when (state.phase) {
            ProcessingPhase.BLOCKED -> blockedRecoveryAction(state.sourceType, message, sourceStatus)
            ProcessingPhase.ERROR -> errorRecoveryAction(state.sourceType, message, sourceStatus)
            else -> null
        }
    }

    private fun blockedRecoveryAction(
        sourceType: String,
        message: String,
        sourceStatus: SourceStatus?,
    ): ProcessingStatusRecoveryAction? =
        when {
            message.isConsentRequiredMessage() -> ProcessingStatusRecoveryAction(
                type = ProcessingStatusRecoveryActionType.OPEN_CONSENT_SETTINGS,
                labelRes = R.string.evidence_import_status_action_consent,
            )
            message.isRecordingSetupMessage() -> ProcessingStatusRecoveryAction(
                type = ProcessingStatusRecoveryActionType.OPEN_RECORDING_SETUP,
                labelRes = R.string.processing_status_action_recording_setup,
            )
            message.isCredentialOrAuthMessage() -> ProcessingStatusRecoveryAction(
                type = ProcessingStatusRecoveryActionType.RECONNECT_SOURCE,
                labelRes = R.string.action_reconnect,
            )
            sourceStatus?.status == SourceConnectionStatus.ERROR -> ProcessingStatusRecoveryAction(
                type = ProcessingStatusRecoveryActionType.RECONNECT_SOURCE,
                labelRes = R.string.action_reconnect,
            )
            sourceType.isSyncRetryableSource() -> ProcessingStatusRecoveryAction(
                type = ProcessingStatusRecoveryActionType.RETRY_SOURCE_SYNC,
                labelRes = R.string.evidence_import_status_action_retry_failed,
            )
            else -> null
        }

    private fun errorRecoveryAction(
        sourceType: String,
        message: String,
        sourceStatus: SourceStatus?,
    ): ProcessingStatusRecoveryAction? {
        val sourceError = sourceStatus?.errorMessage.normalizedRecoveryMessage()
        if (message.isCredentialOrAuthMessage() || sourceError.isCredentialOrAuthMessage()) {
            return ProcessingStatusRecoveryAction(
                type = ProcessingStatusRecoveryActionType.RECONNECT_SOURCE,
                labelRes = R.string.action_reconnect,
            )
        }
        if (!sourceType.isSyncRetryableSource()) return null
        return ProcessingStatusRecoveryAction(
            type = ProcessingStatusRecoveryActionType.RETRY_SOURCE_SYNC,
            labelRes = R.string.evidence_import_status_action_retry_failed,
        )
    }
}

private fun ProcessingSourceState.withSourceStatusOverlay(sourceStatus: SourceStatus?): ProcessingSourceState {
    if (phase != ProcessingPhase.IDLE) return this
    return when (sourceStatus?.status) {
        SourceConnectionStatus.SYNCING -> copy(
            phase = ProcessingPhase.SCANNING,
            message = message ?: sourceStatus.errorMessage,
        )
        SourceConnectionStatus.ERROR -> copy(
            phase = ProcessingPhase.ERROR,
            message = message ?: sourceStatus.errorMessage,
        )
        else -> this
    }
}

private fun RawIngestionEventEntity.toProcessingStatusItem(): ProcessingStatusItem =
    ProcessingStatusItem(
        id = id,
        sourceType = sourceType,
        title = eventTitle,
        sourceRef = sourceRef,
        durationSeconds = durationSeconds,
        syncStatus = syncStatus,
        updatedAt = lastAttemptAt ?: timestamp,
    )

@Composable
public fun ProcessingStatusScreen(
    navController: NavHostController,
    viewModel: ProcessingStatusViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ProcessingStatusContent(
        state = state,
        onBack = navController::popBackStack,
        onOpenSource = { sourceType ->
            navController.navigate(BecalmRoute.SourceDetail(sourceType).path)
        },
        onRecoveryAction = viewModel::onRecoveryAction,
        onConfirmAudioItem = viewModel::onConfirmAudioItem,
        onSkipAudioItem = viewModel::onSkipAudioItem,
    )
    CollectFlowEffect(viewModel.effects) { effect ->
        when (effect) {
            is ProcessingStatusEffect.OpenReconnect ->
                navController.dispatchSourceDetailEffect(
                    SourceDetailEffect.OpenReconnect(
                        destination = effect.destination,
                        sourceType = effect.sourceType,
                        sourceConnectionId = effect.sourceConnectionId,
                    ),
                )
            ProcessingStatusEffect.OpenConsentSettings ->
                navController.navigate(BecalmRoute.Settings.path)
        }
    }
}

@Composable
internal fun ProcessingStatusContent(
    state: ProcessingStatusUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenSource: (String) -> Unit = {},
    onRecoveryAction: (String, ProcessingStatusRecoveryActionType) -> Unit = { _, _ -> },
    onConfirmAudioItem: (String) -> Unit = {},
    onSkipAudioItem: (String) -> Unit = {},
) {
    val visibleRows = state.rows.filter { it.phase != ProcessingPhase.IDLE }
    val activeRows = visibleRows.filter { it.phase.isActive }
    val actionRows = visibleRows.filter { it.phase.isActionRowPhase() }
    val quietRows = visibleRows.filter { row ->
        !row.phase.isActive && !row.phase.isActionRowPhase()
    }
    val activeTitle = stringResource(R.string.processing_status_group_active)
    val actionTitle = stringResource(R.string.processing_status_group_action_needed)
    val quietTitle = stringResource(R.string.processing_status_group_quiet)

    BecalmScaffold(
        modifier = modifier,
        title = stringResource(R.string.processing_status_title),
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                )
            }
        },
    ) { padding ->
        if (visibleRows.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.processing_status_empty_title),
                message = stringResource(R.string.processing_status_empty_message),
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    .testTag("processing-status-list"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "summary") {
                    ProcessingSummary(activeCount = activeRows.size, actionCount = actionRows.size)
                }
                processingGroup(
                    key = "active",
                    title = activeTitle,
                    rows = activeRows,
                    onOpenSource = onOpenSource,
                    onRecoveryAction = onRecoveryAction,
                    onConfirmAudioItem = onConfirmAudioItem,
                    onSkipAudioItem = onSkipAudioItem,
                )
                processingGroup(
                    key = "action",
                    title = actionTitle,
                    rows = actionRows,
                    onOpenSource = onOpenSource,
                    onRecoveryAction = onRecoveryAction,
                    onConfirmAudioItem = onConfirmAudioItem,
                    onSkipAudioItem = onSkipAudioItem,
                )
                processingGroup(
                    key = "quiet",
                    title = quietTitle,
                    rows = quietRows,
                    onOpenSource = onOpenSource,
                    onRecoveryAction = onRecoveryAction,
                    onConfirmAudioItem = onConfirmAudioItem,
                    onSkipAudioItem = onSkipAudioItem,
                )
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.processingGroup(
    key: String,
    title: String,
    rows: List<ProcessingStatusRow>,
    onOpenSource: (String) -> Unit,
    onRecoveryAction: (String, ProcessingStatusRecoveryActionType) -> Unit,
    onConfirmAudioItem: (String) -> Unit,
    onSkipAudioItem: (String) -> Unit,
) {
    if (rows.isEmpty()) return
    item(key = "$key-title") {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    items(rows, key = { "$key-${it.sourceType}" }) { row ->
        ProcessingStatusRowCard(
            row = row,
            onClick = {
                if (row.opensSourceDetail) onOpenSource(row.sourceType)
            },
            onRecoveryAction = onRecoveryAction,
            onConfirmAudioItem = onConfirmAudioItem,
            onSkipAudioItem = onSkipAudioItem,
        )
    }
}

@Composable
private fun ProcessingSummary(activeCount: Int, actionCount: Int) {
    val text = when {
        activeCount > 0 && actionCount > 0 ->
            stringResource(R.string.processing_status_summary_active_action_fmt, activeCount, actionCount)
        activeCount > 0 -> stringResource(R.string.processing_status_summary_active_fmt, activeCount)
        actionCount > 0 -> stringResource(R.string.processing_status_summary_action_fmt, actionCount)
        else -> stringResource(R.string.processing_status_summary_quiet)
    }
    EvidenceCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ProcessingStatusRowCard(
    row: ProcessingStatusRow,
    onClick: () -> Unit,
    onRecoveryAction: (String, ProcessingStatusRecoveryActionType) -> Unit,
    onConfirmAudioItem: (String) -> Unit,
    onSkipAudioItem: (String) -> Unit,
) {
    val sourceLabel = stringResource(sourcePresentationFor(row.sourceType).labelRes)
    val statusText = row.statusText()
    val openDetailLabel = stringResource(R.string.processing_status_open_source_detail_a11y)
    val itemDescription = if (row.opensSourceDetail) {
        "$sourceLabel, $statusText, $openDetailLabel"
    } else {
        "$sourceLabel, $statusText"
    }
    EvidenceCard(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (row.opensSourceDetail) {
                    Modifier.clickable(
                        onClickLabel = openDetailLabel,
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .semantics {
                contentDescription = itemDescription
                stateDescription = statusText
            },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PhaseIndicator(row.phase)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = sourceLabel,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ProcessingItemPreviewList(
                    row = row,
                    onConfirmAudioItem = onConfirmAudioItem,
                    onSkipAudioItem = onSkipAudioItem,
                )
                ProcessingRecoveryActionButton(
                    row = row,
                    onRecoveryAction = onRecoveryAction,
                )
            }
            if (row.opensSourceDetail) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun ProcessingRecoveryActionButton(
    row: ProcessingStatusRow,
    onRecoveryAction: (String, ProcessingStatusRecoveryActionType) -> Unit,
) {
    val action = row.recoveryAction ?: return
    Spacer(modifier = Modifier.size(12.dp))
    BecalmButton(
        text = stringResource(action.labelRes),
        onClick = { onRecoveryAction(row.sourceType, action.type) },
        variant = BecalmButtonVariant.Secondary,
        loading = row.recoveryInProgress,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("processing-status-recovery-${row.sourceType}"),
    )
}

@Composable
private fun ProcessingItemPreviewList(
    row: ProcessingStatusRow,
    onConfirmAudioItem: (String) -> Unit,
    onSkipAudioItem: (String) -> Unit,
) {
    if (row.items.isEmpty()) return
    val awaitingConfirmation = row.phase == ProcessingPhase.AWAITING_CONFIRMATION
    val previewItems = row.items.take(PROCESSING_ITEM_PREVIEW_LIMIT)
    Text(
        text = stringResource(
            if (awaitingConfirmation) {
                R.string.processing_status_audio_confirmation_heading
            } else {
                R.string.processing_status_items_heading
            },
        ),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 8.dp),
    )
    if (awaitingConfirmation) {
        Text(
            text = stringResource(R.string.processing_status_audio_confirmation_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 2.dp),
        )
    }
    previewItems.forEach { item ->
        ProcessingItemPreviewRow(
            item = item,
            awaitingConfirmation = awaitingConfirmation,
            onConfirmAudioItem = onConfirmAudioItem,
            onSkipAudioItem = onSkipAudioItem,
        )
    }
    val remaining = row.items.size - previewItems.size
    if (remaining > 0) {
        Text(
            text = stringResource(R.string.processing_status_items_more_fmt, remaining),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun ProcessingItemPreviewRow(
    item: ProcessingStatusItem,
    awaitingConfirmation: Boolean,
    onConfirmAudioItem: (String) -> Unit,
    onSkipAudioItem: (String) -> Unit,
) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(
            text = "${item.sourceEmoji()} ${item.displayTitle()}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        item.durationText()?.let { duration ->
            Text(
                text = stringResource(R.string.processing_status_audio_duration_fmt, duration),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (awaitingConfirmation && item.syncStatus == RawIngestionSyncStatus.DETECTED_PENDING_CONFIRMATION) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BecalmButton(
                    text = stringResource(R.string.processing_status_audio_confirm),
                    onClick = { onConfirmAudioItem(item.id) },
                    modifier = Modifier.weight(1f),
                )
                BecalmButton(
                    text = stringResource(R.string.processing_status_audio_skip),
                    onClick = { onSkipAudioItem(item.id) },
                    variant = BecalmButtonVariant.Secondary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PhaseIndicator(phase: ProcessingPhase) {
    if (phase.isActive) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp).testTag("processing-phase-active"),
            strokeWidth = 2.dp,
        )
    } else if (phase.isActionRowPhase()) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .size(20.dp)
                .testTag(
                    if (phase == ProcessingPhase.AWAITING_CONFIRMATION) {
                        "processing-phase-awaiting-confirmation"
                    } else if (phase == ProcessingPhase.ERROR) {
                        "processing-phase-error"
                    } else {
                        "processing-phase-blocked"
                    },
                ),
        )
    } else {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp).testTag("processing-phase-success"),
        )
    }
}

private const val PROCESSING_ITEM_PREVIEW_LIMIT = 3

private fun ProcessingPhase.isActionRowPhase(): Boolean =
    this == ProcessingPhase.AWAITING_CONFIRMATION ||
        this == ProcessingPhase.BLOCKED ||
        this == ProcessingPhase.ERROR

@Composable
private fun ProcessingStatusRow.statusText(): String {
    val countText = if (itemCount > 0) {
        " · ${stringResource(R.string.processing_status_item_count_fmt, itemCount)}"
    } else {
        ""
    }
    val messageText = userFacingMessage()?.let { " · $it" }.orEmpty()
    val timeText = updatedAt?.let { " · ${formatTimeHHmm(it)}" }.orEmpty()
    return stringResource(phaseLabelRes(phase)) + countText + messageText + timeText
}

@Composable
private fun ProcessingStatusRow.userFacingMessage(): String? {
    localizedProcessingStatusMessage(message)?.let { return uiMessageStringResource(it) }
    val normalizedMessage = message.normalizedRecoveryMessage()
    return when {
        phase == ProcessingPhase.ERROR && message?.startsWith("HTTP ") == true ->
            stringResource(R.string.processing_status_error_server_temporary)
        (phase == ProcessingPhase.BLOCKED || phase == ProcessingPhase.ERROR) &&
            normalizedMessage.isCredentialOrAuthMessage() ->
            stringResource(R.string.processing_status_error_reconnect_needed)
        phase == ProcessingPhase.AWAITING_CONFIRMATION ->
            message?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.processing_status_audio_confirmation_required)
        phase == ProcessingPhase.BLOCKED || phase == ProcessingPhase.ERROR ->
            message?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.processing_status_error_reconnect_needed)
        else -> message?.takeIf { it.isNotBlank() }
    }
}

private fun phaseLabelRes(phase: ProcessingPhase): Int = when (phase) {
    ProcessingPhase.IDLE -> R.string.processing_phase_idle
    ProcessingPhase.SCANNING -> R.string.processing_phase_scanning
    ProcessingPhase.NEW_ITEMS -> R.string.processing_phase_new_items
    ProcessingPhase.AWAITING_CONFIRMATION -> R.string.processing_phase_audio_confirmation
    ProcessingPhase.GEMINI -> R.string.processing_phase_memory
    ProcessingPhase.UPLOADING -> R.string.processing_phase_uploading
    ProcessingPhase.NO_NEW_ITEMS -> R.string.processing_phase_no_new_items
    ProcessingPhase.SYNCED -> R.string.processing_phase_synced
    ProcessingPhase.BLOCKED,
    ProcessingPhase.ERROR,
    -> R.string.processing_phase_attention_needed
}

@Composable
private fun ProcessingStatusItem.displayTitle(): String =
    title?.takeIf { it.isNotBlank() }
        ?: sourceRef
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.processing_status_item_untitled)

private fun ProcessingStatusItem.sourceEmoji(): String =
    when (sourceType) {
        SourceType.VOICE -> "🎙️"
        SourceType.CALL_RECORDING -> "📞"
        SourceType.MEETING -> "🗣️"
        else -> "📎"
    }

@Composable
private fun ProcessingStatusItem.durationText(): String? {
    val seconds = durationSeconds?.takeIf { it > 0 } ?: return null
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    return if (minutes > 0) {
        stringResource(R.string.processing_status_audio_duration_min_sec_fmt, minutes, remainingSeconds)
    } else {
        stringResource(R.string.processing_status_audio_duration_sec_fmt, remainingSeconds)
    }
}

private fun formatTimeHHmm(at: Instant): String {
    val local = at.toLocalDateTime(TimeZone.currentSystemDefault())
    val hh = local.hour.toString().padStart(2, '0')
    val mm = local.minute.toString().padStart(2, '0')
    return "$hh:$mm"
}

private fun String?.normalizedRecoveryMessage(): String =
    this.orEmpty().trim().lowercase()

private fun String.isConsentRequiredMessage(): Boolean =
    contains("pipa consent") ||
        contains("consent required") ||
        contains("음성 처리 동의") ||
        contains("동의가 필요")

private fun String.isRecordingSetupMessage(): Boolean =
    contains("audio permission missing") ||
        contains("recording path selection missing") ||
        contains("permission missing") ||
        contains("path selection missing") ||
        contains("권한") ||
        contains("폴더")

private fun String.isCredentialOrAuthMessage(): Boolean =
    contains("needs_reauth") ||
        contains("unauthorized") ||
        contains("credential") ||
        contains("credentials missing") ||
        contains("token") ||
        contains("imap credentials") ||
        contains("인증") ||
        contains("다시 연결")

private fun String.isSyncRetryableSource(): Boolean =
    this == SourceType.GMAIL ||
        this == SourceType.OUTLOOK_MAIL ||
        this == SourceType.NAVER_IMAP ||
        this == SourceType.DAUM_IMAP ||
        this == SourceType.GOOGLE_CALENDAR ||
        this == SourceType.OUTLOOK_CALENDAR
