package com.becalm.android.worker

import android.content.Context
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.util.Logger
import com.becalm.android.core.util.redact
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.CommitmentDao
import com.becalm.android.data.local.db.dao.PersonIndexDao
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.api.VoiceApi
import com.becalm.android.data.remote.dto.VoiceErrorEnvelope
import com.becalm.android.data.repository.ProcessingStatusRepository
import com.becalm.android.data.repository.SourceStatusRepository
import com.squareup.moshi.Moshi
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException
import javax.inject.Provider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

@HiltWorker
public class MeetingTranscriptUploadWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val rawIngestionEventDaoProvider: Provider<RawIngestionEventDao>,
    private val commitmentDaoProvider: Provider<CommitmentDao>,
    private val personIndexDaoProvider: Provider<PersonIndexDao>,
    private val voiceApiProvider: Provider<VoiceApi>,
    private val userPrefsStore: UserPrefsStore,
    private val sourceStatusRepositoryProvider: Provider<SourceStatusRepository>,
    private val processingStatusRepository: ProcessingStatusRepository,
    private val workSchedulerProvider: Provider<WorkScheduler>,
    private val processingPauseGate: ProcessingPauseGate,
    private val moshi: Moshi,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : CoroutineWorker(appContext, workerParams) {

    public constructor(
        appContext: Context,
        workerParams: WorkerParameters,
        rawIngestionEventDao: RawIngestionEventDao,
        commitmentDao: CommitmentDao,
        personIndexDao: PersonIndexDao,
        voiceApi: VoiceApi,
        userPrefsStore: UserPrefsStore,
        sourceStatusRepository: SourceStatusRepository,
        processingStatusRepository: ProcessingStatusRepository,
        workScheduler: WorkScheduler,
        processingPauseGate: ProcessingPauseGate,
        moshi: Moshi,
        logger: Logger,
        ioDispatcher: CoroutineDispatcher,
    ) : this(
        appContext = appContext,
        workerParams = workerParams,
        rawIngestionEventDaoProvider = Provider { rawIngestionEventDao },
        commitmentDaoProvider = Provider { commitmentDao },
        personIndexDaoProvider = Provider { personIndexDao },
        voiceApiProvider = Provider { voiceApi },
        userPrefsStore = userPrefsStore,
        sourceStatusRepositoryProvider = Provider { sourceStatusRepository },
        processingStatusRepository = processingStatusRepository,
        workSchedulerProvider = Provider { workScheduler },
        processingPauseGate = processingPauseGate,
        moshi = moshi,
        logger = logger,
        ioDispatcher = ioDispatcher,
    )

    private val rawIngestionEventDao: RawIngestionEventDao
        get() = rawIngestionEventDaoProvider.get()

    private val commitmentDao: CommitmentDao
        get() = commitmentDaoProvider.get()

    private val personIndexDao: PersonIndexDao
        get() = personIndexDaoProvider.get()

    private val voiceApi: VoiceApi
        get() = voiceApiProvider.get()

    private val sourceStatusRepository: SourceStatusRepository
        get() = sourceStatusRepositoryProvider.get()

    private val workScheduler: WorkScheduler
        get() = workSchedulerProvider.get()

    public override suspend fun doWork(): Result = withContext(ioDispatcher) {
        if (processingPauseGate.shouldSkip(TAG)) return@withContext Result.success()

        val rawEventId = inputData.getString(KEY_RAW_EVENT_ID)
        if (rawEventId.isNullOrBlank()) {
            logger.e(TAG, "missing rawEventId")
            return@withContext Result.failure()
        }

        val userId = userPrefsStore.observeCurrentUserId().first()
        if (userId.isNullOrBlank()) {
            logger.e(TAG, "no active userId — failing id=${redact(rawEventId)}")
            return@withContext Result.failure()
        }

        val entity = rawIngestionEventDao.findById(id = rawEventId, userId = userId)
            ?: run {
                logger.e(TAG, "RawIngestionEventEntity not found id=${redact(rawEventId)}")
                return@withContext Result.failure()
            }

        if (parkIfConsentWithdrawn(entity)) {
            return@withContext Result.success()
        }

        val transcript = readTranscriptText(entity.sourceRef)
            ?: run {
                processingStatusRepository.recordError(entity.sourceType, "Transcript unavailable")
                markFailed(entity, reasonCode = "transcript_unavailable")
                return@withContext Result.success()
            }

        if (transcript.isBlank()) {
            processingStatusRepository.recordError(entity.sourceType, "Transcript empty")
            markFailed(entity, reasonCode = "empty_transcript")
            return@withContext Result.success()
        }

        if (parkIfConsentWithdrawn(entity)) {
            return@withContext Result.success()
        }

        val parts = entity.toRequestParts(rawEventId)
        processingStatusRepository.recordGemini(entity.sourceType, "Analyzing transcript with Gemini")
        val response = try {
            voiceApi.transcriptExtract(
                transcript = transcript.toPlainRequestBody(),
                clientEventId = parts.clientEventId,
                rawEventId = parts.rawEventId,
                timestamp = parts.timestamp,
                counterpartyRef = parts.counterpartyRef,
                eventTitle = parts.eventTitle,
            )
        } catch (e: IOException) {
            logger.w(TAG, "network error id=${redact(rawEventId)} attempt=$runAttemptCount: ${e.message}")
            return@withContext handleTransientFailure(entity)
        }

        when (response.code()) {
            200 -> {
                val body = response.body()
                    ?: return@withContext handleTransientFailure(entity)
                extractionPersister().persist(
                    userId = userId,
                    entity = entity,
                    body = body,
                    now = Clock.System.now(),
                )
                Result.success()
            }
            401 -> {
                processingStatusRepository.recordError(entity.sourceType, "Unauthorized")
                markFailed(entity, reasonCode = "unauthorized")
                Result.success()
            }
            403, 413, 422 -> {
                processingStatusRepository.recordError(entity.sourceType, "Transcript rejected")
                markFailed(entity, reasonCode = "non_retryable_http_${response.code()}")
                Result.success()
            }
            502 -> handleVoice502(response.errorBody()?.string(), entity)
            429, 500, 503 -> handleTransientFailure(entity)
            else -> {
                processingStatusRepository.recordError(entity.sourceType, "Unexpected HTTP ${response.code()}")
                markFailed(entity, reasonCode = "unexpected_http_${response.code()}")
                Result.success()
            }
        }
    }

    private fun readTranscriptText(sourceRef: String?): String? {
        val uri = sourceRef?.let(Uri::parse) ?: return null
        val bytes = runCatchingNonCancel(
            logger = logger,
            tag = TAG,
            op = "open transcript stream failed uri=${redact(sourceRef)}",
            block = {
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    val raw = input.readBytes()
                    if (raw.size > MAX_TRANSCRIPT_BYTES) return null
                    raw
                }
            },
            onFailure = { null },
        ) ?: return null
        return bytes.toString(Charsets.UTF_8)
    }

    private suspend fun parkIfConsentWithdrawn(entity: RawIngestionEventEntity): Boolean {
        if (userPrefsStore.observeThirdPartyProvisionConsent().first()) return false
        processingStatusRepository.recordBlocked(entity.sourceType, "PIPA consent required")
        rawIngestionEventDao.update(entity.copy(syncStatus = STATUS_AWAITING_CONSENT))
        return true
    }

    private suspend fun handleVoice502(
        errorBodyString: String?,
        entity: RawIngestionEventEntity,
    ): Result {
        val envelope = runCatchingNonCancel(
            logger = logger,
            tag = TAG,
            op = "HTTP 502 parse failed id=${redact(entity.id)}",
            block = {
                errorBodyString?.let {
                    moshi.adapter(VoiceErrorEnvelope::class.java).fromJson(it)
                }
            },
            onFailure = { null },
        )
        return when (VoiceUploadStateMachine.decide502Action(envelope?.error)) {
            Voice502Action.Quarantine -> {
                processingStatusRepository.recordError(entity.sourceType, envelope?.error ?: "Transcript extraction failed")
                markFailed(entity, reasonCode = envelope?.error ?: "vertex_502_unknown")
                Result.success()
            }
            Voice502Action.HandleAsTransient -> handleTransientFailure(entity)
        }
    }

    private suspend fun handleTransientFailure(entity: RawIngestionEventEntity): Result =
        when (VoiceUploadStateMachine.decideRetryAction(runAttemptCount, MAX_ATTEMPTS)) {
            RetryAction.Quarantine -> {
                markFailed(entity, reasonCode = "retry_exhausted")
                Result.success()
            }
            RetryAction.Retry -> Result.retry()
        }

    private suspend fun markFailed(entity: RawIngestionEventEntity, reasonCode: String?) {
        logger.w(TAG, "mark failed id=${redact(entity.id)} reason=$reasonCode")
        rawIngestionEventDao.update(
            entity.copy(
                syncStatus = "failed",
                retryCount = entity.retryCount + 1,
                lastAttemptAt = Clock.System.now(),
            ),
        )
    }

    private fun extractionPersister(): VoiceExtractionPersister =
        VoiceExtractionPersister(
            rawIngestionEventDao = rawIngestionEventDao,
            commitmentDao = commitmentDao,
            personIndexDao = personIndexDao,
            sourceStatusRepository = sourceStatusRepository,
            processingStatusRepository = processingStatusRepository,
            workScheduler = workScheduler,
            logger = logger,
        )

    public companion object {
        public const val KEY_RAW_EVENT_ID: String = "raw_event_id"
        private const val TAG = "MeetingTranscriptUpload"
        private const val MAX_ATTEMPTS = 3
        private const val MAX_TRANSCRIPT_BYTES = 10 * 1024 * 1024
        private const val STATUS_AWAITING_CONSENT = "awaiting_consent"
    }
}
