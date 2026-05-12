package com.becalm.android.data.repository

import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.entity.PersonEntity
import com.becalm.android.data.local.db.entity.PersonIdentityEntity
import com.becalm.android.data.local.db.dao.PersonIndexDao
import com.becalm.android.domain.person.PersonIdentityResolver
import com.becalm.android.worker.WorkScheduler
import javax.inject.Inject
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
}

public class PersonManualMatchRepositoryImpl @Inject constructor(
    private val personIndexDao: PersonIndexDao,
    private val workScheduler: WorkScheduler,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : PersonManualMatchRepository {

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
