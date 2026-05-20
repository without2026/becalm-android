package com.becalm.android.worker

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.util.Logger
import com.becalm.android.core.util.redact
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.MeetingSpeakerPreviewDao
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.local.db.entity.MeetingSpeakerPreviewEntity
import com.becalm.android.data.local.db.entity.MeetingSpeakerPreviewStatus
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.api.SourceExtractionApi
import com.becalm.android.data.remote.dto.MeetingSpeakerPreviewDto
import com.becalm.android.data.remote.dto.MeetingTranscriptSegmentDto
import com.becalm.android.data.repository.MeetingTranscriptArchiveInput
import com.becalm.android.data.repository.SourceArtifactRepository
import com.becalm.android.data.repository.toPlainRequestBody
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Provider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.BufferedSink

@HiltWorker
public class MeetingSpeakerPreviewWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val rawIngestionEventDaoProvider: Provider<RawIngestionEventDao>,
    private val meetingSpeakerPreviewDaoProvider: Provider<MeetingSpeakerPreviewDao>,
    private val sourceExtractionApiProvider: Provider<SourceExtractionApi>,
    private val sourceArtifactRepositoryProvider: Provider<SourceArtifactRepository>,
    private val userPrefsStore: UserPrefsStore,
    private val moshi: Moshi,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : CoroutineWorker(appContext, workerParams) {

    private val rawIngestionEventDao: RawIngestionEventDao
        get() = rawIngestionEventDaoProvider.get()

    private val meetingSpeakerPreviewDao: MeetingSpeakerPreviewDao
        get() = meetingSpeakerPreviewDaoProvider.get()

    private val sourceExtractionApi: SourceExtractionApi
        get() = sourceExtractionApiProvider.get()

    private val sourceArtifactRepository: SourceArtifactRepository
        get() = sourceArtifactRepositoryProvider.get()

    override suspend fun doWork(): Result = withContext(ioDispatcher) {
        val rawEventId = inputData.getString(KEY_RAW_EVENT_ID)
        val audioUriString = inputData.getString(KEY_AUDIO_URI)
        if (rawEventId.isNullOrBlank() || audioUriString.isNullOrBlank()) {
            logger.e(TAG, "missing input rawEventId=${redact(rawEventId.orEmpty())} audioUri=${redact(audioUriString.orEmpty())}")
            return@withContext Result.failure()
        }

        val userId = userPrefsStore.observeCurrentUserId().first()
        if (userId.isNullOrBlank()) {
            logger.w(TAG, "no current user for preview id=${redact(rawEventId)}")
            return@withContext Result.retry()
        }
        val event = rawIngestionEventDao.findById(rawEventId, userId)
            ?: return@withContext Result.failure()
        ensurePreviewRow(event, audioUriString)

        if (!userPrefsStore.observeThirdPartyProvisionConsent().first()) {
            val now = Clock.System.now()
            rawIngestionEventDao.updateSyncStatus(
                id = rawEventId,
                status = STATUS_AWAITING_CONSENT,
                now = now,
                lastError = null,
            )
            meetingSpeakerPreviewDao.markStatus(
                rawEventId = rawEventId,
                status = MeetingSpeakerPreviewStatus.PENDING,
                lastError = null,
                updatedAt = now,
            )
            return@withContext Result.success()
        }

        val audioUri = Uri.parse(audioUriString)
        val durationSeconds = event.durationSeconds?.takeIf { it > 0 }
            ?: readAudioDurationSeconds(audioUri)
        if (durationSeconds == null) {
            logger.w(TAG, "preview missing audio duration id=${redact(rawEventId)}")
            markFailed(rawEventId, "audio_duration_unavailable")
            return@withContext Result.success()
        }

        val audioPart = buildAudioPart(audioUri)
            ?: run {
                markFailed(rawEventId, "audio_unavailable")
                return@withContext Result.success()
            }

        val response = try {
            sourceExtractionApi.meetingSpeakerPreview(
                audio = audioPart,
                rawEventId = rawEventId.toPlainRequestBody(),
                durationSeconds = durationSeconds.toString().toPlainRequestBody(),
                sourceType = event.sourceType.toPlainRequestBody(),
            )
        } catch (e: IOException) {
            logger.w(TAG, "preview network error id=${redact(rawEventId)} attempt=$runAttemptCount: ${e.message}")
            return@withContext retryOrFail(rawEventId, "network_error")
        }

        val now = Clock.System.now()
        when (response.code()) {
            200 -> {
                val body = response.body()
                    ?: return@withContext retryOrFail(rawEventId, "empty_preview_response")
                meetingSpeakerPreviewDao.markPreviewReady(
                    rawEventId = rawEventId,
                    status = MeetingSpeakerPreviewStatus.REVIEW_REQUIRED,
                    speakerPreviewId = body.speakerPreviewId,
                    speakersJson = speakerAdapter.toJson(body.speakers),
                    transcriptSegmentsJson = transcriptAdapter.toJson(body.transcriptSegments),
                    billableSeconds = body.billableSeconds,
                    expiresAt = kotlinx.datetime.Instant.fromEpochMilliseconds(
                        now.toEpochMilliseconds() + PREVIEW_TTL_MILLIS,
                    ),
                    updatedAt = now,
                )
                sourceArtifactRepository.archiveMeetingTranscript(
                    MeetingTranscriptArchiveInput(
                        userId = event.userId,
                        rawEventId = event.id,
                        sourceType = event.sourceType,
                        sourceRef = event.sourceRef,
                        occurredAt = event.timestamp,
                        title = event.eventTitle,
                        segments = body.transcriptSegments,
                    ),
                )
                rawIngestionEventDao.updateSyncStatus(
                    id = rawEventId,
                    status = MeetingSpeakerPreviewStatus.REVIEW_REQUIRED,
                    now = now,
                    lastError = null,
                )
                Result.success()
            }
            401, 403, 413, 422 -> {
                logger.w(TAG, "preview non-retryable HTTP ${response.code()} id=${redact(rawEventId)}")
                markFailed(rawEventId, "preview_http_${response.code()}")
                Result.success()
            }
            429, 500, 502, 503 -> {
                logger.w(TAG, "preview transient HTTP ${response.code()} id=${redact(rawEventId)} attempt=$runAttemptCount")
                retryOrFail(rawEventId, "preview_http_${response.code()}")
            }
            else -> {
                logger.w(TAG, "preview unexpected HTTP ${response.code()} id=${redact(rawEventId)}")
                markFailed(rawEventId, "preview_unexpected_http_${response.code()}")
                Result.success()
            }
        }
    }

    private suspend fun retryOrFail(rawEventId: String, reasonCode: String): Result =
        if (runAttemptCount >= MAX_ATTEMPTS - 1) {
            markFailed(rawEventId, reasonCode)
            Result.success()
        } else {
            Result.retry()
        }

    private suspend fun markFailed(rawEventId: String, reasonCode: String) {
        val now = Clock.System.now()
        rawIngestionEventDao.updateSyncStatus(
            id = rawEventId,
            status = MeetingSpeakerPreviewStatus.FAILED,
            now = now,
            lastError = reasonCode,
        )
        meetingSpeakerPreviewDao.markStatus(
            rawEventId = rawEventId,
            status = MeetingSpeakerPreviewStatus.FAILED,
            lastError = reasonCode,
            updatedAt = now,
        )
    }

    private suspend fun ensurePreviewRow(event: RawIngestionEventEntity, audioUriString: String) {
        if (meetingSpeakerPreviewDao.findByRawEventId(event.id) != null) return
        val now = Clock.System.now()
        meetingSpeakerPreviewDao.upsert(
            MeetingSpeakerPreviewEntity(
                id = UUID.nameUUIDFromBytes("meeting-preview:${event.id}".toByteArray(Charsets.UTF_8)).toString(),
                userId = event.userId,
                rawEventId = event.id,
                sourceRef = event.sourceRef ?: audioUriString,
                speakerPreviewId = null,
                speakersJson = "[]",
                transcriptSegmentsJson = null,
                billableSeconds = 0,
                status = MeetingSpeakerPreviewStatus.PENDING,
                selectedSelfSpeakerId = null,
                lastError = null,
                expiresAt = null,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private fun buildAudioPart(uri: Uri): MultipartBody.Part? {
        val mediaType = appContext.contentResolver.getType(uri) ?: "audio/m4a"
        runCatching {
            appContext.contentResolver.openInputStream(uri)?.close() ?: return null
        }.onFailure {
            logger.w(TAG, "preview cannot open audio uri=${redact(uri.toString())}: ${it.message}")
            return null
        }
        val body = object : RequestBody() {
            override fun contentType() = mediaType.toMediaTypeOrNull()

            override fun writeTo(sink: BufferedSink) {
                val stream = appContext.contentResolver.openInputStream(uri)
                    ?: throw IOException("Unable to open meeting audio")
                stream.use {
                    val buffer = ByteArray(STREAM_BUFFER_BYTES)
                    var bytesRead: Int
                    while (it.read(buffer).also { bytesRead = it } != -1) {
                        sink.write(buffer, 0, bytesRead)
                    }
                }
            }
        }
        return MultipartBody.Part.createFormData("audio", uri.lastPathSegment ?: "meeting-audio.m4a", body)
    }

    private fun readAudioDurationSeconds(uri: Uri): Int? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.let { durationMillis ->
                    if (durationMillis <= 0L) {
                        null
                    } else {
                        ((durationMillis + 999L) / 1_000L)
                            .takeIf { it in 1..Int.MAX_VALUE.toLong() }
                            ?.toInt()
                    }
                }
        } catch (_: RuntimeException) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: RuntimeException) {
                // Duration fallback is best effort.
            }
        }
    }

    private val speakerAdapter by lazy {
        moshi.adapter<List<MeetingSpeakerPreviewDto>>(
            Types.newParameterizedType(List::class.java, MeetingSpeakerPreviewDto::class.java),
        )
    }

    private val transcriptAdapter by lazy {
        moshi.adapter<List<MeetingTranscriptSegmentDto>>(
            Types.newParameterizedType(List::class.java, MeetingTranscriptSegmentDto::class.java),
        )
    }

    public companion object {
        public const val KEY_RAW_EVENT_ID: String = "raw_event_id"
        public const val KEY_AUDIO_URI: String = "audio_uri"
        private const val TAG = "MeetingSpeakerPreviewWorker"
        private const val MAX_ATTEMPTS = 3
        private const val STREAM_BUFFER_BYTES = 65536
        private const val STATUS_AWAITING_CONSENT = "awaiting_consent"
        private val PREVIEW_TTL_MILLIS: Long = TimeUnit.MINUTES.toMillis(15)
    }
}
