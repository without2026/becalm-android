package com.becalm.android.workers

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.speech.SpeechRecognizer
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.becalm.android.data.local.dao.RawIngestionEventDao
import com.becalm.android.data.local.dao.TranscriptDao
import com.becalm.android.data.local.entities.Transcript
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

// spec: VOI-001 — STT Worker: on-device STT for Samsung Voice Recorder files
// spec: VOI-004 — READ_MEDIA_AUDIO permission check
// spec: VOI-005 — chunk audio > 60s into segments before STT
// Invariant: Transcript is NEVER uploaded to Railway or Supabase

// ---- Injectable STT engine interface (enables unit testing without Android SpeechRecognizer) ----
// spec: VOI-001, VOI-002
interface SttEngine {
    // Transcribe audio from [offsetMs, offsetMs+durationMs). Returns null if STT fails.
    suspend fun transcribeSegment(fileUri: Uri, offsetMs: Long, durationMs: Long): String?
}

// Default engine backed by Android SpeechRecognizer (no-op in unit tests)
class AndroidSttEngine(private val context: Context) : SttEngine {
    override suspend fun transcribeSegment(fileUri: Uri, offsetMs: Long, durationMs: Long): String? {
        // spec: VOI-001 — use Android SpeechRecognizer in offline mode
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            // spec: VOI-003 — if STT unavailable, raw_ingestion_event still flows to Railway
            return null
        }
        // Full implementation requires RecognitionService bound connection with
        // RecognizerIntent.EXTRA_AUDIO_SOURCE pointing to a sliced audio segment.
        // Wired in SP-10 (VOI-001) or Samsung AICore (VOI-002) depending on device capability.
        return null
    }
}

@HiltWorker
class SttWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val rawIngestionEventDao: RawIngestionEventDao,
    private val transcriptDao: TranscriptDao
) : CoroutineWorker(context, workerParams) {

    // sttEngine is overridable for testing; production uses AndroidSttEngine
    internal var sttEngine: SttEngine = AndroidSttEngine(context)

    companion object {
        const val KEY_RAW_INGESTION_ID = "raw_ingestion_id"
        const val KEY_FILE_URI = "file_uri"
        // spec: VOI-005 — chunk threshold and chunk size in seconds
        internal const val CHUNK_THRESHOLD_SECONDS = 60
        internal const val CHUNK_SIZE_SECONDS = 30
    }

    override suspend fun doWork(): Result {
        // spec: VOI-004 — verify READ_MEDIA_AUDIO permission
        if (!hasMediaAudioPermission()) {
            return Result.failure()
        }

        val rawIngestionId = inputData.getString(KEY_RAW_INGESTION_ID)
            ?: return Result.failure()
        val fileUriString = inputData.getString(KEY_FILE_URI)
            ?: return Result.failure()

        val fileUri = Uri.parse(fileUriString)

        // Check if already transcribed (idempotency)
        if (transcriptDao.getByRawIngestionId(rawIngestionId) != null) {
            return Result.success()
        }

        // spec: VOI-005 — determine if chunking needed
        val durationSeconds = getAudioDurationSeconds(fileUri)
        val transcriptText = if (durationSeconds > CHUNK_THRESHOLD_SECONDS) {
            transcribeInChunks(fileUri, durationSeconds)
        } else {
            transcribeSingle(fileUri, durationSeconds)
        }

        if (transcriptText == null || transcriptText.isEmpty()) {
            // STT failed — event still preserved for Railway upload per VOI invariant
            return Result.failure()
        }

        // spec: VOI-001 — INSERT transcript into Room (local only)
        transcriptDao.insert(
            Transcript(
                rawIngestionId = rawIngestionId,
                text = transcriptText
            )
        )

        // Update event_snippet with first ~200 chars of transcript
        val snippet = transcriptText.take(200)
        rawIngestionEventDao.updateSnippetAndCommitmentsCount(rawIngestionId, snippet, 0)

        return Result.success()
    }

    // spec: VOI-005 — split into CHUNK_SIZE_SECONDS segments, run STT on each, concatenate
    internal suspend fun transcribeInChunks(fileUri: Uri, durationSeconds: Int): String? {
        val chunkMs = CHUNK_SIZE_SECONDS * 1000L
        val totalMs = durationSeconds * 1000L
        val results = mutableListOf<String>()

        var offsetMs = 0L
        while (offsetMs < totalMs) {
            val segmentDurationMs = minOf(chunkMs, totalMs - offsetMs)
            val segment = sttEngine.transcribeSegment(fileUri, offsetMs, segmentDurationMs)
                ?: return null  // Any chunk failure → abort (STT not available)
            results.add(segment)
            offsetMs += chunkMs
        }

        return results.joinToString(" ").trim().ifEmpty { null }
    }

    // Single-segment transcription for audio ≤ CHUNK_THRESHOLD_SECONDS
    internal suspend fun transcribeSingle(fileUri: Uri, durationSeconds: Int): String? {
        return sttEngine.transcribeSegment(fileUri, 0L, durationSeconds * 1000L)
    }

    private fun getAudioDurationSeconds(fileUri: Uri): Int {
        return try {
            val extractor = MediaExtractor()
            extractor.setDataSource(context, fileUri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    val durationUs = format.getLong(MediaFormat.KEY_DURATION)
                    extractor.release()
                    return (durationUs / 1_000_000).toInt()
                }
            }
            extractor.release()
            0
        } catch (e: Exception) {
            0
        }
    }

    private fun hasMediaAudioPermission(): Boolean =
        context.checkSelfPermission(android.Manifest.permission.READ_MEDIA_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
}
