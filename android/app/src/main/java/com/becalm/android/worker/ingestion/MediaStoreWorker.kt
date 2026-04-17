package com.becalm.android.worker.ingestion

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Telephony
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.becalm.android.core.util.Logger
import com.becalm.android.core.util.redact
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.worker.WorkScheduler
import com.becalm.android.worker.hasExceededMaxRetries
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.util.UUID

/**
 * Periodic CoroutineWorker that enumerates new SMS messages and voice recordings since
 * the last successful run, advancing per-source watermark cursors in [SyncCursorStore].
 *
 * ## ING-002 — SMS capture
 * Reads `Telephony.Sms.CONTENT_URI` (inbox + sent boxes). SMS is NOT a valid
 * [com.becalm.android.data.remote.dto.SourceType], so no [RawIngestionEventEntity]
 * rows are inserted. Instead the worker records a count observation and advances
 * the "sms" watermark via [SyncCursorStore.setMediaStoreLastSeen].
 * Source status is updated through [SourceStatusRepository.recordSyncSuccess].
 *
 * ## ING-003 — Voice recording capture (VOI-001, VOI-005, VOI-007)
 * Reads `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI` for audio files added since the
 * last watermark. For each newly-discovered recording:
 * 1. A [RawIngestionEventEntity] row (source_type="voice", sync_status="pending") is
 *    inserted via [RawIngestionEventDao.insert] with [OnConflictStrategy.IGNORE].
 *    The `clientEventId` is deterministic (`"mediastore:voice:<mediaId>"`) so re-running
 *    the worker against the same file is idempotent — the DB unique index on
 *    (user_id, client_event_id) silently drops duplicates.
 * 2. If the row was freshly inserted (not a duplicate), [WorkScheduler.enqueueVoiceUpload]
 *    is called with the row's UUID and the content URI so that [VoiceUploadWorker] can
 *    stream audio bytes upstream.
 *
 * The audio content URI is built via
 * `ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, _ID)`.
 * That URI is the value stored in `sourceRef` and passed to [VoiceUploadWorker].
 * `ContentResolver.openInputStream` on that URI returns real audio bytes (VOI-007).
 *
 * ## Cursor unit convention
 * The voice watermark is stored in **epoch milliseconds** (matching the SMS cursor and
 * [SyncCursorStore] contract). `MediaStore.Audio.Media.DATE_ADDED` is epoch **seconds**,
 * so the stored cursor is divided by 1 000 before use in the `DATE_ADDED >= ?` predicate,
 * and the per-row `DATE_ADDED` value is multiplied by 1 000 before being compared/stored.
 *
 * ## Permissions
 * - SMS path: `READ_SMS`
 * - Voice path: `READ_MEDIA_AUDIO` on API 33+ (TIRAMISU), `READ_EXTERNAL_STORAGE` on
 *   API 28–32. Missing permission causes [Result.retry] so WorkManager re-attempts once
 *   the onboarding flow (ONB-003) has granted it.
 *
 * ## PII
 * Raw phone numbers, addresses, and SMS body text are never logged.
 * Audio file display names are logged only as their `hashCode()` in 8-char hex.
 * Counts and hashes are the only identifiers written to logcat.
 *
 * ## Scheduled by
 * SP-32 WorkScheduler registers this class as a periodic job and as an expedited
 * one-shot triggered by [ContentObserverSms.onChange].
 */
@HiltWorker
public class MediaStoreWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncCursorStore: SyncCursorStore,
    private val sourceStatusRepository: SourceStatusRepository,
    private val rawIngestionEventDao: RawIngestionEventDao,
    private val workScheduler: WorkScheduler,
    private val userPrefsStore: UserPrefsStore,
    private val logger: Logger,
) : CoroutineWorker(appContext, workerParams) {

    public override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (hasExceededMaxRetries(logger, TAG, MAX_RETRIES)) return@withContext Result.failure()

        val smsMissing = isMissing(android.Manifest.permission.READ_SMS)

        // VOI-005: READ_MEDIA_AUDIO on API 33+; READ_EXTERNAL_STORAGE on API 28-32.
        val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_AUDIO
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val audioMissing = isMissing(audioPermission)

        if (smsMissing && audioMissing) {
            logger.w(TAG, "both permissions missing — retrying")
            return@withContext Result.retry()
        }

        val now = Clock.System.now()
        val smsResult = if (!smsMissing) ingestSms(now) else 0
        val voiceResult = if (!audioMissing) ingestVoiceRecordings(now) else 0

        logger.d(
            TAG,
            "doWork complete smsCount=$smsResult voiceInserted=${voiceResult}",
        )
        Result.success()
    }

    // ── SMS ───────────────────────────────────────────────────────────────────

    /**
     * Queries SMS inbox and sent boxes for rows newer than the stored watermark.
     * Records only a count and source-status update — no raw event rows are inserted
     * because "sms" is not a valid [com.becalm.android.data.remote.dto.SourceType].
     *
     * @return Count of new SMS rows observed.
     */
    private suspend fun ingestSms(now: Instant): Int {
        val lastSeenMs = syncCursorStore.observeMediaStoreLastSeen(KIND_SMS).first() ?: 0L

        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.DATE,
            Telephony.Sms.ADDRESS,
        )
        val selection = "${Telephony.Sms.DATE} > ?"
        val selectionArgs = arrayOf(lastSeenMs.toString())
        val sortOrder = "${Telephony.Sms.DATE} ASC"

        var count = 0
        var maxDateMs = lastSeenMs

        val scanned = queryMediaStore(
            uri = Telephony.Sms.CONTENT_URI,
            projection = projection,
            selection = selection,
            selectionArgs = selectionArgs,
            sortOrder = sortOrder,
            onError = { e ->
                logger.e(TAG, "SMS query failed", e)
                sourceStatusRepository.recordSyncError(SOURCE_SMS_MMS, e.message ?: "query failed", now)
            },
        ) { cursor ->
            val idxDate = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val idxAddress = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)

            while (cursor.moveToNext()) {
                val dateMs = cursor.getLong(idxDate)
                val rawAddress = cursor.getString(idxAddress) ?: ""
                // PII guard: log only the hashed address, never the raw value
                logger.d(TAG, "sms row hash=${redact(rawAddress)} dateMs=$dateMs")

                count++
                if (dateMs > maxDateMs) maxDateMs = dateMs
            }
        }
        if (!scanned) return 0

        if (count > 0) {
            syncCursorStore.setMediaStoreLastSeen(KIND_SMS, maxDateMs)
            logger.d(TAG, "sms cursor advanced from=$lastSeenMs to=$maxDateMs count=$count")
        }

        sourceStatusRepository.recordSyncSuccess(SOURCE_SMS_MMS, now)
        logger.d(TAG, "ING-002 sms observed count=$count")
        return count
    }

    // ── Voice recordings ──────────────────────────────────────────────────────

    /**
     * Queries `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI` for audio files added since
     * the stored watermark. For each discovered file:
     * - Inserts a [RawIngestionEventEntity] (source_type="voice", sync_status="pending").
     *   `clientEventId = "mediastore:voice:<mediaId>"` is deterministic, so re-running on
     *   the same file is idempotent (the DB UNIQUE index on (user_id, client_event_id)
     *   drops duplicates via [OnConflictStrategy.IGNORE]).
     * - Enqueues [WorkScheduler.enqueueVoiceUpload] only when the row was freshly inserted
     *   (insert return value != -1L), preventing double-enqueue on retry runs.
     *
     * ## Recorder-folder filter (privacy boundary)
     * Only files whose path begins with a known recorder folder are ingested. On API 29+
     * this is enforced via `RELATIVE_PATH LIKE ?` matching "VoiceRecorder%" (Samsung Voice
     * Recorder default, ONB-002) or "Recordings%" (stock Android recorder). On API 28
     * the deprecated `DATA` column is used with equivalent `%/VoiceRecorder/%` and
     * `%/Recordings/%` patterns. This prevents music, podcast, and messenger audio files
     * from being captured as voice events.
     *
     * ## Cursor semantics
     * The watermark is stored in **epoch milliseconds**; DATE_ADDED is epoch seconds, so
     * `lastSeenMs / 1_000` is used in the query predicate and `dateAddedSec * 1_000` in
     * the max-cursor tracking. The predicate uses `>=` (not `>`) to avoid permanently
     * skipping siblings that share the same DATE_ADDED second when a mid-batch failure
     * occurs. Duplicate rows are handled by the deterministic clientEventId dedup via
     * [OnConflictStrategy.IGNORE], so the one-second overlap costs only one extra query
     * overlap per run.
     *
     * The cursor is advanced to `maxDateAddedMs` only after the full loop completes and only
     * for successfully processed rows — failed rows are never counted, ensuring they are
     * retried on the next run.
     *
     * If no signed-in userId is available, voice ingestion is skipped for this cycle and
     * the cursor is NOT advanced (recordings will be retried on the next run).
     *
     * @return Count of [RawIngestionEventEntity] rows freshly inserted this run.
     */
    private suspend fun ingestVoiceRecordings(now: Instant): Int {
        // userId is required: skip rather than insert orphan rows
        val userId = userPrefsStore.observeCurrentUserId().first()
        if (userId == null) {
            logger.w(TAG, "userId null — skipping voice ingestion this cycle")
            return 0
        }

        // Cursor stored in ms; DATE_ADDED is in seconds
        val lastSeenMs = syncCursorStore.observeMediaStoreLastSeen(KIND_VOICE).first() ?: 0L
        val lastSeenSec = lastSeenMs / 1_000L

        // Build folder-filter predicate and projection.
        // API 29+ has RELATIVE_PATH; API 28 uses the deprecated DATA column instead.
        val folderColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.RELATIVE_PATH
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Audio.Media.DATA
        }
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DISPLAY_NAME,
            folderColumn,
        )
        // Use >= so siblings sharing the same DATE_ADDED second are not permanently skipped
        // on a mid-batch failure. clientEventId dedup absorbs the one-second overlap.
        val (folderArg1, folderArg2) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // RELATIVE_PATH ends with "/" (e.g. "VoiceRecorder/"); trailing slash prevents matching siblings
            Pair("${RECORDER_FOLDER_SAMSUNG}/%", "${RECORDER_FOLDER_STOCK}/%")
        } else {
            // DATA is the full absolute path; match either known recorder directory
            Pair("%/${RECORDER_FOLDER_SAMSUNG}/%", "%/${RECORDER_FOLDER_STOCK}/%")
        }
        val selection = "${MediaStore.Audio.Media.DATE_ADDED} >= ? AND " +
            "($folderColumn LIKE ? OR $folderColumn LIKE ?)"
        val selectionArgs = arrayOf(lastSeenSec.toString(), folderArg1, folderArg2)
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} ASC"

        var insertedCount = 0
        var hasInsertFailure = false
        // Track the highest DATE_ADDED (in ms) across successfully processed rows only
        var maxDateAddedMs = lastSeenMs

        val scanned = queryMediaStore(
            uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection = projection,
            selection = selection,
            selectionArgs = selectionArgs,
            sortOrder = sortOrder,
            onError = { e ->
                logger.e(TAG, "voice MediaStore query failed", e)
                sourceStatusRepository.recordSyncError(SourceType.VOICE, e.message ?: "query failed", now)
            },
        ) { cursor ->
            val idxId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val idxDateAdded = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val idxDuration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val idxDisplayName = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val row = readVoiceRow(cursor, idxId, idxDateAdded, idxDuration, idxDisplayName)

                // PII guard: log only a hash of the file name, never the raw path
                logger.d(
                    TAG,
                    "voice row nameHash=${redact(row.displayName)} durationSec=${row.durationSec} dateAddedSec=${row.dateAddedSec}",
                )

                when (val insertResult = insertVoiceRow(row, userId)) {
                    VoiceInsertResult.Failed -> {
                        hasInsertFailure = true
                        // Per-row failure: continue so one bad row doesn't abort the batch.
                        // Do NOT advance maxDateAddedMs here — the failed row must be retried on the
                        // next run. The >= predicate combined with clientEventId dedup ensures it is
                        // re-discovered without double-inserting already-processed siblings.
                        continue
                    }
                    VoiceInsertResult.DedupSkip -> {
                        // Row not found, already extracted, or not in pending state — skip without
                        // advancing the cursor.
                        continue
                    }
                    is VoiceInsertResult.Fresh, is VoiceInsertResult.Dedup -> {
                        val rowMs = row.dateAddedSec * 1_000L
                        if (rowMs > maxDateAddedMs) maxDateAddedMs = rowMs

                        val enqueueId = when (insertResult) {
                            is VoiceInsertResult.Fresh -> {
                                insertedCount++
                                insertResult.id
                            }
                            is VoiceInsertResult.Dedup -> insertResult.id
                            else -> error("unreachable")
                        }
                        val wasFresh = insertResult is VoiceInsertResult.Fresh
                        if (!enqueueVoice(enqueueId, row.audioUri, wasFresh)) {
                            hasInsertFailure = true
                        }
                    }
                }
            }
        }
        if (!scanned) return 0

        // Only advance cursor when every row succeeded. If any insert failed, freeze the
        // cursor so the failed row is re-discovered on the next run (>= predicate + dedup).
        if (!hasInsertFailure && maxDateAddedMs > lastSeenMs) {
            syncCursorStore.setMediaStoreLastSeen(KIND_VOICE, maxDateAddedMs)
            logger.d(
                TAG,
                "voice cursor advanced from=${lastSeenMs}ms to=${maxDateAddedMs}ms inserted=$insertedCount",
            )
        }

        sourceStatusRepository.recordSyncSuccess(SourceType.VOICE, now)
        logger.d(TAG, "ING-003 voice recordings inserted=$insertedCount")
        return insertedCount
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * MediaStore cursor 스캔 스캐폴드를 한 군데로 모은 헬퍼.
     * `CancellationException`은 그대로 rethrow하여 WorkManager 취소 경로를 보존한다.
     * `.use { }` 자원 해제 동작과 null-cursor 시 no-op 처리 또한 원본과 동일하다.
     * 예외 발생 시 [onError]를 호출하고 `false`를 반환해 호출부가 early-return 하도록 한다.
     */
    private inline fun queryMediaStore(
        uri: Uri,
        projection: Array<String>,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
        onError: (Exception) -> Unit,
        onCursor: (Cursor) -> Unit,
    ): Boolean {
        try {
            appContext.contentResolver.query(
                uri,
                projection,
                selection,
                selectionArgs,
                sortOrder,
            )?.use { cursor -> onCursor(cursor) }
        } catch (e: Exception) {
            // Rethrow CancellationException so WorkManager can properly cancel the worker;
            // swallowing it would cause the coroutine to continue after cancellation.
            if (e is CancellationException) throw e
            onError(e)
            return false
        }
        return true
    }

    /** 지정 권한이 현재 미허용 상태면 true를 반환한다. */
    private fun isMissing(perm: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, perm) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED

    // ── Voice-row processing helpers (Round 5) ────────────────────────────────

    /**
     * ingestVoiceRecordings 루프에서 커서 한 행을 읽어 필요한 필드를 추출한 결과다.
     * MediaStore.Audio.Media 커서에 의존하지 않는 순수 데이터 컨테이너로,
     * 이후 단계(insert/enqueue)는 [Cursor] 없이 이 값만으로 동작한다.
     */
    private data class VoiceRow(
        val mediaId: Long,
        val dateAddedSec: Long,
        val durationSec: Int,
        val displayName: String,
        val audioUri: String,
        val clientEventId: String,
    )

    /**
     * insertVoiceRow 결과를 판별한 sealed 타입.
     * - [Fresh]     : DAO insert 성공, 신규 row. insertedCount 증가 대상.
     * - [Dedup]     : UNIQUE index 충돌로 기존 row 재사용 (cursor 진행).
     * - [DedupSkip] : 기존 row가 재업로드 부적격 상태 — cursor 진행 금지.
     * - [Failed]    : DAO insert 중 예외 — cursor 진행 금지 + hasInsertFailure=true.
     */
    private sealed interface VoiceInsertResult {
        data class Fresh(val id: String) : VoiceInsertResult
        data class Dedup(val id: String) : VoiceInsertResult
        data object DedupSkip : VoiceInsertResult
        data object Failed : VoiceInsertResult
    }

    /**
     * 커서에서 한 행의 컬럼을 읽어 [VoiceRow]로 반환한다.
     *
     * - DURATION은 MediaStore 규약에 따라 밀리초이므로 정수 초로 변환한다.
     * - 표시 이름이 null이면 빈 문자열로 표준화한다 (원본과 동일 — PII hash 계산 안정성 보존).
     * - audioUri / clientEventId는 [ingestVoiceRecordings]의 원본 생성 식과 byte-identical 하다.
     */
    private fun readVoiceRow(
        cursor: Cursor,
        idxId: Int,
        idxDateAdded: Int,
        idxDuration: Int,
        idxDisplayName: Int,
    ): VoiceRow {
        val mediaId = cursor.getLong(idxId)
        val dateAddedSec = cursor.getLong(idxDateAdded)
        val durationMs = cursor.getLong(idxDuration)
        val durationSec = (durationMs / 1_000L).toInt()
        val displayName = cursor.getString(idxDisplayName) ?: ""
        val audioUri = ContentUris.withAppendedId(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            mediaId,
        ).toString()
        return VoiceRow(
            mediaId = mediaId,
            dateAddedSec = dateAddedSec,
            durationSec = durationSec,
            displayName = displayName,
            audioUri = audioUri,
            // Deterministic idempotency key: same mediaId always yields same clientEventId
            clientEventId = "mediastore:voice:$mediaId",
        )
    }

    /**
     * DAO insert를 시도하고 결과를 [VoiceInsertResult]로 분류한다.
     *
     * - insert 성공(rowId != -1L)  → [VoiceInsertResult.Fresh]
     * - UNIQUE 충돌(rowId == -1L) → 기존 row를 조회해 업로드 재개 가능 여부 판정
     *     - 재개 가능 (pending & extracted=0) → [VoiceInsertResult.Dedup]
     *     - 재개 불가 (없거나 상태 불일치)     → [VoiceInsertResult.DedupSkip]
     * - 예외 (CancellationException 제외) → 로그 남기고 [VoiceInsertResult.Failed]
     *
     * 에러 로그 문자열("DAO insert failed mediaId=$mediaId nameHash=${redact(displayName)}")은
     * 원본 ingestVoiceRecordings 구현과 완전히 동일하게 유지한다.
     */
    private suspend fun insertVoiceRow(row: VoiceRow, userId: String): VoiceInsertResult {
        val entity = RawIngestionEventEntity(
            id = UUID.randomUUID().toString(),
            userId = userId,
            clientEventId = row.clientEventId,
            sourceType = SourceType.VOICE,
            sourceRef = row.audioUri,
            durationSeconds = row.durationSec,
            timestamp = Instant.fromEpochSeconds(row.dateAddedSec),
            syncStatus = "pending",
        )

        val rowId = try {
            rawIngestionEventDao.insert(entity)
        } catch (e: Exception) {
            // Rethrow CancellationException so WorkManager can properly cancel.
            if (e is CancellationException) throw e
            logger.e(TAG, "DAO insert failed mediaId=${row.mediaId} nameHash=${redact(row.displayName)}", e)
            return VoiceInsertResult.Failed
        }

        if (rowId != -1L) return VoiceInsertResult.Fresh(entity.id)

        // UNIQUE(user_id, client_event_id) 충돌 — 기존 row 조회 후 재업로드 가능 여부 판정.
        val existing = rawIngestionEventDao.findByClientEventId(userId, row.clientEventId)
        return if (existing == null ||
            existing.syncStatus != "pending" ||
            existing.commitmentsExtractedCount > 0
        ) {
            VoiceInsertResult.DedupSkip
        } else {
            VoiceInsertResult.Dedup(existing.id)
        }
    }

    /**
     * [WorkScheduler.enqueueVoiceUpload] 호출을 감싸고 성공/실패를 로그한다.
     *
     * @return 성공이면 true, WorkScheduler 호출 중 예외가 발생하면 false — 호출부가
     * `hasInsertFailure = true` 로 cursor persist를 억제한다.
     *
     * 성공 로그("voice enqueued id=... fresh=...")와 실패 로그("enqueueVoiceUpload failed id=...")는
     * 원본 구현과 byte-identical 하게 유지한다.
     */
    private fun enqueueVoice(enqueueId: String, audioUri: String, wasFresh: Boolean): Boolean {
        return try {
            workScheduler.enqueueVoiceUpload(enqueueId, audioUri)
            logger.d(TAG, "voice enqueued id=${redact(enqueueId)} fresh=$wasFresh")
            true
        } catch (e: Exception) {
            // Rethrow CancellationException so WorkManager can properly cancel.
            if (e is CancellationException) throw e
            logger.e(TAG, "enqueueVoiceUpload failed id=${redact(enqueueId)}", e)
            false
        }
    }

    public companion object {
        private const val TAG = "MediaStoreWorker"

        /** Maximum WorkManager attempts before permanently failing (R4-02). */
        public const val MAX_RETRIES: Int = 5

        /** [SyncCursorStore] MediaStore kind key for SMS. */
        public const val KIND_SMS: String = "sms"

        /** [SyncCursorStore] MediaStore kind key for voice recordings. */
        public const val KIND_VOICE: String = "voice"

        /**
         * Source identifier used with [SourceStatusRepository] for SMS/MMS.
         * Not a [com.becalm.android.data.remote.dto.SourceType] wire value.
         */
        public const val SOURCE_SMS_MMS: String = "sms_mms"

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
