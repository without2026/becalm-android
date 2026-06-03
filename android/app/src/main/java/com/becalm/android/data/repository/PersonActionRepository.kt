package com.becalm.android.data.repository

import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.dao.PersonActionDao
import com.becalm.android.data.local.db.entity.PersonActionItemCacheEntity
import com.becalm.android.data.local.db.entity.PersonActionSyncStateEntity
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.PersonActionEvidenceRefDto
import com.becalm.android.data.remote.dto.PersonActionFeedResponseDto
import com.becalm.android.data.remote.dto.PersonActionItemDto
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import retrofit2.Response

public interface PersonActionRepository {
    public fun observeActiveForSurface(userId: String, surface: String, limit: Int = 100): Flow<List<PersonActionItemCacheEntity>>
    public suspend fun refresh(userId: String, surface: String? = null): BecalmResult<PersonActionRefreshStats>
}

public data class PersonActionRefreshStats(
    val fetched: Int,
    val deleted: Int,
    val serverWatermark: kotlinx.datetime.Instant?,
    val recomputeState: String?,
)

@Singleton
public class PersonActionRepositoryImpl @Inject constructor(
    private val dao: PersonActionDao,
    private val apiProvider: Provider<RailwayApi>,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PersonActionRepository {
    private val api: RailwayApi
        get() = apiProvider.get()

    override fun observeActiveForSurface(userId: String, surface: String, limit: Int): Flow<List<PersonActionItemCacheEntity>> =
        dao.observeActiveForSurface(userId = userId, surface = surface, limit = limit)

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
                    includeStale = true,
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
            surfacesCsv = surfaces.joinToString(","),
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
            reasonCodesCsv = reasonCodes.joinToString(","),
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
        )
    }

    private fun List<PersonActionEvidenceRefDto>.primaryEvidence(): PersonActionEvidenceRefDto? =
        firstOrNull { it.kind == "source_event" } ?: firstOrNull()

    private fun Response<PersonActionFeedResponseDto>.toPersonActionError(): BecalmResult.Failure =
        when (code()) {
            401 -> BecalmResult.Failure(BecalmError.Unauthorized)
            409 -> BecalmResult.Failure(BecalmError.Network(409, "person_action_full_refresh_required"))
            429 -> BecalmResult.Failure(BecalmError.RateLimited(headers()["Retry-After"]?.toLongOrNull()))
            in 500..599 -> BecalmResult.Failure(BecalmError.ServerError(code(), errorBody()?.string()))
            else -> BecalmResult.Failure(BecalmError.Network(code(), errorBody()?.string() ?: message()))
        }

    private fun String?.toSyncSurfaceKey(): String = this?.takeIf(String::isNotBlank) ?: ALL_SURFACES_KEY

    private companion object {
        private const val TAG = "PersonActionRepository"
        private const val PAGE_LIMIT = 100
        private const val MAX_PAGES = 10
        private const val ALL_SURFACES_KEY = "__all__"
    }
}
