package com.becalm.android.data.repository

import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.dao.PersonIndexDao
import com.becalm.android.data.local.db.entity.PersonAliasRuleEntity
import com.becalm.android.data.local.db.entity.PersonManualMatchEntity
import com.becalm.android.domain.person.PersonIdentityResolver
import com.becalm.android.worker.WorkScheduler
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

public interface PersonManualMatchRepository {
    /**
     * Pins one source row to [personAnchor] and, when [nickname] is present, teaches that alias
     * for future person-index rebuilds.
     */
    public suspend fun matchInteraction(
        userId: String,
        sourceType: String,
        sourceRef: String,
        interactionKind: String,
        personAnchor: String,
        nickname: String?,
        sourceScope: String? = null,
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
        sourceScope: String?,
    ): BecalmResult<Unit> = withContext(ioDispatcher) {
        val resolved = PersonIdentityResolver.resolve(userId, personAnchor)
            ?: return@withContext BecalmResult.Failure(
                BecalmError.Validation(
                    field = "personAnchor",
                    message = "person anchor must contain a resolvable name, email, or phone",
                ),
            )
        val now = Clock.System.now()
        val cleanedNickname = nickname?.trim()?.takeIf { it.isNotEmpty() }
        val manualId = UUID.nameUUIDFromBytes(
            "manual-match:$userId:$sourceType:$sourceRef:$interactionKind".toByteArray(Charsets.UTF_8),
        ).toString()

        try {
            personIndexDao.upsertManualMatch(
                PersonManualMatchEntity(
                    id = manualId,
                    userId = userId,
                    sourceType = sourceType,
                    sourceRef = sourceRef,
                    interactionKind = interactionKind,
                    matchedPersonId = resolved.personId,
                    matchedIdentityKey = resolved.identityKey,
                    nickname = cleanedNickname,
                    createdAt = now,
                    updatedAt = now,
                ),
            )

            val normalizedAlias = PersonIdentityResolver.normalizeAlias(cleanedNickname)
            if (normalizedAlias != null) {
                val normalizedScope = sourceScope?.trim()?.takeIf { it.isNotEmpty() }
                val aliasId = UUID.nameUUIDFromBytes(
                    "alias-rule:$userId:$normalizedAlias:${normalizedScope.orEmpty()}".toByteArray(Charsets.UTF_8),
                ).toString()
                personIndexDao.upsertAliasRule(
                    PersonAliasRuleEntity(
                        id = aliasId,
                        userId = userId,
                        alias = cleanedNickname.orEmpty(),
                        normalizedAlias = normalizedAlias,
                        personId = resolved.personId,
                        identityKey = resolved.identityKey,
                        sourceScope = normalizedScope,
                        enabled = true,
                        createdAt = now,
                        updatedAt = now,
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
