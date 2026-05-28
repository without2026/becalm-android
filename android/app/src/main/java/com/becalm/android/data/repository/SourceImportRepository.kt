package com.becalm.android.data.repository

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.MeetingSpeakerPreviewDao
import com.becalm.android.data.local.db.entity.MeetingSpeakerPreviewStatus
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.api.SourceExtractionApi
import com.becalm.android.data.remote.dto.MeetingSpeakerPreviewDto
import com.becalm.android.data.remote.dto.MeetingTranscriptSegmentDto
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.domain.meeting.MeetingImportFilePolicy
import com.becalm.android.domain.meeting.MeetingSpeakerMappingsJson
import com.becalm.android.worker.WorkScheduler
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.BufferedSink

public data class SourceImportResult(
    val rawEventId: String,
    val savedUri: String,
)

public data class MeetingSpeakerPreviewResult(
    val rawEventId: String,
    val sourceRef: String? = null,
    val sourceType: String = SourceType.MEETING,
    val speakerPreviewId: String,
    val speakers: List<MeetingSpeakerPreviewDto>,
    val transcriptSegments: List<MeetingTranscriptSegmentDto> = emptyList(),
    val billableSeconds: Int,
    val expiresAt: Instant? = null,
)

@Singleton
public class SourceImportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPrefsStore: UserPrefsStore,
    private val rawIngestionRepository: RawIngestionRepository,
    private val meetingImportRepository: MeetingImportRepository,
    private val meetingSpeakerPreviewDao: MeetingSpeakerPreviewDao,
    private val sourceExtractionApi: SourceExtractionApi,
    private val workScheduler: WorkScheduler,
    private val moshi: Moshi,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    public fun observeLatestMeetingSpeakerReview(): Flow<MeetingSpeakerPreviewResult?> =
        userPrefsStore.observeCurrentUserId()
            .distinctUntilChanged()
            .flatMapLatest { userId ->
                if (userId.isNullOrBlank()) {
                    flowOf(null)
                } else {
                    meetingSpeakerPreviewDao.observeLatestReviewRequired(userId).map { row ->
                        row?.let {
                            val preview = it.preview
                            MeetingSpeakerPreviewResult(
                                rawEventId = preview.rawEventId,
                                sourceRef = preview.sourceRef,
                                sourceType = it.sourceType,
                                speakerPreviewId = preview.speakerPreviewId.orEmpty(),
                                speakers = speakerListAdapter.fromJson(preview.speakersJson).orEmpty(),
                                transcriptSegments = preview.transcriptSegmentsJson
                                    ?.let(transcriptSegmentListAdapter::fromJson)
                                    .orEmpty(),
                                billableSeconds = preview.billableSeconds,
                                expiresAt = preview.expiresAt,
                            )
                        }
                    }
                }
            }
            .flowOn(ioDispatcher)

    public suspend fun previewMeetingAudioSpeakers(uri: Uri): BecalmResult<MeetingSpeakerPreviewResult> =
        withContext(ioDispatcher) {
            try {
                val resolver = context.contentResolver
                val meta = resolver.readOpenableMeta(uri, fallbackName = "meeting-audio")
                val mimeType = resolver.getType(uri)
                if (!MeetingImportFilePolicy.isAllowedAudio(mimeType, meta.displayName)) {
                    return@withContext BecalmResult.Failure(
                        BecalmError.Validation("file", "unsupported meeting file format"),
                    )
                }
                val rawEventId = UUID.randomUUID().toString()
                val response = sourceExtractionApi.meetingSpeakerPreview(
                    audio = buildAudioPart(uri, meta.displayName, mimeType ?: "audio/m4a"),
                    rawEventId = rawEventId.toPlainRequestBody(),
                    durationSeconds = (readAudioDurationSeconds(uri) ?: 0).toString().toPlainRequestBody(),
                    sourceType = SourceType.MEETING.toPlainRequestBody(),
                    processingConfirmed = true.toString().toPlainRequestBody(),
                )
                if (!response.isSuccessful) {
                    return@withContext BecalmResult.Failure(BecalmError.Network(response.code(), "meeting speaker preview failed"))
                }
                val body = response.body()
                    ?: return@withContext BecalmResult.Failure(BecalmError.Unknown(IllegalStateException("empty preview response")))
                BecalmResult.Success(
                    MeetingSpeakerPreviewResult(
                        rawEventId = body.rawEventId,
                        sourceRef = null,
                        sourceType = SourceType.MEETING,
                        speakerPreviewId = body.speakerPreviewId,
                        speakers = body.speakers,
                        transcriptSegments = body.transcriptSegments,
                        billableSeconds = body.billableSeconds,
                    ),
                )
            } catch (e: IOException) {
                BecalmResult.Failure(BecalmError.Io(e::class.simpleName ?: "I/O error"))
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                BecalmResult.Failure(BecalmError.Unknown(t))
            }
        }

    public suspend fun importMeetingAudio(
        uri: Uri,
        speakerReviewContext: MeetingSpeakerReviewContext? = null,
    ): BecalmResult<MeetingImportResult> =
        meetingImportRepository.importAudio(uri, speakerReviewContext)

    public suspend fun stageMeetingAudioForSpeakerReview(uri: Uri): BecalmResult<MeetingImportResult> =
        meetingImportRepository.stageAudioForSpeakerPreview(uri)

    public suspend fun confirmMeetingSpeakerReview(
        rawEventId: String,
        speakerReviewContext: MeetingSpeakerReviewContext,
    ): BecalmResult<MeetingImportResult> =
        meetingImportRepository.confirmSpeakerReview(rawEventId, speakerReviewContext)

    public suspend fun retryFailedEvidenceImports(): BecalmResult<Int> =
        withContext(ioDispatcher) {
            try {
                val userId = userPrefsStore.observeCurrentUserId().first()
                    ?: return@withContext BecalmResult.Failure(BecalmError.Unauthorized)
                val failedRows = when (
                    val result = rawIngestionRepository.findFailedEvidenceImportsForRetry(userId)
                ) {
                    is BecalmResult.Success -> result.value
                    is BecalmResult.Failure -> return@withContext result
                }
                var enqueued = 0
                val now = Clock.System.now()
                for (event in failedRows) {
                    val sourceRef = event.sourceRef?.takeIf { it.isNotBlank() } ?: continue
                    when (
                        val reset = rawIngestionRepository.resetFailedEvidenceImportForRetry(
                            id = event.id,
                            userId = userId,
                            now = now,
                        )
                    ) {
                        is BecalmResult.Success -> Unit
                        is BecalmResult.Failure -> return@withContext reset
                    }
                    when (event.sourceType) {
                        SourceType.MESSAGE_SCREENSHOT -> {
                            workScheduler.enqueueMessageScreenshotUpload(event.id)
                            enqueued++
                        }
                        SourceType.MEETING -> {
                            enqueued += retryMeetingEvidenceImport(event, sourceRef, now)
                        }
                    }
                }
                if (enqueued == 0) {
                    BecalmResult.Failure(
                        BecalmError.Validation(
                            field = "failed_evidence",
                            message = "no retryable failed evidence imports",
                        ),
                    )
                } else {
                    BecalmResult.Success(enqueued)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                BecalmResult.Failure(BecalmError.Unknown(t))
            }
        }

    public suspend fun importMessageScreenshot(uri: Uri): BecalmResult<SourceImportResult> =
        withContext(ioDispatcher) {
            try {
                val resolver = context.contentResolver
                val meta = resolver.readOpenableMeta(uri, fallbackName = "message-screenshot")
                val mimeType = resolver.getType(uri)
                if (!isAllowedImage(mimeType, meta.displayName)) {
                    return@withContext BecalmResult.Failure(
                        BecalmError.Validation("file", "unsupported message screenshot format"),
                    )
                }
                if (meta.byteSize != null && meta.byteSize > MessageScreenshotImageNormalizer.MAX_SOURCE_BYTES) {
                    return@withContext BecalmResult.Failure(
                        BecalmError.Validation("file", "message screenshot exceeds 25 MiB"),
                    )
                }

                val userId = userPrefsStore.observeCurrentUserId().first()
                    ?: return@withContext BecalmResult.Failure(BecalmError.Unauthorized)
                val occurredAt = Clock.System.now()
                val savedFile = copyIntoMessageScreenshotFolder(
                    resolver = resolver,
                    sourceUri = uri,
                    displayName = meta.displayName,
                    occurredAtMillis = occurredAt.toEpochMilliseconds(),
                )
                val syncStatus = if (userPrefsStore.observeThirdPartyProvisionConsent().first()) {
                    STATUS_PENDING
                } else {
                    STATUS_AWAITING_CONSENT
                }
                val rawEvent = RawIngestionEventEntity(
                    id = UUID.randomUUID().toString(),
                    userId = userId,
                    clientEventId = deterministicClientEventId(savedFile.displayName),
                    sourceType = SourceType.MESSAGE_SCREENSHOT,
                    sourceRef = Uri.fromFile(savedFile.file).toString(),
                    eventTitle = ImportedEvidenceTitleFormatter.messageScreenshot(occurredAt),
                    eventSnippet = ImportedEvidenceTitleFormatter.originalFileSnippet(meta.displayName),
                    timestamp = occurredAt,
                    syncStatus = syncStatus,
                )
                when (val inserted = rawIngestionRepository.insertLocal(rawEvent)) {
                    is BecalmResult.Failure -> return@withContext inserted
                    is BecalmResult.Success -> Unit
                }
                if (syncStatus == STATUS_PENDING) {
                    workScheduler.enqueueMessageScreenshotUpload(rawEvent.id)
                }
                BecalmResult.Success(
                    SourceImportResult(
                        rawEventId = rawEvent.id,
                        savedUri = Uri.fromFile(savedFile.file).toString(),
                    ),
                )
            } catch (e: MessageScreenshotImportValidationException) {
                BecalmResult.Failure(BecalmError.Validation(e.field, e.validationMessage))
            } catch (e: IOException) {
                BecalmResult.Failure(BecalmError.Io(e::class.simpleName ?: "I/O error"))
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                BecalmResult.Failure(BecalmError.Unknown(t))
            }
        }

    private suspend fun retryMeetingEvidenceImport(
        event: RawIngestionEventEntity,
        sourceRef: String,
        now: Instant,
    ): Int {
        val preview = meetingSpeakerPreviewDao.findByRawEventId(event.id)
        val speakerPreviewId = preview?.speakerPreviewId?.takeIf { it.isNotBlank() }
        val selectedSelfSpeakerId = preview?.selectedSelfSpeakerId?.takeIf { it.isNotBlank() }
        val speakers = preview?.speakersJson
            ?.let { speakerListAdapter.fromJson(it) }
            .orEmpty()
        if (speakerPreviewId != null && selectedSelfSpeakerId != null && speakers.isNotEmpty()) {
            meetingSpeakerPreviewDao.markStatus(
                rawEventId = event.id,
                status = MeetingSpeakerPreviewStatus.EXTRACT_PENDING,
                lastError = null,
                updatedAt = now,
            )
            workScheduler.enqueueVoiceUpload(
                rawEventId = event.id,
                audioUri = sourceRef,
                selfSpeakerId = selectedSelfSpeakerId,
                speakerMappingsJson = MeetingSpeakerMappingsJson.encodeMeetingSelf(
                    speakers = speakers,
                    selfSpeakerId = selectedSelfSpeakerId,
                ),
                speakerPreviewId = speakerPreviewId,
            )
        } else {
            meetingSpeakerPreviewDao.markStatus(
                rawEventId = event.id,
                status = MeetingSpeakerPreviewStatus.PENDING,
                lastError = null,
                updatedAt = now,
            )
            workScheduler.enqueueMeetingSpeakerPreview(rawEventId = event.id, audioUri = sourceRef)
        }
        return 1
    }

    private fun copyIntoMessageScreenshotFolder(
        resolver: ContentResolver,
        sourceUri: Uri,
        displayName: String,
        occurredAtMillis: Long,
    ): SavedLocalFile {
        val targetDir = File(context.filesDir, MESSAGE_SCREENSHOT_DIR).apply {
            if (!exists() && !mkdirs()) throw IOException("Unable to create screenshot import folder")
        }
        val targetName = normalizedTargetName(
            occurredAtMillis = occurredAtMillis,
            displayName = displayName,
        )
        val target = File(targetDir, targetName)
        try {
            resolver.openInputStream(sourceUri).use { input ->
                if (input == null) throw IOException("Unable to open screenshot import stream")
                MessageScreenshotImageNormalizer.normalize(input, target)
            }
        } catch (t: Throwable) {
            target.delete()
            throw t
        }
        return SavedLocalFile(file = target, displayName = targetName)
    }

    private fun buildAudioPart(uri: Uri, fileName: String, mimeType: String): MultipartBody.Part {
        val streamingBody = object : RequestBody() {
            override fun contentType() = mimeType.toMediaTypeOrNull()

            override fun writeTo(sink: BufferedSink) {
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IOException("Unable to open meeting audio")
                input.use { stream ->
                    val buffer = ByteArray(STREAM_BUFFER_BYTES)
                    var bytesRead: Int
                    while (stream.read(buffer).also { bytesRead = it } != -1) {
                        sink.write(buffer, 0, bytesRead)
                    }
                }
            }
        }
        return MultipartBody.Part.createFormData("audio", fileName, streamingBody)
    }

    private fun readAudioDurationSeconds(uri: Uri): Int? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.let { ((it + 999L) / 1000L).toInt() }
        } catch (_: RuntimeException) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun ContentResolver.readOpenableMeta(uri: Uri, fallbackName: String): OpenableMeta {
        query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null).use { cursor ->
            if (cursor != null && cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else null
                return OpenableMeta(
                    displayName = sanitizeFileName(name?.takeIf { it.isNotBlank() } ?: fallbackName),
                    byteSize = size,
                )
            }
        }
        return OpenableMeta(displayName = uri.pathFileName() ?: fallbackName, byteSize = null)
    }

    private fun Uri.pathFileName(): String? =
        lastPathSegment
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
            ?.let(::sanitizeFileName)

    private fun isAllowedImage(mimeType: String?, displayName: String): Boolean {
        val normalizedMime = mimeType?.lowercase()
        val normalizedName = displayName.lowercase()
        return normalizedMime in IMAGE_MIME_TYPES ||
            IMAGE_EXTENSIONS.any { normalizedName.endsWith(it) }
    }

    private fun deterministicClientEventId(savedDisplayName: String): String =
        UUID.nameUUIDFromBytes("message_screenshot:$savedDisplayName".toByteArray(StandardCharsets.UTF_8)).toString()

    private fun normalizedTargetName(occurredAtMillis: Long, displayName: String): String {
        val sanitized = sanitizeFileName(displayName)
        val baseName = sanitized.substringBeforeLast('.', sanitized)
            .ifBlank { "message-screenshot" }
            .take(MAX_FILE_NAME_CHARS - MessageScreenshotImageNormalizer.OUTPUT_EXTENSION.length - 1)
        return "$occurredAtMillis-$baseName.${MessageScreenshotImageNormalizer.OUTPUT_EXTENSION}"
    }

    private fun sanitizeFileName(value: String): String =
        value.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim()
            .ifEmpty { "message-screenshot" }
            .take(MAX_FILE_NAME_CHARS)

    private data class OpenableMeta(
        val displayName: String,
        val byteSize: Long?,
    )

    private data class SavedLocalFile(
        val file: File,
        val displayName: String,
    )

    private val speakerListAdapter by lazy {
        moshi.adapter<List<MeetingSpeakerPreviewDto>>(
            Types.newParameterizedType(List::class.java, MeetingSpeakerPreviewDto::class.java),
        )
    }

    private val transcriptSegmentListAdapter by lazy {
        moshi.adapter<List<MeetingTranscriptSegmentDto>>(
            Types.newParameterizedType(List::class.java, MeetingTranscriptSegmentDto::class.java),
        )
    }

    private companion object {
        private const val STATUS_PENDING = "pending"
        private const val STATUS_AWAITING_CONSENT = "awaiting_consent"
        private const val MESSAGE_SCREENSHOT_DIR = "source_imports/message_screenshots"
        private const val MAX_FILE_NAME_CHARS = 96
        private const val STREAM_BUFFER_BYTES = 65536
        private val IMAGE_MIME_TYPES = setOf("image/png", "image/jpeg", "image/jpg", "image/webp")
        private val IMAGE_EXTENSIONS = listOf(".png", ".jpg", ".jpeg", ".webp")
    }
}
