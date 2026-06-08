package com.becalm.android.data.repository

import androidx.room.withTransaction
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.BeCalmDatabaseProvider
import com.becalm.android.data.local.db.dao.CommitmentDao
import com.becalm.android.data.local.db.dao.ManualMemoryOutboxDao
import com.becalm.android.data.local.db.dao.PersonIndexDao
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.CommitmentLifecycleLegacy
import com.becalm.android.data.local.db.entity.CommitmentParticipantEntity
import com.becalm.android.data.local.db.entity.CommitmentScheduleStatus
import com.becalm.android.data.local.db.entity.ManualMemoryOutboxEntity
import com.becalm.android.data.local.db.entity.PersonEntity
import com.becalm.android.data.local.db.entity.PersonIdentityEntity
import com.becalm.android.data.local.db.entity.PersonInteractionEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.domain.onboarding.FirstMemoryInput
import com.becalm.android.domain.onboarding.FirstMemoryKind
import com.becalm.android.domain.person.PersonIdentityResolver
import com.becalm.android.domain.person.SourceInteractionKind
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

@Singleton
public class FirstMemoryRepositoryImpl @Inject constructor(
    private val userPrefsStore: UserPrefsStore,
    private val databaseProvider: BeCalmDatabaseProvider,
    private val commitmentDao: CommitmentDao,
    private val personIndexDao: PersonIndexDao,
    private val manualMemoryOutboxDao: ManualMemoryOutboxDao,
    private val manualMemoryOutboxSyncEngine: ManualMemoryOutboxSyncEngine,
    private val workScheduler: com.becalm.android.worker.WorkScheduler,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : FirstMemoryRepository {

    override suspend fun save(input: FirstMemoryInput): BecalmResult<FirstMemorySaveResult> =
        withContext(ioDispatcher) {
            val userId = userPrefsStore.observeCurrentUserId().firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: return@withContext BecalmResult.Failure(BecalmError.Unauthorized)
            val resolution = PersonIdentityResolver.resolve(userId, input.personName)
                ?: return@withContext BecalmResult.Failure(
                    BecalmError.Validation(field = "person_name", message = "invalid_person_name"),
                )
            val now = Clock.System.now()
            val sourceRef = "manual_memory:${input.clientMemoryId}"
            val commitmentId = stableId("first-memory:commitment", userId, input.clientMemoryId)
            val sourceInteractionId = stableId("first-memory:source", userId, input.clientMemoryId, resolution.personId)
            val commitmentInteractionId = stableId(
                "first-memory:commitment-interaction",
                userId,
                input.clientMemoryId,
                resolution.personId,
            )
            val participantId = stableId(
                "first-memory:commitment-participant",
                userId,
                input.clientMemoryId,
                resolution.personId,
            )

            val itemType = if (input.kind == FirstMemoryKind.SHARED_SCHEDULE) {
                CommitmentItemType.SCHEDULE
            } else {
                CommitmentItemType.ACTION
            }
            val direction = when (input.kind) {
                FirstMemoryKind.MY_ACTION -> "give"
                FirstMemoryKind.THEIR_ACTION -> "take"
                FirstMemoryKind.SHARED_SCHEDULE -> null
            }
            val scheduleStatus = if (input.kind == FirstMemoryKind.SHARED_SCHEDULE) {
                CommitmentScheduleStatus.CONFIRMED
            } else {
                null
            }

            val person = PersonEntity(
                id = resolution.personId,
                userId = userId,
                displayName = input.personName,
                kind = "person",
                primaryEmail = resolution.rawValue.takeIf { resolution.identityType == "email" },
                primaryPhone = resolution.rawValue.takeIf { resolution.identityType == "phone" },
                confidence = resolution.confidence,
                createdAt = now,
                updatedAt = now,
                archivedAt = null,
            )
            val identity = PersonIdentityEntity(
                id = PersonIdentityResolver.stableIdentityId(userId, resolution.identityKey),
                userId = userId,
                personId = resolution.personId,
                identityKey = resolution.identityKey,
                identityType = resolution.identityType,
                rawValue = resolution.rawValue,
                displayNameHint = input.personName,
                identityValue = resolution.rawValue,
                normalizedValue = resolution.identityKey.substringAfter(':', resolution.rawValue),
                displayName = input.personName,
                sourceType = SourceType.MANUAL,
                sourceRef = sourceRef,
                confidence = resolution.confidence,
                isPrimary = true,
                verified = false,
                lastSeenAt = now,
                createdAt = now,
                updatedAt = now,
            )
            val commitment = CommitmentEntity(
                id = commitmentId,
                userId = userId,
                itemType = itemType,
                direction = direction,
                scheduleStatus = scheduleStatus,
                decisionStatus = null,
                counterpartyRaw = input.personName,
                counterpartyRef = resolution.identityKey,
                title = input.promiseText,
                description = null,
                quote = input.promiseText,
                sourceEventTitle = firstMemorySourceTitle(input.personName),
                sourceEventOccurredAt = now,
                dueAt = null,
                dueHint = input.dueHint,
                dueIsApproximate = input.dueHint != null,
                actionState = "pending",
                sourceType = SourceType.MANUAL,
                sourceRef = sourceRef,
                confidence = 1.0,
                commitmentState = CommitmentLifecycleLegacy.DRAFT,
                syncStatus = "manual_pending",
                createdAt = now,
                updatedAt = now,
                lastEditedBy = userId,
                lastEditedAt = now,
            )
            val participant = CommitmentParticipantEntity(
                id = participantId,
                userId = userId,
                commitmentId = commitmentId,
                personId = resolution.personId,
                role = "counterparty",
                evidence = input.promiseText,
                confidence = 1.0,
                createdAt = now,
            )
            val sourceInteraction = PersonInteractionEntity(
                id = sourceInteractionId,
                userId = userId,
                personId = resolution.personId,
                sourceType = SourceType.MANUAL,
                sourceRef = sourceRef,
                interactionKind = SourceInteractionKind.forSourceType(SourceType.MANUAL),
                sourceEventId = null,
                commitmentId = null,
                interactionKey = "$userId:${resolution.personId}:$sourceRef::manual_${input.origin.name.lowercase()}",
                interactionType = "manual_${input.origin.name.lowercase()}",
                role = "counterparty",
                direction = null,
                status = input.origin.name.lowercase(),
                occurredAt = now,
                title = firstMemorySourceTitle(input.personName),
                snippet = input.promiseText,
                confidence = 1.0,
                createdAt = now,
            )
            val commitmentInteraction = PersonInteractionEntity(
                id = commitmentInteractionId,
                userId = userId,
                personId = resolution.personId,
                sourceType = SourceType.MANUAL,
                sourceRef = sourceRef,
                interactionKind = SourceInteractionKind.COMMITMENT,
                sourceEventId = null,
                commitmentId = commitmentId,
                interactionKey = "$userId:${resolution.personId}:$sourceRef:$commitmentId:${SourceInteractionKind.COMMITMENT}",
                interactionType = SourceInteractionKind.COMMITMENT,
                role = itemType,
                direction = direction,
                status = "pending",
                occurredAt = now,
                title = input.promiseText,
                snippet = input.dueHint,
                confidence = 1.0,
                createdAt = now,
            )
            val outbox = input.toManualMemoryOutbox(
                userId = userId,
                personId = resolution.personId,
                commitmentId = commitmentId,
                sourceRef = sourceRef,
                occurredAt = now,
            )

            try {
                databaseProvider.current().withTransaction {
                    personIndexDao.upsertPersons(listOf(person))
                    personIndexDao.upsertIdentities(listOf(identity))
                    commitmentDao.insert(commitment)
                    personIndexDao.upsertCommitmentParticipants(listOf(participant))
                    personIndexDao.upsertInteractions(listOf(sourceInteraction, commitmentInteraction))
                    manualMemoryOutboxDao.upsert(outbox)
                }
            } catch (e: Exception) {
                logger.e(TAG, "failed to save first memory locally", e)
                return@withContext BecalmResult.Failure(BecalmError.Io(e.message ?: "first memory save failed"))
            }

            val syncOutcome = runCatching {
                manualMemoryOutboxSyncEngine.sync(outbox)
            }.getOrElse { error ->
                logger.w(TAG, "first memory backend sync deferred", error)
                ManualMemoryOutboxSyncOutcome.RETRY_NEEDED
            }
            val syncedRemotely = syncOutcome == ManualMemoryOutboxSyncOutcome.SYNCED
            if (syncOutcome == ManualMemoryOutboxSyncOutcome.RETRY_NEEDED) {
                runCatching { workScheduler.enqueueManualMemoryOutboxRetry() }
                    .onFailure { logger.w(TAG, "manual memory outbox retry enqueue failed", it) }
            }
            BecalmResult.Success(
                FirstMemorySaveResult(
                    personId = resolution.personId,
                    commitmentId = commitmentId,
                    sourceRef = sourceRef,
                    syncedRemotely = syncedRemotely,
                ),
            )
        }

    private fun firstMemorySourceTitle(personName: String): String =
        "이제 ${personName}님과의 약속을 잊지 않게 정리했습니다"

    private val FirstMemoryKind.wireValue: String
        get() = when (this) {
            FirstMemoryKind.MY_ACTION -> "my_action"
            FirstMemoryKind.THEIR_ACTION -> "their_action"
            FirstMemoryKind.SHARED_SCHEDULE -> "shared_schedule"
        }

    private fun FirstMemoryInput.toManualMemoryOutbox(
        userId: String,
        personId: String,
        commitmentId: String,
        sourceRef: String,
        occurredAt: Instant,
    ): ManualMemoryOutboxEntity {
        val originChannel = origin.name.lowercase()
        val memoryKind = kind.wireValue
        val backendDueHint = dueHint.takeIf { kind == FirstMemoryKind.SHARED_SCHEDULE }
        return ManualMemoryOutboxEntity(
            userId = userId,
            clientMemoryId = clientMemoryId,
            personId = personId,
            commitmentId = commitmentId,
            sourceRef = sourceRef,
            personDisplayName = personName,
            originChannel = originChannel,
            memoryKind = memoryKind,
            title = promiseText,
            occurredAt = occurredAt,
            dueAt = null,
            dueHint = backendDueHint,
            payloadHash = manualMemoryPayloadHash(
                clientMemoryId = clientMemoryId,
                personDisplayName = personName,
                originChannel = originChannel,
                memoryKind = memoryKind,
                title = promiseText,
                dueAt = null,
                dueHint = backendDueHint,
            ),
            createdAt = occurredAt,
            updatedAt = occurredAt,
        )
    }

    private fun manualMemoryPayloadHash(
        clientMemoryId: String,
        personDisplayName: String,
        originChannel: String,
        memoryKind: String,
        title: String,
        dueAt: Instant?,
        dueHint: String?,
    ): String =
        sha256Hex(
            listOf(
                clientMemoryId,
                personDisplayName,
                originChannel,
                memoryKind,
                title,
                dueAt?.toString().orEmpty(),
                dueHint.orEmpty(),
            ).joinToString("\n"),
        )

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun stableId(vararg parts: String): String =
        UUID.nameUUIDFromBytes(parts.joinToString(":").toByteArray(StandardCharsets.UTF_8)).toString()

    private companion object {
        private const val TAG: String = "FirstMemoryRepository"
    }
}
