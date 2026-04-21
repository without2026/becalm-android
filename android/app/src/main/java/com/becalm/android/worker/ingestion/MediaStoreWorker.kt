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
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.worker.WorkScheduler
import com.becalm.android.worker.hasExceededMaxRetries
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

/**
 * Periodic CoroutineWorker that enumerates new voice recordings since the last
 * successful run, advancing the per-source watermark cursor in [SyncCursorStore].
 *
 * The voice ingestion path is owned by [VoiceMediaStoreProbe]; this worker only
 * orchestrates the audio permission check, dispatches to the probe, and surfaces
 * the [androidx.work.ListenableWorker.Result].
 *
 * The probe is constructed internally from the worker's existing collaborators so that
 * unit tests instantiating [MediaStoreWorker] directly do not need to know about the split.
 *
 * ## ING-003 — Voice recording capture (VOI-001, VOI-005, VOI-007) ([VoiceMediaStoreProbe])
 * Reads `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI` for audio files added since the
 * last watermark. For each newly-discovered recording:
 * 1. A [RawIngestionEventEntity] row (source_type="voice", sync_status="pending") is
 *    inserted via `RawIngestionEventDao.insert` with `OnConflictStrategy.IGNORE`.
 *    The `clientEventId` is deterministic (`"mediastore:voice:<mediaId>"`) so re-running
 *    the worker against the same file is idempotent — the DB unique index on
 *    (user_id, client_event_id) silently drops duplicates.
 * 2. If the row was freshly inserted (not a duplicate), `WorkScheduler.enqueueVoiceUpload`
 *    is called with the row's UUID and the content URI so that
 *    [com.becalm.android.worker.VoiceUploadWorker] can stream audio bytes upstream.
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
 *   API 28–32. Missing permission causes [Result.retry] so WorkManager re-attempts once
 *   the onboarding flow (ONB-003) has granted it.
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
 * ## Known gap (follow-up)
 * The full SAF tree URI migration required by `.spec/onboarding.spec.yml:37-44` (ONB-003)
 * — `ACTION_OPEN_DOCUMENT_TREE` + `takePersistableUriPermission` + `DocumentsContract`
 * child traversal rooted at Samsung One UI 6.x `/storage/emulated/0/Recordings/` — is
 * deferred to a Wave 6 onboarding PR tracked by `refactor/ui/onboarding/saf-tree`. See
 * `docs/plans/worker-voice-ingestion-realign.md` (finding PR #14) for the full design and
 * `docs/plans/worker-voice-ingestion-saf-tree-followup.md` for the deferred sub-scope.
 * This PR only realigns the recorder-folder LIKE patterns to Samsung One UI 6.x's
 * space-separated `"Voice Recorder/"` and declares the `Call/` constant for the follow-up
 * `feat/worker/voice/call-recording` (finding PR #15) to wire through.
 */
@HiltWorker
public class MediaStoreWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    syncCursorStore: SyncCursorStore,
    sourceStatusRepository: SourceStatusRepository,
    rawIngestionEventDao: RawIngestionEventDao,
    workScheduler: WorkScheduler,
    userPrefsStore: UserPrefsStore,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : CoroutineWorker(appContext, workerParams) {

    private val voiceMediaStoreProbe: VoiceMediaStoreProbe = VoiceMediaStoreProbe(
        appContext = appContext,
        syncCursorStore = syncCursorStore,
        sourceStatusRepository = sourceStatusRepository,
        rawIngestionEventDao = rawIngestionEventDao,
        workScheduler = workScheduler,
        userPrefsStore = userPrefsStore,
        logger = logger,
    )

    public override suspend fun doWork(): Result = withContext(ioDispatcher) {
        if (hasExceededMaxRetries(logger, TAG, MAX_RETRIES)) return@withContext Result.failure()

        // VOI-005: READ_MEDIA_AUDIO on API 33+; READ_EXTERNAL_STORAGE on API 28-32.
        val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_AUDIO
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val audioMissing = isMissing(audioPermission)

        if (audioMissing) {
            logger.w(TAG, "audio permission missing — retrying")
            return@withContext Result.retry()
        }

        val now = Clock.System.now()
        val voiceResult = voiceMediaStoreProbe.ingestVoiceRecordings(now)

        logger.d(TAG, "doWork complete voiceInserted=$voiceResult")
        Result.success()
    }

    /** 지정 권한이 현재 미허용 상태면 true를 반환한다. */
    private fun isMissing(perm: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, perm) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED

    public companion object {
        private const val TAG = "MediaStoreWorker"

        /** Maximum WorkManager attempts before permanently failing (R4-02). */
        public const val MAX_RETRIES: Int = 5

        /** [SyncCursorStore] MediaStore kind key for voice recordings. */
        public const val KIND_VOICE: String = "voice"

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
         * `SourceType.CALL_RECORDING` when paths pass through the ingest probe. Wired up by
         * `feat/worker/voice/call-recording` (finding PR #15) — this PR declares the
         * constant only.
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
