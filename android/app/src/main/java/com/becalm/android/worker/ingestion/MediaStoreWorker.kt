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
         * Samsung Voice Recorder default relative folder name (ONB-002 spec reference).
         * Used as a LIKE pattern prefix on API 29+ (`RELATIVE_PATH LIKE 'VoiceRecorder%'`)
         * or as a path segment on API 28 (`DATA LIKE '%/VoiceRecorder/%'`).
         */
        public const val RECORDER_FOLDER_SAMSUNG: String = "VoiceRecorder"

        /**
         * Stock Android voice recorder default relative folder name (AOSP / Pixel Recorder).
         * Used alongside [RECORDER_FOLDER_SAMSUNG] in the OR clause of the MediaStore query
         * to cover devices that do not use Samsung's default path.
         */
        public const val RECORDER_FOLDER_STOCK: String = "Recordings"
    }
}
