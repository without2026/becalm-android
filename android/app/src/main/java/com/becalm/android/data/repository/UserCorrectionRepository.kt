package com.becalm.android.data.repository

import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.NoopSyncCursorStore
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.db.dao.CommitmentDao
import com.becalm.android.data.local.db.dao.PersonIndexDao
import com.becalm.android.data.local.db.dao.ScheduleRowTombstoneDao
import com.becalm.android.data.local.db.dao.UserCorrectionDao
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.CommitmentParticipantEntity
import com.becalm.android.data.local.db.entity.ScheduleRowTombstoneEntity
import com.becalm.android.data.local.db.entity.SourceEventParticipantEntity
import com.becalm.android.data.local.db.entity.UserCorrectionEntity
import com.becalm.android.data.local.db.entity.UserCorrectionStatus
import com.becalm.android.data.local.db.entity.UserCorrectionSyncStatus
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.UserCorrectionBatchRequestDto
import com.becalm.android.data.remote.dto.UserCorrectionBatchResponseDto
import com.becalm.android.data.remote.dto.UserCorrectionDto
import com.becalm.android.data.remote.dto.UserCorrectionRequestDto
import com.becalm.android.domain.schedule.ScheduleRowRef
import com.becalm.android.worker.WorkScheduler
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.json.JSONObject
import retrofit2.Response

public data class UserCorrectionCommand(
    val domain: String,
    val action: String,
    val targetType: String,
    val targetId: String,
    val sourceEventId: String? = null,
    val commitmentId: String? = null,
    val fromPersonId: String? = null,
    val toPersonId: String? = null,
    val conflictKey: String,
    val targetFingerprint: String? = null,
    val payload: Map<String, Any?> = emptyMap(),
)

public interface UserCorrectionRepository {
    public suspend fun submit(userId: String, command: UserCorrectionCommand): BecalmResult<Unit>

    public suspend fun submitParticipantReassign(
        userId: String,
        participantId: String,
        sourceEventId: String,
        fromPersonId: String?,
        toPersonId: String,
        identityType: String?,
        normalizedValue: String?,
        displayNameRaw: String?,
    ): BecalmResult<Unit>

    public suspend fun submitParticipantIgnore(
        userId: String,
        participantId: String,
        sourceEventId: String,
        fromPersonId: String?,
        displayNameRaw: String?,
    ): BecalmResult<Unit>

    public suspend fun submitCommitmentNotAction(
        userId: String,
        commitmentId: String,
        sourceEventId: String?,
        title: String?,
        quote: String?,
    ): BecalmResult<Unit>

    public suspend fun submitScheduleHide(userId: String, rowRef: ScheduleRowRef): BecalmResult<Unit>

    public suspend fun findPendingSync(userId: String, limit: Int): List<UserCorrectionEntity>

    public suspend fun uploadBatch(rows: List<UserCorrectionEntity>): BecalmResult<BatchResponse>

    public suspend fun markSynced(ids: List<String>): BecalmResult<Unit>

    public suspend fun markFailed(id: String, reason: String?): BecalmResult<Unit>

    public suspend fun refreshSince(userId: String, since: Instant? = null): BecalmResult<RefreshStats>

    public suspend fun applyActiveCorrections(userId: String): BecalmResult<Int>

    public data class BatchResponse(
        val acknowledged: Int,
        val failed: List<FailedCorrection>,
    )

    public data class FailedCorrection(
        val id: String?,
        val idempotencyKey: String?,
        val error: String,
        val message: String?,
        val retryable: Boolean,
    )

    public data class RefreshStats(
        val fetched: Int,
        val upserted: Int,
        val hasMore: Boolean,
        val nextCursor: String?,
    )
}

public object NoopUserCorrectionRepository : UserCorrectionRepository {
    override suspend fun submit(userId: String, command: UserCorrectionCommand): BecalmResult<Unit> =
        BecalmResult.Success(Unit)

    override suspend fun submitParticipantReassign(
        userId: String,
        participantId: String,
        sourceEventId: String,
        fromPersonId: String?,
        toPersonId: String,
        identityType: String?,
        normalizedValue: String?,
        displayNameRaw: String?,
    ): BecalmResult<Unit> = BecalmResult.Success(Unit)

    override suspend fun submitParticipantIgnore(
        userId: String,
        participantId: String,
        sourceEventId: String,
        fromPersonId: String?,
        displayNameRaw: String?,
    ): BecalmResult<Unit> = BecalmResult.Success(Unit)

    override suspend fun submitCommitmentNotAction(
        userId: String,
        commitmentId: String,
        sourceEventId: String?,
        title: String?,
        quote: String?,
    ): BecalmResult<Unit> = BecalmResult.Success(Unit)

    override suspend fun submitScheduleHide(userId: String, rowRef: ScheduleRowRef): BecalmResult<Unit> =
        BecalmResult.Success(Unit)

    override suspend fun findPendingSync(userId: String, limit: Int): List<UserCorrectionEntity> = emptyList()

    override suspend fun uploadBatch(rows: List<UserCorrectionEntity>): BecalmResult<UserCorrectionRepository.BatchResponse> =
        BecalmResult.Success(UserCorrectionRepository.BatchResponse(acknowledged = 0, failed = emptyList()))

    override suspend fun markSynced(ids: List<String>): BecalmResult<Unit> = BecalmResult.Success(Unit)

    override suspend fun markFailed(id: String, reason: String?): BecalmResult<Unit> = BecalmResult.Success(Unit)

    override suspend fun refreshSince(userId: String, since: Instant?): BecalmResult<UserCorrectionRepository.RefreshStats> =
        BecalmResult.Success(UserCorrectionRepository.RefreshStats(0, 0, false, null))

    override suspend fun applyActiveCorrections(userId: String): BecalmResult<Int> = BecalmResult.Success(0)
}

@Singleton
public class UserCorrectionRepositoryImpl @Inject constructor(
    private val dao: UserCorrectionDao,
    private val materializer: UserCorrectionMaterializer,
    private val apiProvider: Provider<RailwayApi>,
    private val cursorStore: SyncCursorStore,
    private val workScheduler: WorkScheduler,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : UserCorrectionRepository {

    private val api: RailwayApi
        get() = apiProvider.get()

    public constructor(
        dao: UserCorrectionDao,
        materializer: UserCorrectionMaterializer,
        api: RailwayApi,
        workScheduler: WorkScheduler,
        logger: Logger,
    ) : this(
        dao = dao,
        materializer = materializer,
        apiProvider = Provider { api },
        cursorStore = NoopSyncCursorStore,
        workScheduler = workScheduler,
        logger = logger,
    )

    override suspend fun submit(userId: String, command: UserCorrectionCommand): BecalmResult<Unit> =
        withContext(ioDispatcher) {
            val now = Clock.System.now()
            val entity = command.toEntity(userId = userId, now = now)
            try {
                dao.upsert(entity)
                materializer.apply(entity)
                dao.markApplied(entity.id, Clock.System.now())
                workScheduler.enqueueUpload()
                workScheduler.enqueuePersonInteractionIndex(initialDelaySeconds = 0L)
                BecalmResult.Success(Unit)
            } catch (e: Exception) {
                logger.e(TAG, "submit user correction failed action=${command.action}", e)
                BecalmResult.Failure(BecalmError.Unknown(e))
            }
        }

    override suspend fun submitParticipantReassign(
        userId: String,
        participantId: String,
        sourceEventId: String,
        fromPersonId: String?,
        toPersonId: String,
        identityType: String?,
        normalizedValue: String?,
        displayNameRaw: String?,
    ): BecalmResult<Unit> =
        submit(
            userId = userId,
            command = UserCorrectionCommand(
                domain = "person",
                action = "participant_reassign",
                targetType = "source_event_participant",
                targetId = participantId,
                sourceEventId = sourceEventId,
                fromPersonId = fromPersonId,
                toPersonId = toPersonId,
                conflictKey = "participant:$participantId",
                targetFingerprint = listOfNotNull(sourceEventId, fromPersonId, displayNameRaw)
                    .joinToString("|")
                    .takeIf { it.isNotBlank() },
                payload = mapOf(
                    "identity_type" to identityType,
                    "normalized_value" to normalizedValue,
                    "display_name_raw" to displayNameRaw,
                    "confidence" to 1.0,
                ).filterValues { it != null },
            ),
        )

    override suspend fun submitParticipantIgnore(
        userId: String,
        participantId: String,
        sourceEventId: String,
        fromPersonId: String?,
        displayNameRaw: String?,
    ): BecalmResult<Unit> =
        submit(
            userId = userId,
            command = UserCorrectionCommand(
                domain = "person",
                action = "participant_ignore",
                targetType = "source_event_participant",
                targetId = participantId,
                sourceEventId = sourceEventId,
                fromPersonId = fromPersonId,
                conflictKey = "participant:$participantId",
                targetFingerprint = listOfNotNull(sourceEventId, fromPersonId, displayNameRaw)
                    .joinToString("|")
                    .takeIf { it.isNotBlank() },
                payload = mapOf(
                    "display_name_raw" to displayNameRaw,
                    "confidence" to 1.0,
                ).filterValues { it != null },
            ),
        )

    override suspend fun submitCommitmentNotAction(
        userId: String,
        commitmentId: String,
        sourceEventId: String?,
        title: String?,
        quote: String?,
    ): BecalmResult<Unit> =
        submit(
            userId = userId,
            command = UserCorrectionCommand(
                domain = "commitment",
                action = "commitment_not_action",
                targetType = "commitment",
                targetId = commitmentId,
                sourceEventId = sourceEventId,
                commitmentId = commitmentId,
                conflictKey = "commitment:$commitmentId",
                targetFingerprint = listOfNotNull(sourceEventId, title, quote)
                    .joinToString("|")
                    .takeIf { it.isNotBlank() },
                payload = mapOf(
                    "title" to title,
                    "quote" to quote,
                ).filterValues { it != null },
            ),
        )

    override suspend fun submitScheduleHide(userId: String, rowRef: ScheduleRowRef): BecalmResult<Unit> {
        val command = when (rowRef) {
            is ScheduleRowRef.CalendarEvent -> UserCorrectionCommand(
                domain = "schedule",
                action = "schedule_hide",
                targetType = "calendar_event",
                targetId = rowRef.id,
                sourceEventId = rowRef.id,
                conflictKey = "schedule:calendar_event:${rowRef.id}",
                payload = mapOf(
                    "row_type" to "calendar_event",
                    "source_type" to rowRef.sourceType,
                    "source_ref" to rowRef.sourceRef,
                ).filterValues { it != null },
            )
            is ScheduleRowRef.Meeting -> UserCorrectionCommand(
                domain = "schedule",
                action = "schedule_hide",
                targetType = "meeting",
                targetId = rowRef.id,
                sourceEventId = rowRef.id,
                conflictKey = "schedule:meeting:${rowRef.id}",
                payload = mapOf(
                    "row_type" to "meeting",
                    "source_type" to rowRef.sourceType,
                    "source_ref" to rowRef.sourceRef,
                ).filterValues { it != null },
            )
            is ScheduleRowRef.Commitment -> UserCorrectionCommand(
                domain = "commitment",
                action = "commitment_not_action",
                targetType = "commitment",
                targetId = rowRef.id,
                commitmentId = rowRef.id,
                conflictKey = "commitment:${rowRef.id}",
            )
        }
        return submit(userId, command)
    }

    override suspend fun findPendingSync(userId: String, limit: Int): List<UserCorrectionEntity> =
        dao.findPendingSync(userId, limit)

    override suspend fun uploadBatch(rows: List<UserCorrectionEntity>): BecalmResult<UserCorrectionRepository.BatchResponse> =
        withContext(ioDispatcher) {
            if (rows.isEmpty()) {
                return@withContext BecalmResult.Success(
                    UserCorrectionRepository.BatchResponse(acknowledged = 0, failed = emptyList()),
                )
            }
            val response = try {
                api.uploadUserCorrectionsBatch(
                    request = UserCorrectionBatchRequestDto(
                        items = rows.map(UserCorrectionEntity::toRequestDto),
                    ),
                )
            } catch (e: IOException) {
                logger.w(TAG, "user correction upload network error")
                return@withContext BecalmResult.Failure(BecalmError.Network(0, e.message ?: "network error"))
            } catch (e: Exception) {
                logger.e(TAG, "user correction upload unexpected error", e)
                return@withContext BecalmResult.Failure(BecalmError.Unknown(e))
            }
            if (!response.isSuccessful) {
                logger.w(TAG, "user correction upload HTTP ${response.code()}")
                return@withContext BecalmResult.Failure(response.toError())
            }
            val body = response.body()
                ?: return@withContext BecalmResult.Failure(BecalmError.Unknown(IllegalStateException("null user correction upload body")))
            BecalmResult.Success(
                UserCorrectionRepository.BatchResponse(
                    acknowledged = body.acknowledged,
                    failed = body.failed.map {
                        UserCorrectionRepository.FailedCorrection(
                            id = it.id,
                            idempotencyKey = it.idempotencyKey,
                            error = it.error,
                            message = it.message,
                            retryable = it.retryable,
                        )
                    },
                ),
            )
        }

    override suspend fun markSynced(ids: List<String>): BecalmResult<Unit> =
        withContext(ioDispatcher) {
            if (ids.isEmpty()) return@withContext BecalmResult.Success(Unit)
            try {
                dao.markSynced(ids, Clock.System.now())
                BecalmResult.Success(Unit)
            } catch (e: Exception) {
                logger.e(TAG, "mark user corrections synced failed", e)
                BecalmResult.Failure(BecalmError.Io(e.message ?: "markSynced failed"))
            }
        }

    override suspend fun markFailed(id: String, reason: String?): BecalmResult<Unit> =
        withContext(ioDispatcher) {
            try {
                dao.markFailed(id, reason, Clock.System.now())
                BecalmResult.Success(Unit)
            } catch (e: Exception) {
                logger.e(TAG, "mark user correction failed failed", e)
                BecalmResult.Failure(BecalmError.Io(e.message ?: "markFailed failed"))
            }
        }

    override suspend fun refreshSince(userId: String, since: Instant?): BecalmResult<UserCorrectionRepository.RefreshStats> =
        withContext(ioDispatcher) {
            val cursorKey = MirrorCursorKeys.userCorrections(userId)
            val useStoredCursor = since == null
            var cursor: String? = if (useStoredCursor) cursorStore.observeCursor(cursorKey).first() else null
            var totalFetched = 0
            var totalUpserted = 0
            var lastHasMore = false
            var lastCursor: String? = null
            repeat(REFRESH_PAGE_CAP) { pageIndex ->
                if (pageIndex > 0 && !lastHasMore) return@repeat
                val response = try {
                    api.getUserCorrections(
                        cursor = cursor,
                        limit = PAGE_LIMIT,
                        since = since?.toString(),
                    )
                } catch (e: IOException) {
                    logger.w(TAG, "user correction refresh network error")
                    return@withContext BecalmResult.Failure(BecalmError.Network(0, e.message ?: "network error"))
                } catch (e: Exception) {
                    logger.e(TAG, "user correction refresh unexpected error", e)
                    return@withContext BecalmResult.Failure(BecalmError.Unknown(e))
                }
                if (!response.isSuccessful) {
                    logger.w(TAG, "user correction refresh HTTP ${response.code()}")
                    return@withContext BecalmResult.Failure(response.toError())
                }
                val body = response.body()
                    ?: return@withContext BecalmResult.Failure(BecalmError.Unknown(IllegalStateException("null user correction body")))
                val entities = body.data.map { it.toEntity(userId) }
                if (entities.isNotEmpty()) {
                    dao.upsertAll(entities)
                }
                totalFetched += body.data.size
                totalUpserted += entities.size
                lastHasMore = body.hasMore
                lastCursor = body.cursor
                cursor = body.cursor
                if (useStoredCursor) cursorStore.setCursor(cursorKey, body.cursor)
            }
            BecalmResult.Success(
                UserCorrectionRepository.RefreshStats(
                    fetched = totalFetched,
                    upserted = totalUpserted,
                    hasMore = lastHasMore,
                    nextCursor = lastCursor,
                ),
            )
        }

    override suspend fun applyActiveCorrections(userId: String): BecalmResult<Int> =
        withContext(ioDispatcher) {
            try {
                val active = dao.findActiveForUser(userId)
                active.forEach { materializer.apply(it) }
                if (active.isNotEmpty()) {
                    workScheduler.enqueuePersonInteractionIndex(initialDelaySeconds = 0L)
                }
                BecalmResult.Success(active.size)
            } catch (e: Exception) {
                logger.e(TAG, "apply active user corrections failed", e)
                BecalmResult.Failure(BecalmError.Unknown(e))
            }
        }

    private companion object {
        private const val TAG = "UserCorrectionRepo"
        private const val PAGE_LIMIT = 100
        private const val REFRESH_PAGE_CAP = 5
    }
}

@Singleton
public class UserCorrectionMaterializer @Inject constructor(
    private val personIndexDao: PersonIndexDao,
    private val commitmentDao: CommitmentDao,
    private val scheduleRowTombstoneDao: ScheduleRowTombstoneDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    public suspend fun apply(correction: UserCorrectionEntity): Unit = withContext(ioDispatcher) {
        when (correction.action) {
            "participant_reassign" -> applyParticipantReassign(correction)
            "participant_ignore" -> applyParticipantIgnore(correction)
            "commitment_not_action" -> applyCommitmentNotAction(correction)
            "schedule_hide" -> applyScheduleHide(correction)
        }
    }

    private suspend fun applyParticipantReassign(correction: UserCorrectionEntity) {
        val participant = personIndexDao.findSourceEventParticipantById(correction.userId, correction.targetId)
            ?: return
        val payload = correction.payloadMap()
        val personId = correction.toPersonId ?: payload["person_id"]?.toString() ?: return
        personIndexDao.reassignSourceEventParticipantById(
            userId = correction.userId,
            participantId = correction.targetId,
            personId = personId,
            identityType = payload["identity_type"]?.toString(),
            normalizedValue = payload["normalized_value"]?.toString(),
            displayNameRaw = payload["display_name_raw"]?.toString(),
            confidence = payload["confidence"]?.toString()?.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 1.0,
        )
        val updatedParticipant = personIndexDao.findSourceEventParticipantById(correction.userId, correction.targetId)
            ?: participant.copy(personId = personId, resolutionStatus = "resolved", relationToUser = "counterparty")
        val commitments = liveReviewableCommitmentsFor(updatedParticipant)
        val commitmentParticipants = commitments.map {
            it.toCorrectionCommitmentParticipant(
                userId = correction.userId,
                personId = personId,
                now = Clock.System.now(),
            )
        }
        if (commitments.isNotEmpty()) {
            personIndexDao.deleteCommitmentParticipantsForCommitments(correction.userId, commitments.map(CommitmentEntity::id))
        }
        if (commitmentParticipants.isNotEmpty()) {
            personIndexDao.upsertCommitmentParticipants(commitmentParticipants)
        }
        personIndexDao.deleteInteractionsForSourceEvent(correction.userId, updatedParticipant.sourceEventId)
        commitments.forEach { personIndexDao.deleteInteractionsForCommitment(correction.userId, it.id) }
        personIndexDao.upsertDirtySources(
            PersonIndexDirtySources.forSourceParticipants(listOf(updatedParticipant), reason = "user_correction", now = Clock.System.now()) +
                PersonIndexDirtySources.forCommitments(commitments, reason = "user_correction", now = Clock.System.now()),
        )
    }

    private suspend fun applyParticipantIgnore(correction: UserCorrectionEntity) {
        val participant = personIndexDao.findSourceEventParticipantById(correction.userId, correction.targetId)
            ?: return
        personIndexDao.ignoreSourceEventParticipantById(
            userId = correction.userId,
            participantId = correction.targetId,
            confidence = correction.payloadMap()["confidence"]?.toString()?.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 1.0,
        )
        val commitments = liveReviewableCommitmentsFor(participant)
        if (commitments.isNotEmpty()) {
            personIndexDao.deleteCommitmentParticipantsForCommitments(correction.userId, commitments.map(CommitmentEntity::id))
        }
        personIndexDao.deleteInteractionsForSourceEvent(correction.userId, participant.sourceEventId)
        commitments.forEach { personIndexDao.deleteInteractionsForCommitment(correction.userId, it.id) }
        val updatedParticipant = participant.copy(personId = null, resolutionStatus = "ignored", relationToUser = "counterparty")
        personIndexDao.upsertDirtySources(
            PersonIndexDirtySources.forSourceParticipants(listOf(updatedParticipant), reason = "user_correction", now = Clock.System.now()) +
                PersonIndexDirtySources.forCommitments(commitments, reason = "user_correction", now = Clock.System.now()),
        )
    }

    private suspend fun applyCommitmentNotAction(correction: UserCorrectionEntity) {
        val commitmentId = correction.commitmentId ?: correction.targetId
        val now = Clock.System.now()
        commitmentDao.softDelete(id = commitmentId, actor = correction.userId, at = now)
        personIndexDao.deleteCommitmentParticipantsForCommitments(correction.userId, listOf(commitmentId))
        personIndexDao.deleteInteractionsForCommitment(correction.userId, commitmentId)
        personIndexDao.upsertDirtySources(
            listOf(
                PersonIndexDirtySources.commitment(
                    userId = correction.userId,
                    commitmentId = commitmentId,
                    reason = "user_correction",
                    now = now,
                ),
            ),
        )
    }

    private suspend fun applyScheduleHide(correction: UserCorrectionEntity) {
        val payload = correction.payloadMap()
        val now = Clock.System.now()
        scheduleRowTombstoneDao.upsert(
            ScheduleRowTombstoneEntity(
                id = "${correction.userId}:${correction.targetId}",
                userId = correction.userId,
                rowType = payload["row_type"]?.toString() ?: correction.targetType,
                sourceEventId = correction.sourceEventId ?: correction.targetId,
                sourceType = payload["source_type"]?.toString(),
                sourceRef = payload["source_ref"]?.toString(),
                deletedAt = now,
                // The correction row is the backend-sync source of truth; this tombstone only hides the row locally.
                syncStatus = UserCorrectionSyncStatus.SYNCED,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private suspend fun liveReviewableCommitmentsFor(participant: SourceEventParticipantEntity): List<CommitmentEntity> {
        val refs = listOfNotNull(
            "raw:${participant.sourceEventId}",
            participant.sourceEventId,
            participant.sourceRef,
        ).map(String::trim).filter(String::isNotEmpty).distinct()
        if (refs.isEmpty()) return emptyList()
        return commitmentDao.findLiveReviewableCommitmentsForSourceRefs(
            userId = participant.userId,
            sourceType = participant.sourceType,
            sourceRefs = refs,
        )
    }
}

private fun UserCorrectionCommand.toEntity(userId: String, now: Instant): UserCorrectionEntity {
    val id = UUID.randomUUID().toString()
    return UserCorrectionEntity(
        id = id,
        userId = userId,
        domain = domain,
        action = action,
        targetType = targetType,
        targetId = targetId,
        sourceEventId = sourceEventId,
        commitmentId = commitmentId,
        fromPersonId = fromPersonId,
        toPersonId = toPersonId,
        conflictKey = conflictKey,
        idempotencyKey = "android:$id",
        targetFingerprint = targetFingerprint,
        payloadJson = payload.toPayloadJson(),
        status = UserCorrectionStatus.ACTIVE,
        syncStatus = UserCorrectionSyncStatus.PENDING,
        failureReason = null,
        clientCreatedAt = now,
        appliedAt = null,
        createdAt = now,
        updatedAt = now,
    )
}

private fun UserCorrectionEntity.toRequestDto(): UserCorrectionRequestDto =
    UserCorrectionRequestDto(
        id = id,
        domain = domain,
        action = action,
        targetType = targetType,
        targetId = targetId,
        sourceEventId = sourceEventId,
        commitmentId = commitmentId,
        fromPersonId = fromPersonId,
        toPersonId = toPersonId,
        conflictKey = conflictKey,
        idempotencyKey = idempotencyKey,
        targetFingerprint = targetFingerprint,
        payload = payloadMap(),
        clientCreatedAt = clientCreatedAt,
    )

private fun UserCorrectionDto.toEntity(userIdFallback: String): UserCorrectionEntity =
    UserCorrectionEntity(
        id = id,
        userId = userId ?: userIdFallback,
        domain = domain,
        action = action,
        targetType = targetType,
        targetId = targetId,
        sourceEventId = sourceEventId,
        commitmentId = commitmentId,
        fromPersonId = fromPersonId,
        toPersonId = toPersonId,
        conflictKey = conflictKey,
        idempotencyKey = idempotencyKey,
        targetFingerprint = targetFingerprint,
        payloadJson = payload.toPayloadJson(),
        status = status,
        syncStatus = UserCorrectionSyncStatus.SYNCED,
        failureReason = failureReason,
        clientCreatedAt = clientCreatedAt ?: createdAt,
        appliedAt = appliedAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

private fun CommitmentEntity.toCorrectionCommitmentParticipant(
    userId: String,
    personId: String,
    now: Instant,
): CommitmentParticipantEntity {
    val participantRole = when (itemType) {
        CommitmentItemType.ACTION -> direction ?: "owner"
        CommitmentItemType.DECISION -> "decision_maker"
        else -> "attendee"
    }
    return CommitmentParticipantEntity(
        id = UUID.nameUUIDFromBytes(
            "commitment-participant:$userId:$id:$personId:$participantRole".toByteArray(Charsets.UTF_8),
        ).toString(),
        userId = userId,
        commitmentId = id,
        personId = personId,
        role = participantRole,
        evidence = quote,
        confidence = confidence,
        createdAt = now,
    )
}

private fun UserCorrectionEntity.payloadMap(): Map<String, Any?> =
    payloadJson.toPayloadMap()

private fun Map<String, Any?>.toPayloadJson(): String {
    if (isEmpty()) return "{}"
    val json = JSONObject()
    forEach { (key, value) ->
        when (value) {
            null -> json.put(key, JSONObject.NULL)
            is Number, is Boolean, is String -> json.put(key, value)
            else -> json.put(key, value.toString())
        }
    }
    return json.toString()
}

private fun String.toPayloadMap(): Map<String, Any?> =
    runCatching {
        val json = JSONObject(this)
        buildMap {
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = json.opt(key)
                put(key, if (value == JSONObject.NULL) null else value)
            }
        }
    }.getOrElse { emptyMap() }

private fun <T> Response<T>.toError(): BecalmError = when (code()) {
    401 -> BecalmError.Unauthorized
    404 -> BecalmError.NotFound("user_corrections")
    422 -> BecalmError.Validation(null, message())
    429 -> BecalmError.RateLimited(headers().get("Retry-After")?.toLongOrNull())
    in 500..599 -> BecalmError.ServerError(code(), errorBody()?.string())
    else -> BecalmError.Network(code(), message())
}
