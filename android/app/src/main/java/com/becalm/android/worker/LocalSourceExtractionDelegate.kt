package com.becalm.android.worker

import androidx.work.ListenableWorker
import com.becalm.android.core.util.Logger
import com.becalm.android.core.util.redact
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.CommitmentDao
import com.becalm.android.data.local.db.dao.PersonIndexDao
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.api.SourceExtractionApi
import com.becalm.android.data.repository.ProcessingStatusRepository
import com.becalm.android.data.repository.SourceExtractionInputAdapter
import com.becalm.android.data.repository.SourceStatusRepository
import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Clock

internal class LocalSourceExtractionDelegate(
    private val rawIngestionEventDao: RawIngestionEventDao,
    private val commitmentDao: CommitmentDao,
    private val personIndexDao: PersonIndexDao,
    private val sourceExtractionApi: SourceExtractionApi,
    private val userPrefsStore: UserPrefsStore,
    private val sourceStatusRepository: SourceStatusRepository,
    private val processingStatusRepository: ProcessingStatusRepository,
    private val workScheduler: WorkScheduler,
    private val moshi: Moshi,
    private val logger: Logger,
    private val tag: String,
    private val runAttemptCount: Int,
    private val maxAttempts: Int,
) {
    suspend fun loadContext(rawEventId: String): LocalSourceExtractionLoadResult {
        val userId = userPrefsStore.observeCurrentUserId().first()
        if (userId.isNullOrBlank()) {
            logger.e(tag, "no active userId — failing id=${redact(rawEventId)}")
            return LocalSourceExtractionLoadResult.Terminal(ListenableWorker.Result.failure())
        }
        val entity = rawIngestionEventDao.findById(id = rawEventId, userId = userId)
            ?: run {
                logger.e(tag, "RawIngestionEventEntity not found id=${redact(rawEventId)}")
                return LocalSourceExtractionLoadResult.Terminal(ListenableWorker.Result.failure())
            }
        return LocalSourceExtractionLoadResult.Loaded(userId = userId, entity = entity)
    }

    suspend fun parkIfConsentWithdrawn(entity: RawIngestionEventEntity, stage: String? = null): Boolean {
        if (userPrefsStore.observeThirdPartyProvisionConsent().first()) return false
        stage?.let { logger.w(tag, "$it — parking id=${redact(entity.id)} as awaiting_consent") }
        processingStatusRepository.recordBlocked(entity.sourceType, "PIPA consent required")
        rawIngestionEventDao.update(entity.copy(syncStatus = STATUS_AWAITING_CONSENT))
        return true
    }

    suspend fun markFailed(entity: RawIngestionEventEntity, reasonCode: String?) {
        logger.w(tag, "mark failed id=${redact(entity.id)} reason=$reasonCode")
        rawIngestionEventDao.update(
            entity.copy(
                syncStatus = "failed",
                retryCount = entity.retryCount + 1,
                lastAttemptAt = Clock.System.now(),
            ),
        )
    }

    fun uploadRunner(): SourceExtractionUploadRunner =
        SourceExtractionUploadRunner(
            sourceExtractionApi = sourceExtractionApi,
            sourceExtractionInputAdapter = SourceExtractionInputAdapter(),
            extractionPersister = StructuredExtractionPersister(
                rawIngestionEventDao = rawIngestionEventDao,
                commitmentDao = commitmentDao,
                personIndexDao = personIndexDao,
                sourceStatusRepository = sourceStatusRepository,
                processingStatusRepository = processingStatusRepository,
                workScheduler = workScheduler,
                logger = logger,
            ),
            processingStatusRepository = processingStatusRepository,
            moshi = moshi,
            logger = logger,
            tag = tag,
            runAttemptCount = runAttemptCount,
            maxAttempts = maxAttempts,
        )

    private companion object {
        private const val STATUS_AWAITING_CONSENT = "awaiting_consent"
    }
}

internal sealed interface LocalSourceExtractionLoadResult {
    data class Loaded(
        val userId: String,
        val entity: RawIngestionEventEntity,
    ) : LocalSourceExtractionLoadResult

    data class Terminal(val result: ListenableWorker.Result) : LocalSourceExtractionLoadResult
}
