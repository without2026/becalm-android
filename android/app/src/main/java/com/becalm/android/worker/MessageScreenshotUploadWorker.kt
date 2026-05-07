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
import com.becalm.android.data.remote.api.SourceExtractionApi
import com.becalm.android.data.repository.ProcessingStatusRepository
import com.becalm.android.data.repository.SourceStatusRepository
import com.squareup.moshi.Moshi
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import javax.inject.Provider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.BufferedSink

@HiltWorker
public class MessageScreenshotUploadWorker @AssistedInject constructor(
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

        if (delegate.parkIfConsentWithdrawn(entity, stage = "PIPA consent not granted")) {
            return@withContext Result.success()
        }

        val imagePart = buildImagePart(entity)
            ?: run {
                processingStatusRepository.recordError(entity.sourceType, "Screenshot unavailable")
                delegate.markFailed(entity, reasonCode = "image_unavailable")
                return@withContext Result.success()
            }

        if (delegate.parkIfConsentWithdrawn(entity, stage = "PIPA consent withdrawn before network call")) {
            return@withContext Result.success()
        }

        processingStatusRepository.recordGemini(entity.sourceType, "Analyzing screenshot with Gemini")
        delegate.uploadRunner().upload(
            SourceExtractionUploadRequest(
                userId = context.userId,
                entity = entity,
                rawEventId = rawEventId,
                inputModality = "image",
                imagePart = imagePart,
                nonRetryableErrorMessage = "Screenshot rejected",
                onMarkFailed = { reasonCode -> delegate.markFailed(entity, reasonCode) },
            ),
        )
    }

    private fun buildImagePart(entity: RawIngestionEventEntity): MultipartBody.Part? {
        val sourceRef = entity.sourceRef ?: return null
        val uri = Uri.parse(sourceRef)
        val mimeType = imageMimeType(uri, entity.eventTitle)
        val streamProvider = runCatchingNonCancel(
            logger = logger,
            tag = TAG,
            op = "open image stream failed uri=${redact(sourceRef)}",
            block = {
                if (uri.scheme == "file") {
                    File(requireNotNull(uri.path)).inputStream()
                } else {
                    appContext.contentResolver.openInputStream(uri) ?: return null
                }
            },
            onFailure = { return null },
        )
        val requestBody = object : RequestBody() {
            override fun contentType() = mimeType.toMediaTypeOrNull()

            override fun writeTo(sink: BufferedSink) {
                streamProvider.use { input ->
                    val buffer = ByteArray(STREAM_BUFFER_BYTES)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        sink.write(buffer, 0, bytesRead)
                    }
                }
            }
        }
        val fileName = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: entity.eventTitle
            ?: "message-screenshot.jpg"
        return MultipartBody.Part.createFormData("image", fileName, requestBody)
    }

    private fun imageMimeType(uri: Uri, eventTitle: String?): String {
        val resolverType = runCatching { appContext.contentResolver.getType(uri) }.getOrNull()
        if (!resolverType.isNullOrBlank()) return resolverType.lowercase()
        val name = (uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: eventTitle.orEmpty()).lowercase()
        return when {
            name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
            name.endsWith(".webp") -> "image/webp"
            else -> "image/png"
        }
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
        private const val TAG = "MessageScreenshotUpload"
        private const val MAX_ATTEMPTS = 3
        private const val STREAM_BUFFER_BYTES = 8 * 1024
    }
}
