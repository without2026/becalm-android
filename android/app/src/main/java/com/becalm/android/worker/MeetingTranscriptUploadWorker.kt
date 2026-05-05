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
import com.becalm.android.data.remote.api.SourceExtractionApi
import com.becalm.android.data.repository.ProcessingStatusRepository
import com.becalm.android.data.repository.SourceStatusRepository
import com.squareup.moshi.Moshi
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

@HiltWorker
public class MeetingTranscriptUploadWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val rawIngestionEventDaoProvider: Provider<RawIngestionEventDao>,
    private val commitmentDaoProvider: Provider<CommitmentDao>,
    private val personIndexDaoProvider: Provider<PersonIndexDao>,
    private val sourceExtractionApiProvider: Provider<SourceExtractionApi>,
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
        sourceExtractionApi: SourceExtractionApi,
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
        sourceExtractionApiProvider = Provider { sourceExtractionApi },
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

    private val sourceExtractionApi: SourceExtractionApi
        get() = sourceExtractionApiProvider.get()

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

        val delegate = extractionDelegate()
        val context = when (val load = delegate.loadContext(rawEventId)) {
            is LocalSourceExtractionLoadResult.Loaded -> load
            is LocalSourceExtractionLoadResult.Terminal -> return@withContext load.result
        }
        val entity = context.entity

        if (delegate.parkIfConsentWithdrawn(entity)) {
            return@withContext Result.success()
        }

        val transcript = readTranscriptText(entity.sourceRef)
            ?: run {
                processingStatusRepository.recordError(entity.sourceType, "Transcript unavailable")
                delegate.markFailed(entity, reasonCode = "transcript_unavailable")
                return@withContext Result.success()
            }

        if (transcript.isBlank()) {
            processingStatusRepository.recordError(entity.sourceType, "Transcript empty")
            delegate.markFailed(entity, reasonCode = "empty_transcript")
            return@withContext Result.success()
        }

        if (delegate.parkIfConsentWithdrawn(entity)) {
            return@withContext Result.success()
        }

        processingStatusRepository.recordGemini(entity.sourceType, "Analyzing transcript with Gemini")
        delegate.uploadRunner().upload(
            SourceExtractionUploadRequest(
                userId = context.userId,
                entity = entity,
                rawEventId = rawEventId,
                inputModality = "transcript",
                bodyText = transcript,
                nonRetryableErrorMessage = "Transcript rejected",
                onMarkFailed = { reasonCode -> delegate.markFailed(entity, reasonCode) },
            )
        )
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

    private fun extractionDelegate(): LocalSourceExtractionDelegate =
        LocalSourceExtractionDelegate(
            rawIngestionEventDao = rawIngestionEventDao,
            commitmentDao = commitmentDao,
            personIndexDao = personIndexDao,
            sourceExtractionApi = sourceExtractionApi,
            userPrefsStore = userPrefsStore,
            sourceStatusRepository = sourceStatusRepository,
            processingStatusRepository = processingStatusRepository,
            workScheduler = workScheduler,
            moshi = moshi,
            logger = logger,
            tag = TAG,
            runAttemptCount = runAttemptCount,
            maxAttempts = MAX_ATTEMPTS,
        )

    public companion object {
        public const val KEY_RAW_EVENT_ID: String = "raw_event_id"
        private const val TAG = "MeetingTranscriptUpload"
        private const val MAX_ATTEMPTS = 3
        private const val MAX_TRANSCRIPT_BYTES = 10 * 1024 * 1024
    }
}
