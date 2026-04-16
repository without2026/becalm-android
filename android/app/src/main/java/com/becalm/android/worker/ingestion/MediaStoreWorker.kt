package com.becalm.android.worker.ingestion

import android.content.Context
import android.provider.CallLog
import android.provider.Telephony
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.repository.SourceStatusRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Periodic CoroutineWorker that enumerates new SMS messages and call log entries since
 * the last successful run, advancing per-source watermark cursors in [SyncCursorStore].
 *
 * ## ING-002 — SMS capture
 * Reads `Telephony.Sms.CONTENT_URI` (inbox + sent boxes). SMS is NOT a valid
 * [com.becalm.android.data.remote.dto.SourceType], so no [com.becalm.android.data.local.db.entity.RawIngestionEventEntity]
 * rows are inserted. Instead the worker records a count observation and advances
 * the "sms" watermark via [SyncCursorStore.setMediaStoreLastSeen].
 * Source status is updated through [SourceStatusRepository.recordSyncSuccess].
 *
 * ## ING-003 — Call log capture
 * Reads `CallLog.Calls.CONTENT_URI`. For each entry the MediaStore URI is forwarded to
 * SP-31 (VoiceTranscriptionWorker) via [Data] output so that SP-31 can insert the
 * [com.becalm.android.data.local.db.entity.RawIngestionEventEntity] after transcription.
 * No raw event rows are inserted here.
 *
 * ## Permissions
 * Requires `READ_SMS` and `READ_CALL_LOG`. Missing permissions cause [Result.retry] so
 * WorkManager will re-attempt once the onboarding flow (SP-53) has granted them.
 *
 * ## PII
 * Raw phone numbers, addresses, and SMS body text are never logged.
 * Counts and 8-char hex hashes of phone numbers are the only identifiers written
 * to logcat (pattern from [com.becalm.android.data.repository.PersonEnrichmentRepository.redact]).
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
    private val logger: Logger,
) : CoroutineWorker(appContext, workerParams) {

    public override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val smsMissing = ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.READ_SMS,
        ) != android.content.pm.PackageManager.PERMISSION_GRANTED

        val callLogMissing = ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.READ_CALL_LOG,
        ) != android.content.pm.PackageManager.PERMISSION_GRANTED

        if (smsMissing || callLogMissing) {
            logger.w(
                TAG,
                "permissions missing sms=$smsMissing callLog=$callLogMissing — retrying",
            )
            return@withContext Result.retry()
        }

        val now = Clock.System.now()
        val smsResult = ingestSms(now)
        val callLogUris = ingestCallLog(now)

        val outputData = Data.Builder()
            .putStringArray(OUTPUT_KEY_CALL_LOG_URIS, callLogUris.toTypedArray())
            .build()

        logger.d(
            TAG,
            "doWork complete smsCount=${smsResult} callLogEntries=${callLogUris.size}",
        )
        Result.success(outputData)
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

        try {
            appContext.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder,
            )?.use { cursor ->
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
        } catch (e: Exception) {
            logger.e(TAG, "SMS query failed", e)
            sourceStatusRepository.recordSyncError(SOURCE_SMS_MMS, e.message ?: "query failed", now)
            return 0
        }

        if (count > 0) {
            syncCursorStore.setMediaStoreLastSeen(KIND_SMS, maxDateMs)
            logger.d(TAG, "sms cursor advanced from=$lastSeenMs to=$maxDateMs count=$count")
        }

        sourceStatusRepository.recordSyncSuccess(SOURCE_SMS_MMS, now)
        logger.d(TAG, "ING-002 sms observed count=$count")
        return count
    }

    // ── Call log ──────────────────────────────────────────────────────────────

    /**
     * Queries the call log for entries newer than the stored watermark.
     * Returns the list of MediaStore content URIs for SP-31 to consume.
     * No [com.becalm.android.data.local.db.entity.RawIngestionEventEntity] rows are inserted here.
     *
     * @return Ordered list of call-log content URI strings (one per entry).
     */
    private suspend fun ingestCallLog(now: Instant): List<String> {
        val lastSeenMs = syncCursorStore.observeMediaStoreLastSeen(KIND_VOICE).first() ?: 0L

        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.NUMBER,
        )
        val selection = "${CallLog.Calls.DATE} > ?"
        val selectionArgs = arrayOf(lastSeenMs.toString())
        val sortOrder = "${CallLog.Calls.DATE} ASC"

        val uris = mutableListOf<String>()
        var maxDateMs = lastSeenMs

        try {
            appContext.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder,
            )?.use { cursor ->
                val idxId = cursor.getColumnIndexOrThrow(CallLog.Calls._ID)
                val idxDate = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val idxDuration = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                val idxNumber = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)

                while (cursor.moveToNext()) {
                    val rowId = cursor.getLong(idxId)
                    val dateMs = cursor.getLong(idxDate)
                    val duration = cursor.getLong(idxDuration)
                    val rawNumber = cursor.getString(idxNumber) ?: ""

                    // PII guard: log only hashed number and duration
                    logger.d(
                        TAG,
                        "call row hash=${redact(rawNumber)} durationSec=$duration dateMs=$dateMs",
                    )

                    val entryUri = "${CallLog.Calls.CONTENT_URI}/$rowId"
                    uris.add(entryUri)

                    if (dateMs > maxDateMs) maxDateMs = dateMs
                }
            }
        } catch (e: Exception) {
            logger.e(TAG, "call log query failed", e)
            sourceStatusRepository.recordSyncError(SOURCE_VOICE, e.message ?: "query failed", now)
            return emptyList()
        }

        if (uris.isNotEmpty()) {
            syncCursorStore.setMediaStoreLastSeen(KIND_VOICE, maxDateMs)
            logger.d(
                TAG,
                "call cursor advanced from=$lastSeenMs to=$maxDateMs count=${uris.size}",
            )
        }

        sourceStatusRepository.recordSyncSuccess(SOURCE_VOICE, now)
        logger.d(TAG, "ING-003 call log entries=${uris.size}")
        return uris
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns an 8-char hex surrogate for [value].
     * Mirrors the pattern used in PersonEnrichmentRepository to prevent PII from
     * appearing in logcat.
     */
    private fun redact(value: String): String = "%08x".format(value.hashCode())

    public companion object {
        private const val TAG = "MediaStoreWorker"

        /** [SyncCursorStore] MediaStore kind key for SMS. */
        public const val KIND_SMS: String = "sms"

        /** [SyncCursorStore] MediaStore kind key for voice/call. */
        public const val KIND_VOICE: String = "voice"

        /**
         * Source identifier used with [SourceStatusRepository] for SMS/MMS.
         * Not a [com.becalm.android.data.remote.dto.SourceType] wire value.
         */
        public const val SOURCE_SMS_MMS: String = "sms_mms"

        /**
         * Source identifier used with [SourceStatusRepository] for call log / voice.
         * Matches [com.becalm.android.data.remote.dto.SourceType.VOICE].
         */
        public const val SOURCE_VOICE: String = "voice"

        /**
         * Output [Data] key carrying the array of call-log content URI strings forwarded to
         * SP-31 (VoiceTranscriptionWorker) via chained WorkManager tasks.
         */
        public const val OUTPUT_KEY_CALL_LOG_URIS: String = "call_log_uris"
    }
}
