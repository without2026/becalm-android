package com.becalm.android.worker

import androidx.work.ListenableWorker
import com.becalm.android.core.analytics.NoopProductAnalyticsClient
import com.becalm.android.core.analytics.ProductAnalyticsClient
import com.becalm.android.core.analytics.ProductAnalyticsEvent
import com.becalm.android.core.analytics.ProductAnalyticsEvents
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.core.util.redact
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.api.SourceExtractionApi
import com.becalm.android.data.remote.dto.CommitmentExtractionJobCreateRequest
import com.becalm.android.data.remote.dto.ExtractionUploadPrepareRequest
import com.becalm.android.data.remote.dto.SourceExtractionErrorEnvelope
import com.becalm.android.data.remote.dto.SourceExtractionResponse
import com.becalm.android.data.repository.SourceExtractionInputAdapter
import com.becalm.android.data.repository.SourceExtractionRequestParts
import com.becalm.android.data.repository.ProcessingStatusRepository
import com.becalm.android.data.repository.ProcessingStatusMessages
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.toPlainRequestBody
import com.squareup.moshi.Moshi
import java.io.IOException
import java.util.UUID
import kotlinx.datetime.Clock
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.BufferedSink
import retrofit2.Response

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
    val onJobAccepted: (suspend (jobId: String, retryAfterSeconds: Long?) -> ListenableWorker.Result)? = null,
    val onJobRetryableFailure: (suspend (retryAfterSeconds: Long?) -> ListenableWorker.Result)? = null,
    val onRestartSpeakerPreview: (suspend () -> ListenableWorker.Result)? = null,
    val selfSpeakerId: String? = null,
    val speakerMappingsJson: String? = null,
    val speakerPreviewId: String? = null,
    val processingConfirmed: Boolean = false,
)

internal class SourceExtractionUploadRunner(
    private val sourceExtractionApi: SourceExtractionApi,
    private val rawIngestionRepository: RawIngestionRepository,
    private val sourceExtractionInputAdapter: SourceExtractionInputAdapter,
    private val extractionPersister: StructuredExtractionPersister,
    private val processingStatusRepository: ProcessingStatusRepository,
    private val moshi: Moshi,
    private val logger: Logger,
    private val tag: String,
    private val runAttemptCount: Int,
    private val maxAttempts: Int,
    private val productAnalytics: ProductAnalyticsClient = NoopProductAnalyticsClient(),
) {
    suspend fun upload(request: SourceExtractionUploadRequest): ListenableWorker.Result {
        ensureSourceEventUploaded(request)?.let { return it }
        val parts = sourceExtractionInputAdapter.toRequestParts(
            event = request.entity,
            rawEventId = request.rawEventId,
        )
        trackExtraction(
            eventName = ProductAnalyticsEvents.EXTRACTION_STARTED,
            request = request,
            result = "started",
        )
        directUploadMedia(request)?.let { media ->
            return uploadWithPreparedStorage(request, parts, media)
        }
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
                conversationRef = parts.conversationRef,
                previousThreadContext = null,
                selfSpeakerId = request.selfSpeakerId?.toPlainRequestBody(),
                speakerMappings = request.speakerMappingsJson?.toPlainRequestBody(),
                speakerPreviewId = request.speakerPreviewId?.toPlainRequestBody(),
                processingConfirmed = request.processingConfirmed.toString().toPlainRequestBody(),
            )
        } catch (e: IOException) {
            logger.w(tag, "network error id=${redact(request.rawEventId)} attempt=$runAttemptCount: ${e.message}")
            trackExtraction(
                eventName = ProductAnalyticsEvents.EXTRACTION_FAILED,
                request = request,
                result = "network_error",
                retryable = true,
            )
            return handleTransientFailure(request)
        }

        return handleCommitmentExtractionResponse(response, request)
    }

    private suspend fun uploadWithPreparedStorage(
        request: SourceExtractionUploadRequest,
        parts: SourceExtractionRequestParts,
        media: DirectUploadMedia,
    ): ListenableWorker.Result {
        val contentType = media.part.body.contentType()?.toString() ?: defaultMediaContentType(media.kind)
        val contentLength = runCatching {
            media.part.body.contentLength()
        }.getOrDefault(-1L).takeIf { it > 0L }

        val prepareResponse = try {
            sourceExtractionApi.prepareCommitmentExtractionUpload(
                ExtractionUploadPrepareRequest(
                    inputModality = request.inputModality,
                    sourceType = request.entity.sourceType,
                    rawEventId = request.rawEventId,
                    contentType = contentType,
                    contentLength = contentLength,
                ),
            )
        } catch (e: IOException) {
            logger.w(
                tag,
                "direct upload prepare network error id=${redact(request.rawEventId)} " +
                    "attempt=$runAttemptCount: ${e.message}",
            )
            trackExtraction(
                eventName = ProductAnalyticsEvents.EXTRACTION_FAILED,
                request = request,
                result = "direct_upload_prepare_network_error",
                retryable = true,
            )
            return handleTransientFailure(request)
        }
        if (prepareResponse.code() != 200) {
            return handleExtractionHttpFailure(
                httpStatus = prepareResponse.code(),
                retryAfterSeconds = prepareResponse.headers()[HEADER_RETRY_AFTER]?.toLongOrNull(),
                errorBodyString = prepareResponse.errorBody()?.string(),
                request = request,
            )
        }
        val prepare = prepareResponse.body() ?: return handleTransientFailure(request)
        val uploadResponse = try {
            sourceExtractionApi.uploadExtractionMediaToSignedUrl(
                signedUploadUrl = prepare.signedUploadUrl,
                file = media.part.toSignedUploadPart(prepare.uploadContentType),
            )
        } catch (e: IOException) {
            logger.w(
                tag,
                "signed media upload network error id=${redact(request.rawEventId)} " +
                    "attempt=$runAttemptCount: ${e.message}",
            )
            trackExtraction(
                eventName = ProductAnalyticsEvents.EXTRACTION_FAILED,
                request = request,
                result = "signed_upload_network_error",
                retryable = true,
            )
            return handleTransientFailure(request)
        }
        uploadResponse.body()?.close()
        if (!uploadResponse.isSuccessful) {
            uploadResponse.errorBody()?.close()
            logger.w(
                tag,
                "signed media upload HTTP ${uploadResponse.code()} id=${redact(request.rawEventId)}",
            )
            if (uploadResponse.code() == 413) {
                processingStatusRepository.recordError(request.entity.sourceType, request.nonRetryableErrorMessage)
                request.onMarkFailed("signed_upload_too_large")
                trackExtraction(
                    eventName = ProductAnalyticsEvents.EXTRACTION_FAILED,
                    request = request,
                    result = "signed_upload_too_large",
                    retryable = false,
                )
                return ListenableWorker.Result.success()
            }
            trackExtraction(
                eventName = ProductAnalyticsEvents.EXTRACTION_FAILED,
                request = request,
                result = "signed_upload_http_${uploadResponse.code()}",
                retryable = true,
            )
            return handleTransientFailure(request)
        }

        val jobResponse = try {
            sourceExtractionApi.createCommitmentExtractionJob(
                CommitmentExtractionJobCreateRequest(
                    inputModality = request.inputModality,
                    sourceType = request.entity.sourceType,
                    clientEventId = request.entity.clientEventId,
                    rawEventId = request.rawEventId,
                    timestamp = request.entity.timestamp.toString(),
                    storageRef = prepare.storageRef,
                    durationSeconds = request.entity.durationSeconds
                        ?: if (request.inputModality == MEDIA_KIND_AUDIO) 0 else null,
                    counterpartyRef = request.entity.counterpartyRef,
                    eventTitle = request.entity.eventTitle,
                    folder = request.entity.folder,
                    conversationRef = request.entity.conversationRef,
                    previousThreadContext = null,
                    selfSpeakerId = request.selfSpeakerId,
                    processingConfirmed = request.processingConfirmed,
                ),
            )
        } catch (e: IOException) {
            logger.w(
                tag,
                "direct extraction job create network error id=${redact(request.rawEventId)} " +
                    "attempt=$runAttemptCount: ${e.message}",
            )
            trackExtraction(
                eventName = ProductAnalyticsEvents.EXTRACTION_FAILED,
                request = request,
                result = "direct_job_create_network_error",
                retryable = true,
            )
            return handleTransientFailure(request)
        }
        return handleCommitmentExtractionResponse(jobResponse, request)
    }

    private suspend fun handleCommitmentExtractionResponse(
        response: Response<SourceExtractionResponse>,
        request: SourceExtractionUploadRequest,
    ): ListenableWorker.Result {
        return when (response.code()) {
            200 -> persistSuccess(response.body() ?: return handleTransientFailure(request), request)
            202 -> handleJobAccepted(response.body(), request)
            else -> handleExtractionHttpFailure(
                httpStatus = response.code(),
                retryAfterSeconds = response.headers()[HEADER_RETRY_AFTER]?.toLongOrNull(),
                errorBodyString = response.errorBody()?.string(),
                request = request,
            )
        }
    }

    suspend fun pollJob(
        request: SourceExtractionUploadRequest,
        jobId: String,
    ): ListenableWorker.Result {
        val normalizedJobId = jobId.trim()
        if (normalizedJobId.isEmpty()) {
            return handleTransientFailure(request)
        }
        trackExtraction(
            eventName = ProductAnalyticsEvents.EXTRACTION_STARTED,
            request = request,
            result = "job_poll",
        )
        val response = try {
            sourceExtractionApi.commitmentExtractionJob(normalizedJobId)
        } catch (e: IOException) {
            logger.w(
                tag,
                "job poll network error id=${redact(request.rawEventId)} job=${redact(normalizedJobId)} " +
                    "attempt=$runAttemptCount: ${e.message}",
            )
            trackExtraction(
                eventName = ProductAnalyticsEvents.EXTRACTION_FAILED,
                request = request,
                result = "job_poll_network_error",
                retryable = true,
            )
            return handleTransientFailure(request)
        }

        return when (response.code()) {
            200 -> handleJobBody(response.body() ?: return handleTransientFailure(request), request, normalizedJobId)
            401 -> {
                logger.w(tag, "job poll HTTP 401 id=${redact(request.rawEventId)} — marking failed")
                processingStatusRepository.recordError(request.entity.sourceType, "Unauthorized")
                request.onMarkFailed("unauthorized")
                trackExtraction(
                    eventName = ProductAnalyticsEvents.EXTRACTION_FAILED,
                    request = request,
                    result = "job_poll_unauthorized",
                    retryable = false,
                )
                ListenableWorker.Result.success()
            }
            404 -> {
                logger.w(
                    tag,
                    "job poll not found id=${redact(request.rawEventId)} job=${redact(normalizedJobId)} — re-uploading",
                )
                trackExtraction(
                    eventName = ProductAnalyticsEvents.EXTRACTION_FAILED,
                    request = request,
                    result = "job_not_found",
                    retryable = true,
                )
                request.onJobRetryableFailure?.invoke(null) ?: handleTransientFailure(request)
            }
            429 -> {
                trackExtraction(
                    eventName = ProductAnalyticsEvents.EXTRACTION_FAILED,
                    request = request,
                    result = "job_poll_rate_limited",
                    retryable = true,
                )
                request.onJobAccepted?.invoke(
                    normalizedJobId,
                    response.headers()[HEADER_RETRY_AFTER]?.toLongOrNull(),
                ) ?: handleTransientFailure(request)
            }
            500, 503 -> {
                logger.w(
                    tag,
                    "job poll HTTP ${response.code()} transient id=${redact(request.rawEventId)} " +
                        "job=${redact(normalizedJobId)} attempt=$runAttemptCount",
                )
                trackExtraction(
                    eventName = ProductAnalyticsEvents.EXTRACTION_FAILED,
                    request = request,
                    result = "job_poll_transient_http_${response.code()}",
                    retryable = true,
                )
                request.onJobAccepted?.invoke(normalizedJobId, null) ?: handleTransientFailure(request)
            }
            else -> {
                logger.w(
                    tag,
                    "job poll HTTP ${response.code()} unexpected id=${redact(request.rawEventId)} — marking failed",
                )
                processingStatusRepository.recordError(request.entity.sourceType, "Unexpected HTTP ${response.code()}")
                request.onMarkFailed("job_poll_unexpected_http_${response.code()}")
                trackExtraction(
                    eventName = ProductAnalyticsEvents.EXTRACTION_FAILED,
                    request = request,
                    result = "job_poll_unexpected_http_${response.code()}",
                    retryable = false,
                )
                ListenableWorker.Result.success()
            }
        }
    }

    private suspend fun persistSuccess(
        body: SourceExtractionResponse,
        request: SourceExtractionUploadRequest,
    ): ListenableWorker.Result {
        extractionPersister.persist(
            userId = request.userId,
            entity = request.entity,
            body = body,
            now = Clock.System.now(),
        )
        logger.d(tag, "upload success id=${redact(request.rawEventId)} items=${body.items.size}")
        trackExtraction(
            eventName = ProductAnalyticsEvents.EXTRACTION_COMPLETED,
            request = request,
            result = "success",
            itemCount = body.items.size,
            participantCount = body.sourceEventParticipants.size,
        )
        return ListenableWorker.Result.success()
    }

    private suspend fun handleJobAccepted(
        body: SourceExtractionResponse?,
        request: SourceExtractionUploadRequest,
    ): ListenableWorker.Result {
        val jobId = body?.jobId?.trim().orEmpty()
        if (jobId.isEmpty()) {
            logger.w(tag, "async job accepted without job id=${redact(request.rawEventId)}")
            return handleTransientFailure(request)
        }
        processingStatusRepository.recordGemini(request.entity.sourceType, "내용 정리 중")
        trackExtraction(
            eventName = ProductAnalyticsEvents.EXTRACTION_STARTED,
            request = request,
            result = "async_job_accepted",
        )
        return request.onJobAccepted?.invoke(jobId, body?.retryAfterSeconds)
            ?: handleTransientFailure(request)
    }

    private suspend fun handleJobBody(
        body: SourceExtractionResponse,
        request: SourceExtractionUploadRequest,
        fallbackJobId: String,
    ): ListenableWorker.Result {
        return when (body.status?.trim()?.lowercase()) {
            null, "", JOB_STATUS_SUCCEEDED -> persistSuccess(body, request)
            JOB_STATUS_PENDING, JOB_STATUS_PROCESSING -> {
                val jobId = body.jobId?.trim().orEmpty().ifEmpty { fallbackJobId }
                processingStatusRepository.recordGemini(request.entity.sourceType, "내용 정리 중")
                trackExtraction(
                    eventName = ProductAnalyticsEvents.EXTRACTION_STARTED,
                    request = request,
                    result = "async_job_${body.status}",
                )
                request.onJobAccepted?.invoke(jobId, body.retryAfterSeconds)
                    ?: handleTransientFailure(request)
            }
            JOB_STATUS_FAILED -> {
                val errorCode = body.error ?: "extraction_job_failed"
                if (body.retryable == true) {
                    processingStatusRepository.recordGemini(request.entity.sourceType, "내용 정리 중")
                    trackExtraction(
                        eventName = ProductAnalyticsEvents.EXTRACTION_FAILED,
                        request = request,
                        result = errorCode,
                        retryable = true,
                    )
                    request.onJobRetryableFailure?.invoke(body.retryAfterSeconds)
                        ?: handleTransientFailure(request)
                } else {
                    processingStatusRepository.recordError(
                        request.entity.sourceType,
                        body.message ?: errorCode,
                    )
                    request.onMarkFailed(errorCode)
                    trackExtraction(
                        eventName = ProductAnalyticsEvents.EXTRACTION_FAILED,
                        request = request,
                        result = errorCode,
                        retryable = false,
                    )
                    ListenableWorker.Result.success()
                }
            }
            else -> {
                logger.w(
                    tag,
                    "unknown job status id=${redact(request.rawEventId)} status=${body.status}",
                )
                handleTransientFailure(request)
            }
        }
    }

    private suspend fun ensureSourceEventUploaded(
        request: SourceExtractionUploadRequest,
    ): ListenableWorker.Result? {
        if (request.entity.syncStatus != STATUS_PENDING) return null
        return when (val result = rawIngestionRepository.uploadBatch(listOf(request.entity))) {
            is BecalmResult.Success -> {
                val failure = result.value.failed.firstOrNull { it.clientEventId == request.entity.clientEventId }
                when {
                    failure == null -> {
                        rawIngestionRepository.markSynced(listOf(request.entity.id))
                        logger.d(tag, "source event pre-uploaded id=${redact(request.rawEventId)}")
                        null
                    }
                    failure.retryable -> {
                        logger.w(tag, "source event pre-upload retryable id=${redact(request.rawEventId)} reason=${failure.reason}")
                        handleTransientFailure(request)
                    }
                    else -> {
                        logger.w(tag, "source event pre-upload rejected id=${redact(request.rawEventId)} reason=${failure.reason}")
                        request.onMarkFailed(failure.reason)
                        trackExtraction(
                            eventName = ProductAnalyticsEvents.EXTRACTION_FAILED,
                            request = request,
                            result = "source_event_pre_upload_rejected",
                            retryable = false,
                        )
                        ListenableWorker.Result.success()
                    }
                }
            }
            is BecalmResult.Failure -> {
                logger.w(tag, "source event pre-upload failed id=${redact(request.rawEventId)}")
                handleTransientFailure(request)
            }
        }
    }

    private suspend fun handle502(
        errorBodyString: String?,
        request: SourceExtractionUploadRequest,
    ): ListenableWorker.Result {
        val envelope = parseExtractionErrorEnvelope(
            errorBodyString = errorBodyString,
            request = request,
            httpStatus = 502,
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
                trackExtraction(
                    eventName = ProductAnalyticsEvents.EXTRACTION_FAILED,
                    request = request,
                    result = errorCode ?: "vertex_502_unknown",
                    retryable = false,
                )
                ListenableWorker.Result.success()
            }
            SourceExtraction502Action.HandleAsTransient -> {
                trackExtraction(
                    eventName = ProductAnalyticsEvents.EXTRACTION_FAILED,
                    request = request,
                    result = errorCode ?: "transient_502",
                    retryable = true,
                )
                handleTransientFailure(request)
            }
        }
    }

    private suspend fun handleExtractionHttpFailure(
        httpStatus: Int,
        retryAfterSeconds: Long?,
        errorBodyString: String?,
        request: SourceExtractionUploadRequest,
    ): ListenableWorker.Result {
        return when (httpStatus) {
            401 -> {
                logger.w(tag, "HTTP 401 after refresh id=${redact(request.rawEventId)} — marking failed")
                processingStatusRepository.recordError(request.entity.sourceType, "Unauthorized")
                request.onMarkFailed("unauthorized")
                trackExtraction(
                    eventName = ProductAnalyticsEvents.EXTRACTION_FAILED,
                    request = request,
                    result = "unauthorized",
                    retryable = false,
                )
                ListenableWorker.Result.success()
            }
            403, 413, 422, 428 -> {
                logger.w(tag, "HTTP $httpStatus non-retryable id=${redact(request.rawEventId)} — quarantining")
                processingStatusRepository.recordError(request.entity.sourceType, request.nonRetryableErrorMessage)
                request.onMarkFailed("non_retryable_http_$httpStatus")
                trackExtraction(
                    eventName = ProductAnalyticsEvents.EXTRACTION_FAILED,
                    request = request,
                    result = "non_retryable_http_$httpStatus",
                    retryable = false,
                )
                ListenableWorker.Result.success()
            }
            502 -> handle502(errorBodyString, request)
            429 -> {
                val envelope = parseExtractionErrorEnvelope(
                    errorBodyString = errorBodyString,
                    request = request,
                    httpStatus = 429,
                )
                val errorCode = envelope?.error
                if (errorCode == ProcessingStatusMessages.LLM_DAILY_BUDGET_EXCEEDED) {
                    processingStatusRepository.recordBlocked(
                        request.entity.sourceType,
                        ProcessingStatusMessages.LLM_DAILY_BUDGET_EXCEEDED,
                    )
                } else {
                    processingStatusRepository.recordGemini(
                        request.entity.sourceType,
                        ProcessingStatusMessages.LLM_RATE_LIMITED_RETRYING,
                    )
                }
                trackExtraction(
                    eventName = ProductAnalyticsEvents.EXTRACTION_FAILED,
                    request = request,
                    result = errorCode ?: "rate_limited",
                    retryable = true,
                )
                request.onRateLimited?.invoke(retryAfterSeconds)
                    ?: handleTransientFailure(request)
            }
            500, 503 -> {
                val envelope = parseExtractionErrorEnvelope(
                    errorBodyString = errorBodyString,
                    request = request,
                    httpStatus = httpStatus,
                )
                if (
                    httpStatus == 503 &&
                    request.onRestartSpeakerPreview != null &&
                    envelope.isSpeakerPreviewUnavailable()
                ) {
                    logger.w(
                        tag,
                        "speaker preview unavailable id=${redact(request.rawEventId)} — restarting preview",
                    )
                    trackExtraction(
                        eventName = ProductAnalyticsEvents.EXTRACTION_FAILED,
                        request = request,
                        result = SourceExtractionErrorEnvelope.SPEAKER_PREVIEW_UNAVAILABLE,
                        retryable = true,
                    )
                    return request.onRestartSpeakerPreview.invoke()
                }
                logger.w(tag, "HTTP $httpStatus transient id=${redact(request.rawEventId)} attempt=$runAttemptCount")
                trackExtraction(
                    eventName = ProductAnalyticsEvents.EXTRACTION_FAILED,
                    request = request,
                    result = envelope?.error ?: "transient_http_$httpStatus",
                    retryable = true,
                )
                handleTransientFailure(request)
            }
            else -> {
                logger.w(tag, "HTTP $httpStatus unexpected id=${redact(request.rawEventId)} — marking failed")
                processingStatusRepository.recordError(request.entity.sourceType, "Unexpected HTTP $httpStatus")
                request.onMarkFailed("unexpected_http_$httpStatus")
                trackExtraction(
                    eventName = ProductAnalyticsEvents.EXTRACTION_FAILED,
                    request = request,
                    result = "unexpected_http_$httpStatus",
                    retryable = false,
                )
                ListenableWorker.Result.success()
            }
        }
    }

    private fun parseExtractionErrorEnvelope(
        errorBodyString: String?,
        request: SourceExtractionUploadRequest,
        httpStatus: Int,
    ): SourceExtractionErrorEnvelope? =
        runCatchingNonCancel(
            logger = logger,
            tag = tag,
            op = "HTTP $httpStatus parse failed id=${redact(request.rawEventId)}",
            block = {
                errorBodyString?.let {
                    moshi.adapter(SourceExtractionErrorEnvelope::class.java).fromJson(it)
                }
            },
            onFailure = { null },
        )

    private suspend fun handleTransientFailure(
        request: SourceExtractionUploadRequest,
    ): ListenableWorker.Result {
        return when (SourceExtractionUploadStateMachine.decideRetryAction(runAttemptCount, maxAttempts)) {
            RetryAction.Quarantine -> {
                logger.w(tag, "exhausted retries id=${redact(request.entity.id)} — marking failed")
                request.onMarkFailed("retry_exhausted")
                trackExtraction(
                    eventName = ProductAnalyticsEvents.EXTRACTION_FAILED,
                    request = request,
                    result = "retry_exhausted",
                    retryable = false,
                )
                ListenableWorker.Result.success()
            }
            RetryAction.Retry -> ListenableWorker.Result.retry()
        }
    }

    private fun trackExtraction(
        eventName: String,
        request: SourceExtractionUploadRequest,
        result: String,
        retryable: Boolean? = null,
        itemCount: Int? = null,
        participantCount: Int? = null,
    ) {
        val properties = buildMap<String, Any> {
            put("source_type", request.entity.sourceType)
            put("input_modality", request.inputModality)
            put("result", result)
            retryable?.let { put("retryable", it) }
            itemCount?.let { put("item_count", it) }
            participantCount?.let { put("participant_count", it) }
            put("attempt", runAttemptCount)
        }
        productAnalytics.track(
            ProductAnalyticsEvent(
                eventId = UUID.randomUUID().toString(),
                eventName = eventName,
                occurredAt = Clock.System.now(),
                properties = properties,
            ),
        )
    }

    private companion object {
        private const val HEADER_RETRY_AFTER: String = "Retry-After"
        private const val STATUS_PENDING: String = "pending"
        private const val MEDIA_KIND_AUDIO: String = "audio"
        private const val JOB_STATUS_PENDING: String = "pending"
        private const val JOB_STATUS_PROCESSING: String = "processing"
        private const val JOB_STATUS_SUCCEEDED: String = "succeeded"
        private const val JOB_STATUS_FAILED: String = "failed"
    }
}

private fun SourceExtractionErrorEnvelope?.isSpeakerPreviewUnavailable(): Boolean =
    this != null &&
        (
            error == SourceExtractionErrorEnvelope.SPEAKER_PREVIEW_UNAVAILABLE ||
                clientAction == SourceExtractionErrorEnvelope.RESTART_MEETING_SPEAKER_PREVIEW
            )

private data class DirectUploadMedia(
    val kind: String,
    val part: MultipartBody.Part,
)

private fun directUploadMedia(request: SourceExtractionUploadRequest): DirectUploadMedia? {
    if (!request.speakerPreviewId.isNullOrBlank() || !request.speakerMappingsJson.isNullOrBlank()) {
        return null
    }
    request.imagePart?.let { return DirectUploadMedia("image", it) }
    request.audioPart?.let { return DirectUploadMedia("audio", it) }
    return null
}

private fun MultipartBody.Part.toSignedUploadPart(uploadContentType: String): MultipartBody.Part =
    MultipartBody.Part.createFormData(
        "file",
        fileName() ?: "extraction-media",
        UploadContentTypeRequestBody(body, uploadContentType),
    )

private fun MultipartBody.Part.fileName(): String? =
    headers
        ?.get("Content-Disposition")
        ?.let { DirectUploadFilenameRegex.find(it)?.groupValues?.getOrNull(1) }
        ?.takeIf { it.isNotBlank() }

private fun defaultMediaContentType(kind: String): String =
    if (kind == "audio") "audio/m4a" else "image/png"

private val DirectUploadFilenameRegex = Regex("""filename="([^"]+)"""")

private class UploadContentTypeRequestBody(
    private val delegate: RequestBody,
    private val uploadContentType: String,
) : RequestBody() {
    override fun contentType(): MediaType? =
        uploadContentType.toMediaTypeOrNull() ?: delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun isOneShot(): Boolean = true

    override fun writeTo(sink: BufferedSink) {
        delegate.writeTo(sink)
    }
}
