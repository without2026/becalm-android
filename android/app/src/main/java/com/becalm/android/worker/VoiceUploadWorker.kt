package com.becalm.android.worker

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.becalm.android.core.di.IoDispatcher
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
import com.becalm.android.data.repository.ProcessingStatusRepository
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.domain.voice.Direction
import com.squareup.moshi.Moshi
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineDispatcher
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
 * `POST /v1/voice/transcribe_extract` endpoint, receives extracted business items from
 * Vertex AI Gemini 2.5 Flash, and persists the current action subset as
 * [CommitmentEntity] rows in Room.
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
 *    extracted action item (deterministic IDs keyed on rawEventId+index), update raw-event
 *    action count and snippet, record sync success.
 * 7. Error handling per VOI-006:
 *    - HTTP 401 (after AuthInterceptor refresh): mark "failed", [Result.success].
 *    - HTTP 403/413/422: retryable=false, mark "failed", [Result.success].
 *    - HTTP 502: parse error envelope. `output_truncated`/`schema_violation` → mark "failed"
 *      (non-retryable). `vertex_upstream_error` or parse failure → [handleFailure] (transient retry).
 *    - HTTP 429: parse the server-supplied `Retry-After` header (seconds) and honor it via
 *      re-enqueue with initial delay through [WorkScheduler.enqueueVoiceUploadWithDelay] +
 *      [UploadBackoff.nextDelaySeconds] (api-contract.yml 429; VOI-006). Quarantine when the
 *      current execution is already the [MAX_ATTEMPTS]th; else return [Result.success] (the
 *      delayed re-enqueue replaces the in-flight unique work via ExistingWorkPolicy.REPLACE).
 *    - HTTP 500/503 or IOException: quarantine when the current execution is already the
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
    private val rawIngestionEventDaoProvider: Provider<RawIngestionEventDao>,
    private val commitmentDaoProvider: Provider<CommitmentDao>,
    private val voiceApiProvider: Provider<VoiceApi>,
    private val userPrefsStore: UserPrefsStore,
    private val sourceStatusRepositoryProvider: Provider<SourceStatusRepository>,
    private val processingStatusRepository: ProcessingStatusRepository,
    private val workSchedulerProvider: Provider<WorkScheduler>,
    private val processingPauseGate: ProcessingPauseGate,
    private val voiceFailureNotifier: VoiceFailureNotifier,
    private val moshi: Moshi,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : CoroutineWorker(appContext, workerParams) {

    public constructor(
        appContext: Context,
        workerParams: WorkerParameters,
        rawIngestionEventDao: RawIngestionEventDao,
        commitmentDao: CommitmentDao,
        voiceApi: VoiceApi,
        userPrefsStore: UserPrefsStore,
        sourceStatusRepository: SourceStatusRepository,
        processingStatusRepository: ProcessingStatusRepository,
        workScheduler: WorkScheduler,
        processingPauseGate: ProcessingPauseGate,
        voiceFailureNotifier: VoiceFailureNotifier,
        moshi: Moshi,
        logger: Logger,
        ioDispatcher: CoroutineDispatcher,
    ) : this(
        appContext = appContext,
        workerParams = workerParams,
        rawIngestionEventDaoProvider = Provider { rawIngestionEventDao },
        commitmentDaoProvider = Provider { commitmentDao },
        voiceApiProvider = Provider { voiceApi },
        userPrefsStore = userPrefsStore,
        sourceStatusRepositoryProvider = Provider { sourceStatusRepository },
        processingStatusRepository = processingStatusRepository,
        workSchedulerProvider = Provider { workScheduler },
        processingPauseGate = processingPauseGate,
        voiceFailureNotifier = voiceFailureNotifier,
        moshi = moshi,
        logger = logger,
        ioDispatcher = ioDispatcher,
    )

    private val rawIngestionEventDao: RawIngestionEventDao
        get() = rawIngestionEventDaoProvider.get()

    private val commitmentDao: CommitmentDao
        get() = commitmentDaoProvider.get()

    private val voiceApi: VoiceApi
        get() = voiceApiProvider.get()

    private val sourceStatusRepository: SourceStatusRepository
        get() = sourceStatusRepositoryProvider.get()

    private val workScheduler: WorkScheduler
        get() = workSchedulerProvider.get()

    public override suspend fun doWork(): Result = withContext(ioDispatcher) {
        if (processingPauseGate.shouldSkip(TAG)) {
            return@withContext Result.success()
        }
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
        processingStatusRepository.recordUploading(entity.sourceType, "Queued audio analysis")

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
                processingStatusRepository.recordError(entity.sourceType, "Audio file unavailable")
                markFailed(entity, reasonCode = "audio_unavailable")
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

                // Insert extracted trackable items into Room.
                // IDs are deterministic (UUID v5 keyed on rawEventId+index) so that a
                // replayed successful response produces an upsert on the same PK rather than
                // a duplicate row (CommitmentDao uses OnConflictStrategy.REPLACE — finding #2 fix).
                val commitmentEntities = body.items.mapIndexed { index, dto ->
                    dto.toTrackableCommitmentEntity(
                        rawEventId = rawEventId,
                        index = index,
                        userId = userId,
                        sourceRef = entity.sourceRef,
                        sourceType = entity.sourceType,
                        sourceEventTitle = entity.eventTitle,
                        sourceEventOccurredAt = entity.timestamp,
                        now = now,
                    )
                }
                if (commitmentEntities.isNotEmpty()) {
                    commitmentDao.insertAll(commitmentEntities)
                }

                // Update raw event metadata:
                // - commitmentsExtractedCount now mirrors the persisted trackable-item count.
                // - eventSnippet uses the first extracted item's quote for recall.
                val snippet = body.items.firstOrNull()?.quote?.take(SNIPPET_MAX_CHARS)
                val updated = entity.copy(
                    commitmentsExtractedCount = body.items.size,
                    eventSnippet = snippet,
                    syncStatus = STATUS_PENDING,
                )
                rawIngestionEventDao.update(updated)

                sourceStatusRepository.recordSyncSuccess(SourceType.VOICE, now)
                processingStatusRepository.recordSynced(entity.sourceType, body.items.size)
                logger.d(
                    TAG,
                    "upload success id=${redact(rawEventId)} items=${body.items.size}",
                )
                Result.success()
            }

            401 -> {
                // AuthInterceptor already attempted refresh; second 401 means token is invalid
                logger.w(TAG, "HTTP 401 after refresh id=${redact(rawEventId)} — marking failed")
                processingStatusRepository.recordError(entity.sourceType, "Unauthorized")
                markFailed(entity, reasonCode = "unauthorized")
                Result.success()
            }

            403, 413, 422 -> {
                // Non-retryable: PIPA server-side guard failure, body too large, or duration exceeded
                logger.w(TAG, "HTTP ${response.code()} non-retryable id=${redact(rawEventId)} — quarantining")
                processingStatusRepository.recordError(entity.sourceType, "Upload rejected")
                markFailed(entity, reasonCode = "non_retryable_http_${response.code()}")
                Result.success()
            }

            502 -> {
                // Parse the error envelope to distinguish deterministic vs transient failures.
                // api-contract.yml: output_truncated and schema_violation are non-retryable;
                // only vertex_upstream_error is retryable (finding #4 fix).
                handleVoice502(response.errorBody()?.string(), entity, rawEventId)
            }

            429 -> {
                val retryAfterSec = response.headers()[HEADER_RETRY_AFTER]?.toLongOrNull()
                val rateLimitedAttempt = inputData.getInt(KEY_RATE_LIMITED_ATTEMPT, 0)
                logger.w(
                    TAG,
                    "HTTP 429 id=${redact(rawEventId)} retryAfter=${retryAfterSec}s " +
                        "rateLimitedAttempt=$rateLimitedAttempt",
                )
                handleRateLimited(entity, rawEventId, audioUriString, retryAfterSec, rateLimitedAttempt)
            }

            500, 503 -> {
                logger.w(TAG, "HTTP ${response.code()} transient id=${redact(rawEventId)} attempt=$runAttemptCount")
                handleFailure(entity)
            }

            else -> {
                logger.w(TAG, "HTTP ${response.code()} unexpected id=${redact(rawEventId)} — marking failed")
                processingStatusRepository.recordError(entity.sourceType, "Unexpected HTTP ${response.code()}")
                markFailed(entity, reasonCode = "unexpected_http_${response.code()}")
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
        processingStatusRepository.recordBlocked(entity.sourceType, "PIPA consent required")
        val parked = entity.copy(syncStatus = STATUS_AWAITING_CONSENT)
        rawIngestionEventDao.update(parked)
        return true
    }

    /**
     * Handles an HTTP 502 response by parsing the error envelope and dispatching via
     * [VoiceUploadStateMachine.decide502Action]. Finding #4 fix.
     */
    private suspend fun handleVoice502(
        errorBodyString: String?,
        entity: RawIngestionEventEntity,
        rawEventId: String,
    ): Result {
        val envelope = runCatchingNonCancel(
            logger = logger,
            tag = TAG,
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

        return when (VoiceUploadStateMachine.decide502Action(errorCode)) {
            Voice502Action.Quarantine -> {
                // Deterministic server-side failure — no point retrying.
                logger.w(TAG, "HTTP 502 non-retryable ($errorCode) id=${redact(rawEventId)} — quarantining")
                processingStatusRepository.recordError(entity.sourceType, errorCode ?: "Voice extraction failed")
                markFailed(entity, reasonCode = errorCode ?: "vertex_502_unknown")
                Result.success()
            }
            Voice502Action.HandleAsTransient -> {
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
            logger = logger,
            tag = TAG,
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
     * Handles a transient failure by dispatching via [VoiceUploadStateMachine.decideRetryAction]
     * (spec VOI-006).
     */
    private suspend fun handleFailure(entity: RawIngestionEventEntity): Result {
        return when (VoiceUploadStateMachine.decideRetryAction(runAttemptCount, MAX_ATTEMPTS)) {
            RetryAction.Quarantine -> {
                logger.w(TAG, "exhausted retries id=${redact(entity.id)} — marking failed")
                markFailed(entity, reasonCode = "retry_exhausted")
                Result.success()
            }
            // The row stays "pending" so WorkManager can retry it on the next attempt.
            RetryAction.Retry -> Result.retry()
        }
    }

    /**
     * Handles an HTTP 429 by honoring the server-supplied `Retry-After` seconds hint.
     *
     * When retries remain, re-enqueues a [VoiceUploadWorker] with the delay computed by
     * [UploadBackoff.nextDelaySeconds] (Retry-After wins; otherwise exponential-with-jitter)
     * via [WorkScheduler.enqueueVoiceUploadWithDelay]. The current run returns
     * [Result.success] so WorkManager does not also schedule its static
     * [androidx.work.BackoffPolicy.EXPONENTIAL] retry — the new delayed request supersedes
     * the in-flight unique work through [androidx.work.ExistingWorkPolicy.REPLACE].
     *
     * ## Persistent 429 counter (finding — codex round 1)
     *
     * Every successful 429 re-enqueue creates a brand-new [androidx.work.OneTimeWorkRequest]
     * whose native `runAttemptCount` is 0 for its first execution. Relying on
     * `runAttemptCount` alone would therefore let a persistent 429 loop forever because
     * the quarantine guard never trips. Instead we pass a logical counter through
     * [androidx.work.Data] via [KEY_RATE_LIMITED_ATTEMPT]: each re-enqueue increments the
     * value it read from [inputData] and threads the new value into the next request. When
     * the incremented value would reach [MAX_RATE_LIMITED_ATTEMPTS], we quarantine instead
     * of re-enqueuing.
     *
     * 500/503 and network IOException paths intentionally keep using `runAttemptCount`
     * because those returns produce [Result.retry] (same WorkRequest, counter persists).
     *
     * Spec refs: api-contract.yml 429 (Retry-After 존중), VOI-006.
     */
    private suspend fun handleRateLimited(
        entity: RawIngestionEventEntity,
        rawEventId: String,
        audioUri: String,
        retryAfterSec: Long?,
        rateLimitedAttempt: Int,
    ): Result {
        // Quarantine when the *next* re-enqueue would push the counter past the budget,
        // i.e. the current execution is already the MAX_RATE_LIMITED_ATTEMPTSth.
        val nextAttempt = rateLimitedAttempt + 1
        return if (nextAttempt >= MAX_RATE_LIMITED_ATTEMPTS) {
            logger.w(
                TAG,
                "exhausted 429 budget id=${redact(rawEventId)} " +
                    "rateLimitedAttempt=$rateLimitedAttempt — marking failed",
            )
            markFailed(entity, reasonCode = "rate_limited_exhausted")
            Result.success()
        } else {
            val delaySec = UploadBackoff.nextDelaySeconds(
                attempt = nextAttempt,
                retryAfterSec = retryAfterSec,
            )
            logger.d(
                TAG,
                "429 re-enqueue id=${redact(rawEventId)} delaySec=$delaySec " +
                    "rateLimitedAttempt=$rateLimitedAttempt → nextAttempt=$nextAttempt",
            )
            workScheduler.enqueueVoiceUploadWithDelay(
                rawEventId = rawEventId,
                audioUri = audioUri,
                initialDelaySec = delaySec,
                rateLimitedAttempt = nextAttempt,
            )
            Result.success()
        }
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
    private suspend fun markFailed(entity: RawIngestionEventEntity, reasonCode: String?) {
        rawIngestionEventDao.update(
            entity.copy(
                syncStatus = "failed",
                retryCount = entity.retryCount + 1,
                lastAttemptAt = Clock.System.now(),
            ),
        )
        if (userPrefsStore.observeNotificationsEnabled().first()) {
            voiceFailureNotifier.notifyFailure(
                context = appContext,
                rawEventId = entity.id,
                eventTitle = entity.eventTitle,
                reasonCode = reasonCode,
            )
        }
    }

    // ── Companion ─────────────────────────────────────────────────────────────

    public companion object {
        private const val TAG = "VoiceUploadWorker"
        private const val SNIPPET_MAX_CHARS = 200
        private const val MAX_ATTEMPTS = 3

        /**
         * Maximum number of 429 (rate-limited) re-enqueues before a row is quarantined.
         *
         * Counted separately from [MAX_ATTEMPTS] because each 429 response produces a brand
         * new [androidx.work.OneTimeWorkRequest] whose native `runAttemptCount` resets to 0,
         * making [MAX_ATTEMPTS] ineffective for this path. The counter is threaded through
         * [androidx.work.Data] via [KEY_RATE_LIMITED_ATTEMPT] across re-enqueues.
         *
         * Set equal to [MAX_ATTEMPTS] so a sustained 429 gets the same end-to-end budget as
         * a sustained 500/503.
         */
        private const val MAX_RATE_LIMITED_ATTEMPTS = MAX_ATTEMPTS

        /** Streaming buffer size — 64 KiB chunks per VOI-007. */
        private const val STREAM_BUFFER_BYTES = 65536

        /**
         * HTTP header carrying a server-supplied retry hint in seconds on 429 responses.
         * Spec refs: api-contract.yml § /v1/voice/transcribe_extract 429; VOI-006.
         */
        private const val HEADER_RETRY_AFTER: String = "Retry-After"

        /** WorkManager input [androidx.work.Data] key: UUID of the owning [RawIngestionEventEntity]. */
        public const val KEY_RAW_EVENT_ID: String = "raw_event_id"

        /** WorkManager input [androidx.work.Data] key: content URI string of the audio file. */
        public const val KEY_AUDIO_URI: String = "audio_uri"

        /**
         * WorkManager input [androidx.work.Data] key: logical 429 retry counter threaded
         * across re-enqueues to drive the [MAX_RATE_LIMITED_ATTEMPTS] quarantine guard.
         * Defaults to 0 on the first execution.
         */
        public const val KEY_RATE_LIMITED_ATTEMPT: String = "rate_limited_attempt"
    }
}
