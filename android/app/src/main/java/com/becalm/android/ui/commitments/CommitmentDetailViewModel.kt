package com.becalm.android.ui.commitments

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.R
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.MeetingSpeakerAliasDao
import com.becalm.android.data.local.db.dao.NoopSourceEventAnchorDao
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.local.db.dao.SourceEventAnchorDao
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.MeetingSpeakerAliasEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.local.db.entity.SourceEventAnchorEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.PersonActionRefreshStats
import com.becalm.android.data.repository.PersonActionRepository
import com.becalm.android.data.repository.PersonEnrichmentRepository
import com.becalm.android.data.repository.SourceArtifactRepository
import com.becalm.android.data.repository.SourceOriginalContext
import com.becalm.android.data.repository.SourceOriginalResolver
import com.becalm.android.domain.commitment.CommitmentState
import com.becalm.android.ui.actions.PersonActionEvidenceDetailUi
import com.becalm.android.ui.actions.PersonActionItemUi
import com.becalm.android.ui.actions.toPersonActionEvidenceDetailUi
import com.becalm.android.ui.actions.toPersonActionItemUi
import com.becalm.android.ui.components.UiMessage
import com.becalm.android.ui.navigation.BecalmRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant

public enum class CommitmentSheetAction {
    REMIND,
    FOLLOW_UP,
    COMPLETE,
    CANCEL,
}

public data class CommitmentDetailActionState(
    val availableActions: Set<CommitmentSheetAction> = emptySet(),
    val editEnabled: Boolean = false,
    val reminderToggleVisible: Boolean = false,
    val reminderEnabled: Boolean = false,
)

public data class CommitmentSourcePresentation(
    val isManual: Boolean = false,
    val sourceType: String? = null,
    val sourceTitle: String? = null,
    val sourceOccurredAt: Instant? = null,
    val sourceLabel: CommitmentText? = null,
)

public data class CommitmentHistoryPresentation(
    val lastEditedAt: Instant? = null,
    val disputeRaisedAt: Instant? = null,
    val showSupersedeLink: Boolean = false,
)

public data class MeetingTranscriptPresentation(
    val rawEventId: String,
    val originalBodyText: String,
    val bodyText: String,
    val speakerIds: List<String>,
    val aliases: Map<String, String>,
    val truncated: Boolean,
)

public sealed interface CommitmentDetailEffect {
    public data class OpenEdit(val commitmentId: String) : CommitmentDetailEffect
}

// ─── UI state ─────────────────────────────────────────────────────────────────

/**
 * Immutable snapshot of [CommitmentDetailSheet] UI.
 *
 * @property entity Backing commitment row, or null while loading / on soft-delete.
 * @property counterpartyDisplayName Resolved display name for the counterparty (CMT-001
 *   fallback chain: `enrichment.displayName` → `nickname` → `counterpartyRef` →
 *   `counterpartyRaw`). Null when nothing resolves.
 * @property actionState Typed enum mirror of [entity.actionState]; exposed so the
 *   composable can drive button enable/disable state without parsing wire strings.
 * @property loading True until the first emission from [CommitmentRepository.observeById]
 *   lands. The sheet renders a spinner during this window.
 * @property error Non-null when the row is missing or soft-deleted ("삭제된 약속") or
 *   the upstream flow threw.
 */
public data class DetailUiState(
    val entity: CommitmentEntity? = null,
    val quote: String = "",
    val counterpartyDisplayName: String? = null,
    val actionState: CommitmentState = CommitmentState.PENDING,
    val source: CommitmentSourcePresentation = CommitmentSourcePresentation(),
    val actionButtons: CommitmentDetailActionState = CommitmentDetailActionState(),
    val history: CommitmentHistoryPresentation = CommitmentHistoryPresentation(),
    val meetingTranscript: MeetingTranscriptPresentation? = null,
    val relatedAction: PersonActionItemUi? = null,
    val evidenceDetail: PersonActionEvidenceDetailUi? = null,
    val sourceEvidenceDetail: PersonActionEvidenceDetailUi? = null,
    val loadingEvidenceActionId: String? = null,
    val loadingSourceEvidence: Boolean = false,
    val loading: Boolean = true,
    val error: UiMessage? = null,
)

// ─── ViewModel ────────────────────────────────────────────────────────────────

private const val TAG = "CommitmentDetailVM"

/**
 * ViewModel for [CommitmentDetailSheet] (CMT-003 + EDIT-008 + MAN-004).
 *
 * Subscribes to [CommitmentRepository.observeById] for the commitment identified by
 * the `id` nav argument and joins it against [PersonEnrichmentRepository.observeEnrichmentMap]
 * so the counterparty display name is resolved through the PIPA-scoped on-device
 * enrichment table.
 *
 * No mutating actions — state transitions remain the responsibility of
 * [CommitmentManagementViewModel]. The sheet composable holds a second
 * `CommitmentManagementViewModel` handle to call `onRemind/onFollowUp/onComplete/onCancel`;
 * both VMs talk to the same singleton repository so the detached instance is safe.
 *
 * @param commitmentRepository Source of the reactive commitment row.
 * @param personEnrichmentRepository PIPA-scoped on-device enrichment map source.
 * @param savedStateHandle Navigation argument carrier; expects
 *   [BecalmRoute.CommitmentDetail.ARG_ID].
 * @param logger Structured log sink.
 */
@HiltViewModel
public class CommitmentDetailViewModel @Inject constructor(
    private val commitmentRepository: CommitmentRepository,
    private val personEnrichmentRepository: PersonEnrichmentRepository,
    private val rawIngestionEventDao: RawIngestionEventDao,
    private val sourceEventAnchorDao: SourceEventAnchorDao = NoopSourceEventAnchorDao,
    private val meetingSpeakerAliasDao: MeetingSpeakerAliasDao,
    private val sourceArtifactRepository: SourceArtifactRepository,
    private val sourceOriginalResolver: SourceOriginalResolver,
    private val personActionRepository: PersonActionRepository = NoopCommitmentDetailPersonActionRepository,
    private val userPrefsStore: UserPrefsStore,
    savedStateHandle: SavedStateHandle,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val id: String =
        savedStateHandle.get<String>(BecalmRoute.CommitmentDetail.ARG_ID).orEmpty()

    private val _uiState: MutableStateFlow<DetailUiState> = MutableStateFlow(DetailUiState())

    /** Current UI state; starts loading and settles on the first Room emission. */
    public val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    private val _effects: MutableSharedFlow<CommitmentDetailEffect> = MutableSharedFlow(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    public val effects: SharedFlow<CommitmentDetailEffect> = _effects.asSharedFlow()

    init {
        if (id.isEmpty()) {
            _uiState.update {
                it.copy(loading = false, error = UiMessage.resource(R.string.commitment_detail_error_missing_id))
            }
        } else {
            observe()
        }
    }

    public fun onEditClick() {
        if (_uiState.value.actionButtons.editEnabled) {
            _effects.tryEmit(CommitmentDetailEffect.OpenEdit(id))
        }
    }

    public fun onSpeakerAliasChange(speakerId: String, displayName: String) {
        val current = _uiState.value.meetingTranscript ?: return
        val normalizedSpeakerId = speakerId.trim()
        val normalizedDisplayName = displayName.trim().ifBlank { normalizedSpeakerId }
        if (normalizedSpeakerId !in current.speakerIds) return
        viewModelScope.launch(ioDispatcher) {
            val userId = userPrefsStore.observeCurrentUserId().firstOrNull() ?: return@launch
            meetingSpeakerAliasDao.upsert(
                MeetingSpeakerAliasEntity(
                    userId = userId,
                    rawEventId = current.rawEventId,
                    speakerId = normalizedSpeakerId,
                    displayName = normalizedDisplayName,
                    updatedAt = kotlinx.datetime.Clock.System.now(),
                ),
            )
            _uiState.update { state ->
                val transcript = state.meetingTranscript ?: return@update state
                val aliases = transcript.aliases + (normalizedSpeakerId to normalizedDisplayName)
                state.copy(
                    meetingTranscript = transcript.copy(
                        aliases = aliases,
                        bodyText = applySpeakerAliases(transcript.originalBodyText, aliases),
                    ),
                )
            }
        }
    }

    // ─── Private ──────────────────────────────────────────────────────────────

    private fun observe() {
        viewModelScope.launch {
            // User-scoped observation: the deep link / nav argument carries only
            // the commitment id, but the shared Room DB could otherwise surface
            // another account's row after account switching. Resolving the user
            // id here and switching to observeByIdForUser closes that gap
            // (cross-account leak guard, data-model.yml:476).
            userPrefsStore.observeCurrentUserId()
                .flatMapLatest { userId ->
                    if (userId.isNullOrBlank()) {
                        flowOf(CommitmentDetailProjector.buildMissingState())
                    } else {
                        combine(
                            commitmentRepository.observeByIdForUser(userId, id),
                            personEnrichmentRepository.observeEnrichmentMap(),
                            userPrefsStore.observeDisabledCommitmentReminderIds(),
                            personActionRepository.observeActiveForCommitment(userId, id, limit = 10),
                        ) { entity, enrichment, disabledReminderIds, actionRows ->
                            if (entity == null) {
                                CommitmentDetailProjector.buildMissingState()
                            } else {
                                CommitmentDetailProjector.buildLoadedState(
                                    entity = entity,
                                    enrichment = enrichment,
                                    meetingTranscript = loadMeetingTranscript(userId, entity),
                                    disabledReminderIds = disabledReminderIds,
                                ).copy(
                                    relatedAction = actionRows
                                        .sortedWith(
                                            compareByDescending<com.becalm.android.data.local.db.entity.PersonActionItemCacheEntity> { it.urgencyScore }
                                                .thenByDescending { it.updatedAt },
                                        )
                                        .firstOrNull()
                                        ?.toPersonActionItemUi(),
                                )
                            }
                        }
                    }
                }
                .distinctUntilChanged()
                .flowOn(ioDispatcher)
                .catch { e ->
                    logger.e(TAG, "observe failed id=${hashId(id)}", e)
                    _uiState.update {
                        it.copy(loading = false, error = UiMessage.resource(R.string.commitment_detail_error_load_failed))
                    }
                }
                .collect { state ->
                    _uiState.update { current ->
                        state.copy(
                            evidenceDetail = current.evidenceDetail,
                            sourceEvidenceDetail = current.sourceEvidenceDetail,
                            loadingEvidenceActionId = current.loadingEvidenceActionId,
                            loadingSourceEvidence = current.loadingSourceEvidence,
                        )
                    }
                }
        }
    }

    public fun onOpenRelatedActionEvidence(
        actionItemId: String,
        evidenceKind: String?,
        evidenceId: String?,
    ) {
        if (actionItemId.isBlank() || evidenceKind.isNullOrBlank() || evidenceId.isNullOrBlank()) {
            _uiState.update { it.copy(error = UiMessage.resource(R.string.commitments_error_evidence_failed)) }
            return
        }
        viewModelScope.launch(ioDispatcher) {
            val userId = userPrefsStore.observeCurrentUserId().firstOrNull()
            if (userId.isNullOrBlank()) {
                _uiState.update { it.copy(error = UiMessage.resource(R.string.commitments_error_evidence_failed)) }
                return@launch
            }
            _uiState.update { it.copy(loadingEvidenceActionId = actionItemId, error = null) }
            when (
                val result = personActionRepository.fetchEvidenceOriginal(
                    userId = userId,
                    actionItemId = actionItemId,
                    evidenceKind = evidenceKind,
                    evidenceId = evidenceId,
                )
            ) {
                is BecalmResult.Success -> {
                    _uiState.update {
                        it.copy(
                            loadingEvidenceActionId = null,
                            evidenceDetail = result.value.toPersonActionEvidenceDetailUi(),
                            error = null,
                        )
                    }
                }
                is BecalmResult.Failure -> {
                    logger.w(TAG, "commitment detail evidence failed id=${hashId(actionItemId)}: ${result.error}")
                    _uiState.update {
                        it.copy(
                            loadingEvidenceActionId = null,
                            error = UiMessage.resource(R.string.commitments_error_evidence_failed),
                        )
                    }
                }
            }
        }
    }

    public fun onOpenSourceEvidence() {
        val entity = _uiState.value.entity ?: return
        viewModelScope.launch(ioDispatcher) {
            val userId = userPrefsStore.observeCurrentUserId().firstOrNull()
            if (userId.isNullOrBlank()) {
                _uiState.update { it.copy(error = UiMessage.resource(R.string.commitments_error_evidence_failed)) }
                return@launch
            }
            _uiState.update { it.copy(loadingSourceEvidence = true, error = null) }
            val detail = runCatching { buildSourceEvidenceDetail(userId, entity) }
                .onFailure { e -> logger.w(TAG, "source evidence failed id=${hashId(entity.id)}", e) }
                .getOrNull()
            if (detail == null) {
                _uiState.update {
                    it.copy(
                        loadingSourceEvidence = false,
                        error = UiMessage.resource(R.string.commitments_error_evidence_failed),
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        loadingSourceEvidence = false,
                        sourceEvidenceDetail = detail,
                        error = null,
                    )
                }
            }
        }
    }

    public fun onDismissRelatedActionEvidence() {
        _uiState.update { it.copy(evidenceDetail = null) }
    }

    public fun onDismissSourceEvidence() {
        _uiState.update { it.copy(sourceEvidenceDetail = null) }
    }

    private suspend fun loadMeetingTranscript(
        userId: String,
        entity: CommitmentEntity,
    ): MeetingTranscriptPresentation? {
        if (entity.sourceType != SourceType.MEETING || entity.itemType != CommitmentItemType.SCHEDULE) return null
        val rawEvent = entity.sourceEventId
            ?.takeIf { it.isNotBlank() }
            ?.let { rawIngestionEventDao.findById(it, userId) }
            ?.takeIf { it.sourceType == SourceType.MEETING }
            ?: entity.sourceRef
                ?.takeIf { it.isNotBlank() }
                ?.let { sourceRef ->
                    rawIngestionEventDao.findBySourceRefsForUser(userId, listOf(sourceRef))
                        .firstOrNull { it.sourceType == SourceType.MEETING }
                }
            ?: return null
        val archived = sourceArtifactRepository.findMarkdownOriginal(userId, rawEvent.id) ?: return null
        val markdown = archived.markdown?.takeIf { it.isNotBlank() } ?: return null
        val aliases = meetingSpeakerAliasDao.findForRawEvent(userId, rawEvent.id)
            .associate { it.speakerId to it.displayName }
        val speakerIds = extractSpeakerIds(markdown)
        return MeetingTranscriptPresentation(
            rawEventId = rawEvent.id,
            originalBodyText = markdown,
            bodyText = applySpeakerAliases(markdown, aliases),
            speakerIds = speakerIds,
            aliases = aliases,
            truncated = archived.markdownTruncated,
        )
    }

    private fun extractSpeakerIds(markdown: String): List<String> =
        SPEAKER_ID_REGEX.findAll(markdown)
            .map { it.value }
            .distinct()
            .sorted()
            .toList()

    private suspend fun buildSourceEvidenceDetail(
        userId: String,
        entity: CommitmentEntity,
    ): PersonActionEvidenceDetailUi? {
        if (entity.sourceType == SourceType.MANUAL) return null
        val sourceEvent = resolveCommitmentSourceEvent(userId, entity)
        val original = sourceEvent?.let { source ->
            sourceOriginalResolver.resolve(
                userId = userId,
                event = source.event,
                fallbackRawEventIds = source.fallbackRawEventIds,
            )
        }
        val originalText = original?.toDisplayText()
            ?: entity.quote.takeIf { it.isNotBlank() }
            ?: entity.description?.takeIf { it.isNotBlank() }
            ?: return null
        return PersonActionEvidenceDetailUi(
            actionItemId = entity.id,
            evidenceLabel = entity.sourceEventTitle?.takeIf { it.isNotBlank() } ?: entity.title,
            whyText = entity.quote.takeIf { it.isNotBlank() } ?: entity.title,
            originalTitle = sourceEvent?.event?.eventTitle?.takeIf { it.isNotBlank() }
                ?: entity.sourceEventTitle?.takeIf { it.isNotBlank() },
            originalText = originalText,
            sourceType = entity.sourceType,
        )
    }

    private suspend fun resolveCommitmentSourceEvent(
        userId: String,
        entity: CommitmentEntity,
    ): CommitmentSourceEvent? {
        val idCandidates = listOfNotNull(
            entity.sourceEventId,
            entity.sourceRef?.removePrefix("raw:"),
        ).distinctNonBlank()
        firstRawEventById(userId, idCandidates)?.let {
            return CommitmentSourceEvent(event = it, fallbackRawEventIds = emptyList())
        }

        val anchor = firstSourceAnchor(
            userId = userId,
            refs = listOfNotNull(entity.sourceEventId, entity.sourceRef).distinctNonBlank(),
        )
        if (anchor != null) {
            anchor.localRawEventId
                ?.takeIf { it.isNotBlank() }
                ?.let { rawIngestionEventDao.findById(it, userId) }
                ?.let { raw ->
                    return CommitmentSourceEvent(event = raw, fallbackRawEventIds = emptyList())
                }
            return CommitmentSourceEvent(
                event = anchor.toSyntheticRawEvent(),
                fallbackRawEventIds = listOfNotNull(anchor.localRawEventId).distinctNonBlank(),
            )
        }

        val sourceRefCandidates = listOfNotNull(
            entity.sourceRef,
            entity.sourceEventId?.let { "raw:$it" },
            entity.sourceEventId,
        ).distinctNonBlank()
        if (sourceRefCandidates.isNotEmpty()) {
            rawIngestionEventDao.findBySourceRefsForUser(userId, sourceRefCandidates)
                .firstOrNull()
                ?.let { return CommitmentSourceEvent(event = it, fallbackRawEventIds = emptyList()) }
        }

        return entity.toSyntheticRawEvent()?.let {
            CommitmentSourceEvent(event = it, fallbackRawEventIds = emptyList())
        }
    }

    private suspend fun firstRawEventById(
        userId: String,
        ids: List<String>,
    ): RawIngestionEventEntity? {
        for (candidate in ids) {
            rawIngestionEventDao.findById(candidate, userId)?.let { return it }
        }
        return null
    }

    private suspend fun firstSourceAnchor(
        userId: String,
        refs: List<String>,
    ): SourceEventAnchorEntity? {
        for (ref in refs) {
            sourceEventAnchorDao.findBestForEventRef(userId = userId, eventRef = ref)?.let { return it }
        }
        return null
    }

    private fun SourceEventAnchorEntity.toSyntheticRawEvent(): RawIngestionEventEntity =
        RawIngestionEventEntity(
            id = sourceEventId ?: localRawEventId ?: id,
            userId = userId,
            clientEventId = localRawEventId ?: sourceEventId ?: id,
            sourceType = sourceType,
            sourceRef = sourceRef ?: providerEventId,
            eventTitle = title,
            eventSnippet = snippet,
            conversationRef = conversationRef,
            commitmentsExtractedCount = 0,
            timestamp = occurredAt ?: kotlinx.datetime.Clock.System.now(),
            syncStatus = "synced",
        )

    private fun CommitmentEntity.toSyntheticRawEvent(): RawIngestionEventEntity? {
        val eventId = sourceEventId?.takeIf { it.isNotBlank() }
            ?: sourceRef?.removePrefix("raw:")?.takeIf { it.isNotBlank() }
            ?: return null
        return RawIngestionEventEntity(
            id = eventId,
            userId = userId,
            clientEventId = sourceRef?.removePrefix("raw:")?.takeIf { it.isNotBlank() } ?: eventId,
            sourceType = sourceType,
            sourceRef = sourceRef,
            eventTitle = sourceEventTitle,
            eventSnippet = quote,
            commitmentsExtractedCount = 1,
            timestamp = sourceEventOccurredAt,
            syncStatus = syncStatus,
        )
    }

    private fun SourceOriginalContext.toDisplayText(): String? =
        listOfNotNull(
            emailBody?.bodyPlain?.takeIf { it.isNotBlank() },
            emailBody?.bodyHtml?.takeIf { it.isNotBlank() },
            archivedOriginal?.markdown?.takeIf { it.isNotBlank() },
        ).firstOrNull()

    private fun List<String>.distinctNonBlank(): List<String> =
        map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    private fun applySpeakerAliases(markdown: String, aliases: Map<String, String>): String =
        aliases.entries.fold(markdown) { acc, (speakerId, alias) ->
            acc.replace(Regex("\\b${Regex.escape(speakerId)}\\b"), alias)
        }

    private fun hashId(id: String): String = "%08x".format(id.hashCode())

    private companion object {
        private val SPEAKER_ID_REGEX = Regex("\\bSPEAKER_\\d+\\b")
    }
}

private data class CommitmentSourceEvent(
    val event: RawIngestionEventEntity,
    val fallbackRawEventIds: List<String>,
)

private object NoopCommitmentDetailPersonActionRepository : PersonActionRepository {
    override fun observeActiveForSurface(
        userId: String,
        surface: String,
        limit: Int,
    ): kotlinx.coroutines.flow.Flow<List<com.becalm.android.data.local.db.entity.PersonActionItemCacheEntity>> =
        flowOf(emptyList())

    override fun observeActiveForPerson(
        userId: String,
        personId: String,
        limit: Int,
    ): kotlinx.coroutines.flow.Flow<List<com.becalm.android.data.local.db.entity.PersonActionItemCacheEntity>> =
        flowOf(emptyList())

    override fun observeActiveForCommitment(
        userId: String,
        commitmentId: String,
        limit: Int,
    ): kotlinx.coroutines.flow.Flow<List<com.becalm.android.data.local.db.entity.PersonActionItemCacheEntity>> =
        flowOf(emptyList())

    override fun observeActiveForCalendarEvent(
        userId: String,
        calendarEventId: String,
        limit: Int,
    ): kotlinx.coroutines.flow.Flow<List<com.becalm.android.data.local.db.entity.PersonActionItemCacheEntity>> =
        flowOf(emptyList())

    override suspend fun refresh(
        userId: String,
        surface: String?,
    ): BecalmResult<PersonActionRefreshStats> =
        BecalmResult.Success(
            PersonActionRefreshStats(
                fetched = 0,
                deleted = 0,
                serverWatermark = null,
                recomputeState = null,
            ),
        )

    override suspend fun completeAction(
        userId: String,
        actionItemId: String,
        expectedUpdatedAt: kotlinx.datetime.Instant?,
    ): BecalmResult<com.becalm.android.data.repository.PersonActionMutationSyncStats> =
        BecalmResult.Success(noopMutationStats())

    override suspend fun dismissAction(
        userId: String,
        actionItemId: String,
        reason: String?,
        expectedUpdatedAt: kotlinx.datetime.Instant?,
    ): BecalmResult<com.becalm.android.data.repository.PersonActionMutationSyncStats> =
        BecalmResult.Success(noopMutationStats())

    override suspend fun snoozeAction(
        userId: String,
        actionItemId: String,
        snoozedUntil: kotlinx.datetime.Instant,
        reason: String?,
        expectedUpdatedAt: kotlinx.datetime.Instant?,
    ): BecalmResult<com.becalm.android.data.repository.PersonActionMutationSyncStats> =
        BecalmResult.Success(noopMutationStats())

    override suspend fun submitActionFeedback(
        userId: String,
        actionItemId: String,
        feedbackType: String,
        reason: String?,
        correctedPersonId: String?,
        correctedDueAt: kotlinx.datetime.Instant?,
    ): BecalmResult<com.becalm.android.data.repository.PersonActionMutationSyncStats> =
        BecalmResult.Success(noopMutationStats())

    override suspend fun syncPendingMutations(
        userId: String,
        limit: Int,
    ): BecalmResult<com.becalm.android.data.repository.PersonActionMutationSyncStats> =
        BecalmResult.Success(noopMutationStats())

    private fun noopMutationStats(): com.becalm.android.data.repository.PersonActionMutationSyncStats =
        com.becalm.android.data.repository.PersonActionMutationSyncStats(queued = 0, synced = 0, retryable = 0, failed = 0)
}
