package com.becalm.android.worker

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.CommitmentDao
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.api.VoiceApi
import com.becalm.android.data.remote.dto.CommitmentDraftDto
import com.becalm.android.data.remote.dto.VoiceErrorEnvelope
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.domain.commitment.CommitmentState
import com.becalm.android.domain.voice.Direction
import com.squareup.moshi.Moshi
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.IOException
import java.util.UUID

/**
 * [CoroutineWorker] that uploads a voice recording to Railway's
 * `POST /v1/voice/transcribe_extract` endpoint, receives extracted commitments from
 * Vertex AI Gemini 2.5 Flash, and persists them as [CommitmentEntity] rows in Room.
 *
 * ## Inputs ([androidx.work.Data])
 * - [KEY_RAW_EVENT_ID] — String UUID of the [RawIngestionEventEntity] to process.
 * - [KEY_AUDIO_URI]    — String content URI of the audio file (read-only SAF access).
 *
 * ## Lifecycle (VOI-001)
 * 1. Validate input keys; absent → [Result.failure].
 * 2. Verify READ_MEDIA_AUDIO permission (VOI-005); missing → [Result.failure] (onboarding gates enqueue).
 * 3. Load [RawIngestionEventEntity] by [KEY_RAW_EVENT_ID]; not found → [Result.failure].
 * 4. **PIPA gate — first check (VOI-004)**: if [UserPrefsStore.observeThirdPartyProvisionConsent]
 *    is false, mark entity `sync_status='awaiting_consent'` and return [Result.success] (not retry —
 *    consent toggle in Stream 2 will re-enqueue).
 * 5. Build multipart audio part by streaming bytes from [ContentResolver.openInputStream] —
 *    no temp-file copy on device (VOI-007).
 * 5a. **PIPA gate — second check (VOI-004)**: immediately before the network call, re-check consent.
 *    If consent was withdrawn after step 4 passed (in-flight race), park entity as
 *    `awaiting_consent` and return [Result.success].
 * 6. Call [VoiceApi.transcribeExtract]. On success: insert [CommitmentEntity] rows for each
 *    extracted commitment (deterministic IDs keyed on rawEventId+index), update entity counts and
 *    snippet, record sync success.
 * 7. Error handling per VOI-006:
 *    - HTTP 401 (after AuthInterceptor refresh): mark "failed", [Result.success].
 *    - HTTP 403/413/422: retryable=false, mark "failed", [Result.success].
 *    - HTTP 502: parse error envelope. `output_truncated`/`schema_violation` → mark "failed"
 *      (non-retryable). `vertex_upstream_error` or parse failure → [handleFailure] (transient retry).
 *    - HTTP 429/500/503 or IOException: quarantine when the current execution is already the
 *      [MAX_ATTEMPTS]th (runAttemptCount ≥ [MAX_ATTEMPTS] - 1) — mark "failed", [Result.success];
 *      else increment retry metadata and return [Result.retry].
 *
 * ## Privacy (VOI-007)
 * Audio bytes flow: ContentResolver → OkHttp RequestBody stream → Railway.
 * No copy is written to `filesDir` or `cacheDir`. Original file is never deleted or moved.
 *
 * Spec refs: VOI-001, VOI-004, VOI-005, VOI-006, VOI-007.
 */
@HiltWorker
public class VoiceUploadWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val rawIngestionEventDao: RawIngestionEventDao,
    private val commitmentDao: CommitmentDao,
    private val voiceApi: VoiceApi,
    private val userPrefsStore: UserPrefsStore,
    private val sourceStatusRepository: SourceStatusRepository,
    private val moshi: Moshi,
    private val logger: Logger,
) : CoroutineWorker(appContext, workerParams) {

    public override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val rawEventId = inputData.getString(KEY_RAW_EVENT_ID)
        val audioUriString = inputData.getString(KEY_AUDIO_URI)

        if (rawEventId.isNullOrBlank() || audioUriString.isNullOrBlank()) {
            logger.e(TAG, "missing input keys rawEventId=${redact(rawEventId ?: "")} audioUri=${redact(audioUriString ?: "")}")
            return@withContext Result.failure()
        }

        // VOI-005: permission gate — failure (not retry) because onboarding gates enqueue
        val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_AUDIO
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(
                appContext, audioPermission,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            logger.e(TAG, "audio permission not granted ($audioPermission) — failing (not retrying; onboarding gate violated)")
            return@withContext Result.failure()
        }

        // Step 3: load entity
        val entity = rawIngestionEventDao.findById(rawEventId)
        if (entity == null) {
            logger.e(TAG, "RawIngestionEventEntity not found id=${redact(rawEventId)}")
            return@withContext Result.failure()
        }

        // VOI-004: PIPA consent gate
        val hasConsent = userPrefsStore.observeThirdPartyProvisionConsent().first()
        if (!hasConsent) {
            logger.w(TAG, "PIPA consent not granted — parking id=${redact(rawEventId)} as awaiting_consent")
            val parked = entity.copy(syncStatus = "awaiting_consent")
            rawIngestionEventDao.update(parked)
            return@withContext Result.success()
        }

        val audioUri = Uri.parse(audioUriString)

        // VOI-007: stream audio bytes directly — no temp-file copy
        val audioPart = buildAudioPart(audioUri)
            ?: run {
                logger.e(TAG, "cannot open audio stream uri=${redact(audioUriString)}")
                return@withContext handleFailure(entity)
            }

        // VOI-004: second PIPA gate — consent may have been withdrawn while this job was queued
        // or while buildAudioPart was running. Check immediately before the network call so no
        // audio bytes are transmitted after consent is revoked (finding #1 in-flight race fix).
        val hasConsentPreCall = userPrefsStore.observeThirdPartyProvisionConsent().first()
        if (!hasConsentPreCall) {
            logger.w(TAG, "PIPA consent withdrawn before network call — parking id=${redact(rawEventId)} as awaiting_consent")
            val parked = entity.copy(syncStatus = "awaiting_consent")
            rawIngestionEventDao.update(parked)
            return@withContext Result.success()
        }

        val clientEventIdBody = entity.clientEventId.toPlainRequestBody()
        val rawEventIdBody = rawEventId.toPlainRequestBody()
        val durationBody = (entity.durationSeconds ?: 0).toString().toPlainRequestBody()
        val timestampBody = entity.timestamp.toString().toPlainRequestBody()
        val personRefBody = entity.personRef?.toPlainRequestBody()
        val eventTitleBody = entity.eventTitle?.toPlainRequestBody()

        val response = try {
            voiceApi.transcribeExtract(
                audio = audioPart,
                clientEventId = clientEventIdBody,
                rawEventId = rawEventIdBody,
                durationSeconds = durationBody,
                timestamp = timestampBody,
                personRef = personRefBody,
                eventTitle = eventTitleBody,
            )
        } catch (e: IOException) {
            logger.w(TAG, "network error id=${redact(rawEventId)} attempt=$runAttemptCount: ${e.message}")
            return@withContext handleFailure(entity)
        }

        when (response.code()) {
            200 -> {
                val body = response.body()
                if (body == null) {
                    logger.e(TAG, "empty 200 body id=${redact(rawEventId)}")
                    return@withContext handleFailure(entity)
                }

                val userId = entity.userId
                val now = Clock.System.now()

                // Insert commitments into Room.
                // IDs are deterministic (UUID v5 keyed on rawEventId+index) so that a
                // replayed successful response produces an upsert on the same PK rather than
                // a duplicate row (CommitmentDao uses OnConflictStrategy.REPLACE — finding #2 fix).
                val commitmentEntities = body.commitments.mapIndexed { index, dto ->
                    dto.toCommitmentEntity(
                        rawEventId = rawEventId,
                        index = index,
                        userId = userId,
                        sourceRef = entity.sourceRef,
                        sourceEventTitle = entity.eventTitle,
                        sourceEventOccurredAt = entity.timestamp,
                        now = now,
                    )
                }
                if (commitmentEntities.isNotEmpty()) {
                    commitmentDao.insertAll(commitmentEntities)
                }

                // Update raw event: count, snippet, keep sync_status='pending' for batch mirror
                val snippet = body.commitments.firstOrNull()?.quote?.take(SNIPPET_MAX_CHARS)
                val updated = entity.copy(
                    commitmentsExtractedCount = body.commitments.size,
                    eventSnippet = snippet,
                    syncStatus = "pending",
                )
                rawIngestionEventDao.update(updated)

                sourceStatusRepository.recordSyncSuccess(SOURCE_VOICE, now)
                logger.d(TAG, "upload success id=${redact(rawEventId)} commitments=${body.commitments.size}")
                Result.success()
            }

            401 -> {
                // AuthInterceptor already attempted refresh; second 401 means token is invalid
                logger.w(TAG, "HTTP 401 after refresh id=${redact(rawEventId)} — marking failed")
                markFailed(entity)
                Result.success()
            }

            403, 413, 422 -> {
                // Non-retryable: PIPA server-side guard failure, body too large, or duration exceeded
                logger.w(TAG, "HTTP ${response.code()} non-retryable id=${redact(rawEventId)} — quarantining")
                markFailed(entity)
                Result.success()
            }

            502 -> {
                // Parse the error envelope to distinguish deterministic vs transient failures.
                // api-contract.yml: output_truncated and schema_violation are non-retryable;
                // only vertex_upstream_error is retryable (finding #4 fix).
                handleVoice502(response.errorBody()?.string(), entity, rawEventId)
            }

            429, 500, 503 -> {
                logger.w(TAG, "HTTP ${response.code()} transient id=${redact(rawEventId)} attempt=$runAttemptCount")
                handleFailure(entity)
            }

            else -> {
                logger.w(TAG, "HTTP ${response.code()} unexpected id=${redact(rawEventId)} — marking failed")
                markFailed(entity)
                Result.success()
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Handles an HTTP 502 response by parsing the error envelope from [errorBodyString].
     *
     * Per api-contract.yml, three error codes are possible in the 502 envelope:
     * - [VoiceErrorEnvelope.OUTPUT_TRUNCATED] — non-retryable; quarantine immediately.
     * - [VoiceErrorEnvelope.SCHEMA_VIOLATION] — non-retryable; quarantine immediately.
     * - [VoiceErrorEnvelope.VERTEX_UPSTREAM_ERROR] — transient; delegate to [handleFailure].
     *
     * If the body cannot be parsed (malformed JSON, null body, or unknown error code), the
     * failure is treated as transient to avoid silently quarantining an event that might
     * succeed on retry.
     *
     * Finding #4 fix.
     *
     * @param errorBodyString Raw error body string from the 502 response, or null.
     * @param entity The [RawIngestionEventEntity] being processed.
     * @param rawEventId Redacted-safe event ID string for logging.
     */
    private suspend fun handleVoice502(
        errorBodyString: String?,
        entity: RawIngestionEventEntity,
        rawEventId: String,
    ): Result {
        val envelope = try {
            errorBodyString?.let {
                moshi.adapter(VoiceErrorEnvelope::class.java).fromJson(it)
            }
        } catch (e: Exception) {
            logger.w(TAG, "HTTP 502 — failed to parse error envelope id=${redact(rawEventId)}: ${e.message}")
            null
        }

        val errorCode = envelope?.error
        logger.w(TAG, "HTTP 502 id=${redact(rawEventId)} error=$errorCode attempt=$runAttemptCount")

        return when (errorCode) {
            VoiceErrorEnvelope.OUTPUT_TRUNCATED, VoiceErrorEnvelope.SCHEMA_VIOLATION -> {
                // Deterministic server-side failure — no point retrying.
                logger.w(TAG, "HTTP 502 non-retryable ($errorCode) id=${redact(rawEventId)} — quarantining")
                markFailed(entity)
                Result.success()
            }
            else -> {
                // vertex_upstream_error or unparseable — treat as transient.
                handleFailure(entity)
            }
        }
    }

    /**
     * Builds a [MultipartBody.Part] by opening a streaming [okhttp3.RequestBody] from the
     * audio file at [uri] via [ContentResolver]. No bytes are buffered to disk (VOI-007).
     *
     * Returns null when [ContentResolver.openInputStream] fails (e.g. URI revoked).
     */
    private fun buildAudioPart(uri: Uri): MultipartBody.Part? {
        val inputStream = try {
            appContext.contentResolver.openInputStream(uri) ?: return null
        } catch (e: Exception) {
            logger.w(TAG, "openInputStream failed uri=${redact(uri.toString())}: ${e.message}")
            return null
        }

        val streamingBody = object : RequestBody() {
            override fun contentType() = "audio/m4a".toMediaTypeOrNull()

            override fun writeTo(sink: BufferedSink) {
                inputStream.use { stream ->
                    val buffer = ByteArray(STREAM_BUFFER_BYTES)
                    var bytesRead: Int
                    while (stream.read(buffer).also { bytesRead = it } != -1) {
                        sink.write(buffer, 0, bytesRead)
                    }
                }
            }
        }

        return MultipartBody.Part.createFormData("audio", "audio.m4a", streamingBody)
    }

    /**
     * Handles a transient failure. WorkManager's [runAttemptCount] is zero-based: the current
     * execution is the Nth attempt where N = runAttemptCount + 1. Spec VOI-006 caps total
     * attempts at [MAX_ATTEMPTS] (3), so we quarantine when the current execution is already
     * the 3rd — i.e. runAttemptCount >= MAX_ATTEMPTS - 1 — otherwise one extra Vertex upload
     * would occur on bad recordings.
     */
    private suspend fun handleFailure(entity: RawIngestionEventEntity): Result {
        if (runAttemptCount >= MAX_ATTEMPTS - 1) {
            logger.w(TAG, "exhausted retries id=${redact(entity.id)} — marking failed")
            markFailed(entity)
            return Result.success()
        }
        // Record transient attempt: increment retry_count and stamp last_attempt_at without
        // changing sync_status so the row stays "pending" for the next WorkManager attempt.
        rawIngestionEventDao.markTransientFailure(id = entity.id, now = Clock.System.now())
        return Result.retry()
    }

    /**
     * Permanently quarantines the entity by setting [RawIngestionEventEntity.syncStatus] to
     * "failed" via the existing [RawIngestionEventDao.markFailed] query.
     *
     * Passes [retryIncrement]=0 because [retry_count] was already incremented on each prior
     * transient failure by [handleFailure]. We only need to stamp the final attempt time and
     * flip the status (VOI-006 finding #6 fix).
     */
    private suspend fun markFailed(entity: RawIngestionEventEntity) {
        rawIngestionEventDao.markFailed(id = entity.id, retryIncrement = 0, now = Clock.System.now())
    }

    // ── Companion ─────────────────────────────────────────────────────────────

    public companion object {
        private const val TAG = "VoiceUploadWorker"
        private const val SNIPPET_MAX_CHARS = 200
        private const val MAX_ATTEMPTS = 3
        private const val SOURCE_VOICE = "voice"

        /** Streaming buffer size — 64 KiB chunks per VOI-007. */
        private const val STREAM_BUFFER_BYTES = 65536

        /** WorkManager input [androidx.work.Data] key: UUID of the owning [RawIngestionEventEntity]. */
        public const val KEY_RAW_EVENT_ID: String = "raw_event_id"

        /** WorkManager input [androidx.work.Data] key: content URI string of the audio file. */
        public const val KEY_AUDIO_URI: String = "audio_uri"

        /**
         * Returns an 8-char hex surrogate for [s] to prevent PII (event IDs, audio URIs)
         * from appearing in logcat. Mirrors the pattern used in other workers.
         */
        private fun redact(s: String): String = "%08x".format(s.hashCode())
    }
}

// ── Mapper extension ──────────────────────────────────────────────────────────

/**
 * Maps a [CommitmentDraftDto] received from Railway to a [CommitmentEntity] ready for
 * Room insertion.
 *
 * The [CommitmentEntity.id] is a deterministic UUID v3 (name-based, MD5) keyed on
 * `"commitment:<rawEventId>:<index>"`. This ensures that re-processing the same audio
 * (e.g., a replayed 200 response) produces an upsert on the same primary key rather than
 * inserting a duplicate row — CommitmentDao uses [OnConflictStrategy.REPLACE], so duplicate
 * inserts become idempotent (finding #2 fix).
 *
 * @param rawEventId UUID of the originating [RawIngestionEventEntity] (used for deterministic ID).
 * @param index 0-based position of this commitment in the response list (used for deterministic ID).
 * @param userId Supabase auth.users UUID of the owning user.
 * @param sourceRef Source reference from the originating [RawIngestionEventEntity].
 * @param sourceEventTitle Event title from the originating [RawIngestionEventEntity].
 * @param sourceEventOccurredAt Timestamp of the source event (not extraction time).
 * @param now Wall-clock instant used for [CommitmentEntity.createdAt] and [CommitmentEntity.updatedAt].
 */
private fun CommitmentDraftDto.toCommitmentEntity(
    rawEventId: String,
    index: Int,
    userId: String,
    sourceRef: String?,
    sourceEventTitle: String?,
    sourceEventOccurredAt: Instant,
    now: Instant,
): CommitmentEntity = CommitmentEntity(
    // Deterministic UUID v3 (nameUUIDFromBytes uses MD5) keyed on rawEventId+index.
    // Re-processing the same response yields the same ID → upsert, not duplicate.
    id = UUID.nameUUIDFromBytes(
        "commitment:$rawEventId:$index".toByteArray(Charsets.UTF_8),
    ).toString(),
    userId = userId,
    direction = direction.lowercase(),
    counterpartyRaw = null,
    personRef = personRef,
    title = text.take(500), // reasonable title truncation
    description = null,
    quote = quote,
    sourceEventTitle = sourceEventTitle,
    sourceEventOccurredAt = sourceEventOccurredAt,
    dueDate = dueAt?.toLocalDate(),
    actionState = "pending",
    sourceType = "voice",
    sourceRef = sourceRef,
    confidence = confidence.toDouble(),
    commitmentState = CommitmentState.DRAFT,
    syncStatus = "pending",
    createdAt = now,
    updatedAt = now,
)

/** Converts an [Instant] to a [LocalDate] in the device's system time zone. */
private fun Instant.toLocalDate(): LocalDate =
    toLocalDateTime(TimeZone.currentSystemDefault()).date

/** Convenience: creates a plain-text [RequestBody] from this String. */
private fun String.toPlainRequestBody(): RequestBody =
    toRequestBody("text/plain".toMediaTypeOrNull())
