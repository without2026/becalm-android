package com.becalm.android.data.repository

import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.entity.PersonEntity
import com.becalm.android.data.local.db.entity.PersonIdentityEntity
import com.becalm.android.data.local.db.dao.PersonIndexDao
import com.becalm.android.data.local.db.entity.PendingSourceParticipantMirrorEntity
import com.becalm.android.data.local.db.entity.SourceEventParticipantEntity
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.SourceEventParticipantPatchRequestDto
import com.becalm.android.domain.person.PersonIdentityResolver
import com.becalm.android.worker.WorkScheduler
import java.io.IOException
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

public interface PersonManualMatchRepository {
    /**
     * Resolves the canonical source participant rows for one review event to [personAnchor].
     */
    public suspend fun matchInteraction(
        userId: String,
        sourceType: String,
        sourceRef: String,
        interactionKind: String,
        personAnchor: String,
        nickname: String?,
    ): BecalmResult<Unit>

    /**
     * Marks unresolved participants for one review event as the authenticated user.
     */
    public suspend fun matchInteractionAsSelf(
        userId: String,
        sourceType: String,
        sourceRef: String,
        interactionKind: String,
    ): BecalmResult<Unit>

    /**
     * Reopens a weak self suggestion as a counterparty review item.
     */
    public suspend fun rejectInteractionAsSelf(
        userId: String,
        sourceType: String,
        sourceRef: String,
        interactionKind: String,
    ): BecalmResult<Unit>
}

public class PersonManualMatchRepositoryImpl @Inject constructor(
    private val personIndexDao: PersonIndexDao,
    private val workScheduler: WorkScheduler,
    private val apiProvider: Provider<RailwayApi>,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : PersonManualMatchRepository {

    private val api: RailwayApi?
        get() = runCatching { apiProvider.get() }.getOrNull()

    public constructor(
        personIndexDao: PersonIndexDao,
        workScheduler: WorkScheduler,
        logger: Logger,
        ioDispatcher: CoroutineDispatcher,
    ) : this(
        personIndexDao = personIndexDao,
        workScheduler = workScheduler,
        apiProvider = Provider { error("RailwayApi is not configured for this test repository") },
        logger = logger,
        ioDispatcher = ioDispatcher,
    )

    override suspend fun matchInteraction(
        userId: String,
        sourceType: String,
        sourceRef: String,
        interactionKind: String,
        personAnchor: String,
        nickname: String?,
    ): BecalmResult<Unit> = withContext(ioDispatcher) {
        val cleanedNickname = nickname?.trim()?.takeIf { it.isNotEmpty() }
        val resolved = resolveManualMatch(userId, personAnchor, cleanedNickname)
            ?: return@withContext BecalmResult.Failure(
                BecalmError.Validation(
                    field = "personAnchor",
                    message = "person anchor must contain a resolvable name, email, or phone",
                ),
            )

        try {
            val sourceEventId = sourceRef.removePrefix("raw:")
            val updated = personIndexDao.resolveUnmatchedSourceEventParticipants(
                userId = userId,
                sourceType = sourceType,
                sourceRef = sourceRef,
                sourceEventId = sourceEventId,
                personId = resolved.personId,
                identityType = resolved.identityType,
                normalizedValue = resolved.normalizedValue,
                rawValue = resolved.rawValue,
                displayNameHint = cleanedNickname ?: resolved.displayNameHint,
                confidence = resolved.confidence,
            )
            if (updated == 0) {
                logger.w(TAG, "manual match found no unresolved source participant source=$sourceType ref=$sourceRef")
            } else {
                personIndexDao.upsertDirtySources(
                    listOf(
                        PersonIndexDirtySources.rawEvent(
                            userId = userId,
                            sourceType = sourceType,
                            sourceEventId = sourceEventId,
                            reason = "manual_match",
                            now = Clock.System.now(),
                        ),
                    ),
                )
                mirrorManualMatch(
                    userId = userId,
                    sourceType = sourceType,
                    sourceEventId = sourceEventId,
                    resolved = resolved,
                    displayNameHint = cleanedNickname ?: resolved.displayNameHint,
                )
            }
            workScheduler.enqueuePersonInteractionIndex(initialDelaySeconds = 0L)
            logger.d(TAG, "manual match saved source=$sourceType/$interactionKind ref=$sourceRef")
            BecalmResult.Success(Unit)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            logger.e(TAG, "manual match write failed", t)
            BecalmResult.Failure(BecalmError.Unknown(t))
        }
    }

    override suspend fun matchInteractionAsSelf(
        userId: String,
        sourceType: String,
        sourceRef: String,
        interactionKind: String,
    ): BecalmResult<Unit> = withContext(ioDispatcher) {
        try {
            val sourceEventId = sourceRef.removePrefix("raw:")
            val updated = personIndexDao.resolveUnmatchedSourceEventParticipantsAsSelf(
                userId = userId,
                sourceType = sourceType,
                sourceRef = sourceRef,
                sourceEventId = sourceEventId,
                confidence = SELF_MATCH_CONFIDENCE,
            )
            val deleted = personIndexDao.deleteUnmatchedInteractionsForSource(
                userId = userId,
                sourceType = sourceType,
                sourceRef = sourceRef,
                interactionKind = interactionKind,
            )
            if (updated == 0 && deleted == 0) {
                logger.w(TAG, "self match found no unresolved source participant source=$sourceType ref=$sourceRef")
            } else {
                personIndexDao.upsertDirtySources(
                    listOf(
                        PersonIndexDirtySources.rawEvent(
                            userId = userId,
                            sourceType = sourceType,
                            sourceEventId = sourceEventId,
                            reason = "self_match",
                            now = Clock.System.now(),
                        ),
                    ),
                )
                mirrorSelfMatch(
                    userId = userId,
                    sourceType = sourceType,
                    sourceEventId = sourceEventId,
                )
            }
            workScheduler.enqueuePersonInteractionIndex(initialDelaySeconds = 0L)
            logger.d(TAG, "self match saved source=$sourceType/$interactionKind ref=$sourceRef")
            BecalmResult.Success(Unit)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            logger.e(TAG, "self match write failed", t)
            BecalmResult.Failure(BecalmError.Unknown(t))
        }
    }

    override suspend fun rejectInteractionAsSelf(
        userId: String,
        sourceType: String,
        sourceRef: String,
        interactionKind: String,
    ): BecalmResult<Unit> = withContext(ioDispatcher) {
        try {
            val sourceEventId = sourceRef.removePrefix("raw:")
            val updated = personIndexDao.rejectSelfSourceEventParticipants(
                userId = userId,
                sourceType = sourceType,
                sourceRef = sourceRef,
                sourceEventId = sourceEventId,
                confidence = NOT_SELF_CONFIDENCE,
            )
            if (updated == 0) {
                logger.w(TAG, "not-self review found no suggested self participant source=$sourceType ref=$sourceRef")
            } else {
                personIndexDao.upsertDirtySources(
                    listOf(
                        PersonIndexDirtySources.rawEvent(
                            userId = userId,
                            sourceType = sourceType,
                            sourceEventId = sourceEventId,
                            reason = "not_self",
                            now = Clock.System.now(),
                        ),
                    ),
                )
                mirrorNotSelf(
                    userId = userId,
                    sourceType = sourceType,
                    sourceEventId = sourceEventId,
                )
            }
            workScheduler.enqueuePersonInteractionIndex(initialDelaySeconds = 0L)
            logger.d(TAG, "not-self review saved source=$sourceType/$interactionKind ref=$sourceRef")
            BecalmResult.Success(Unit)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            logger.e(TAG, "not-self review write failed", t)
            BecalmResult.Failure(BecalmError.Unknown(t))
        }
    }

    private suspend fun mirrorManualMatch(
        userId: String,
        sourceType: String,
        sourceEventId: String,
        resolved: ManualMatchResolution,
        displayNameHint: String?,
    ) {
        val remoteApi = api ?: return
        val participants = personIndexDao.findSourceEventParticipantsForUserAndEventIds(
            userId = userId,
            sourceEventIds = listOf(sourceEventId),
        ).filter {
            it.sourceType == sourceType &&
                it.personId == resolved.personId &&
                it.resolutionStatus == "resolved"
        }
        participants.forEach { participant ->
            val request = SourceEventParticipantPatchRequestDto(
                personId = resolved.personId,
                identityType = resolved.identityType,
                normalizedValue = resolved.normalizedValue,
                displayNameRaw = displayNameHint ?: participant.displayNameRaw,
                emailRaw = if (resolved.identityType == "email") resolved.rawValue else participant.emailRaw,
                phoneRaw = if (resolved.identityType == "phone") resolved.rawValue else participant.phoneRaw,
                organizationRaw = participant.organizationRaw,
                titleRaw = participant.titleRaw,
                confidence = resolved.confidence,
                relationToUser = participant.relationToUser.takeIf { it.isNotBlank() } ?: "counterparty",
                resolutionStatus = "resolved",
            )
            val response = try {
                remoteApi.patchSourceEventParticipant(
                    participantId = participant.id,
                    request = request,
                )
            } catch (e: IOException) {
                queueMirrorRetry(userId, participant.id, request, e.message ?: "network error")
                logger.w(TAG, "manual match remote mirror network failed participant=${participant.id}: ${e.message}")
                return@forEach
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                queueMirrorRetry(userId, participant.id, request, t.message ?: t::class.java.simpleName)
                logger.w(TAG, "manual match remote mirror failed participant=${participant.id}: ${t.message}")
                return@forEach
            }
            if (!response.isSuccessful) {
                logger.w(TAG, "manual match remote mirror HTTP ${response.code()} participant=${participant.id}")
                if (response.code().isRetryableMirrorStatus()) {
                    queueMirrorRetry(userId, participant.id, request, "HTTP ${response.code()}")
                }
            } else {
                personIndexDao.deletePendingSourceParticipantMirrors(userId, listOf(participant.id))
            }
        }
    }

    private suspend fun mirrorSelfMatch(
        userId: String,
        sourceType: String,
        sourceEventId: String,
    ) {
        val remoteApi = api ?: return
        val participants = personIndexDao.findSourceEventParticipantsForUserAndEventIds(
            userId = userId,
            sourceEventIds = listOf(sourceEventId),
        ).filter {
            it.sourceType == sourceType &&
                it.resolutionStatus == "self_resolved"
        }
        participants.forEach { participant ->
            val request = participant.toSelfPatchRequest()
            val response = try {
                remoteApi.patchSourceEventParticipant(
                    participantId = participant.id,
                    request = request,
                )
            } catch (e: IOException) {
                queueMirrorRetry(userId, participant.id, request, e.message ?: "network error")
                logger.w(TAG, "self match remote mirror network failed participant=${participant.id}: ${e.message}")
                return@forEach
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                queueMirrorRetry(userId, participant.id, request, t.message ?: t::class.java.simpleName)
                logger.w(TAG, "self match remote mirror failed participant=${participant.id}: ${t.message}")
                return@forEach
            }
            if (!response.isSuccessful) {
                logger.w(TAG, "self match remote mirror HTTP ${response.code()} participant=${participant.id}")
                if (response.code().isRetryableMirrorStatus()) {
                    queueMirrorRetry(userId, participant.id, request, "HTTP ${response.code()}")
                }
            } else {
                personIndexDao.deletePendingSourceParticipantMirrors(userId, listOf(participant.id))
            }
        }
    }

    private suspend fun mirrorNotSelf(
        userId: String,
        sourceType: String,
        sourceEventId: String,
    ) {
        val remoteApi = api ?: return
        val participants = personIndexDao.findSourceEventParticipantsForUserAndEventIds(
            userId = userId,
            sourceEventIds = listOf(sourceEventId),
        ).filter {
            it.sourceType == sourceType &&
                it.resolutionStatus == "unresolved" &&
                it.relationToUser == "counterparty"
        }
        participants.forEach { participant ->
            val request = participant.toNotSelfPatchRequest()
            val response = try {
                remoteApi.patchSourceEventParticipant(
                    participantId = participant.id,
                    request = request,
                )
            } catch (e: IOException) {
                queueMirrorRetry(userId, participant.id, request, e.message ?: "network error")
                logger.w(TAG, "not-self remote mirror network failed participant=${participant.id}: ${e.message}")
                return@forEach
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                queueMirrorRetry(userId, participant.id, request, t.message ?: t::class.java.simpleName)
                logger.w(TAG, "not-self remote mirror failed participant=${participant.id}: ${t.message}")
                return@forEach
            }
            if (!response.isSuccessful) {
                logger.w(TAG, "not-self remote mirror HTTP ${response.code()} participant=${participant.id}")
                if (response.code().isRetryableMirrorStatus()) {
                    queueMirrorRetry(userId, participant.id, request, "HTTP ${response.code()}")
                }
            } else {
                personIndexDao.deletePendingSourceParticipantMirrors(userId, listOf(participant.id))
            }
        }
    }

    private suspend fun queueMirrorRetry(
        userId: String,
        participantId: String,
        request: SourceEventParticipantPatchRequestDto,
        lastError: String,
    ) {
        val now = Clock.System.now()
        personIndexDao.upsertPendingSourceParticipantMirrors(
            listOf(
                PendingSourceParticipantMirrorEntity(
                    participantId = participantId,
                    userId = userId,
                    personId = request.personId,
                    identityType = request.identityType,
                    normalizedValue = request.normalizedValue,
                    displayNameRaw = request.displayNameRaw,
                    emailRaw = request.emailRaw,
                    phoneRaw = request.phoneRaw,
                    organizationRaw = request.organizationRaw,
                    titleRaw = request.titleRaw,
                    confidence = request.confidence,
                    relationToUser = request.relationToUser,
                    resolutionStatus = request.resolutionStatus,
                    retryCount = 0,
                    lastError = lastError,
                    createdAt = now,
                    updatedAt = now,
                ),
            ),
        )
        workScheduler.enqueueSourceParticipantMirrorRetry()
    }

    private fun SourceEventParticipantEntity.toSelfPatchRequest(): SourceEventParticipantPatchRequestDto =
        SourceEventParticipantPatchRequestDto(
            identityType = identityType,
            normalizedValue = normalizedValue,
            displayNameRaw = displayNameRaw,
            emailRaw = emailRaw,
            phoneRaw = phoneRaw,
            organizationRaw = organizationRaw,
            titleRaw = titleRaw,
            confidence = confidence.coerceAtLeast(SELF_MATCH_CONFIDENCE),
            relationToUser = "self",
            resolutionStatus = "self_resolved",
        )

    private fun SourceEventParticipantEntity.toNotSelfPatchRequest(): SourceEventParticipantPatchRequestDto =
        SourceEventParticipantPatchRequestDto(
            identityType = identityType,
            normalizedValue = normalizedValue,
            displayNameRaw = displayNameRaw,
            emailRaw = emailRaw,
            phoneRaw = phoneRaw,
            organizationRaw = organizationRaw,
            titleRaw = titleRaw,
            confidence = confidence.coerceAtLeast(NOT_SELF_CONFIDENCE),
            relationToUser = "counterparty",
            resolutionStatus = "unresolved",
        )

    private fun Int.isRetryableMirrorStatus(): Boolean =
        this == 401 || this == 408 || this == 429 || this in 500..599

    private suspend fun resolveManualMatch(
        userId: String,
        personAnchor: String,
        nickname: String?,
    ): ManualMatchResolution? {
        val anchor = personAnchor.trim().takeIf { it.isNotEmpty() } ?: return null
        personIndexDao.findPersonForMemory(userId, anchor)?.let { person ->
            return resolveExistingPerson(userId = userId, person = person, nickname = nickname)
        }
        return PersonIdentityResolver.resolve(userId, anchor)?.let { resolved ->
            ManualMatchResolution(
                personId = resolved.personId,
                identityType = resolved.identityType,
                normalizedValue = resolved.identityKey.substringAfter(':', resolved.rawValue),
                rawValue = resolved.rawValue,
                displayNameHint = resolved.displayNameHint,
                confidence = resolved.confidence,
            )
        }
    }

    private suspend fun resolveExistingPerson(
        userId: String,
        person: PersonEntity,
        nickname: String?,
    ): ManualMatchResolution {
        val identity = personIndexDao.findIdentitiesForMemory(userId, person.id)
            .firstOrNull { it.identityType in MATCHABLE_IDENTITY_TYPES && it.normalizedValue.isNotBlank() }
        if (identity != null) {
            return identity.toManualMatchResolution(personId = person.id, displayNameFallback = nickname ?: person.displayName)
        }

        person.primaryEmail?.let { email ->
            PersonIdentityResolver.normalizeEmailAnchor(email)?.let { normalized ->
                return ManualMatchResolution(
                    personId = person.id,
                    identityType = "email",
                    normalizedValue = normalized,
                    rawValue = email,
                    displayNameHint = nickname ?: person.displayName,
                    confidence = person.confidence.coerceAtLeast(EXISTING_PERSON_CONFIDENCE),
                )
            }
        }
        person.primaryPhone?.let { phone ->
            PersonIdentityResolver.normalizePhoneAnchor(phone)?.let { normalized ->
                return ManualMatchResolution(
                    personId = person.id,
                    identityType = "phone",
                    normalizedValue = normalized,
                    rawValue = phone,
                    displayNameHint = nickname ?: person.displayName,
                    confidence = person.confidence.coerceAtLeast(EXISTING_PERSON_CONFIDENCE),
                )
            }
        }

        val nameResolution = PersonIdentityResolver.resolve(userId, nickname ?: person.displayName)
        return ManualMatchResolution(
            personId = person.id,
            identityType = nameResolution?.identityType ?: "name",
            normalizedValue = nameResolution?.identityKey?.substringAfter(':', nameResolution.rawValue)
                ?: person.displayName.lowercase().replace(Regex("\\s+"), "-"),
            rawValue = nameResolution?.rawValue ?: person.displayName,
            displayNameHint = nickname ?: person.displayName,
            confidence = person.confidence.coerceAtLeast(EXISTING_PERSON_CONFIDENCE),
        )
    }

    private fun PersonIdentityEntity.toManualMatchResolution(
        personId: String,
        displayNameFallback: String,
    ): ManualMatchResolution =
        ManualMatchResolution(
            personId = personId,
            identityType = identityType,
            normalizedValue = normalizedValue.ifBlank {
                identityKey.substringAfter(':', rawValue)
            },
            rawValue = rawValue.ifBlank { identityValue.ifBlank { normalizedValue } },
            displayNameHint = displayName ?: displayNameHint ?: displayNameFallback,
            confidence = confidence.coerceAtLeast(EXISTING_PERSON_CONFIDENCE),
        )

    private companion object {
        private const val TAG = "PersonManualMatchRepo"
        private const val EXISTING_PERSON_CONFIDENCE = 0.95
        private const val SELF_MATCH_CONFIDENCE = 0.98
        private const val NOT_SELF_CONFIDENCE = 1.0
        private val MATCHABLE_IDENTITY_TYPES = setOf("email", "phone", "alias", "name")
    }
}

private data class ManualMatchResolution(
    val personId: String,
    val identityType: String,
    val normalizedValue: String,
    val rawValue: String,
    val displayNameHint: String?,
    val confidence: Double,
)
