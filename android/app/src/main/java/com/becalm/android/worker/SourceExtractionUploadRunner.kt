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
import com.becalm.android.data.remote.dto.SourceExtractionErrorEnvelope
import com.becalm.android.data.repository.SourceExtractionInputAdapter
import com.becalm.android.data.repository.ProcessingStatusRepository
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.toPlainRequestBody
import com.squareup.moshi.Moshi
import java.io.IOException
import java.util.UUID
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
            trackExtraction(
                eventName = ProductAnalyticsEvents.EXTRACTION_FAILED,
                request = request,
                result = "network_error",
                retryable = true,
            )
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
                trackExtraction(
                    eventName = ProductAnalyticsEvents.EXTRACTION_COMPLETED,
                    request = request,
                    result = "success",
                    itemCount = body.items.size,
                    participantCount = body.sourceEventParticipants.size,
                )
                ListenableWorker.Result.success()
            }
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
            403, 413, 422 -> {
                logger.w(tag, "HTTP ${response.code()} non-retryable id=${redact(request.rawEventId)} — quarantining")
                processingStatusRepository.recordError(request.entity.sourceType, request.nonRetryableErrorMessage)
                request.onMarkFailed("non_retryable_http_${response.code()}")
                trackExtraction(
                    eventName = ProductAnalyticsEvents.EXTRACTION_FAILED,
                    request = request,
                    result = "non_retryable_http_${response.code()}",
                    retryable = false,
                )
                ListenableWorker.Result.success()
            }
            502 -> handle502(response.errorBody()?.string(), request)
            429 -> {
                trackExtraction(
                    eventName = ProductAnalyticsEvents.EXTRACTION_FAILED,
                    request = request,
                    result = "rate_limited",
                    retryable = true,
                )
                request.onRateLimited?.invoke(response.headers()[HEADER_RETRY_AFTER]?.toLongOrNull())
                    ?: handleTransientFailure(request)
            }
            500, 503 -> {
                logger.w(tag, "HTTP ${response.code()} transient id=${redact(request.rawEventId)} attempt=$runAttemptCount")
                trackExtraction(
                    eventName = ProductAnalyticsEvents.EXTRACTION_FAILED,
                    request = request,
                    result = "transient_http_${response.code()}",
                    retryable = true,
                )
                handleTransientFailure(request)
            }
            else -> {
                logger.w(tag, "HTTP ${response.code()} unexpected id=${redact(request.rawEventId)} — marking failed")
                processingStatusRepository.recordError(request.entity.sourceType, "Unexpected HTTP ${response.code()}")
                request.onMarkFailed("unexpected_http_${response.code()}")
                trackExtraction(
                    eventName = ProductAnalyticsEvents.EXTRACTION_FAILED,
                    request = request,
                    result = "unexpected_http_${response.code()}",
                    retryable = false,
                )
                ListenableWorker.Result.success()
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
    }
}
