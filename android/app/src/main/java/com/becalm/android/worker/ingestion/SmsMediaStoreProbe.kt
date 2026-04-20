package com.becalm.android.worker.ingestion

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import com.becalm.android.core.util.Logger
import com.becalm.android.core.util.redact
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.repository.SourceStatusRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Instant

/**
 * SMS path of [MediaStoreWorker].
 *
 * Owns ING-002: enumerates new SMS rows since the stored watermark, advances the
 * `KIND_SMS` cursor, and reports source status. SMS is not a wire
 * [com.becalm.android.data.remote.dto.SourceType], so no [com.becalm.android.data.local.db.entity.RawIngestionEventEntity]
 * rows are written here — only a count and a source-status update.
 *
 * Behaviour is byte-identical with the original `MediaStoreWorker.ingestSms` body and is
 * exercised solely through [MediaStoreWorker.doWork] tests; no public surface change.
 *
 * Constructed by [MediaStoreWorker] from its own collaborators rather than via Hilt
 * `@Inject`, so unit tests do not need to know about the split.
 */
internal class SmsMediaStoreProbe(
    private val appContext: Context,
    private val syncCursorStore: SyncCursorStore,
    private val sourceStatusRepository: SourceStatusRepository,
    private val logger: Logger,
) {

    /**
     * Queries SMS inbox and sent boxes for rows newer than the stored watermark.
     * Records only a count and source-status update — no raw event rows are inserted
     * because "sms" is not a valid [com.becalm.android.data.remote.dto.SourceType].
     *
     * @return Count of new SMS rows observed.
     */
    suspend fun ingestSms(now: Instant): Int {
        val lastSeenMs = syncCursorStore.observeMediaStoreLastSeen(MediaStoreWorker.KIND_SMS).first() ?: 0L

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
                sourceStatusRepository.recordSyncError(MediaStoreWorker.SOURCE_SMS_MMS, e.message ?: "query failed", now)
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
            syncCursorStore.setMediaStoreLastSeen(MediaStoreWorker.KIND_SMS, maxDateMs)
            logger.d(TAG, "sms cursor advanced from=$lastSeenMs to=$maxDateMs count=$count")
        }

        sourceStatusRepository.recordSyncSuccess(MediaStoreWorker.SOURCE_SMS_MMS, now)
        logger.d(TAG, "ING-002 sms observed count=$count")
        return count
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

    private companion object {
        private const val TAG = "MediaStoreWorker"
    }
}
