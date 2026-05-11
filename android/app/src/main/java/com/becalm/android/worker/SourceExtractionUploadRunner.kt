package com.becalm.android.worker

import androidx.work.ListenableWorker
import com.becalm.android.core.util.Logger
import com.becalm.android.core.util.redact
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.api.SourceExtractionApi
import com.becalm.android.data.remote.dto.SourceExtractionErrorEnvelope
import com.becalm.android.data.repository.SourceExtractionInputAdapter
import com.becalm.android.data.repository.ProcessingStatusRepository
import com.becalm.android.data.repository.toPlainRequestBody
import com.squareup.moshi.Moshi
import java.io.IOException
import kotlinx.datetime.Clock
import okhttp3.MultipartBody
import okhttp3.RequestBody

internal data class SourceExtractionUploadRequest(
    val userId: String,
    val entity: RawIngestionEventEntity,
    val rawEventId: String,
    val inputModality: String,
    val audioPart: MultipartBody.Part? = null,
    val imagePart: MultipartBody.Part? = null,
    val durationSecondsFallback: RequestBody? = null,
    val nonRetryableErrorMessage: String,
    val onMarkFailed: suspend (reasonCode: String?) -> Unit,
    val onRateLimited: (suspend (retryAfterSeconds: Long?) -> ListenableWorker.Result)? = null,
    val selfSpeakerId: String? = null,
    val speakerMappingsJson: String? = null,
    val speakerPreviewId: String? = null,
)

internal class SourceExtractionUploadRunner(
    private val sourceExtractionApi: SourceExtractionApi,
    private val sourceExtractionInputAdapter: SourceExtractionInputAdapter,
    private val extractionPersister: StructuredExtractionPersister,
    private val processingStatusRepository: ProcessingStatusRepository,
    private val moshi: Moshi,
    private val logger: Logger,
    private val tag: String,
    private val runAttemptCount: Int,
    private val maxAttempts: Int,
) {
    suspend fun upload(request: SourceExtractionUploadRequest): ListenableWorker.Result {
        val parts = sourceExtractionInputAdapter.toRequestParts(
            event = request.entity,
            rawEventId = request.rawEventId,
        )
        val response = try {
            sourceExtractionApi.commitmentExtract(
                audio = request.audioPart,
                image = request.imagePart,
                inputModality = request.inputModality.toPlainRequestBody(),
                sourceType = parts.sourceType,
                clientEventId = parts.clientEventId,
                rawEventId = parts.rawEventId,
                durationSeconds = parts.durationSeconds ?: request.durationSecondsFallback,
                timestamp = parts.timestamp,
                counterpartyRef = parts.counterpartyRef,
                eventTitle = parts.eventTitle,
                folder = parts.folder,
                conversationRef = null,
                previousThreadContext = null,
                selfSpeakerId = request.selfSpeakerId?.toPlainRequestBody(),
                speakerMappings = request.speakerMappingsJson?.toPlainRequestBody(),
                speakerPreviewId = request.speakerPreviewId?.toPlainRequestBody(),
            )
        } catch (e: IOException) {
            logger.w(tag, "network error id=${redact(request.rawEventId)} attempt=$runAttemptCount: ${e.message}")
            return handleTransientFailure(request)
        }

        return when (response.code()) {
            200 -> {
                val body = response.body()
                    ?: return handleTransientFailure(request)
                extractionPersister.persist(
                    userId = request.userId,
                    entity = request.entity,
                    body = body,
                    now = Clock.System.now(),
                )
                logger.d(tag, "upload success id=${redact(request.rawEventId)} items=${body.items.size}")
                ListenableWorker.Result.success()
            }
            401 -> {
                logger.w(tag, "HTTP 401 after refresh id=${redact(request.rawEventId)} — marking failed")
                processingStatusRepository.recordError(request.entity.sourceType, "Unauthorized")
                request.onMarkFailed("unauthorized")
                ListenableWorker.Result.success()
            }
            403, 413, 422 -> {
                logger.w(tag, "HTTP ${response.code()} non-retryable id=${redact(request.rawEventId)} — quarantining")
                processingStatusRepository.recordError(request.entity.sourceType, request.nonRetryableErrorMessage)
                request.onMarkFailed("non_retryable_http_${response.code()}")
                ListenableWorker.Result.success()
            }
            502 -> handle502(response.errorBody()?.string(), request)
            429 -> request.onRateLimited?.invoke(response.headers()[HEADER_RETRY_AFTER]?.toLongOrNull())
                ?: handleTransientFailure(request)
            500, 503 -> {
                logger.w(tag, "HTTP ${response.code()} transient id=${redact(request.rawEventId)} attempt=$runAttemptCount")
                handleTransientFailure(request)
            }
            else -> {
                logger.w(tag, "HTTP ${response.code()} unexpected id=${redact(request.rawEventId)} — marking failed")
                processingStatusRepository.recordError(request.entity.sourceType, "Unexpected HTTP ${response.code()}")
                request.onMarkFailed("unexpected_http_${response.code()}")
                ListenableWorker.Result.success()
            }
        }
    }

    private suspend fun handle502(
        errorBodyString: String?,
        request: SourceExtractionUploadRequest,
    ): ListenableWorker.Result {
        val envelope = runCatchingNonCancel(
            logger = logger,
            tag = tag,
            op = "HTTP 502 parse failed id=${redact(request.rawEventId)}",
            block = {
                errorBodyString?.let {
                    moshi.adapter(SourceExtractionErrorEnvelope::class.java).fromJson(it)
                }
            },
            onFailure = { null },
        )
        val errorCode = envelope?.error
        logger.w(tag, "HTTP 502 id=${redact(request.rawEventId)} error=$errorCode attempt=$runAttemptCount")
        return when (SourceExtractionUploadStateMachine.decide502Action(errorCode)) {
            SourceExtraction502Action.Quarantine -> {
                processingStatusRepository.recordError(
                    request.entity.sourceType,
                    errorCode ?: "Source extraction failed",
                )
                request.onMarkFailed(errorCode ?: "vertex_502_unknown")
                ListenableWorker.Result.success()
            }
            SourceExtraction502Action.HandleAsTransient -> handleTransientFailure(request)
        }
    }

    private suspend fun handleTransientFailure(
        request: SourceExtractionUploadRequest,
    ): ListenableWorker.Result {
        return when (SourceExtractionUploadStateMachine.decideRetryAction(runAttemptCount, maxAttempts)) {
            RetryAction.Quarantine -> {
                logger.w(tag, "exhausted retries id=${redact(request.entity.id)} — marking failed")
                request.onMarkFailed("retry_exhausted")
                ListenableWorker.Result.success()
            }
            RetryAction.Retry -> ListenableWorker.Result.retry()
        }
    }

    private companion object {
        private const val HEADER_RETRY_AFTER: String = "Retry-After"
    }
}
