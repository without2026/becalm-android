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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

// spec: VOI-001 — STT Worker: on-device STT for Samsung Voice Recorder files
// spec: VOI-004 — READ_MEDIA_AUDIO permission check
// spec: VOI-005 — chunk audio > 60s into segments before STT
// Invariant: Transcript is NEVER uploaded to Railway or Supabase

@HiltWorker
class SttWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val rawIngestionEventDao: RawIngestionEventDao,
    private val transcriptDao: TranscriptDao
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val KEY_RAW_INGESTION_ID = "raw_ingestion_id"
        const val KEY_FILE_URI = "file_uri"
        // spec: VOI-005 — chunk threshold
        private const val CHUNK_THRESHOLD_SECONDS = 60
        private const val CHUNK_SIZE_SECONDS = 30
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
            transcribeFull(fileUri)
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

    // spec: VOI-005 — merge chunked transcripts
    private suspend fun transcribeInChunks(fileUri: Uri, durationSeconds: Int): String? {
        // On Android, SpeechRecognizer does not support arbitrary file positions.
        // Chunking is implemented by Android MediaExtractor to slice the audio,
        // then running STT on each slice. MVP uses Android SpeechRecognizer.
        // Full chunked implementation requires platform-specific audio splitting.
        // For MVP: attempt full transcription; fallback to chunked if OOM.
        return transcribeFull(fileUri)
    }

    // Transcribe full audio using Android SpeechRecognizer offline mode
    private suspend fun transcribeFull(fileUri: Uri): String? {
        // spec: VOI-001 — use Android SpeechRecognizer in offline mode
        // Samsung devices have SpeechRecognizer.isRecognitionAvailable() == true
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            // Fall back: no STT available — transcript will be empty
            // spec: VOI-003 — if STT unavailable, raw_ingestion_event still flows to Railway
            return null
        }

        // SpeechRecognizer is UI-thread bound; for background STT we use
        // Android's RecognitionService intent with file URI input.
        // This is a skeleton — full implementation requires platform-specific
        // intent construction with RecognizerIntent.EXTRA_AUDIO_SOURCE.
        // MVP delegates to a suspending wrapper.
        return runSpeechRecognizer(fileUri)
    }

    private suspend fun runSpeechRecognizer(fileUri: Uri): String? {
        // Placeholder: actual SpeechRecognizer invocation requires UI thread + Activity context.
        // Background STT in WorkManager context uses RecognitionService bound connection.
        // Full implementation: SP-10 spec VOI-001 wires this to Android SpeechRecognizer
        // or Samsung AICore (Gemini Nano) depending on device capability (VOI-002).
        // For scaffold: return null (worker fails gracefully; event preserved for Railway upload).
        return null
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
