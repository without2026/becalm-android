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
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

// ─── STT interfaces (implemented by SP-33/34/35 in Round 5) ──────────────────

/**
 * Abstraction over a single speech-to-text back-end.
 *
 * Implementations are provided by SP-34 (Whisper.cpp native) and SP-35
 * (Android SpeechRecognizer) in Round 5. Until those ship, any injection of
 * [SttBackendSelector] will fail at runtime — the worker is not enqueued before
 * onboarding completes, so this is a no-op until R5 ships.
 *
 * TODO: SttBackendSelector impl bound by SP-33/34/35 in Round 5.
 */
public interface SttBackend {
    /**
     * Transcribes a single decoded [AudioChunk] and returns the recognised text.
     *
     * @param chunk 16 kHz 16-bit mono PCM window to transcribe.
     * @return [BecalmResult.Success] carrying the transcript string (may be empty),
     *         or [BecalmResult.Failure] on codec / network / permission error.
     */
    public suspend fun transcribe(chunk: AudioChunk): BecalmResult<String>
}

/**
 * Selects the appropriate [SttBackend] for the current device / network conditions.
 *
 * Prefers Android [android.speech.SpeechRecognizer] when online; falls back to the
 * Whisper.cpp native engine (SP-34) when offline or when the on-device recogniser is
 * unavailable.
 *
 * TODO: SttBackendSelector impl bound by SP-33/34/35 in Round 5.
 */
public interface SttBackendSelector {
    /**
     * Returns the [SttBackend] that should handle the next transcription request.
     */
    public fun select(): SttBackend
}

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
    private val sttBackendSelector: SttBackendSelector,
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
        val transcriptResult = runCatching {
            transcribeAudio(audioUri)
        }

        if (transcriptResult.isFailure) {
            val ex = transcriptResult.exceptionOrNull()
            logger.e(TAG, "transcription failed id=${redact(rawEventId)} attempt=$runAttemptCount", ex)
            return@withContext handleFailure(entity)
        }

        val transcript = transcriptResult.getOrDefault("")

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
     * Decodes [uri] into chunks and accumulates the full transcript string.
     *
     * @param uri MediaStore URI of the audio file.
     * @return Concatenated transcript text (may be empty if no speech is detected).
     * @throws Exception if [VoiceChunker] or [SttBackend] throws unexpectedly.
     */
    private suspend fun transcribeAudio(uri: Uri): String {
        val backend = sttBackendSelector.select()
        val chunks = voiceChunker.chunks(appContext, uri).toList()

        if (chunks.isEmpty()) {
            logger.d(TAG, "no chunks produced for uri=${redact(uri.toString())}")
            return ""
        }

        val builder = StringBuilder()
        for (chunk in chunks) {
            when (val result = backend.transcribe(chunk)) {
                is BecalmResult.Success -> {
                    val text = result.value.trim()
                    if (text.isNotEmpty()) {
                        if (builder.isNotEmpty()) builder.append(' ')
                        builder.append(text)
                    }
                    logger.d(TAG, "chunk [${chunk.startMs}–${chunk.endMs}ms] len=${text.length}")
                }
                is BecalmResult.Failure -> {
                    logger.w(
                        TAG,
                        "STT failure for chunk [${chunk.startMs}–${chunk.endMs}ms]: ${result.error}",
                    )
                    // Continue with next chunk; partial transcript is better than none.
                }
            }
        }

        return builder.toString()
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
