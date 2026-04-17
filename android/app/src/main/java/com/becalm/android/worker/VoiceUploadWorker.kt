package com.becalm.android.worker

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.becalm.android.core.util.Logger
import com.becalm.android.core.util.redact
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.CommitmentDao
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.api.VoiceApi
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.remote.dto.VoiceErrorEnvelope
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.domain.voice.Direction
import com.squareup.moshi.Moshi
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException

// sync_status 리터럴. STATUS_PENDING 은 [VoiceUploadMappers] 와 공유하기 위해 그쪽에 internal 로 두고
// STATUS_AWAITING_CONSENT 는 이 파일에서만 쓰이므로 file-private 으로 유지한다.
private const val STATUS_AWAITING_CONSENT = "awaiting_consent"

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
 *      else return [Result.retry] (WorkManager's runAttemptCount is the single retry counter;
 *      the DB retry_count column is only bumped on terminal failure to avoid double-counting).
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

        // Step 3: load entity — scoped to the current user so a stale enqueue from a previous
        // session cannot read another user's row if the DB was not wiped on sign-out.
        val userId = userPrefsStore.observeCurrentUserId().first()
        if (userId.isNullOrBlank()) {
            logger.e(TAG, "no active userId — failing id=${redact(rawEventId)}")
            return@withContext Result.failure()
        }
        val entity = rawIngestionEventDao.findById(id = rawEventId, userId = userId)
        if (entity == null) {
            logger.e(TAG, "RawIngestionEventEntity not found id=${redact(rawEventId)}")
            return@withContext Result.failure()
        }

        // VOI-004: PIPA consent gate (first check — pre-upload).
        if (parkIfConsentWithdrawn(entity, rawEventId, stage = "PIPA consent not granted")) {
            return@withContext Result.success()
        }

        val audioUri = Uri.parse(audioUriString)

        // VOI-007: stream audio bytes directly — no temp-file copy.
        //
        // URI accessibility guard: content:// URIs backed by MediaStore (see MediaStoreWorker)
        // can become unreachable if the underlying file was deleted by the user or if a
        // temporary URI permission expired after device restart. When openInputStream fails,
        // retrying won't help — the URI is permanently gone — so quarantine the row directly
        // instead of wasting the remaining WorkManager retry attempts.
        val audioPart = buildAudioPart(audioUri)
            ?: run {
                logger.e(
                    TAG,
                    "cannot open audio stream uri=${redact(audioUriString)} — URI no longer accessible; quarantining",
                )
                markFailed(entity)
                return@withContext Result.success()
            }

        // VOI-004: second PIPA gate — consent may have been withdrawn while this job was queued
        // or while buildAudioPart was running. Check immediately before the network call so no
        // audio bytes are transmitted after consent is revoked (finding #1 in-flight race fix).
        if (parkIfConsentWithdrawn(entity, rawEventId, stage = "PIPA consent withdrawn before network call")) {
            return@withContext Result.success()
        }

        val parts = entity.toRequestParts(rawEventId)

        val response = try {
            voiceApi.transcribeExtract(
                audio = audioPart,
                clientEventId = parts.clientEventId,
                rawEventId = parts.rawEventId,
                durationSeconds = parts.durationSeconds,
                timestamp = parts.timestamp,
                personRef = parts.personRef,
                eventTitle = parts.eventTitle,
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
                    syncStatus = STATUS_PENDING,
                )
                rawIngestionEventDao.update(updated)

                sourceStatusRepository.recordSyncSuccess(SourceType.VOICE, now)
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
     * VOI-004 PIPA consent gate 구현 — pre-upload, post-upload 두 번 호출된다.
     * 반환값이 true 이면 caller 는 즉시 중단해야 한다.
     * 동의가 철회된 경우 entity 를 `awaiting_consent` 상태로 파킹하고,
     * 업로드는 절대 수행하지 않는다 (오디오 바이트가 서버로 나가지 않음).
     * [stage] 는 로그에 기록되어 어느 gate 에서 차단됐는지 구분한다.
     */
    private suspend fun parkIfConsentWithdrawn(
        entity: RawIngestionEventEntity,
        rawEventId: String,
        stage: String,
    ): Boolean {
        val hasConsent = userPrefsStore.observeThirdPartyProvisionConsent().first()
        if (hasConsent) return false
        logger.w(TAG, "$stage — parking id=${redact(rawEventId)} as awaiting_consent")
        val parked = entity.copy(syncStatus = STATUS_AWAITING_CONSENT)
        rawIngestionEventDao.update(parked)
        return true
    }

    /**
     * 반복되는 try/catch 블록을 통합한다.
     * CancellationException 은 반드시 rethrow 하여 WorkManager 의 cancel 신호를 보존한다.
     * 실패 시 `"$op: ${e.message}"` 형태로 warn 로그를 남기고 [onFailure] 결과를 반환한다.
     */
    private inline fun <T> runCatchingNonCancel(
        op: String,
        block: () -> T,
        onFailure: (Exception) -> T,
    ): T {
        return try {
            block()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.w(TAG, "$op: ${e.message}")
            onFailure(e)
        }
    }

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
        val envelope = runCatchingNonCancel(
            op = "HTTP 502 — failed to parse error envelope id=${redact(rawEventId)}",
            block = {
                errorBodyString?.let {
                    moshi.adapter(VoiceErrorEnvelope::class.java).fromJson(it)
                }
            },
            onFailure = { null },
        )

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
        val inputStream = runCatchingNonCancel(
            op = "openInputStream failed uri=${redact(uri.toString())}",
            block = { appContext.contentResolver.openInputStream(uri) ?: return null },
            onFailure = { return null },
        )

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
        // Rely solely on WorkManager's runAttemptCount for retry accounting; do NOT increment
        // the DB retry_count column here. Previously the worker maintained two independent
        // counters (WorkManager's runAttemptCount + the DB retry_count via markTransientFailure),
        // which double-counted transient failures. The row stays "pending" so WorkManager can
        // retry it on the next attempt.
        return Result.retry()
    }

    /**
     * Permanently quarantines the entity by setting [RawIngestionEventEntity.syncStatus] to
     * "failed" via the existing [RawIngestionEventDao.markFailed] query.
     *
     * Passes [retryIncrement]=1 so the DB retry_count reflects that a final terminal attempt
     * occurred. Transient retries no longer touch retry_count (WorkManager's runAttemptCount
     * is the single source of truth for retry counting), so this is the only place the column
     * is bumped for voice uploads.
     */
    private suspend fun markFailed(entity: RawIngestionEventEntity) {
        rawIngestionEventDao.markFailed(id = entity.id, retryIncrement = 1, now = Clock.System.now())
    }

    // ── Companion ─────────────────────────────────────────────────────────────

    public companion object {
        private const val TAG = "VoiceUploadWorker"
        private const val SNIPPET_MAX_CHARS = 200
        private const val MAX_ATTEMPTS = 3

        /** Streaming buffer size — 64 KiB chunks per VOI-007. */
        private const val STREAM_BUFFER_BYTES = 65536

        /** WorkManager input [androidx.work.Data] key: UUID of the owning [RawIngestionEventEntity]. */
        public const val KEY_RAW_EVENT_ID: String = "raw_event_id"

        /** WorkManager input [androidx.work.Data] key: content URI string of the audio file. */
        public const val KEY_AUDIO_URI: String = "audio_uri"
    }
}
