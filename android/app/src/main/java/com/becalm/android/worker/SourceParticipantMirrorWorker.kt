package com.becalm.android.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.PersonIndexDao
import com.becalm.android.data.local.db.entity.PendingSourceParticipantMirrorEntity
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.SourceEventParticipantPatchRequestDto
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException
import javax.inject.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

@HiltWorker
public class SourceParticipantMirrorWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val userPrefsStore: UserPrefsStore,
    private val personIndexDaoProvider: Provider<PersonIndexDao>,
    private val apiProvider: Provider<RailwayApi>,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : CoroutineWorker(appContext, workerParams) {

    public constructor(
        appContext: Context,
        workerParams: WorkerParameters,
        userPrefsStore: UserPrefsStore,
        personIndexDao: PersonIndexDao,
        api: RailwayApi,
        logger: Logger,
        ioDispatcher: CoroutineDispatcher,
    ) : this(
        appContext = appContext,
        workerParams = workerParams,
        userPrefsStore = userPrefsStore,
        personIndexDaoProvider = Provider { personIndexDao },
        apiProvider = Provider { api },
        logger = logger,
        ioDispatcher = ioDispatcher,
    )

    override suspend fun doWork(): Result = withContext(ioDispatcher) {
        if (hasExceededMaxRetries(logger, TAG, MAX_RETRIES)) return@withContext Result.failure()
        val userId = userPrefsStore.observeCurrentUserId().first()
            ?.takeIf { it.isNotBlank() }
            ?: return@withContext Result.success()
        val dao = personIndexDaoProvider.get()
        val pending = dao.findPendingSourceParticipantMirrors(userId = userId, limit = BATCH_LIMIT + 1)
        if (pending.isEmpty()) return@withContext Result.success()

        val api = apiProvider.get()
        val rows = pending.take(BATCH_LIMIT)
        val hasMore = pending.size > BATCH_LIMIT
        var shouldRetry = false
        val succeeded = mutableListOf<String>()
        rows.forEach { row ->
            val response = try {
                api.patchSourceEventParticipant(
                    participantId = row.participantId,
                    request = row.toPatchRequest(),
                )
            } catch (e: IOException) {
                shouldRetry = true
                dao.markPendingSourceParticipantMirrorFailed(
                    userId = userId,
                    participantId = row.participantId,
                    lastError = e.message ?: "network error",
                    updatedAt = Clock.System.now(),
                )
                return@forEach
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                shouldRetry = true
                dao.markPendingSourceParticipantMirrorFailed(
                    userId = userId,
                    participantId = row.participantId,
                    lastError = t.message ?: t::class.java.simpleName,
                    updatedAt = Clock.System.now(),
                )
                return@forEach
            }
            if (response.isSuccessful) {
                succeeded += row.participantId
            } else if (response.code().isRetryableMirrorStatus()) {
                shouldRetry = true
                dao.markPendingSourceParticipantMirrorFailed(
                    userId = userId,
                    participantId = row.participantId,
                    lastError = "HTTP ${response.code()}",
                    updatedAt = Clock.System.now(),
                )
            } else {
                succeeded += row.participantId
                logger.w(TAG, "dropping non-retryable mirror participant=${row.participantId} http=${response.code()}")
            }
        }
        if (succeeded.isNotEmpty()) {
            dao.deletePendingSourceParticipantMirrors(userId = userId, participantIds = succeeded)
        }
        if (shouldRetry || hasMore) Result.retry() else Result.success()
    }

    private fun PendingSourceParticipantMirrorEntity.toPatchRequest(): SourceEventParticipantPatchRequestDto =
        SourceEventParticipantPatchRequestDto(
            personId = personId,
            identityType = identityType,
            normalizedValue = normalizedValue,
            displayNameRaw = displayNameRaw,
            emailRaw = emailRaw,
            phoneRaw = phoneRaw,
            organizationRaw = organizationRaw,
            titleRaw = titleRaw,
            confidence = confidence,
            relationToUser = relationToUser,
            resolutionStatus = resolutionStatus,
        )

    private fun Int.isRetryableMirrorStatus(): Boolean =
        this == 401 || this == 408 || this == 429 || this in 500..599

    private companion object {
        private const val TAG = "SourceParticipantMirrorWorker"
        private const val BATCH_LIMIT = 50
        private const val MAX_RETRIES = 5
    }
}
