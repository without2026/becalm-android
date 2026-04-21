package com.becalm.android.worker.ingestion

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.becalm.android.core.util.Logger
import com.becalm.android.core.util.redact
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.worker.WorkScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Instant
import java.util.UUID

/**
 * Voice-recording path of [MediaStoreWorker] (ING-003 / VOI-001 / VOI-004 / VOI-005 / VOI-007).
 *
 * Reads `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI` for newly-added recordings, inserts
 * deduped [RawIngestionEventEntity] rows (`source_type="voice"`), and enqueues per-row uploads
 * via [WorkScheduler.enqueueVoiceUpload]. The cursor is advanced only when every row in the
 * batch succeeded, so failed rows are re-discovered (the `>=` predicate combined with the
 * deterministic `clientEventId` dedup absorbs the one-second overlap).
 *
 * Per `.spec/cold-sync.spec.yml:49`, the inserted `sync_status` is gated by the user's PIPA
 * third-party provision consent at insertion time: `"pending"` when consented, else
 * `"awaiting_consent"`. The consent value is snapshotted once per batch via
 * [UserPrefsStore.observeThirdPartyProvisionConsent] `.first()` so the batch is race-free
 * against mid-batch Settings toggles; consent transitions are handled elsewhere by
 * `RawIngestionEventDao.releaseAwaitingConsentToPending` (OFF→ON) and
 * `RawIngestionRepository.parkVoicePendingAsAwaitingConsent` (ON→OFF).
 *
 * Behaviour is byte-identical with the original `MediaStoreWorker.ingestVoiceRecordings`
 * body and is exercised solely through [MediaStoreWorker.doWork] tests.
 *
 * Constructed by [MediaStoreWorker] from its own collaborators rather than via Hilt
 * `@Inject`, so unit tests do not need to know about the split.
 */
internal class VoiceMediaStoreProbe(
    private val appContext: Context,
    private val syncCursorStore: SyncCursorStore,
    private val sourceStatusRepository: SourceStatusRepository,
    private val rawIngestionEventDao: RawIngestionEventDao,
    private val workScheduler: WorkScheduler,
    private val userPrefsStore: UserPrefsStore,
    private val logger: Logger,
) {

    /**
     * Queries `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI` for audio files added since
     * the stored watermark. For each discovered file:
     * - Inserts a [RawIngestionEventEntity] (source_type="voice"). `sync_status` is
     *   `"pending"` when the user has granted PIPA third-party provision consent, else
     *   `"awaiting_consent"` (cold-sync.spec:49 / VOI-004). The consent is snapshotted
     *   once per batch so mid-batch toggles do not split rows across states.
     *   `clientEventId = "mediastore:voice:<mediaId>"` is deterministic, so re-running on
     *   the same file is idempotent (the DB UNIQUE index on (user_id, client_event_id)
     *   drops duplicates via [androidx.room.OnConflictStrategy.IGNORE]).
     * - Enqueues [WorkScheduler.enqueueVoiceUpload] only when the row was freshly inserted
     *   (insert return value != -1L), preventing double-enqueue on retry runs.
     *
     * See [MediaStoreWorker]'s class KDoc for the full recorder-folder filter / cursor /
     * permission contract — the body here is byte-identical with the original implementation.
     *
     * @return Count of [RawIngestionEventEntity] rows freshly inserted this run.
     */
    suspend fun ingestVoiceRecordings(now: Instant): Int {
        // userId is required: skip rather than insert orphan rows
        val userId = userPrefsStore.observeCurrentUserId().first()
        if (userId == null) {
            logger.w(TAG, "userId null — skipping voice ingestion this cycle")
            return 0
        }

        // Snapshot PIPA third-party provision consent ONCE per batch. cold-sync.spec:49
        // requires insertion-time gating: false → "awaiting_consent", true → "pending".
        // Using .first() (single read) keeps the batch race-free against Settings toggles
        // that may fire mid-scan; OFF→ON and ON→OFF transitions are handled by the DAO /
        // Repository park & release paths outside this probe.
        val pipaConsented = userPrefsStore.observeThirdPartyProvisionConsent().first()

        // Cursor stored in ms; DATE_ADDED is in seconds
        val lastSeenMs = syncCursorStore.observeMediaStoreLastSeen(MediaStoreWorker.KIND_VOICE).first() ?: 0L
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
            Pair("${MediaStoreWorker.RECORDER_FOLDER_SAMSUNG}/%", "${MediaStoreWorker.RECORDER_FOLDER_STOCK}/%")
        } else {
            // DATA is the full absolute path; match either known recorder directory
            Pair("%/${MediaStoreWorker.RECORDER_FOLDER_SAMSUNG}/%", "%/${MediaStoreWorker.RECORDER_FOLDER_STOCK}/%")
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

                when (val insertResult = insertVoiceRow(row, userId, pipaConsented)) {
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
                    is VoiceInsertResult.Fresh -> {
                        val rowMs = row.dateAddedSec * 1_000L
                        if (rowMs > maxDateAddedMs) maxDateAddedMs = rowMs

                        insertedCount++
                        if (!enqueueVoice(insertResult.id, row.audioUri, wasFresh = true)) {
                            hasInsertFailure = true
                        }
                    }
                    is VoiceInsertResult.Dedup -> {
                        val rowMs = row.dateAddedSec * 1_000L
                        if (rowMs > maxDateAddedMs) maxDateAddedMs = rowMs

                        if (!enqueueVoice(insertResult.id, row.audioUri, wasFresh = false)) {
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
            syncCursorStore.setMediaStoreLastSeen(MediaStoreWorker.KIND_VOICE, maxDateAddedMs)
            logger.d(
                TAG,
                "voice cursor advanced from=${lastSeenMs}ms to=${maxDateAddedMs}ms inserted=$insertedCount",
            )
        }

        sourceStatusRepository.recordSyncSuccess(SourceType.VOICE, now)
        logger.d(TAG, "ING-003 voice recordings inserted=$insertedCount")
        return insertedCount
    }

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
     *
     * [pipaConsented] 는 배치 전체에 대해 [ingestVoiceRecordings] 가 1회 스냅샷한 값이며,
     * cold-sync.spec:49 에 따라 insertion 시점의 sync_status 를 결정한다.
     */
    private suspend fun insertVoiceRow(
        row: VoiceRow,
        userId: String,
        pipaConsented: Boolean,
    ): VoiceInsertResult {
        val entity = RawIngestionEventEntity(
            id = UUID.randomUUID().toString(),
            userId = userId,
            clientEventId = row.clientEventId,
            sourceType = SourceType.VOICE,
            sourceRef = row.audioUri,
            durationSeconds = row.durationSec,
            timestamp = Instant.fromEpochSeconds(row.dateAddedSec),
            syncStatus = if (pipaConsented) "pending" else "awaiting_consent",
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

    private companion object {
        private const val TAG = "MediaStoreWorker"
    }
}
