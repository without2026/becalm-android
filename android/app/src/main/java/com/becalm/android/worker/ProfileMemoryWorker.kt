package com.becalm.android.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.repository.PersonMemoryInputCollector
import com.becalm.android.data.repository.PersonMemoryRemoteRepository
import com.becalm.android.data.repository.PersonMemorySemanticIndexStore
import com.becalm.android.data.repository.PersonMemoryStore
import com.becalm.android.domain.person.PersonMemoryMarkdownBuilder
import com.becalm.android.domain.person.PersonMemoryMarkdownValidator
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

@HiltWorker
public class ProfileMemoryWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val userPrefsStoreProvider: Provider<UserPrefsStore>,
    private val inputCollectorProvider: Provider<PersonMemoryInputCollector>,
    private val memoryStoreProvider: Provider<PersonMemoryStore>,
    private val semanticIndexStoreProvider: Provider<PersonMemorySemanticIndexStore>,
    private val remoteRepositoryProvider: Provider<PersonMemoryRemoteRepository>,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : CoroutineWorker(appContext, workerParams) {

    public constructor(
        appContext: Context,
        workerParams: WorkerParameters,
        userPrefsStore: UserPrefsStore,
        inputCollector: PersonMemoryInputCollector,
        memoryStore: PersonMemoryStore,
        semanticIndexStore: PersonMemorySemanticIndexStore,
        remoteRepository: PersonMemoryRemoteRepository,
        logger: Logger,
        ioDispatcher: CoroutineDispatcher,
    ) : this(
        appContext = appContext,
        workerParams = workerParams,
        userPrefsStoreProvider = Provider { userPrefsStore },
        inputCollectorProvider = Provider { inputCollector },
        memoryStoreProvider = Provider { memoryStore },
        semanticIndexStoreProvider = Provider { semanticIndexStore },
        remoteRepositoryProvider = Provider { remoteRepository },
        logger = logger,
        ioDispatcher = ioDispatcher,
    )

    override suspend fun doWork(): Result = withContext(ioDispatcher) {
        if (hasExceededMaxRetries(logger, TAG, MAX_RETRIES)) return@withContext Result.failure()

        val personId = inputData.getString(KEY_PERSON_ID)?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return@withContext Result.failure(
                workDataOf(KEY_STATUS to STATUS_MISSING_PERSON_ID),
            )
        val userId = userPrefsStoreProvider.get().observeCurrentUserId().first()
            ?.takeIf { it.isNotBlank() }
            ?: return@withContext Result.success(
                workDataOf(
                    KEY_STATUS to STATUS_SKIPPED_NO_USER,
                    KEY_PERSON_ID to personId,
                ),
            )

        val input = inputCollectorProvider.get().collect(
            userId = userId,
            personId = personId,
            generatedAt = Clock.System.now(),
        )
        if (input == null) {
            memoryStoreProvider.get().delete(userId, personId)
            return@withContext Result.success(
                workDataOf(
                    KEY_STATUS to STATUS_SKIPPED_NO_EVIDENCE,
                    KEY_PERSON_ID to personId,
                ),
            )
        }

        val markdown = PersonMemoryMarkdownBuilder.build(input)
        val validation = PersonMemoryMarkdownValidator.validate(
            markdown = markdown,
            expectedUserId = userId,
            expectedPersonId = personId,
        )
        if (validation.errors.isNotEmpty()) {
            logger.w(TAG, "memory validation failed personId=$personId errors=${validation.errors.joinToString()}")
            return@withContext Result.failure(
                workDataOf(
                    KEY_STATUS to STATUS_VALIDATION_FAILED,
                    KEY_PERSON_ID to personId,
                ),
            )
        }

        semanticIndexStoreProvider.get().upsert(input)
        val write = memoryStoreProvider.get().write(
            userId = userId,
            personId = personId,
            markdown = markdown,
        )
        logger.d(TAG, "memory written personId=$personId bytes=${write.byteSize}")
        when (val upload = remoteRepositoryProvider.get().uploadLocalMemory(userId, personId)) {
            is BecalmResult.Success -> {
                logger.d(TAG, "memory uploaded personId=$personId object=${upload.value.objectPath}")
                Result.success(
                    workDataOf(
                        KEY_STATUS to STATUS_WRITTEN_UPLOADED,
                        KEY_PERSON_ID to personId,
                        KEY_RELATIVE_PATH to write.relativePath,
                        KEY_CONTENT_HASH to write.contentHash,
                        KEY_BYTE_SIZE to write.byteSize,
                        KEY_REMOTE_OBJECT_PATH to upload.value.objectPath,
                    ),
                )
            }
            is BecalmResult.Failure -> {
                logger.w(TAG, "memory upload pending personId=$personId error=${upload.error::class.simpleName}")
                Result.success(
                    workDataOf(
                        KEY_STATUS to STATUS_WRITTEN_UPLOAD_PENDING,
                        KEY_PERSON_ID to personId,
                        KEY_RELATIVE_PATH to write.relativePath,
                        KEY_CONTENT_HASH to write.contentHash,
                        KEY_BYTE_SIZE to write.byteSize,
                        KEY_UPLOAD_ERROR to (upload.error::class.simpleName ?: "BecalmError"),
                    ),
                )
            }
        }
    }

    public companion object {
        public const val KEY_PERSON_ID: String = "person_id"
        public const val KEY_STATUS: String = "status"
        public const val KEY_RELATIVE_PATH: String = "relative_path"
        public const val KEY_CONTENT_HASH: String = "content_hash"
        public const val KEY_BYTE_SIZE: String = "byte_size"
        public const val KEY_REMOTE_OBJECT_PATH: String = "remote_object_path"
        public const val KEY_UPLOAD_ERROR: String = "upload_error"

        public const val STATUS_WRITTEN_UPLOADED: String = "written_uploaded"
        public const val STATUS_WRITTEN_UPLOAD_PENDING: String = "written_upload_pending"
        public const val STATUS_SKIPPED_NO_USER: String = "skipped_no_user"
        public const val STATUS_SKIPPED_NO_EVIDENCE: String = "skipped_no_evidence"
        public const val STATUS_MISSING_PERSON_ID: String = "missing_person_id"
        public const val STATUS_VALIDATION_FAILED: String = "validation_failed"

        private const val TAG: String = "ProfileMemoryWorker"
        private const val MAX_RETRIES: Int = 3
    }
}
