package com.becalm.android.data.repository

import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.dao.CommitmentDao
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.local.db.dao.SelfIdentityAnchorDao
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.CommitmentParticipantEntity
import com.becalm.android.data.local.db.entity.PersonEntity
import com.becalm.android.data.local.db.entity.PersonIdentityEntity
import com.becalm.android.data.local.db.dao.PersonIndexDao
import com.becalm.android.data.local.db.entity.PendingSourceParticipantMirrorEntity
import com.becalm.android.data.local.db.entity.SelfIdentityAnchorEntity
import com.becalm.android.data.local.db.entity.SourceEventParticipantEntity
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.SourceEventParticipantPatchRequestDto
import com.becalm.android.domain.person.PersonIdentityResolver
import com.becalm.android.domain.person.PersonIdentityTypes
import com.becalm.android.worker.WorkScheduler
import java.io.IOException
import java.util.UUID
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
    private val selfIdentityAnchorDao: SelfIdentityAnchorDao,
    private val rawIngestionEventDao: RawIngestionEventDao,
    private val commitmentDao: CommitmentDao,
    private val workScheduler: WorkScheduler,
    private val apiProvider: Provider<RailwayApi> = Provider { error("RailwayApi is not configured for this repository") },
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : PersonManualMatchRepository {

    private val api: RailwayApi?
        get() = runCatching { apiProvider.get() }.getOrNull()

    override suspend fun matchInteraction(
        userId: String,
        sourceType: String,
        sourceRef: String,
        interactionKind: String,
        personAnchor: String,
        nickname: String?,
    ): BecalmResult<Unit> = withContext(ioDispatcher) {
        val cleanedNickname = nickname?.trim()?.takeIf { it.isNotEmpty() }
        val cleanedAnchor = personAnchor.trim()
        if (PersonIdentityResolver.isSpeakerLabelValue(cleanedAnchor)) {
            return@withContext speakerLabelFailure()
        }
        val resolved = resolveManualMatch(userId, personAnchor, cleanedNickname)
            ?: return@withContext speakerLabelFailure()
        val displayNameHint = cleanedNickname
            ?.takeUnless { PersonIdentityResolver.isSpeakerLabelValue(it) }
            ?: resolved.displayNameHint?.takeUnless { PersonIdentityResolver.isSpeakerLabelValue(it) }

        try {
            val sourceEventId = sourceRef.removePrefix("raw:")
            val now = Clock.System.now()
            val updated = personIndexDao.resolveUnmatchedSourceEventParticipants(
                userId = userId,
                sourceType = sourceType,
                sourceRef = sourceRef,
                sourceEventId = sourceEventId,
                personId = resolved.personId,
                identityType = resolved.identityType,
                normalizedValue = resolved.normalizedValue,
                rawValue = resolved.rawValue,
                displayNameHint = displayNameHint,
                confidence = resolved.confidence,
            )
            if (updated == 0) {
                logger.w(TAG, "manual match found no unresolved source participant source=$sourceType ref=$sourceRef")
                return@withContext noParticipantFailure("manual_match")
            } else {
                val linkedCommitments = upsertCommitmentParticipantsForResolvedCounterparty(
                    userId = userId,
                    sourceType = sourceType,
                    sourceRef = sourceRef,
                    sourceEventId = sourceEventId,
                    resolved = resolved,
                    now = now,
                )
                val dirtySources = listOf(
                    PersonIndexDirtySources.rawEvent(
                        userId = userId,
                        sourceType = sourceType,
                        sourceEventId = sourceEventId,
                        reason = "manual_match",
                        now = now,
                    ),
                ) + PersonIndexDirtySources.forCommitments(
                    commitments = linkedCommitments,
                    reason = "manual_match",
                    now = now,
                )
                personIndexDao.upsertDirtySources(dirtySources)
                mirrorManualMatch(
                    userId = userId,
                    sourceType = sourceType,
                    sourceEventId = sourceEventId,
                    resolved = resolved,
                    displayNameHint = displayNameHint,
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
                return@withContext noParticipantFailure("self_match")
            } else {
                upsertSelfIdentityAnchors(
                    userId = userId,
                    sourceType = sourceType,
                    sourceEventId = sourceEventId,
                )
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
                return@withContext noParticipantFailure("not_self")
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
                sourceEventId = participant.sourceEventId,
                sourceType = participant.sourceType,
                sourceRef = participant.sourceRef,
                personId = resolved.personId,
                role = participant.role,
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

    private suspend fun upsertCommitmentParticipantsForResolvedCounterparty(
        userId: String,
        sourceType: String,
        sourceRef: String,
        sourceEventId: String,
        resolved: ManualMatchResolution,
        now: kotlinx.datetime.Instant,
    ): List<CommitmentEntity> {
        val participants = personIndexDao.findSourceEventParticipantsForUserAndEventIds(
            userId = userId,
            sourceEventIds = listOf(sourceEventId),
        ).filter { it.sourceType == sourceType }
        val sourceRefs = buildList {
            add(sourceRef)
            add(sourceEventId)
            add(sourceRef.removePrefix("raw:"))
            rawIngestionEventDao.findById(sourceEventId, userId)?.sourceRef?.let(::add)
            participants.mapNotNullTo(this) { it.sourceRef }
        }
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        if (sourceRefs.isEmpty()) return emptyList()

        val commitments = commitmentDao.findLiveReviewableCommitmentsForSourceRefs(
            userId = userId,
            sourceType = sourceType,
            sourceRefs = sourceRefs,
        )
        if (commitments.isEmpty()) return emptyList()

        val singleConfirmedCounterpartyPersonId = participants.asSequence()
            .filter { it.personId != null }
            .filterNot { it.relationToUser.equals("self", ignoreCase = true) }
            .filterNot { it.role.equals("self", ignoreCase = true) }
            .mapNotNull { it.personId }
            .distinct()
            .take(2)
            .toList()
            .singleOrNull()

        val linkableCommitments = commitments.filter { commitment ->
            commitment.matchesResolvedCounterparty(resolved) ||
                singleConfirmedCounterpartyPersonId == resolved.personId
        }
        if (linkableCommitments.isEmpty()) return emptyList()

        personIndexDao.upsertCommitmentParticipants(
            linkableCommitments.map { commitment ->
                commitment.toReviewCommitmentParticipant(
                    userId = userId,
                    personId = resolved.personId,
                    now = now,
                )
            },
        )
        return linkableCommitments
    }

    private suspend fun upsertSelfIdentityAnchors(
        userId: String,
        sourceType: String,
        sourceEventId: String,
    ) {
        val now = Clock.System.now()
        val participants = personIndexDao.findSourceEventParticipantsForUserAndEventIds(
            userId = userId,
            sourceEventIds = listOf(sourceEventId),
        ).filter {
            it.sourceType == sourceType && it.resolutionStatus == "self_resolved"
        }
        val anchors = participants
            .flatMap { it.toUserConfirmedSelfAnchors(now) }
            .distinctBy { "${it.anchorType}:${it.normalizedValue}" }
        if (anchors.isNotEmpty()) {
            selfIdentityAnchorDao.insertAll(anchors)
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
            sourceEventId = sourceEventId,
            sourceType = sourceType,
            sourceRef = sourceRef,
            role = role,
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
            sourceEventId = sourceEventId,
            sourceType = sourceType,
            sourceRef = sourceRef,
            role = role,
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

    private fun SourceEventParticipantEntity.toUserConfirmedSelfAnchors(now: kotlinx.datetime.Instant): List<SelfIdentityAnchorEntity> =
        buildList {
            val email = PersonIdentityResolver.normalizeEmailAnchor(emailRaw)
                ?: normalizedValue.takeIf { identityType == "email" }
                    ?.let(PersonIdentityResolver::normalizeEmailAnchor)
            email?.let { normalized ->
                add(toSelfAnchor(anchorType = "email", normalized = normalized, displayValue = emailRaw ?: normalized, now = now))
            }
            val phone = PersonIdentityResolver.normalizePhoneAnchor(phoneRaw)
                ?: normalizedValue.takeIf { identityType == "phone" }
                    ?.let(PersonIdentityResolver::normalizePhoneAnchor)
            phone?.let { normalized ->
                add(toSelfAnchor(anchorType = "phone", normalized = normalized, displayValue = phoneRaw ?: normalized, now = now))
            }
            listOf(displayNameRaw, normalizedValue.takeIf { identityType in setOf("name", "alias") })
                .firstNotNullOfOrNull(PersonIdentityResolver::normalizeAlias)
                ?.let { normalized ->
                    add(toSelfAnchor(anchorType = "alias", normalized = normalized, displayValue = displayNameRaw ?: normalized, now = now))
                }
        }

    private fun SourceEventParticipantEntity.toSelfAnchor(
        anchorType: String,
        normalized: String,
        displayValue: String,
        now: kotlinx.datetime.Instant,
    ): SelfIdentityAnchorEntity =
        SelfIdentityAnchorEntity(
            id = UUID.nameUUIDFromBytes("self-anchor:$userId:$anchorType:$normalized:manual_match".toByteArray(Charsets.UTF_8))
                .toString(),
            userId = userId,
            anchorType = anchorType,
            normalizedValue = normalized,
            displayValue = displayValue,
            source = "manual_match",
            scope = "global",
            sourceConnectionId = null,
            sourceEventId = null,
            trust = "user_confirmed",
            status = "active",
            createdAt = now,
            updatedAt = now,
        )

    private fun Int.isRetryableMirrorStatus(): Boolean =
        this == 401 || this == 408 || this == 429 || this in 500..599

    private fun noParticipantFailure(action: String): BecalmResult.Failure =
        BecalmResult.Failure(BecalmError.NotFound("source_event_participant:$action"))

    private fun speakerLabelFailure(): BecalmResult.Failure =
        BecalmResult.Failure(
            BecalmError.Validation(
                field = "personAnchor",
                message = "speaker labels must be matched to a real person name, email, phone, or existing person",
            ),
        )

    private suspend fun resolveManualMatch(
        userId: String,
        personAnchor: String,
        nickname: String?,
    ): ManualMatchResolution? {
        val anchor = personAnchor.trim().takeIf { it.isNotEmpty() } ?: return null
        if (PersonIdentityResolver.isSpeakerLabelValue(anchor)) return null
        personIndexDao.findPersonForMemory(userId, anchor)?.let { person ->
            if (person.isSpeakerLabelPerson()) return null
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
    ): ManualMatchResolution? {
        val identity = personIndexDao.findIdentitiesForMemory(userId, person.id)
            .firstOrNull {
                it.identityType in MATCHABLE_IDENTITY_TYPES &&
                    it.normalizedValue.isNotBlank() &&
                    !it.isSpeakerLabelIdentity()
            }
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

        val displayName = nickname ?: person.displayName
        if (PersonIdentityResolver.isSpeakerLabelValue(displayName)) return null
        val nameResolution = PersonIdentityResolver.resolve(userId, displayName)
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

private fun CommitmentEntity.matchesResolvedCounterparty(resolved: ManualMatchResolution): Boolean =
    listOfNotNull(counterpartyRef, counterpartyRaw)
        .any(resolved::matchesAnchor)

private fun CommitmentEntity.toReviewCommitmentParticipant(
    userId: String,
    personId: String,
    now: kotlinx.datetime.Instant,
): CommitmentParticipantEntity {
    val participantRole = reviewParticipantRole()
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

private fun CommitmentEntity.reviewParticipantRole(): String =
    when (itemType) {
        CommitmentItemType.ACTION -> direction ?: "owner"
        CommitmentItemType.DECISION -> "decision_maker"
        else -> "attendee"
    }

private fun PersonEntity.isSpeakerLabelPerson(): Boolean =
    PersonIdentityResolver.isSpeakerLabelValue(displayName) ||
        PersonIdentityResolver.isSpeakerLabelValue(primaryEmail) ||
        PersonIdentityResolver.isSpeakerLabelValue(primaryPhone)

private fun PersonIdentityEntity.isSpeakerLabelIdentity(): Boolean =
    identityType == PersonIdentityTypes.SPEAKER_LABEL ||
        PersonIdentityResolver.isSpeakerLabelValue(normalizedValue) ||
        PersonIdentityResolver.isSpeakerLabelValue(rawValue) ||
        PersonIdentityResolver.isSpeakerLabelValue(displayName) ||
        PersonIdentityResolver.isSpeakerLabelValue(displayNameHint)

private data class ManualMatchResolution(
    val personId: String,
    val identityType: String,
    val normalizedValue: String,
    val rawValue: String,
    val displayNameHint: String?,
    val confidence: Double,
) {
    fun matchesAnchor(value: String): Boolean =
        when (identityType) {
            "email" -> {
                val resolvedEmail = PersonIdentityResolver.normalizeRelationEmailAnchor(normalizedValue)
                    ?: PersonIdentityResolver.normalizeRelationEmailAnchor(rawValue)
                resolvedEmail != null && PersonIdentityResolver.normalizeRelationEmailAnchor(value) == resolvedEmail
            }
            "phone" -> {
                val resolvedPhone = PersonIdentityResolver.normalizePhoneAnchor(normalizedValue)
                    ?: PersonIdentityResolver.normalizePhoneAnchor(rawValue)
                resolvedPhone != null && PersonIdentityResolver.normalizePhoneAnchor(value) == resolvedPhone
            }
            else -> {
                val resolvedAlias = listOf(rawValue, displayNameHint, normalizedValue)
                    .firstNotNullOfOrNull(PersonIdentityResolver::normalizeAlias)
                resolvedAlias != null && PersonIdentityResolver.normalizeAlias(value) == resolvedAlias
            }
        }
}
