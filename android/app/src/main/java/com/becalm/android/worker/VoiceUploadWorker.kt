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
import com.becalm.android.data.local.db.dao.PersonIndexDao
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.api.SourceExtractionApi
import com.becalm.android.data.repository.ProcessingStatusRepository
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.data.repository.toPlainRequestBody
import com.squareup.moshi.Moshi
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.BufferedSink

/**
 * [CoroutineWorker] that uploads a voice recording to Railway's
 * `POST /v1/extractions/commitments` endpoint, receives extracted business items from
 * the backend audio extraction pipeline, and persists the current action subset as
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
 * 6. Call [SourceExtractionApi.commitmentExtract]. On success: insert [CommitmentEntity] rows for each
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
    private val personIndexDaoProvider: Provider<PersonIndexDao>,
    private val sourceExtractionApiProvider: Provider<SourceExtractionApi>,
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
        personIndexDao: PersonIndexDao,
        sourceExtractionApi: SourceExtractionApi,
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
        personIndexDaoProvider = Provider { personIndexDao },
        sourceExtractionApiProvider = Provider { sourceExtractionApi },
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

    private val personIndexDao: PersonIndexDao
        get() = personIndexDaoProvider.get()

    private val sourceExtractionApi: SourceExtractionApi
        get() = sourceExtractionApiProvider.get()

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
        val selfSpeakerId = inputData.getString(KEY_SELF_SPEAKER_ID)
        val speakerMappingsJson = inputData.getString(KEY_SPEAKER_MAPPINGS_JSON)
        val speakerPreviewId = inputData.getString(KEY_SPEAKER_PREVIEW_ID)

        if (rawEventId.isNullOrBlank() || audioUriString.isNullOrBlank()) {
            logger.e(TAG, "missing input keys rawEventId=${redact(rawEventId ?: "")} audioUri=${redact(audioUriString ?: "")}")
            return@withContext Result.failure()
        }

        val hasServerPreview = !speakerPreviewId.isNullOrBlank()
        if (!hasServerPreview) {
            // VOI-005: permission gate — failure (not retry) because onboarding gates enqueue.
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
        }

        val delegate = extractionDelegate()
        val context = when (val load = delegate.loadContext(rawEventId)) {
            is LocalSourceExtractionLoadResult.Loaded -> load
            is LocalSourceExtractionLoadResult.Terminal -> return@withContext load.result
        }
        val entity = context.entity
        processingStatusRepository.recordUploading(entity.sourceType, "Queued audio analysis")

        // VOI-004: PIPA consent gate (first check — pre-upload).
        if (delegate.parkIfConsentWithdrawn(entity, stage = "PIPA consent not granted")) {
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
        val audioPart = if (hasServerPreview) {
            null
        } else {
            buildAudioPart(audioUri)
                ?: run {
                    logger.e(
                        TAG,
                        "cannot open audio stream uri=${redact(audioUriString)} — URI no longer accessible; quarantining",
                    )
                    processingStatusRepository.recordError(entity.sourceType, "Audio file unavailable")
                    markFailed(delegate, entity, reasonCode = "audio_unavailable")
                    return@withContext Result.success()
                }
        }

        // VOI-004: second PIPA gate — consent may have been withdrawn while this job was queued
        // or while buildAudioPart was running. Check immediately before the network call so no
        // audio bytes are transmitted after consent is revoked (finding #1 in-flight race fix).
        if (delegate.parkIfConsentWithdrawn(entity, stage = "PIPA consent withdrawn before network call")) {
            return@withContext Result.success()
        }

        processingStatusRepository.recordGemini(entity.sourceType, "내용 정리 중")
        delegate.uploadRunner().upload(
            SourceExtractionUploadRequest(
                userId = context.userId,
                entity = entity,
                rawEventId = rawEventId,
                inputModality = "audio",
                audioPart = audioPart,
                durationSecondsFallback = "0".toPlainRequestBody(),
                nonRetryableErrorMessage = "Upload rejected",
                onMarkFailed = { reasonCode -> markFailed(delegate, entity, reasonCode) },
                onRateLimited = { retryAfterSec ->
                    val rateLimitedAttempt = inputData.getInt(KEY_RATE_LIMITED_ATTEMPT, 0)
                    logger.w(
                        TAG,
                        "HTTP 429 id=${redact(rawEventId)} retryAfter=${retryAfterSec}s " +
                            "rateLimitedAttempt=$rateLimitedAttempt",
                    )
                    handleRateLimited(
                        delegate = delegate,
                        entity = entity,
                        rawEventId = rawEventId,
                        audioUri = audioUriString,
                        retryAfterSec = retryAfterSec,
                        rateLimitedAttempt = rateLimitedAttempt,
                        selfSpeakerId = selfSpeakerId,
                        speakerMappingsJson = speakerMappingsJson,
                        speakerPreviewId = speakerPreviewId,
                    )
                },
                selfSpeakerId = selfSpeakerId,
                speakerMappingsJson = speakerMappingsJson,
                speakerPreviewId = speakerPreviewId,
            )
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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

    /**
     * Builds a [MultipartBody.Part] by opening a streaming [okhttp3.RequestBody] from the
     * audio file at [uri] via [ContentResolver]. No bytes are buffered to disk (VOI-007).
     *
     * Returns null when [ContentResolver.openInputStream] fails (e.g. URI revoked).
     */
    private fun buildAudioPart(uri: Uri): MultipartBody.Part? {
        runCatchingNonCancel(
            logger = logger,
            tag = TAG,
            op = "openInputStream failed uri=${redact(uri.toString())}",
            block = { appContext.contentResolver.openInputStream(uri)?.close() ?: return null },
            onFailure = { return null },
        )

        val streamingBody = object : RequestBody() {
            override fun contentType() = audioMediaType(uri).toMediaTypeOrNull()

            override fun writeTo(sink: BufferedSink) {
                val stream = appContext.contentResolver.openInputStream(uri)
                    ?: throw java.io.FileNotFoundException("Unable to open audio stream: $uri")
                stream.use {
                    val buffer = ByteArray(STREAM_BUFFER_BYTES)
                    var bytesRead: Int
                    while (it.read(buffer).also { bytesRead = it } != -1) {
                        sink.write(buffer, 0, bytesRead)
                    }
                }
            }
        }

        return MultipartBody.Part.createFormData("audio", audioFileName(uri), streamingBody)
    }

    private fun audioMediaType(uri: Uri): String =
        when (uri.lastPathSegment?.substringAfterLast('/', "")?.substringAfterLast('.', "")?.lowercase()) {
            "wav" -> "audio/wav"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/m4a"
            "aac" -> "audio/aac"
            "mp4" -> "audio/mp4"
            "3gp" -> "audio/3gpp"
            else -> appContext.contentResolver.getType(uri) ?: "audio/m4a"
        }

    private fun audioFileName(uri: Uri): String {
        val lastPath = uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
        return lastPath ?: "audio.m4a"
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
        delegate: LocalSourceExtractionDelegate,
        entity: RawIngestionEventEntity,
        rawEventId: String,
        audioUri: String,
        retryAfterSec: Long?,
        rateLimitedAttempt: Int,
        selfSpeakerId: String?,
        speakerMappingsJson: String?,
        speakerPreviewId: String?,
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
            markFailed(delegate, entity, reasonCode = "rate_limited_exhausted")
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
                selfSpeakerId = selfSpeakerId,
                speakerMappingsJson = speakerMappingsJson,
                speakerPreviewId = speakerPreviewId,
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
    private suspend fun markFailed(
        delegate: LocalSourceExtractionDelegate,
        entity: RawIngestionEventEntity,
        reasonCode: String?,
    ) {
        delegate.markFailed(entity, reasonCode)
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

        public const val KEY_SELF_SPEAKER_ID: String = "self_speaker_id"

        public const val KEY_SPEAKER_MAPPINGS_JSON: String = "speaker_mappings_json"

        public const val KEY_SPEAKER_PREVIEW_ID: String = "speaker_preview_id"
    }
}
