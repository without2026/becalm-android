package com.becalm.android.data.repository

import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.local.db.dao.SourceEventAnchorDao
import com.becalm.android.data.local.db.dao.PersonActionDao
import com.becalm.android.data.local.db.entity.PersonActionItemCacheEntity
import com.becalm.android.data.local.db.entity.PersonActionMutationQueueEntity
import com.becalm.android.data.local.db.entity.PersonActionSyncStateEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.local.db.entity.SourceEventAnchorEntity
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.CalendarWriteJobResponseDto
import com.becalm.android.data.remote.dto.ErrorEnvelopeDto
import com.becalm.android.data.remote.dto.PersonActionDraftDto
import com.becalm.android.data.remote.dto.PersonActionDraftEvidenceRefDto
import com.becalm.android.data.remote.dto.PersonActionDraftRequestDto
import com.becalm.android.data.remote.dto.PersonActionEvidenceOriginalDto
import com.becalm.android.data.remote.dto.PersonActionEvidenceRefDto
import com.becalm.android.data.remote.dto.PersonActionFeedbackDto
import com.becalm.android.data.remote.dto.PersonActionFeedResponseDto
import com.becalm.android.data.remote.dto.PersonActionRecoveryActionDto
import com.becalm.android.data.remote.dto.PersonActionItemDto
import com.becalm.android.data.remote.dto.PersonActionProviderWritePatchDto
import com.becalm.android.data.remote.dto.PersonActionStatePatchDto
import com.becalm.android.data.remote.dto.PersonActionEmptyStateDto
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import retrofit2.Response
import java.util.UUID
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds

public interface PersonActionRepository {
    public fun observeActiveForSurface(userId: String, surface: String, limit: Int = 100): Flow<List<PersonActionItemCacheEntity>>
    public fun observeActiveForPerson(userId: String, personId: String, limit: Int = 100): Flow<List<PersonActionItemCacheEntity>>
    public fun observeActiveForCommitment(userId: String, commitmentId: String, limit: Int = 100): Flow<List<PersonActionItemCacheEntity>>
    public fun observeActiveForCalendarEvent(userId: String, calendarEventId: String, limit: Int = 100): Flow<List<PersonActionItemCacheEntity>>
    public fun observeSyncState(
        userId: String,
        surface: String? = null,
        status: String = "active",
    ): Flow<PersonActionSyncStateEntity?> = kotlinx.coroutines.flow.flowOf(null)
    public suspend fun refresh(userId: String, surface: String? = null): BecalmResult<PersonActionRefreshStats>
    public suspend fun completeAction(userId: String, actionItemId: String, expectedUpdatedAt: Instant? = null): BecalmResult<PersonActionMutationSyncStats>
    public suspend fun completeActionWithProviderWrite(
        userId: String,
        actionItemId: String,
        providerWrite: PersonActionProviderWriteRequest,
        expectedUpdatedAt: Instant? = null,
    ): BecalmResult<PersonActionMutationSyncStats> =
        completeAction(userId = userId, actionItemId = actionItemId, expectedUpdatedAt = expectedUpdatedAt)
    public suspend fun dismissAction(userId: String, actionItemId: String, reason: String? = null, expectedUpdatedAt: Instant? = null): BecalmResult<PersonActionMutationSyncStats>
    public suspend fun snoozeAction(userId: String, actionItemId: String, snoozedUntil: Instant, reason: String? = null, expectedUpdatedAt: Instant? = null): BecalmResult<PersonActionMutationSyncStats>
    public suspend fun submitActionFeedback(
        userId: String,
        actionItemId: String,
        feedbackType: String,
        reason: String? = null,
        correctedPersonId: String? = null,
        correctedDueAt: Instant? = null,
    ): BecalmResult<PersonActionMutationSyncStats>
    public suspend fun fetchEvidenceOriginal(
        userId: String,
        actionItemId: String,
        evidenceKind: String,
        evidenceId: String,
    ): BecalmResult<PersonActionEvidenceOriginalDto> =
        BecalmResult.Failure(BecalmError.NotFound("person_action_evidence"))
    public suspend fun generateDraft(
        userId: String,
        actionItemId: String,
        draftKind: String,
        channel: String = "email",
        evidenceRefs: List<PersonActionDraftEvidenceRef> = emptyList(),
        userInstruction: String? = null,
    ): BecalmResult<PersonActionDraftDto> =
        BecalmResult.Failure(BecalmError.NotFound("person_action_draft"))
    public suspend fun fetchCalendarWriteJobStatus(
        userId: String,
        jobId: String,
    ): BecalmResult<CalendarWriteJobStatus> =
        BecalmResult.Failure(BecalmError.NotFound("calendar_write_job"))
    public suspend fun syncPendingMutations(userId: String, limit: Int = 20): BecalmResult<PersonActionMutationSyncStats>
}

public data class PersonActionRefreshStats(
    val fetched: Int,
    val deleted: Int,
    val serverWatermark: kotlinx.datetime.Instant?,
    val recomputeState: String?,
)

public data class PersonActionMutationSyncStats(
    val queued: Int,
    val synced: Int,
    val retryable: Int,
    val failed: Int,
    val providerWriteJobId: String? = null,
)

public data class PersonActionProviderWriteRequest(
    val kind: String = "add_to_calendar",
    val provider: String?,
    val sourceConnectionId: String?,
    val scheduleEventLinkId: String?,
)

public data class PersonActionDraftEvidenceRef(
    val kind: String,
    val evidenceId: String,
)

public data class CalendarWriteJobStatus(
    val jobId: String,
    val status: String,
    val accepted: Boolean,
    val retryAfterSeconds: Int? = null,
    val provider: String? = null,
    val writeKind: String? = null,
    val actionItemId: String? = null,
    val scheduleEventLinkId: String? = null,
    val sourceConnectionId: String? = null,
    val providerEventId: String? = null,
    val calendarEventId: String? = null,
    val attempts: Int = 0,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val nextAttemptAt: Instant? = null,
    val clientAction: String? = null,
)

@Singleton
public class PersonActionRepositoryImpl @Inject constructor(
    private val dao: PersonActionDao,
    private val apiProvider: Provider<RailwayApi>,
    private val rawIngestionEventDao: RawIngestionEventDao? = null,
    private val sourceEventAnchorDao: SourceEventAnchorDao? = null,
    private val sourceOriginalResolver: SourceOriginalResolver? = null,
    moshi: Moshi,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PersonActionRepository {
    private val api: RailwayApi
        get() = apiProvider.get()
    private val statePatchPayloadAdapter = moshi.adapter(StatePatchMutationPayload::class.java)
    private val feedbackPayloadAdapter = moshi.adapter(FeedbackMutationPayload::class.java)
    private val errorEnvelopeAdapter = moshi.adapter(ErrorEnvelopeDto::class.java)
    private val recoveryActionsAdapter = moshi.adapter<List<PersonActionRecoveryActionDto>>(
        Types.newParameterizedType(List::class.java, PersonActionRecoveryActionDto::class.java),
    )
    private val emptyStateAdapter = moshi.adapter(PersonActionEmptyStateDto::class.java)
    private val serverTimingAdapter = moshi.adapter<Map<String, Double>>(
        Types.newParameterizedType(Map::class.java, String::class.java, java.lang.Double::class.javaObjectType),
    )

    override fun observeActiveForSurface(userId: String, surface: String, limit: Int): Flow<List<PersonActionItemCacheEntity>> =
        dao.observeActiveForSurface(userId = userId, surface = surface, limit = limit)

    override fun observeActiveForPerson(userId: String, personId: String, limit: Int): Flow<List<PersonActionItemCacheEntity>> =
        dao.observeActiveForPerson(userId = userId, personId = personId, limit = limit)

    override fun observeActiveForCommitment(userId: String, commitmentId: String, limit: Int): Flow<List<PersonActionItemCacheEntity>> =
        dao.observeActiveForCommitment(userId = userId, commitmentId = commitmentId, limit = limit)

    override fun observeActiveForCalendarEvent(userId: String, calendarEventId: String, limit: Int): Flow<List<PersonActionItemCacheEntity>> =
        dao.observeActiveForCalendarEvent(userId = userId, calendarEventId = calendarEventId, limit = limit)

    override fun observeSyncState(
        userId: String,
        surface: String?,
        status: String,
    ): Flow<PersonActionSyncStateEntity?> =
        dao.observeSyncState(userId = userId, surfaceKey = surface.toSyncSurfaceKey(), status = status)

    override suspend fun refresh(userId: String, surface: String?): BecalmResult<PersonActionRefreshStats> = withContext(ioDispatcher) {
        val status = "active"
        val surfaceKey = surface.toSyncSurfaceKey()
        val changedSince = dao.latestServerWatermark(userId = userId, surfaceKey = surfaceKey, status = status)?.toString()
        when (val delta = fetchAndApply(userId = userId, changedSince = changedSince, surface = surface)) {
            is BecalmResult.Success -> delta
            is BecalmResult.Failure -> {
                val error = delta.error
                if (changedSince != null && error is BecalmError.Network && error.code == 409) {
                    fetchAndApply(userId = userId, changedSince = null, surface = surface)
                } else {
                    delta
                }
            }
        }
    }

    override suspend fun completeAction(
        userId: String,
        actionItemId: String,
        expectedUpdatedAt: Instant?,
    ): BecalmResult<PersonActionMutationSyncStats> =
        enqueueStatePatch(
            userId = userId,
            actionItemId = actionItemId,
            status = "completed",
            snoozedUntil = null,
            reason = null,
            expectedUpdatedAt = expectedUpdatedAt,
            providerWrite = null,
        )

    override suspend fun completeActionWithProviderWrite(
        userId: String,
        actionItemId: String,
        providerWrite: PersonActionProviderWriteRequest,
        expectedUpdatedAt: Instant?,
    ): BecalmResult<PersonActionMutationSyncStats> =
        enqueueStatePatch(
            userId = userId,
            actionItemId = actionItemId,
            status = "completed",
            snoozedUntil = null,
            reason = null,
            expectedUpdatedAt = expectedUpdatedAt,
            providerWrite = providerWrite,
        )

    override suspend fun dismissAction(
        userId: String,
        actionItemId: String,
        reason: String?,
        expectedUpdatedAt: Instant?,
    ): BecalmResult<PersonActionMutationSyncStats> =
        enqueueStatePatch(
            userId = userId,
            actionItemId = actionItemId,
            status = "dismissed",
            snoozedUntil = null,
            reason = reason,
            expectedUpdatedAt = expectedUpdatedAt,
            providerWrite = null,
        )

    override suspend fun snoozeAction(
        userId: String,
        actionItemId: String,
        snoozedUntil: Instant,
        reason: String?,
        expectedUpdatedAt: Instant?,
    ): BecalmResult<PersonActionMutationSyncStats> =
        enqueueStatePatch(
            userId = userId,
            actionItemId = actionItemId,
            status = "snoozed",
            snoozedUntil = snoozedUntil,
            reason = reason,
            expectedUpdatedAt = expectedUpdatedAt,
            providerWrite = null,
        )

    override suspend fun submitActionFeedback(
        userId: String,
        actionItemId: String,
        feedbackType: String,
        reason: String?,
        correctedPersonId: String?,
        correctedDueAt: Instant?,
    ): BecalmResult<PersonActionMutationSyncStats> = withContext(ioDispatcher) {
        val now = Clock.System.now()
        val payload = feedbackPayloadAdapter.toJson(
            FeedbackMutationPayload(
                feedbackType = feedbackType,
                reason = reason,
                correctedPersonId = correctedPersonId,
                correctedDueAt = correctedDueAt,
                updatedAt = now,
            ),
        )
        enqueueMutation(
            userId = userId,
            actionItemId = actionItemId,
            mutationKind = MUTATION_KIND_FEEDBACK,
            payloadJson = payload,
            now = now,
        )
    }

    override suspend fun fetchEvidenceOriginal(
        userId: String,
        actionItemId: String,
        evidenceKind: String,
        evidenceId: String,
    ): BecalmResult<PersonActionEvidenceOriginalDto> = withContext(ioDispatcher) {
        if (userId.isBlank() || actionItemId.isBlank() || evidenceKind.isBlank() || evidenceId.isBlank()) {
            return@withContext BecalmResult.Failure(BecalmError.Validation("evidence", "missing evidence original lookup id"))
        }
        val response = try {
            api.getPersonActionEvidenceOriginal(
                id = actionItemId,
                evidenceKind = evidenceKind,
                evidenceId = evidenceId,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: java.io.IOException) {
            logger.w(TAG, "person action evidence original network error", error)
            return@withContext BecalmResult.Failure(BecalmError.Network(0, error.message ?: "network error"))
        } catch (error: Exception) {
            logger.e(TAG, "person action evidence original fetch failed", error)
            return@withContext BecalmResult.Failure(BecalmError.Unknown(error))
        }
        if (!response.isSuccessful) {
            return@withContext response.toPersonActionLookupError(
                validationField = "evidence",
                notFoundResource = "person_action_evidence",
            )
        }
        val data = response.body()?.data
            ?: return@withContext BecalmResult.Failure(
                BecalmError.Unknown(IllegalStateException("empty person action evidence original response")),
            )
        BecalmResult.Success(data.withLocalOriginal(userId = userId))
    }

    override suspend fun generateDraft(
        userId: String,
        actionItemId: String,
        draftKind: String,
        channel: String,
        evidenceRefs: List<PersonActionDraftEvidenceRef>,
        userInstruction: String?,
    ): BecalmResult<PersonActionDraftDto> = withContext(ioDispatcher) {
        if (userId.isBlank() || actionItemId.isBlank() || draftKind.isBlank()) {
            return@withContext BecalmResult.Failure(BecalmError.Validation("draft", "missing draft lookup id"))
        }
        val response = try {
            api.generatePersonActionDraft(
                id = actionItemId,
                request = PersonActionDraftRequestDto(
                    clientRequestId = UUID.randomUUID().toString(),
                    draftKind = draftKind,
                    channel = channel,
                    evidenceRefs = evidenceRefs
                        .filter { it.kind.isNotBlank() && it.evidenceId.isNotBlank() }
                        .map { ref ->
                            PersonActionDraftEvidenceRefDto(
                                kind = ref.kind,
                                evidenceId = ref.evidenceId,
                            )
                        },
                    userInstruction = userInstruction?.trim()?.takeIf { it.isNotBlank() },
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: java.io.IOException) {
            logger.w(TAG, "person action draft network error", error)
            return@withContext BecalmResult.Failure(BecalmError.Network(0, error.message ?: "network error"))
        } catch (error: Exception) {
            logger.e(TAG, "person action draft failed", error)
            return@withContext BecalmResult.Failure(BecalmError.Unknown(error))
        }
        if (!response.isSuccessful) {
            return@withContext response.toPersonActionLookupError(
                validationField = "draft",
                notFoundResource = "person_action_draft",
            )
        }
        val data = response.body()?.data
            ?: return@withContext BecalmResult.Failure(
                BecalmError.Unknown(IllegalStateException("empty person action draft response")),
            )
        BecalmResult.Success(data)
    }

    override suspend fun fetchCalendarWriteJobStatus(
        userId: String,
        jobId: String,
    ): BecalmResult<CalendarWriteJobStatus> = withContext(ioDispatcher) {
        if (userId.isBlank() || jobId.isBlank()) {
            return@withContext BecalmResult.Failure(BecalmError.Validation("calendar_write_job", "missing calendar write job id"))
        }
        val response = try {
            api.getCalendarWriteJob(jobId = jobId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: java.io.IOException) {
            logger.w(TAG, "calendar write job network error", error)
            return@withContext BecalmResult.Failure(BecalmError.Network(0, error.message ?: "network error"))
        } catch (error: Exception) {
            logger.e(TAG, "calendar write job fetch failed", error)
            return@withContext BecalmResult.Failure(BecalmError.Unknown(error))
        }
        if (!response.isSuccessful) {
            return@withContext response.toPersonActionLookupError(
                validationField = "calendar_write_job",
                notFoundResource = "calendar_write_job",
            )
        }
        val data = response.body()
            ?: return@withContext BecalmResult.Failure(
                BecalmError.Unknown(IllegalStateException("empty calendar write job response")),
            )
        BecalmResult.Success(data.toCalendarWriteJobStatus())
    }

    private suspend fun PersonActionEvidenceOriginalDto.withLocalOriginal(
        userId: String,
    ): PersonActionEvidenceOriginalDto {
        val resolver = sourceOriginalResolver ?: return this
        val resolution = resolveEvidenceRawEvent(userId) ?: return this
        val local = try {
            resolver.resolve(
                userId = userId,
                event = resolution.event,
                fallbackRawEventIds = resolution.fallbackRawEventIds,
            ).toLocalEvidenceOriginal(resolution.event)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.w(TAG, "person action local original join failed", error)
            null
        } ?: return this
        return copy(
            original = original.copy(
                localOriginalTitle = local.title,
                localOriginalText = local.text,
                localOriginalTruncated = local.truncated,
            ),
        )
    }

    private suspend fun PersonActionEvidenceOriginalDto.resolveEvidenceRawEvent(
        userId: String,
    ): EvidenceRawEventResolution? {
        val rawDao = rawIngestionEventDao ?: return null
        val anchorDao = sourceEventAnchorDao
        for (ref in evidenceLookupRefs()) {
            val anchor = anchorDao?.findBestForEventRef(userId = userId, eventRef = ref)
            if (anchor != null) {
                anchor.toEvidenceRawEventResolution(userId)?.let { return it }
            }
        }

        val idCandidates = evidenceRawIdCandidates()
        if (idCandidates.isNotEmpty()) {
            rawDao.findByIdsForUser(userId = userId, ids = idCandidates)
                .firstOrNull()
                ?.let { return EvidenceRawEventResolution(event = it, fallbackRawEventIds = idCandidates - it.id) }
        }

        val sourceRefCandidates = evidenceSourceRefCandidates()
        if (sourceRefCandidates.isNotEmpty()) {
            rawDao.findBySourceRefsForUser(userId = userId, sourceRefs = sourceRefCandidates)
                .firstOrNull()
                ?.let { return EvidenceRawEventResolution(event = it, fallbackRawEventIds = idCandidates) }
        }

        val sourceType = original.sourceType?.takeIf(String::isNotBlank)
        val conversationRef = original.conversationRef
            ?.takeIf(String::isNotBlank)
            ?: original.metadata["thread_id"]?.takeIf(String::isNotBlank)
        if (sourceType != null && conversationRef != null) {
            rawDao.findByConversationRefForUser(
                userId = userId,
                sourceType = sourceType,
                conversationRef = conversationRef,
                limit = LOCAL_EVIDENCE_CONVERSATION_LOOKUP_LIMIT,
            ).firstOrNull()
                ?.let { return EvidenceRawEventResolution(event = it, fallbackRawEventIds = idCandidates) }
        }

        return null
    }

    private suspend fun SourceEventAnchorEntity.toEvidenceRawEventResolution(
        userId: String,
    ): EvidenceRawEventResolution? {
        val rawDao = rawIngestionEventDao
        val localRawEventId = localRawEventId?.takeIf(String::isNotBlank)
        val sourceEventId = sourceEventId?.takeIf(String::isNotBlank)
        val idCandidates = listOfNotNull(localRawEventId, sourceEventId).distinctNonBlank()
        for (rawEventId in idCandidates) {
            val event = rawDao?.findById(rawEventId, userId)
                ?: if (rawEventId == localRawEventId) toSyntheticRawEvent(rawEventId) else null
            if (event != null) {
                return EvidenceRawEventResolution(event = event, fallbackRawEventIds = idCandidates - event.id)
            }
        }
        return null
    }

    private fun SourceEventAnchorEntity.toSyntheticRawEvent(rawEventId: String): RawIngestionEventEntity =
        RawIngestionEventEntity(
            id = rawEventId,
            userId = userId,
            clientEventId = rawEventId,
            sourceType = sourceType,
            sourceRef = sourceRef ?: providerEventId,
            eventTitle = title,
            eventSnippet = snippet,
            conversationRef = conversationRef,
            commitmentsExtractedCount = 0,
            timestamp = occurredAt ?: Clock.System.now(),
            syncStatus = "synced",
        )

    private fun SourceOriginalContext.toLocalEvidenceOriginal(
        event: RawIngestionEventEntity,
    ): LocalEvidenceOriginal? {
        emailBody?.bodyPlain?.takeIf(String::isNotBlank)?.let { body ->
            return LocalEvidenceOriginal(
                title = emailBody.subject?.takeIf(String::isNotBlank) ?: event.eventTitle,
                text = body,
                truncated = false,
            )
        }
        emailBody?.bodyHtml?.takeIf(String::isNotBlank)?.let { body ->
            return LocalEvidenceOriginal(
                title = emailBody.subject?.takeIf(String::isNotBlank) ?: event.eventTitle,
                text = body,
                truncated = false,
            )
        }
        val archived = archivedOriginal
        archived?.markdown?.takeIf(String::isNotBlank)?.let { body ->
            return LocalEvidenceOriginal(
                title = event.eventTitle,
                text = body,
                truncated = archived.markdownTruncated,
            )
        }
        return null
    }

    private fun PersonActionEvidenceOriginalDto.evidenceLookupRefs(): List<String> =
        listOfNotNull(
            original.localLookupKey,
            original.metadata["local_lookup_key"],
            original.metadata["raw_event_id"],
            original.sourceEventId,
            original.transcriptRawEventId,
            original.clientEventId,
            original.id,
            evidence.id,
            original.sourceRef,
            evidence.sourceRef,
            original.providerEventId,
            original.metadata["provider_event_id"],
            original.metadata["message_id"],
            original.metadata["thread_id"],
            original.conversationRef,
        ).distinctNonBlank()

    private fun PersonActionEvidenceOriginalDto.evidenceRawIdCandidates(): List<String> =
        listOfNotNull(
            original.localLookupKey,
            original.metadata["local_lookup_key"],
            original.metadata["raw_event_id"],
            original.sourceEventId,
            original.transcriptRawEventId,
            original.clientEventId,
            original.id.takeIf { original.kind == "source_event" },
            evidence.id.takeIf { evidence.kind == "source_event" },
        )
            .map { it.removePrefix("raw:") }
            .distinctNonBlank()

    private fun PersonActionEvidenceOriginalDto.evidenceSourceRefCandidates(): List<String> =
        listOfNotNull(
            original.sourceRef,
            evidence.sourceRef,
            original.providerEventId,
            original.metadata["provider_event_id"],
            original.metadata["message_id"],
            original.localLookupKey,
            original.metadata["local_lookup_key"],
        ).distinctNonBlank()

    private suspend fun enqueueStatePatch(
        userId: String,
        actionItemId: String,
        status: String,
        snoozedUntil: Instant?,
        reason: String?,
        expectedUpdatedAt: Instant?,
        providerWrite: PersonActionProviderWriteRequest?,
    ): BecalmResult<PersonActionMutationSyncStats> = withContext(ioDispatcher) {
        val now = Clock.System.now()
        val payload = statePatchPayloadAdapter.toJson(
            StatePatchMutationPayload(
                status = status,
                snoozedUntil = snoozedUntil,
                reason = reason,
                expectedUpdatedAt = expectedUpdatedAt,
                providerWrite = providerWrite?.toPatchDto(),
                updatedAt = now,
            ),
        )
        enqueueMutation(
            userId = userId,
            actionItemId = actionItemId,
            mutationKind = MUTATION_KIND_STATE_PATCH,
            payloadJson = payload,
            now = now,
        )
    }

    private suspend fun enqueueMutation(
        userId: String,
        actionItemId: String,
        mutationKind: String,
        payloadJson: String,
        now: Instant,
    ): BecalmResult<PersonActionMutationSyncStats> {
        val id = UUID.randomUUID().toString()
        return try {
            dao.upsertMutation(
                PersonActionMutationQueueEntity(
                    id = id,
                    userId = userId,
                    actionItemId = actionItemId,
                    clientMutationId = "android:$id",
                    mutationKind = mutationKind,
                    payloadJson = payloadJson,
                    syncStatus = MUTATION_STATUS_PENDING,
                    attemptCount = 0,
                    lastErrorCode = null,
                    lastErrorClientAction = null,
                    nextAttemptAt = null,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            when (val sync = syncPendingMutations(userId = userId, limit = MUTATION_DRAIN_LIMIT)) {
                is BecalmResult.Success -> BecalmResult.Success(sync.value.copy(queued = sync.value.queued + 1))
                is BecalmResult.Failure -> BecalmResult.Success(PersonActionMutationSyncStats(queued = 1, synced = 0, retryable = 1, failed = 0))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.e(TAG, "person action mutation enqueue failed", error)
            BecalmResult.Failure(BecalmError.Io(error.message ?: "person action mutation enqueue failed"))
        }
    }

    override suspend fun syncPendingMutations(userId: String, limit: Int): BecalmResult<PersonActionMutationSyncStats> = withContext(ioDispatcher) {
        val now = Clock.System.now()
        val mutations = try {
            dao.findPendingMutations(userId = userId, now = now, limit = limit.coerceIn(1, MUTATION_DRAIN_LIMIT))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.e(TAG, "person action mutation load failed", error)
            return@withContext BecalmResult.Failure(BecalmError.Io(error.message ?: "person action mutation load failed"))
        }
        var synced = 0
        var retryable = 0
        var failed = 0
        var providerWriteJobId: String? = null
        for (mutation in mutations) {
            when (val result = syncMutation(mutation)) {
                is MutationAttempt.Synced -> {
                    synced += 1
                    providerWriteJobId = result.providerWriteJobId ?: providerWriteJobId
                    dao.markMutationSynced(userId = mutation.userId, clientMutationId = mutation.clientMutationId, updatedAt = Clock.System.now())
                }
                is MutationAttempt.Retry -> {
                    retryable += 1
                    dao.markMutationFailed(
                        userId = mutation.userId,
                        clientMutationId = mutation.clientMutationId,
                        errorCode = result.errorCode,
                        clientAction = result.clientAction,
                        nextAttemptAt = result.nextAttemptAt,
                        updatedAt = Clock.System.now(),
                    )
                }
                is MutationAttempt.Failed -> {
                    failed += 1
                    dao.markMutationFailed(
                        userId = mutation.userId,
                        clientMutationId = mutation.clientMutationId,
                        errorCode = result.errorCode,
                        clientAction = result.clientAction,
                        nextAttemptAt = null,
                        updatedAt = Clock.System.now(),
                    )
                }
                is MutationAttempt.TerminalConflict -> {
                    fetchAndApply(userId = mutation.userId, changedSince = null, surface = null)
                    failed += 1
                    dao.markMutationFailed(
                        userId = mutation.userId,
                        clientMutationId = mutation.clientMutationId,
                        errorCode = result.errorCode,
                        clientAction = result.clientAction,
                        nextAttemptAt = null,
                        updatedAt = Clock.System.now(),
                    )
                }
                is MutationAttempt.RefreshRequired -> {
                    when (fetchAndApply(userId = mutation.userId, changedSince = null, surface = null)) {
                        is BecalmResult.Success -> {
                            synced += 1
                            dao.markMutationSynced(
                                userId = mutation.userId,
                                clientMutationId = mutation.clientMutationId,
                                updatedAt = Clock.System.now(),
                            )
                        }
                        is BecalmResult.Failure -> {
                            failed += 1
                            dao.markMutationFailed(
                                userId = mutation.userId,
                                clientMutationId = mutation.clientMutationId,
                                errorCode = result.errorCode,
                                clientAction = result.clientAction,
                                nextAttemptAt = null,
                                updatedAt = Clock.System.now(),
                            )
                        }
                    }
                }
            }
        }
        BecalmResult.Success(
            PersonActionMutationSyncStats(
                queued = 0,
                synced = synced,
                retryable = retryable,
                failed = failed,
                providerWriteJobId = providerWriteJobId,
            ),
        )
    }

    private suspend fun syncMutation(mutation: PersonActionMutationQueueEntity): MutationAttempt {
        val response = try {
            when (mutation.mutationKind) {
                MUTATION_KIND_STATE_PATCH -> {
                    val payload = mutation.payloadJson.toStatePatchPayload()
                        ?: return MutationAttempt.Failed("invalid_mutation_payload", "discard_local_mutation")
                    api.patchPersonActionItem(
                        id = mutation.actionItemId,
                        request = PersonActionStatePatchDto(
                            clientMutationId = mutation.clientMutationId,
                            status = payload.status,
                            snoozedUntil = payload.snoozedUntil,
                            reason = payload.reason,
                            expectedUpdatedAt = payload.expectedUpdatedAt,
                            providerWrite = payload.providerWrite,
                            updatedAt = payload.updatedAt,
                        ),
                    )
                }
                MUTATION_KIND_FEEDBACK -> {
                    val payload = mutation.payloadJson.toFeedbackPayload()
                        ?: return MutationAttempt.Failed("invalid_mutation_payload", "discard_local_mutation")
                    api.submitPersonActionFeedback(
                        id = mutation.actionItemId,
                        request = PersonActionFeedbackDto(
                            clientMutationId = mutation.clientMutationId,
                            feedbackType = payload.feedbackType,
                            reason = payload.reason,
                            correctedPersonId = payload.correctedPersonId,
                            correctedDueAt = payload.correctedDueAt,
                            updatedAt = payload.updatedAt,
                        ),
                    )
                }
                else -> return MutationAttempt.Failed("unsupported_mutation_kind", "discard_local_mutation")
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: java.io.IOException) {
            logger.w(TAG, "person action mutation network error", error)
            return MutationAttempt.Retry("network_error", "retry_later", retryAt(mutation.attemptCount))
        } catch (error: Exception) {
            logger.e(TAG, "person action mutation failed", error)
            return MutationAttempt.Retry("unknown_error", "retry_later", retryAt(mutation.attemptCount))
        }

        if (response.isSuccessful) {
            val item = response.body()?.data
            if (item != null) {
                dao.upsertActionItems(listOf(item.toCacheEntity(serverWatermark = item.updatedAt)))
            }
            return MutationAttempt.Synced(providerWriteJobId = item?.providerWrite?.jobId)
        }
        return response.toMutationAttempt(mutation)
    }

    private suspend fun fetchAndApply(
        userId: String,
        changedSince: String?,
        surface: String?,
    ): BecalmResult<PersonActionRefreshStats> {
        var cursor: String? = null
        var snapshotId: String? = null
        val allRows = ArrayList<PersonActionItemCacheEntity>()
        val allDeletedIds = linkedSetOf<String>()
        repeat(MAX_PAGES) {
            val response = try {
                api.getPersonActionItems(
                    cursor = cursor,
                    snapshotId = snapshotId,
                    limit = PAGE_LIMIT,
                    changedSince = changedSince,
                    surface = surface,
                    status = "active",
                    includeStale = false,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: java.io.IOException) {
                logger.w(TAG, "person action refresh network error", error)
                return BecalmResult.Failure(BecalmError.Network(0, error.message ?: "network error"))
            } catch (error: Exception) {
                logger.e(TAG, "person action refresh failed", error)
                return BecalmResult.Failure(BecalmError.Unknown(error))
            }
            if (!response.isSuccessful) {
                return response.toPersonActionError()
            }
            val body = response.body()
                ?: return BecalmResult.Failure(BecalmError.Unknown(IllegalStateException("empty person action feed")))
            val rows = body.data.map { it.toCacheEntity(serverWatermark = body.serverWatermark) }
            allRows += rows
            allDeletedIds += body.deletedIds
            if (!body.hasMore) {
                val now = Clock.System.now()
                dao.applyFeedSnapshot(
                    userId = userId,
                    rows = allRows,
                    deletedIds = allDeletedIds.toList(),
                    replaceScope = changedSince == null,
                    surface = surface,
                    syncState = PersonActionSyncStateEntity(
                        userId = userId,
                        surfaceKey = surface.toSyncSurfaceKey(),
                        status = "active",
                        serverWatermark = body.serverWatermark,
                        recomputeState = body.recomputeState,
                        capacityState = body.capacityState?.state,
                        capacityBacklogLagSeconds = body.capacityState?.backlogLagSeconds,
                        capacityLastCaughtUpAt = body.capacityState?.lastCaughtUpAt,
                        capacityIncidentId = body.capacityState?.incidentId,
                        recoveryActionsJson = body.recoveryActions
                            .takeIf { it.isNotEmpty() }
                            ?.let(recoveryActionsAdapter::toJson),
                        emptyStateJson = body.emptyState?.let(emptyStateAdapter::toJson),
                        serverTimingJson = body.serverTimingMs
                            .takeIf { it.isNotEmpty() }
                            ?.let(serverTimingAdapter::toJson),
                        serverTimingTotalMs = body.serverTimingMs["total"],
                        lastSyncedAt = now,
                        updatedAt = now,
                    ),
                )
                return BecalmResult.Success(
                    PersonActionRefreshStats(
                        fetched = allRows.size,
                        deleted = allDeletedIds.size,
                        serverWatermark = body.serverWatermark,
                        recomputeState = body.recomputeState,
                    ),
                )
            }
            cursor = body.cursor.takeIf { it.isNotBlank() }
            snapshotId = body.snapshotId
            if (cursor == null || snapshotId == null) {
                return BecalmResult.Failure(
                    BecalmError.ServerError(
                        200,
                        "person_action_feed pagination missing cursor/snapshot_id",
                    ),
                )
            }
        }
        return BecalmResult.Failure(
            BecalmError.ServerError(
                200,
                "person_action_feed exceeded max pages without a terminal snapshot page",
            ),
        )
    }

    private fun PersonActionItemDto.toCacheEntity(serverWatermark: kotlinx.datetime.Instant): PersonActionItemCacheEntity {
        val primaryEvidence = evidenceRefs.primaryEvidence()
        return PersonActionItemCacheEntity(
            id = id,
            userId = userId,
            personId = personId,
            personDisplayName = personDisplayName,
            personSortKey = personSortKey,
            surfacesCsv = surfaces.toCsvTokens(),
            actionKind = actionKind,
            status = status,
            title = title,
            primaryVerb = primaryVerb,
            shortReason = shortReason,
            commitmentId = commitmentId,
            calendarEventId = calendarEventId,
            sourceEventId = sourceEventId,
            sourceType = sourceType,
            sourceRef = sourceRef,
            dueAt = dueAt,
            dueHint = dueHint,
            dueIsApproximate = dueIsApproximate,
            staleAfter = staleAfter,
            urgencyScore = urgencyScore,
            importanceScore = importanceScore,
            confidence = confidence,
            reasonCodesCsv = reasonCodes.toCsvTokens(),
            primaryEvidenceKind = primaryEvidence?.kind,
            primaryEvidenceId = primaryEvidence?.id,
            primaryEvidenceSourceRef = primaryEvidence?.sourceRef,
            primaryEvidenceOccurredAt = primaryEvidence?.occurredAt,
            primaryEvidenceLabel = primaryEvidence?.label,
            primaryEvidenceQuote = primaryEvidence?.quote,
            inputWatermark = inputWatermark,
            serverWatermark = serverWatermark,
            computedAt = computedAt,
            updatedAt = updatedAt,
            snoozedUntil = snoozedUntil,
            completedAt = completedAt,
            dismissedAt = dismissedAt,
            providerWriteKind = providerWrite?.kind,
            providerWriteState = providerWrite?.state,
            providerWriteProvider = providerWrite?.provider,
            providerWriteSourceConnectionId = providerWrite?.sourceConnectionId,
            providerWriteScheduleEventLinkId = providerWrite?.scheduleEventLinkId,
        )
    }

    private fun CalendarWriteJobResponseDto.toCalendarWriteJobStatus(): CalendarWriteJobStatus =
        CalendarWriteJobStatus(
            jobId = jobId,
            status = status,
            accepted = accepted,
            retryAfterSeconds = retryAfterSeconds,
            provider = provider,
            writeKind = writeKind,
            actionItemId = actionItemId,
            scheduleEventLinkId = scheduleEventLinkId,
            sourceConnectionId = sourceConnectionId,
            providerEventId = providerEventId,
            calendarEventId = calendarEventId,
            attempts = attempts,
            errorCode = errorCode,
            errorMessage = errorMessage,
            nextAttemptAt = nextAttemptAt,
            clientAction = clientAction,
        )

    private fun List<PersonActionEvidenceRefDto>.primaryEvidence(): PersonActionEvidenceRefDto? =
        firstOrNull { it.kind == "source_event" } ?: firstOrNull()

    private fun List<String>.toCsvTokens(): String =
        map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .joinToString(",")

    private fun List<String>.distinctNonBlank(): List<String> =
        map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()

    private fun Response<PersonActionFeedResponseDto>.toPersonActionError(): BecalmResult.Failure =
        when (code()) {
            401 -> BecalmResult.Failure(BecalmError.Unauthorized)
            409 -> BecalmResult.Failure(BecalmError.Network(409, "person_action_full_refresh_required"))
            429 -> BecalmResult.Failure(BecalmError.RateLimited(headers()["Retry-After"]?.toLongOrNull()))
            in 500..599 -> BecalmResult.Failure(BecalmError.ServerError(code(), errorBody()?.string()))
            else -> BecalmResult.Failure(BecalmError.Network(code(), errorBody()?.string() ?: message()))
        }

    private fun Response<*>.toMutationAttempt(mutation: PersonActionMutationQueueEntity): MutationAttempt {
        val code = code()
        val body = runCatching { errorBody()?.string().orEmpty() }.getOrDefault("")
        val envelope = body.toErrorEnvelope()
        val errorCode = envelope?.error
        val clientAction = envelope?.clientAction
        return when {
            code == 409 && (errorCode == "idempotency_conflict" || clientAction == "discard_local_mutation") ->
                MutationAttempt.TerminalConflict(
                    errorCode ?: "idempotency_conflict",
                    clientAction ?: "discard_local_mutation",
                )
            code == 409 && (errorCode == "stale_action" || clientAction == "refresh_action") ->
                MutationAttempt.RefreshRequired(
                    errorCode ?: "stale_action",
                    clientAction ?: "refresh_action",
                )
            code == 409 && ("idempotency_conflict" in body || "discard_local_mutation" in body) ->
                MutationAttempt.TerminalConflict("idempotency_conflict", "discard_local_mutation")
            code == 409 && ("stale_action" in body || "refresh_action" in body) ->
                MutationAttempt.RefreshRequired("stale_action", "refresh_action")
            code == 401 ->
                MutationAttempt.Failed("unauthorized", "reauth")
            code == 400 || code == 422 ->
                MutationAttempt.Failed("validation_failed", "fix_input")
            code == 429 ->
                MutationAttempt.Retry("rate_limited", "retry_later", retryAfterHeader() ?: retryAt(mutation.attemptCount))
            code in 500..599 ->
                MutationAttempt.Retry("server_error", "retry_later", retryAt(mutation.attemptCount))
            else ->
                MutationAttempt.Failed("mutation_failed_$code", "contact_support")
        }
    }

    private fun String.toErrorEnvelope(): ErrorEnvelopeDto? =
        takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { errorEnvelopeAdapter.fromJson(raw) }.getOrNull()
        }

    private fun Response<*>.toPersonActionLookupError(
        validationField: String,
        notFoundResource: String,
    ): BecalmResult.Failure {
        val code = code()
        val body = runCatching { errorBody()?.string().orEmpty() }.getOrDefault("")
        val envelope = body.toErrorEnvelope()
        return when (code) {
            401 -> BecalmResult.Failure(BecalmError.Unauthorized)
            404 -> BecalmResult.Failure(
                BecalmError.NotFound(envelope?.clientAction ?: envelope?.error ?: notFoundResource),
            )
            400, 422 -> BecalmResult.Failure(
                BecalmError.Validation(validationField, envelope?.clientAction ?: envelope?.error ?: "validation_failed"),
            )
            409 -> BecalmResult.Failure(
                BecalmError.Validation(validationField, envelope?.clientAction ?: envelope?.error ?: "refresh_action"),
            )
            429 -> BecalmResult.Failure(
                BecalmError.RateLimited(headers()["Retry-After"]?.toLongOrNull() ?: envelope?.retryAfterSeconds),
            )
            in 500..599 -> BecalmResult.Failure(BecalmError.ServerError(code, body.ifBlank { envelope?.error.orEmpty() }))
            else -> BecalmResult.Failure(BecalmError.Network(code, envelope?.clientAction ?: envelope?.error ?: body.ifBlank { message() }))
        }
    }

    private fun String?.toSyncSurfaceKey(): String = this?.takeIf(String::isNotBlank) ?: ALL_SURFACES_KEY

    private fun String.toStatePatchPayload(): StatePatchMutationPayload? =
        runCatching { statePatchPayloadAdapter.fromJson(this) }.getOrNull()

    private fun String.toFeedbackPayload(): FeedbackMutationPayload? =
        runCatching { feedbackPayloadAdapter.fromJson(this) }.getOrNull()

    private fun retryAt(attemptCount: Int): Instant {
        val seconds = min(1_800L, 5L * (1L shl min(attemptCount.coerceAtLeast(0), 8)))
        return Clock.System.now().plus(seconds.seconds)
    }

    private fun Response<*>.retryAfterHeader(): Instant? {
        val seconds = headers()["Retry-After"]?.toLongOrNull() ?: return null
        return Clock.System.now().plus(seconds.coerceAtLeast(1).seconds)
    }

    private fun PersonActionProviderWriteRequest.toPatchDto(): PersonActionProviderWritePatchDto =
        PersonActionProviderWritePatchDto(
            kind = kind,
            provider = provider,
            sourceConnectionId = sourceConnectionId,
            scheduleEventLinkId = scheduleEventLinkId,
        )

    private companion object {
        private const val TAG = "PersonActionRepository"
        private const val PAGE_LIMIT = 100
        private const val MAX_PAGES = 10
        private const val MUTATION_DRAIN_LIMIT = 20
        private const val ALL_SURFACES_KEY = "__all__"
        private const val MUTATION_KIND_STATE_PATCH = "state_patch"
        private const val MUTATION_KIND_FEEDBACK = "feedback"
        private const val MUTATION_STATUS_PENDING = "pending"
        private const val LOCAL_EVIDENCE_CONVERSATION_LOOKUP_LIMIT = 5
    }
}

private data class EvidenceRawEventResolution(
    val event: RawIngestionEventEntity,
    val fallbackRawEventIds: List<String>,
)

private data class LocalEvidenceOriginal(
    val title: String?,
    val text: String,
    val truncated: Boolean,
)

private sealed interface MutationAttempt {
    data class Synced(val providerWriteJobId: String? = null) : MutationAttempt
    data class Retry(val errorCode: String, val clientAction: String, val nextAttemptAt: Instant) : MutationAttempt
    data class Failed(val errorCode: String, val clientAction: String) : MutationAttempt
    data class TerminalConflict(val errorCode: String, val clientAction: String) : MutationAttempt
    data class RefreshRequired(val errorCode: String, val clientAction: String) : MutationAttempt
}

@JsonClass(generateAdapter = true)
internal data class StatePatchMutationPayload(
    @field:Json(name = "status") val status: String,
    @field:Json(name = "snoozed_until") val snoozedUntil: Instant? = null,
    @field:Json(name = "reason") val reason: String? = null,
    @field:Json(name = "expected_updated_at") val expectedUpdatedAt: Instant? = null,
    @field:Json(name = "provider_write") val providerWrite: PersonActionProviderWritePatchDto? = null,
    @field:Json(name = "updated_at") val updatedAt: Instant,
)

@JsonClass(generateAdapter = true)
internal data class FeedbackMutationPayload(
    @field:Json(name = "feedback_type") val feedbackType: String,
    @field:Json(name = "reason") val reason: String? = null,
    @field:Json(name = "corrected_person_id") val correctedPersonId: String? = null,
    @field:Json(name = "corrected_due_at") val correctedDueAt: Instant? = null,
    @field:Json(name = "updated_at") val updatedAt: Instant,
)
