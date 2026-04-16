package com.becalm.android.worker

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.domain.stt.SttBackend
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

// ─── Worker ───────────────────────────────────────────────────────────────────

/**
 * [CoroutineWorker] that transcribes a single voice recording and persists the
 * result into the existing [RawIngestionEventEntity] that owns the recording.
 *
 * ## Inputs (WorkManager [androidx.work.Data])
 * - [KEY_RAW_EVENT_ID] — String UUID of the [RawIngestionEventEntity] to update.
 * - [KEY_AUDIO_URI]    — String MediaStore URI of the audio file.
 *
 * ## Lifecycle
 * 1. Resolve [RawIngestionEventEntity] by [KEY_RAW_EVENT_ID]; absent → [Result.failure].
 * 2. Gate on [android.Manifest.permission.READ_MEDIA_AUDIO] (API 33+) or
 *    [android.Manifest.permission.READ_EXTERNAL_STORAGE] (≤ API 32); missing → [Result.retry].
 * 3. Decode audio via [VoiceChunker.chunks]; route each chunk through [SttBackendSelector].
 * 4. Persist transcript (first 200 chars) in [RawIngestionEventEntity.eventSnippet] and
 *    reset [RawIngestionEventEntity.syncStatus] to "pending" so the upload worker re-sends
 *    the enriched row.
 * 5. Call [SourceStatusRepository.recordSyncSuccess] for source "voice".
 * 6. On any transient error → [Result.retry] up to [MAX_ATTEMPTS]; afterwards mark
 *    [RawIngestionEventEntity.syncStatus] = "failed" and return [Result.success] so the
 *    WorkManager chain is not blocked.
 *
 * ## Permissions (VOI-005)
 * Requires [android.Manifest.permission.READ_MEDIA_AUDIO] on API ≥ 33 or
 * [android.Manifest.permission.READ_EXTERNAL_STORAGE] on API ≤ 32.
 * Missing permission causes [Result.retry]; permission is expected to have been granted
 * during onboarding (SP-53) before the worker is ever enqueued.
 *
 * ## SP-34/35 dependency
 * [SttBackendSelector] has no Hilt binding until Round 5. The worker will fail to
 * construct (Hilt unsatisfied binding) until SP-33/34/35 ship. This is intentional —
 * the worker is not enqueued until onboarding completes at that point.
 *
 * TODO: SttBackendSelector impl bound by SP-33/34/35 in Round 5.
 *
 * Spec coverage: VOI-001, VOI-002, VOI-004, VOI-005.
 */
@HiltWorker
public class VoiceTranscriptionWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val rawIngestionEventDao: RawIngestionEventDao,
    private val voiceChunker: VoiceChunker,
    private val sttBackend: SttBackend,
    private val sourceStatusRepository: SourceStatusRepository,
    private val logger: Logger,
) : CoroutineWorker(appContext, workerParams) {

    public override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val rawEventId = inputData.getString(KEY_RAW_EVENT_ID)
        val audioUriString = inputData.getString(KEY_AUDIO_URI)

        if (rawEventId.isNullOrBlank() || audioUriString.isNullOrBlank()) {
            logger.e(TAG, "missing input keys rawEventId=${redact(rawEventId ?: "")} audioUri=${redact(audioUriString ?: "")}")
            return@withContext Result.failure()
        }

        // VOI-005: permission gate
        val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_AUDIO
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(
                appContext, audioPermission,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            logger.w(TAG, "audio permission not granted ($audioPermission) — retrying")
            return@withContext Result.retry()
        }

        // Step 1: Resolve entity (VOI-001)
        val entity = rawIngestionEventDao.findById(rawEventId)
        if (entity == null) {
            logger.e(TAG, "RawIngestionEventEntity not found id=$rawEventId")
            return@withContext Result.failure()
        }

        val audioUri = Uri.parse(audioUriString)

        // Steps 2–3: Decode + transcribe
        val transcriptResult = transcribeAudio(audioUri)

        if (transcriptResult is BecalmResult.Failure) {
            logger.e(TAG, "transcription failed id=${redact(rawEventId)} attempt=$runAttemptCount error=${transcriptResult.error}")
            return@withContext handleFailure(entity)
        }

        val transcript = (transcriptResult as BecalmResult.Success).value

        // Step 4: Persist snippet and re-queue for upload (VOI-004)
        val updatedEntity = entity.copy(
            eventSnippet = transcript.take(SNIPPET_MAX_CHARS),
            syncStatus = "pending",
        )
        rawIngestionEventDao.update(updatedEntity)
        logger.d(TAG, "transcript persisted id=${redact(rawEventId)} snippetLen=${updatedEntity.eventSnippet?.length}")

        // Step 5: Record source success
        sourceStatusRepository.recordSyncSuccess(SOURCE_VOICE, Clock.System.now())

        Result.success()
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    /**
     * Delegates transcription of [uri] to [sttBackend].
     *
     * Returns the [BecalmResult] from the backend directly so the caller's existing
     * error-handling (retry mapping via [handleFailure]) can decide what to do —
     * no exception is thrown here.
     *
     * @param uri MediaStore URI of the audio file; its string form is passed to the backend.
     * @return [BecalmResult.Success] with transcript text, or [BecalmResult.Failure] on error.
     */
    private suspend fun transcribeAudio(uri: Uri): BecalmResult<String> {
        val result = sttBackend.transcribeAudio(uri.toString())
        if (result is BecalmResult.Failure) {
            logger.w(TAG, "STT failure uri=${redact(uri.toString())}: ${result.error}")
        }
        return result
    }

    /**
     * Handles a failed transcription attempt.
     *
     * If [runAttemptCount] has reached [MAX_ATTEMPTS], marks the entity as "failed"
     * so the sync queue is not permanently blocked, then returns [Result.success] to
     * prevent WorkManager from retrying further.
     *
     * Otherwise returns [Result.retry] to let WorkManager back-off and retry.
     *
     * @param entity The [RawIngestionEventEntity] associated with this work item.
     */
    private suspend fun handleFailure(entity: RawIngestionEventEntity): Result {
        if (runAttemptCount >= MAX_ATTEMPTS) {
            logger.w(TAG, "exhausted retries id=${redact(entity.id)} — marking failed")
            val failed = entity.copy(syncStatus = "failed")
            rawIngestionEventDao.update(failed)
            return Result.success()
        }
        return Result.retry()
    }

    public companion object {
        private const val TAG = "VoiceTranscriptionWorker"
        private const val SNIPPET_MAX_CHARS = 200
        private const val MAX_ATTEMPTS = 3
        private const val SOURCE_VOICE = "voice"

        /** WorkManager input [androidx.work.Data] key: UUID of the owning [RawIngestionEventEntity]. */
        public const val KEY_RAW_EVENT_ID: String = "raw_event_id"

        /** WorkManager input [androidx.work.Data] key: MediaStore URI string of the audio file. */
        public const val KEY_AUDIO_URI: String = "audio_uri"

        /**
         * Returns an 8-char hex surrogate for [s] to prevent PII (event IDs, audio URIs)
         * from appearing in logcat. Mirrors the pattern used in [GmailWorker].
         */
        private fun redact(s: String): String = "%08x".format(s.hashCode())
    }
}
