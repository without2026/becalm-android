package com.becalm.android.worker.ingestion

import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.ProcessingStatusRepository
import com.becalm.android.data.repository.SourceArtifactRepository
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.worker.ColdSyncWorkInputs
import com.becalm.android.worker.ProcessingPauseGate
import com.becalm.android.worker.WorkScheduler
import com.becalm.android.worker.hasExceededMaxRetries
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

/**
 * Periodic CoroutineWorker that enumerates new voice **and call** recordings since the
 * last successful run, advancing the shared [SyncCursorStore.KIND_VOICE] watermark.
 *
 * Both ingestion paths are owned by [VoiceMediaStoreProbe]; this worker only
 * orchestrates the audio permission check, dispatches to the probe's two sibling
 * methods ([VoiceMediaStoreProbe.ingestVoiceRecordings] and
 * [VoiceMediaStoreProbe.ingestCallRecordings]), and surfaces the
 * [androidx.work.ListenableWorker.Result]. The two branches scan disjoint path
 * subtrees (voice excludes `Recordings/Call/%`; call_recording is `Recordings/Call/%`
 * only) so no file is counted twice.
 *
 * The probe is constructed internally from the worker's existing collaborators so that
 * unit tests instantiating [MediaStoreWorker] directly do not need to know about the split.
 *
 * ## ING-001 / ING-003 — Voice + Call recording capture (VOI-001, VOI-005, VOI-007) ([VoiceMediaStoreProbe])
 * Reads `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI` for audio files added since the
 * last watermark. For each newly-discovered recording:
 * 1. A [RawIngestionEventEntity] row is inserted via `RawIngestionEventDao.insert` with
 *    `OnConflictStrategy.IGNORE`. `source_type` is `"voice"` for files under
 *    `Recordings/Voice Recorder/` / `Recordings/` / legacy `VoiceRecorder/`, and
 *    `"call_recording"` for files under `Recordings/Call/`. For call_recording rows,
 *    `person_ref` is the E.164 normalization of the counterparty number extracted from
 *    the DISPLAY_NAME (null when no valid phone number can be parsed — per ING-001
 *    "없으면 null"). The `clientEventId` is deterministic
 *    (deterministic UUID derived from `"mediastore:voice:<mediaId>"` or
 *    `"mediastore:call_recording:<mediaId>"`) so
 *    re-running the worker against the same file is idempotent — the DB unique index
 *    on (user_id, client_event_id) silently drops duplicates.
 * 2. If the row was freshly inserted (not a duplicate), `WorkScheduler.enqueueVoiceUpload`
 *    is called with the row's UUID and the content URI so that
 *    [com.becalm.android.worker.VoiceUploadWorker] can stream audio bytes upstream.
 *    Both source_types share the same upload pipeline (VOI-001).
 *
 * The audio content URI is built via
 * `ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, _ID)`.
 * That URI is the value stored in `sourceRef` and passed to `VoiceUploadWorker`.
 * `ContentResolver.openInputStream` on that URI returns real audio bytes (VOI-007).
 *
 * ## Cursor unit convention
 * The voice watermark is stored in **epoch milliseconds** (matching the [SyncCursorStore]
 * contract). `MediaStore.Audio.Media.DATE_ADDED` is epoch **seconds**, so the stored
 * cursor is divided by 1 000 before use in the `DATE_ADDED >= ?` predicate, and the
 * per-row `DATE_ADDED` value is multiplied by 1 000 before being compared/stored.
 *
 * ## Permissions
 * - Voice path: `READ_MEDIA_AUDIO` on API 33+ (TIRAMISU), `READ_EXTERNAL_STORAGE` on
 *   API 28–32. Missing permission records a blocked status and exits successfully; the
 *   onboarding/permission flow re-enqueues ingestion after the user grants access.
 *
 * ## PII
 * Audio file display names are logged only as their `hashCode()` in 8-char hex.
 * Counts and hashes are the only identifiers written to logcat.
 *
 * ## Scheduled by
 * SP-32 WorkScheduler registers this class as a periodic job and as an expedited
 * one-shot triggered by [com.becalm.android.worker.ContentObserverBootstrap]'s voice
 * observer (pending `refactor/worker/voice/ingestion-realign`).
 *
 * ## SAF grant contract
 * ONB-003 now requires a persisted Recordings-tree SAF URI grant before voice ingestion
 * is enabled. This worker still discovers files through MediaStore, but it refuses to
 * run unless that persisted Recordings tree grant exists. Full `DocumentsContract`
 * subtree traversal remains out of scope for the current local-owner implementation.
 */
@HiltWorker
public class MediaStoreWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncCursorStoreProvider: Provider<SyncCursorStore>,
    private val sourceStatusRepositoryProvider: Provider<SourceStatusRepository>,
    private val rawIngestionEventDaoProvider: Provider<RawIngestionEventDao>,
    private val sourceArtifactRepositoryProvider: Provider<SourceArtifactRepository>,
    private val workSchedulerProvider: Provider<WorkScheduler>,
    private val userPrefsStore: UserPrefsStore,
    private val processingStatusRepository: ProcessingStatusRepository,
    private val processingPauseGate: ProcessingPauseGate,
    private val callRecordingPersonMatcher: CallRecordingPersonMatcher,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : CoroutineWorker(appContext, workerParams) {

    public constructor(
        appContext: Context,
        workerParams: WorkerParameters,
        syncCursorStore: SyncCursorStore,
        sourceStatusRepository: SourceStatusRepository,
        rawIngestionEventDao: RawIngestionEventDao,
        sourceArtifactRepository: SourceArtifactRepository,
        workScheduler: WorkScheduler,
        userPrefsStore: UserPrefsStore,
        processingStatusRepository: ProcessingStatusRepository,
        processingPauseGate: ProcessingPauseGate,
        callRecordingPersonMatcher: CallRecordingPersonMatcher = NoOpCallRecordingPersonMatcher,
        logger: Logger,
        ioDispatcher: CoroutineDispatcher,
    ) : this(
        appContext = appContext,
        workerParams = workerParams,
        syncCursorStoreProvider = Provider { syncCursorStore },
        sourceStatusRepositoryProvider = Provider { sourceStatusRepository },
            rawIngestionEventDaoProvider = Provider { rawIngestionEventDao },
            sourceArtifactRepositoryProvider = Provider { sourceArtifactRepository },
            workSchedulerProvider = Provider { workScheduler },
        userPrefsStore = userPrefsStore,
        processingStatusRepository = processingStatusRepository,
        processingPauseGate = processingPauseGate,
        callRecordingPersonMatcher = callRecordingPersonMatcher,
        logger = logger,
        ioDispatcher = ioDispatcher,
    )

    public override suspend fun doWork(): Result = withContext(ioDispatcher) {
        if (processingPauseGate.shouldSkip(TAG)) {
            return@withContext Result.success()
        }
        if (hasExceededMaxRetries(logger, TAG, MAX_RETRIES)) return@withContext Result.failure()

        // VOI-005: READ_MEDIA_AUDIO on API 33+; READ_EXTERNAL_STORAGE on API 28-32.
        val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_AUDIO
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val audioMissing = isMissing(audioPermission)
        val lookbackDays = inputData.getInt(ColdSyncWorkInputs.KEY_LOOKBACK_DAYS, NO_LOOKBACK)
            .takeIf { it > 0 }

        val voiceEnabled = userPrefsStore.observeSourceEnabled(SourceType.VOICE).first()
        val meetingEnabled = userPrefsStore.observeSourceEnabled(SourceType.MEETING).first()

        if (audioMissing && voiceEnabled) {
            logger.w(TAG, "audio permission missing — blocked until user grants permission")
            processingStatusRepository.recordBlocked(SourceType.VOICE, "Audio permission missing")
            processingStatusRepository.recordBlocked(SourceType.CALL_RECORDING, "Audio permission missing")
        }

        val recordingsTreeUri = userPrefsStore.observeRecordingFolderTreeUri().first()
        if (recordingsTreeUri.isNullOrBlank()) {
            logger.w(TAG, "recordings tree grant missing — blocked until user grants folder access")
            if (voiceEnabled) {
                processingStatusRepository.recordBlocked(SourceType.VOICE, "Recording folder permission missing")
                processingStatusRepository.recordBlocked(SourceType.CALL_RECORDING, "Recording folder permission missing")
            }
            if (meetingEnabled) {
                processingStatusRepository.recordBlocked(SourceType.MEETING, "Recording folder permission missing")
            }
            return@withContext Result.success()
        }

        val now = Clock.System.now()
        if (voiceEnabled && !audioMissing) {
            processingStatusRepository.recordScanning(SourceType.VOICE)
            processingStatusRepository.recordScanning(SourceType.CALL_RECORDING)
        }
        if (meetingEnabled) {
            processingStatusRepository.recordScanning(SourceType.MEETING)
        }
        val voiceMediaStoreProbe = createProbe()
        val voiceInserted = if (voiceEnabled && !audioMissing) {
            voiceMediaStoreProbe.ingestVoiceRecordings(now, lookbackDays)
        } else {
            0
        }
        val callOutcome = if (voiceEnabled && !audioMissing) {
            voiceMediaStoreProbe.ingestCallRecordings(now, lookbackDays)
        } else {
            CallRecordingIngestOutcome.Success(insertedCount = 0)
        }
        val meetingAudioOutcome = if (meetingEnabled && !audioMissing) {
            voiceMediaStoreProbe.ingestMeetingAudio(now, lookbackDays)
        } else {
            MeetingIngestOutcome.Success(insertedCount = 0)
        }
        val meetingTranscriptOutcome = if (meetingEnabled) {
            createMeetingDocumentTreeProbe().ingestMeetingTranscripts(now, lookbackDays)
        } else {
            MeetingIngestOutcome.Success(insertedCount = 0)
        }

        // A ContentResolver query failure in the `Recordings/Call/` scan is recorded in
        // SourceStatusRepository but does not advance any cursor — returning
        // [Result.success] would swallow the failure and skip retry/backoff. Map to
        // [Result.retry] so WorkManager re-attempts this run (ING-001 data-loss guard).
        if (callOutcome is CallRecordingIngestOutcome.ScanFailed) {
            logger.w(TAG, "doWork call_recording scan failed — requesting retry")
            processingStatusRepository.recordError(SourceType.CALL_RECORDING, "MediaStore scan failed")
            return@withContext Result.retry()
        }
        if (meetingAudioOutcome is MeetingIngestOutcome.ScanFailed ||
            meetingTranscriptOutcome is MeetingIngestOutcome.ScanFailed
        ) {
            logger.w(TAG, "doWork meeting scan failed — requesting retry")
            processingStatusRepository.recordError(SourceType.MEETING, "Meeting scan failed")
            return@withContext Result.retry()
        }

        val callInserted = (callOutcome as CallRecordingIngestOutcome.Success).insertedCount
        val meetingInserted = (meetingAudioOutcome as MeetingIngestOutcome.Success).insertedCount +
            (meetingTranscriptOutcome as MeetingIngestOutcome.Success).insertedCount
        if (voiceEnabled && !audioMissing) {
            processingStatusRepository.recordScanResult(SourceType.VOICE, voiceInserted, "Queued upload")
            processingStatusRepository.recordScanResult(SourceType.CALL_RECORDING, callInserted, "Queued upload")
        }
        if (meetingEnabled) {
            processingStatusRepository.recordScanResult(SourceType.MEETING, meetingInserted, "Queued meeting import")
        }
        logger.d(
            TAG,
            "doWork complete voiceInserted=$voiceInserted callRecordingInserted=$callInserted " +
                "meetingInserted=$meetingInserted",
        )
        workSchedulerProvider.get().enqueuePersonInteractionIndex()
        Result.success()
    }

    /** 지정 권한이 현재 미허용 상태면 true를 반환한다. */
    private fun isMissing(perm: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, perm) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun createProbe(): VoiceMediaStoreProbe =
        VoiceMediaStoreProbe(
            appContext = appContext,
            syncCursorStore = syncCursorStoreProvider.get(),
            sourceStatusRepository = sourceStatusRepositoryProvider.get(),
            rawIngestionEventDao = rawIngestionEventDaoProvider.get(),
            workScheduler = workSchedulerProvider.get(),
            userPrefsStore = userPrefsStore,
            callRecordingPersonMatcher = callRecordingPersonMatcher,
            logger = logger,
        )

    private fun createMeetingDocumentTreeProbe(): MeetingDocumentTreeProbe =
        MeetingDocumentTreeProbe(
            appContext = appContext,
            syncCursorStore = syncCursorStoreProvider.get(),
            sourceStatusRepository = sourceStatusRepositoryProvider.get(),
            rawIngestionEventDao = rawIngestionEventDaoProvider.get(),
            sourceArtifactRepository = sourceArtifactRepositoryProvider.get(),
            userPrefsStore = userPrefsStore,
            logger = logger,
        )

    public companion object {
        private const val TAG = "MediaStoreWorker"

        /** Maximum WorkManager attempts before permanently failing (R4-02). */
        public const val MAX_RETRIES: Int = 5

        /** [SyncCursorStore] MediaStore kind key for voice recordings (`Recordings/Voice Recorder/`). */
        public const val KIND_VOICE: String = "voice"

        /**
         * [SyncCursorStore] MediaStore kind key for Samsung call recordings
         * (`Recordings/Call/`). Persisted separately from [KIND_VOICE] so that advancing
         * the voice branch's watermark cannot skip past unseen call-recording rows whose
         * `DATE_ADDED` happens to be older than the newest voice memo ingested in the
         * same worker run (ING-001 data-loss safety). The two scans read disjoint folder
         * subtrees, so an independent cursor per kind is the correctness-preserving choice.
         */
        public const val KIND_CALL_RECORDING: String = "call_recording"

        /** [SyncCursorStore] MediaStore kind key for meeting audio under `Recordings/BeCalm Meetings/Audio/`. */
        public const val KIND_MEETING: String = "meeting"

        /** [SyncCursorStore] cursor key for meeting transcripts under `Recordings/BeCalm Meetings/Transcripts/`. */
        public const val KIND_MEETING_TRANSCRIPT: String = "meeting_transcript"
        private const val NO_LOOKBACK: Int = -1

        /**
         * Samsung Voice Recorder default relative folder name (ONB-002 / ONB-003 spec
         * reference — `.spec/onboarding.spec.yml:29-33, 37-44`). Samsung One UI 6.x stores
         * voice-memo recordings under `Recordings/Voice Recorder/` with the space-separated
         * folder name; the legacy spelling without a space did NOT match real-device
         * layouts and caused ingestion to silently return zero rows.
         *
         * Used as a LIKE pattern prefix on API 29+ (`RELATIVE_PATH LIKE 'Voice Recorder/%'`)
         * or as a path segment on API 28 (`DATA LIKE '%/Voice Recorder/%'` — the embedded
         * space is safely carried through quoted selectionArgs).
         */
        public const val RECORDER_FOLDER_SAMSUNG: String = "Voice Recorder"

        /**
         * Legacy Samsung voice-recorder folder name without the space — covers older and
         * variant One UI builds that still store recordings at
         * `/storage/emulated/0/VoiceRecorder/` rather than the One UI 6.x
         * `Recordings/Voice Recorder/` nested layout. Documented at
         * `.spec/onboarding.spec.yml:33` as the fallback auto-discovery root.
         *
         * Kept as its own constant (rather than folded into [RECORDER_FOLDER_SAMSUNG]) so
         * that the probe can LIKE-match both spellings independently and neither one
         * shadows the other in future string-building.
         */
        public const val RECORDER_FOLDER_SAMSUNG_LEGACY: String = "VoiceRecorder"

        /**
         * Samsung One UI 6.x `Call/` subfolder under `Recordings/`. Maps to
         * `SourceType.CALL_RECORDING` when paths pass through
         * [VoiceMediaStoreProbe.ingestCallRecordings] (ING-001, finding PR #15).
         */
        public const val CALL_FOLDER: String = "Call"

        // NOTE: a `RECORDER_FOLDER_STOCK = "Recordings"` constant was removed because every
        // call site is now inline inside [VoiceMediaStoreProbe]'s selection SQL, which
        // carves out `Recordings/Call/%` via a NOT LIKE clause. Do NOT reintroduce a bare
        // `Recordings/%` LIKE without that guard — Samsung One UI 6.x stores call
        // recordings under `Recordings/Call/...` and a broad match would misclassify them
        // as `SourceType.VOICE`. Call-recording ingestion is owned by
        // `feat/worker/voice/call-recording` (PR #15) with the correct
        // `SourceType.CALL_RECORDING` tagging.
    }
}
