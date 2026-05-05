package com.becalm.android.data.repository

import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
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
        val resolved = PersonIdentityResolver.resolve(userId, personAnchor)
            ?: return@withContext BecalmResult.Failure(
                BecalmError.Validation(
                    field = "personAnchor",
                    message = "person anchor must contain a resolvable name, email, or phone",
                ),
            )
        val cleanedNickname = nickname?.trim()?.takeIf { it.isNotEmpty() }

        try {
            val sourceEventId = sourceRef.removePrefix("raw:")
            val normalizedValue = resolved.identityKey.substringAfter(':', resolved.rawValue)
            val updated = personIndexDao.resolveUnmatchedSourceEventParticipants(
                userId = userId,
                sourceType = sourceType,
                sourceRef = sourceRef,
                sourceEventId = sourceEventId,
                personId = resolved.personId,
                identityType = resolved.identityType,
                normalizedValue = normalizedValue,
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

    private companion object {
        private const val TAG = "PersonManualMatchRepo"
    }
}
