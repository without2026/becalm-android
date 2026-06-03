package com.becalm.android.worker.ingestion

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.becalm.android.core.util.Logger
import com.becalm.android.core.util.PhoneNumberUtils
import com.becalm.android.core.util.redact
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.local.db.entity.MeetingSpeakerPreviewStatus
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.local.db.entity.RawIngestionSyncStatus
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.domain.meeting.MeetingImportFolderKind
import com.becalm.android.domain.meeting.MeetingImportFolders
import com.becalm.android.worker.WorkScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Instant
import java.util.UUID

/**
 * Voice- and call-recording path of [MediaStoreWorker] (ING-001 / ING-003 / VOI-001 /
 * VOI-004 / VOI-005 / VOI-007).
 *
 * Reads `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI` for newly-added recordings and
 * fans out into two disjoint branches that share cursor, consent gating, and dedup
 * semantics:
 * - [ingestVoiceRecordings] — Samsung One UI 6.x `Recordings/Voice Recorder/`, stock
 *   AOSP `Recordings/`, and legacy `VoiceRecorder/`. Inserts `source_type="voice"` rows
 *   with `counterparty_ref=null` and waits for explicit user confirmation before upload.
 * - [ingestCallRecordings] — Samsung One UI 6.x `Recordings/Call/`. Inserts
 *   `source_type="call_recording"` rows with `counterparty_ref` set to the E.164 normalization
 *   of the counterparty number extracted from the MediaStore DISPLAY_NAME (null when no
 *   valid number can be parsed — per ING-001 "없으면 null").
 *
 * The two branches scan the same table with disjoint path patterns (the voice branch
 * explicitly excludes `Recordings/Call/%`) so no file is ingested twice. Each branch
 * owns an independent watermark cursor — [MediaStoreWorker.KIND_VOICE] for the voice
 * scan, [MediaStoreWorker.KIND_CALL_RECORDING] for the call scan. Using a single
 * shared watermark would silently skip older rows in the second-scanned subtree when
 * the first-scanned subtree advanced the cursor past them, which is an ING-001
 * data-loss hazard. Generic voice rows enqueue [WorkScheduler.enqueueVoiceUpload] only
 * after confirmation, while call/meeting rows enqueue speaker preview before final extraction
 * only after confirmation. Each branch's
 * cursor is advanced only when every row in that branch's batch
 * succeeded so failed rows are re-discovered (the `>=` predicate combined with the
 * deterministic `clientEventId` dedup absorbs the one-second overlap).
 *
 * Freshly inserted rows use `sync_status="detected_pending_confirmation"` so a detected
 * file cannot start billable processing until the user confirms that exact file. PIPA
 * consent is still checked when confirmed work is about to start.
 *
 * The voice branch's behaviour is byte-identical with the original
 * `MediaStoreWorker.ingestVoiceRecordings` body; the call branch reuses the same
 * cursor/insert/enqueue helpers via optional `sourceType` / `counterpartyRef` /
 * `clientEventIdPrefix` parameters. Both are exercised solely through
 * [MediaStoreWorker.doWork] tests.
 *
 * Constructed by [MediaStoreWorker] from its own collaborators rather than via Hilt
 * `@Inject`, so unit tests do not need to know about the split.
 *
 * ## Recording path contract
 * ONB-003 persists an app-owned MediaStore path selection before voice ingestion is
 * enabled. This probe queries `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI` directly
 * and constrains every scan to recorder-owned path patterns.
 */
internal class VoiceMediaStoreProbe(
    private val appContext: Context,
    private val syncCursorStore: SyncCursorStore,
    private val sourceStatusRepository: SourceStatusRepository,
    private val rawIngestionEventDao: RawIngestionEventDao,
    private val workScheduler: WorkScheduler,
    private val userPrefsStore: UserPrefsStore,
    private val callRecordingPersonMatcher: CallRecordingPersonMatcher,
    private val logger: Logger,
) {

    /**
     * Queries `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI` for audio files added since
     * the stored watermark. For each discovered file:
     * - Inserts a [RawIngestionEventEntity] (source_type="voice"). `sync_status` is always
     *   `"detected_pending_confirmation"` so a detected file cannot start billable processing
     *   until the user confirms it.
     *   The clientEventId is a deterministic UUID derived from
     *   `"mediastore:voice:<mediaId>"`, so re-running on the same file is idempotent
     *   (the DB UNIQUE index on (user_id, client_event_id) drops duplicates via
     *   [androidx.room.OnConflictStrategy.IGNORE]).
     * - Does not enqueue [WorkScheduler.enqueueVoiceUpload] for fresh detections. Confirmation
     *   from processing status is the only path that starts upload/STT work.
     *
     * See [MediaStoreWorker]'s class KDoc for the full recorder-folder filter / cursor /
     * permission contract — the body here is byte-identical with the original implementation.
     *
     * @return Count of [RawIngestionEventEntity] rows freshly inserted this run.
     */
    suspend fun ingestVoiceRecordings(now: Instant, lookbackDays: Int? = null): VoiceIngestOutcome {
        // userId is required: skip rather than insert orphan rows
        val userId = userPrefsStore.observeCurrentUserId().first()
        if (userId == null) {
            logger.w(TAG, "userId null — skipping voice ingestion this cycle")
            return VoiceIngestOutcome(insertedCount = 0, hasMore = false)
        }

        // Cursor stored in ms; DATE_ADDED is in seconds
        val persistedCursorMs = syncCursorStore.observeMediaStoreLastSeen(MediaStoreWorker.KIND_VOICE).first()
        val lookbackCursorMs = lookbackDays?.let { days ->
            now.toEpochMilliseconds() - days * 86_400_000L
        } ?: 0L
        val sourceEnabledAtMs = userPrefsStore.observeSourceEnabledAt(SourceType.VOICE).first() ?: 0L
        val lastSeenMs = maxOf(persistedCursorMs ?: 0L, lookbackCursorMs, sourceEnabledAtMs)
        val lastSeenSec = lastSeenMs / 1_000L

        // Build folder-filter predicate and projection.
        // API 29+ has RELATIVE_PATH; API 28 uses the deprecated DATA column instead.
        val folderColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.RELATIVE_PATH
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Audio.Media.DATA
        }
        val projection = mediaStoreAudioProjection(folderColumn)
        // Use >= so siblings sharing the same DATE_ADDED second are not permanently skipped
        // on a mid-batch failure. clientEventId dedup absorbs the one-second overlap.
        //
        // Four real-world recorder layouts, all tagged `SourceType.VOICE`:
        //   1. Samsung One UI 6.x Voice Recorder nested under `Recordings/Voice Recorder/`.
        //      Covered by the broad `Recordings/%` pattern.
        //   2. Stock AOSP / Pixel Recorder writing voice memos directly under `Recordings/`
        //      with no recorder-specific subfolder. Covered by the same broad `Recordings/%`.
        //   3. Samsung Voice Recorder root-relative with space: `Voice Recorder/...`
        //      (some variant One UI builds place the recorder folder at path root).
        //   4. Samsung legacy fallback without space: `VoiceRecorder/...` — explicitly
        //      documented at `.spec/onboarding.spec.yml:33` as the secondary auto-discovery
        //      root for older and variant devices.
        //
        // CRITICAL CARVE-OUT: Samsung One UI 6.x *also* stores call recordings under
        // `Recordings/Call/...`. Ingesting those as `SourceType.VOICE` would misclassify
        // call audio. The broad `Recordings/%` match is therefore AND-ed with
        // `NOT LIKE 'Recordings/Call/%'` so the `Call/` subtree is excluded. Call-recording
        // ingestion is owned by `feat/worker/voice/call-recording` (finding PR #15), which
        // will add an inclusive `Recordings/Call/%` query with the correct
        // `SourceType.CALL_RECORDING` tagging.
        val callSubfolderExclusion = "Recordings/${MediaStoreWorker.CALL_FOLDER}/%"
        data class PathPatterns(
            val recordings: String,
            val voiceRecorder: String,
            val voiceRecorderLegacy: String,
            val callExclusion: String,
            val meetingExclusion: String,
        )
        val patterns = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // RELATIVE_PATH ends with "/" (e.g. "Voice Recorder/"); trailing slash prevents
            // matching siblings. Embedded spaces in "Voice Recorder" are carried safely
            // through quoted selectionArgs.
            PathPatterns(
                recordings = "Recordings/%",
                voiceRecorder = "${MediaStoreWorker.RECORDER_FOLDER_SAMSUNG}/%",
                voiceRecorderLegacy = "${MediaStoreWorker.RECORDER_FOLDER_SAMSUNG_LEGACY}/%",
                callExclusion = callSubfolderExclusion,
                meetingExclusion = MeetingImportFolders.MEETINGS_RELATIVE_PATH_PATTERN,
            )
        } else {
            // DATA is the full absolute path — match at any depth with leading `%`.
            PathPatterns(
                recordings = "%/Recordings/%",
                voiceRecorder = "%/${MediaStoreWorker.RECORDER_FOLDER_SAMSUNG}/%",
                voiceRecorderLegacy = "%/${MediaStoreWorker.RECORDER_FOLDER_SAMSUNG_LEGACY}/%",
                callExclusion = "%/$callSubfolderExclusion",
                meetingExclusion = MeetingImportFolders.MEETINGS_DATA_PATH_PATTERN,
            )
        }
        val selection = "${MediaStore.Audio.Media.DATE_ADDED} >= ? AND (" +
            "($folderColumn LIKE ? AND $folderColumn NOT LIKE ? AND $folderColumn NOT LIKE ?)" +
            " OR $folderColumn LIKE ?" +
            " OR $folderColumn LIKE ?" +
            ")"
        val selectionArgs = arrayOf(
            lastSeenSec.toString(),
            patterns.recordings,
            patterns.callExclusion,
            patterns.meetingExclusion,
            patterns.voiceRecorder,
            patterns.voiceRecorderLegacy,
        )
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} ASC"

        var insertedCount = 0
        var hasInsertFailure = false
        val batchLimiter = MediaStoreBatchLimiter()
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
            val idxFolder = cursor.getColumnIndex(folderColumn)
            val idxIsPending = cursor.getColumnIndex(MEDIASTORE_IS_PENDING)

            while (cursor.moveToNext()) {
                val row = readVoiceRow(
                    cursor = cursor,
                    idxId = idxId,
                    idxDateAdded = idxDateAdded,
                    idxDuration = idxDuration,
                    idxDisplayName = idxDisplayName,
                    idxFolder = idxFolder,
                    idxIsPending = idxIsPending,
                )
                if (batchLimiter.shouldStopBefore(row.dateAddedSec)) break

                // PII guard: log only a hash of the file name, never the raw path
                logger.d(
                    TAG,
                    "voice row nameHash=${redact(row.displayName)} durationSec=${row.durationSec} dateAddedSec=${row.dateAddedSec}",
                )
                when (val decision = row.ingestionDecision(SourceType.VOICE)) {
                    AudioMediaStoreDecision.Process -> Unit
                    is AudioMediaStoreDecision.Defer -> {
                        hasInsertFailure = true
                        logger.d(TAG, "voice row deferred reason=${decision.reason} dateAddedSec=${row.dateAddedSec}")
                        continue
                    }
                    is AudioMediaStoreDecision.Ignore -> {
                        maxDateAddedMs = maxOf(maxDateAddedMs, row.dateAddedSec * 1_000L)
                        logger.d(TAG, "voice row ignored reason=${decision.reason} dateAddedSec=${row.dateAddedSec}")
                        continue
                    }
                }

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
                    is VoiceInsertResult.Fresh -> {
                        val rowMs = row.dateAddedSec * 1_000L
                        if (rowMs > maxDateAddedMs) maxDateAddedMs = rowMs

                        insertedCount++
                    }
                    is VoiceInsertResult.Dedup -> {
                        val rowMs = row.dateAddedSec * 1_000L
                        if (rowMs > maxDateAddedMs) maxDateAddedMs = rowMs

                        if (!enqueueAudioReviewOrUpload(insertResult.id, row.audioUri, insertResult.syncStatus, wasFresh = false)) {
                            hasInsertFailure = true
                        }
                    }
                }
            }
        }
        if (!scanned) return VoiceIngestOutcome(insertedCount = 0, hasMore = false)

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
        logger.d(TAG, "ING-003 voice recordings inserted=$insertedCount hasMore=${batchLimiter.hasMore}")
        return VoiceIngestOutcome(insertedCount = insertedCount, hasMore = batchLimiter.hasMore)
    }

    /**
     * Call-recording sibling of [ingestVoiceRecordings] (ING-001 / VOI-001).
     *
     * Queries `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI` for recordings under
     * Samsung One UI 6.x's `Recordings/Call/` subtree only — disjoint from the voice
     * branch (which excludes `Recordings/Call/%`) so no file is ingested twice.
     *
     * For each discovered file:
     * - [sourceType] is [SourceType.CALL_RECORDING] (wire value `"call_recording"`).
     * - [RawIngestionEventEntity.counterpartyRef] is set to the E.164-normalized counterparty
     *   number extracted from the MediaStore DISPLAY_NAME via
     *   [PhoneNumberUtils.extractCounterpartyNumberFromDisplayName]. Null when no valid
     *   phone number can be parsed — per ING-001 "없으면 null", we never throw on
     *   unparseable inputs (the user can still link the event manually later).
     * - [clientEventId] is a deterministic UUID derived from a key with the
     *   `"mediastore:call_recording:"` prefix, disjoint from voice's
     *   `"mediastore:voice:"` prefix so a single row never collides across the two
     *   source_types at the UNIQUE (user_id, client_event_id) index.
     *
     * The watermark cursor is **independent** from the voice branch's
     * [MediaStoreWorker.KIND_VOICE]; this scan reads and advances
     * [MediaStoreWorker.KIND_CALL_RECORDING] instead. A shared cursor would silently
     * skip a call recording whose `DATE_ADDED` is older than the newest voice memo
     * ingested earlier in the same worker run (the voice branch runs first in
     * [MediaStoreWorker.doWork] and advances its cursor before the call branch reads).
     * PIPA consent snapshot, dedup, enqueue, and cursor advance semantics are otherwise
     * identical to the voice branch via the shared helpers.
     *
     * @return [CallRecordingIngestOutcome] distinguishing a clean scan (inserted rows,
     *   zero or otherwise) from a scan failure. The worker maps [CallRecordingIngestOutcome.ScanFailed]
     *   to `Result.retry()` so WorkManager re-attempts instead of silently succeeding past
     *   a ContentResolver query exception (ING-001 data-loss guard).
     */
    suspend fun ingestCallRecordings(now: Instant, lookbackDays: Int? = null): CallRecordingIngestOutcome {
        val userId = userPrefsStore.observeCurrentUserId().first()
        if (userId == null) {
            logger.w(TAG, "userId null — skipping call_recording ingestion this cycle")
            return CallRecordingIngestOutcome.Success(insertedCount = 0, hasMore = false)
        }

        val callLogMatchingConsented = userPrefsStore.observeCallLogMatchingConsent().first()

        // Independent watermark per MediaStore folder subtree. Sharing [KIND_VOICE] between
        // the two branches would allow the voice scan — which runs first in
        // [MediaStoreWorker.doWork] — to advance past a call recording whose DATE_ADDED is
        // older than the newest voice memo in the same run, permanently skipping it
        // (ING-001 data-loss). Using [KIND_CALL_RECORDING] keeps the two subtrees
        // cursor-independent.
        val persistedCursorMs = syncCursorStore.observeMediaStoreLastSeen(MediaStoreWorker.KIND_CALL_RECORDING).first()
        val lookbackCursorMs = lookbackDays?.let { days ->
            now.toEpochMilliseconds() - days * 86_400_000L
        } ?: 0L
        val sourceEnabledAtMs = userPrefsStore.observeSourceEnabledAt(SourceType.CALL_RECORDING).first() ?: 0L
        val lastSeenMs = maxOf(persistedCursorMs ?: 0L, lookbackCursorMs, sourceEnabledAtMs)
        val lastSeenSec = lastSeenMs / 1_000L

        val folderColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.RELATIVE_PATH
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Audio.Media.DATA
        }
        val projection = mediaStoreAudioProjection(folderColumn, includeTitle = true)
        // Inclusive Call/ pattern — disjoint from the voice branch's NOT LIKE exclusion,
        // so the two scans never overlap.
        val callPattern = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // RELATIVE_PATH ends with "/", so the Call/ trailing slash guard prevents
            // matching siblings named "CallLog" etc.
            "Recordings/${MediaStoreWorker.CALL_FOLDER}/%"
        } else {
            // DATA is the full absolute path — match at any depth with leading `%`.
            "%/Recordings/${MediaStoreWorker.CALL_FOLDER}/%"
        }
        val selection = "${MediaStore.Audio.Media.DATE_ADDED} >= ? AND $folderColumn LIKE ?"
        val selectionArgs = arrayOf(lastSeenSec.toString(), callPattern)
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} ASC"

        var insertedCount = 0
        var hasInsertFailure = false
        val batchLimiter = MediaStoreBatchLimiter()
        var maxDateAddedMs = lastSeenMs

        val scanned = queryMediaStore(
            uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection = projection,
            selection = selection,
            selectionArgs = selectionArgs,
            sortOrder = sortOrder,
            onError = { e ->
                logger.e(TAG, "call_recording MediaStore query failed", e)
                sourceStatusRepository.recordSyncError(
                    SourceType.CALL_RECORDING,
                    e.message ?: "query failed",
                    now,
                )
            },
        ) { cursor ->
            val idxId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val idxDateAdded = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val idxDuration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val idxDisplayName = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val idxTitle = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
            val idxFolder = cursor.getColumnIndex(folderColumn)
            val idxIsPending = cursor.getColumnIndex(MEDIASTORE_IS_PENDING)
            val idxSize = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)

            while (cursor.moveToNext()) {
                val row = readVoiceRow(
                    cursor,
                    idxId,
                    idxDateAdded,
                    idxDuration,
                    idxDisplayName,
                    idxFolder,
                    idxIsPending,
                    clientEventIdPrefix = CALL_RECORDING_CLIENT_EVENT_ID_PREFIX,
                    idxTitle = idxTitle.takeIf { it >= 0 },
                    idxSize = idxSize.takeIf { it >= 0 },
                )
                if (batchLimiter.shouldStopBefore(row.dateAddedSec)) break
                when (val decision = row.ingestionDecision(SourceType.CALL_RECORDING)) {
                    AudioMediaStoreDecision.Process -> Unit
                    is AudioMediaStoreDecision.Defer -> {
                        hasInsertFailure = true
                        logger.d(TAG, "call_recording row deferred reason=${decision.reason} dateAddedSec=${row.dateAddedSec}")
                        continue
                    }
                    is AudioMediaStoreDecision.Ignore -> {
                        maxDateAddedMs = maxOf(maxDateAddedMs, row.dateAddedSec * 1_000L)
                        logger.d(TAG, "call_recording row ignored reason=${decision.reason} dateAddedSec=${row.dateAddedSec}")
                        continue
                    }
                }

                // Person-first contract: prefer explicit-consent CallLog matching because
                // Samsung call-recording filenames often omit the counterparty number.
                // Fall back to filename parsing so existing behavior is preserved when the
                // user has not granted CallLog matching consent/permission.
                val callLogMatch = if (callLogMatchingConsented) {
                    callRecordingPersonMatcher.match(
                        recordingDateAddedSec = row.dateAddedSec,
                        recordingDurationSec = row.durationSec,
                        displayName = row.displayName,
                    )
                } else {
                    null
                }
                val filenamePersonRef = PhoneNumberUtils.extractCounterpartyNumberFromDisplayName(row.displayName)
                val counterpartyRef = callLogMatch?.personRef ?: filenamePersonRef

                logger.d(
                    TAG,
                    "call_recording row nameHash=${redact(row.displayName)} durationSec=${row.durationSec} " +
                        "dateAddedSec=${row.dateAddedSec} counterpartyRefPresent=${counterpartyRef != null} " +
                        "callLogMatched=${callLogMatch != null}",
                )

                val insertResult = insertVoiceRow(
                    row = row,
                    userId = userId,
                    sourceType = SourceType.CALL_RECORDING,
                    counterpartyRef = counterpartyRef,
                    // ING-001: event_title sourced from MediaStore TITLE. Samsung's
                    // call-recording files have a meaningful title (timestamp + number);
                    // null when the column is absent on older Android versions.
                    eventTitle = row.title,
                )
                when (insertResult) {
                    VoiceInsertResult.Failed -> {
                        hasInsertFailure = true
                        continue
                    }
                    VoiceInsertResult.DedupSkip -> continue
                    is VoiceInsertResult.Fresh -> {
                        val rowMs = row.dateAddedSec * 1_000L
                        if (rowMs > maxDateAddedMs) maxDateAddedMs = rowMs

                        insertedCount++
                    }
                    is VoiceInsertResult.Dedup -> {
                        val rowMs = row.dateAddedSec * 1_000L
                        if (rowMs > maxDateAddedMs) maxDateAddedMs = rowMs

                        if (!enqueueAudioReviewOrUpload(insertResult.id, row.audioUri, insertResult.syncStatus, wasFresh = false)) {
                            hasInsertFailure = true
                        }
                    }
                }
            }
        }
        if (!scanned) {
            // ContentResolver.query threw — error already recorded in SourceStatusRepository
            // by queryMediaStore's onError. Surface to the worker so it retries via
            // WorkManager backoff; returning Success(0) here would mark the run complete
            // despite the ING-001 subtree being skipped this cycle.
            return CallRecordingIngestOutcome.ScanFailed
        }

        if (!hasInsertFailure && maxDateAddedMs > lastSeenMs) {
            syncCursorStore.setMediaStoreLastSeen(MediaStoreWorker.KIND_CALL_RECORDING, maxDateAddedMs)
            logger.d(
                TAG,
                "call_recording cursor advanced from=${lastSeenMs}ms to=${maxDateAddedMs}ms inserted=$insertedCount",
            )
        }

        sourceStatusRepository.recordSyncSuccess(SourceType.CALL_RECORDING, now)
        logger.d(TAG, "ING-001 call recordings inserted=$insertedCount hasMore=${batchLimiter.hasMore}")
        return CallRecordingIngestOutcome.Success(insertedCount = insertedCount, hasMore = batchLimiter.hasMore)
    }

    suspend fun ingestMeetingAudio(now: Instant, lookbackDays: Int? = null): MeetingIngestOutcome {
        val userId = userPrefsStore.observeCurrentUserId().first()
        if (userId == null) {
            logger.w(TAG, "userId null — skipping meeting audio ingestion this cycle")
            return MeetingIngestOutcome.Success(insertedCount = 0, hasMore = false)
        }

        val persistedCursorMs = syncCursorStore.observeMediaStoreLastSeen(MediaStoreWorker.KIND_MEETING).first()
        val lookbackCursorMs = lookbackDays?.let { days ->
            now.toEpochMilliseconds() - days * 86_400_000L
        } ?: 0L
        val sourceEnabledAtMs = userPrefsStore.observeSourceEnabledAt(SourceType.MEETING).first() ?: 0L
        val lastSeenMs = maxOf(persistedCursorMs ?: 0L, lookbackCursorMs, sourceEnabledAtMs)
        val lastSeenSec = lastSeenMs / 1_000L

        val folderColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.RELATIVE_PATH
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Audio.Media.DATA
        }
        val projection = mediaStoreAudioProjection(folderColumn)
        val meetingAudioPattern = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MeetingImportFolders.targetRelativePath(MeetingImportFolderKind.Audio)
        } else {
            MeetingImportFolders.targetDataPath(MeetingImportFolderKind.Audio)
        }
        val selection = "${MediaStore.Audio.Media.DATE_ADDED} >= ? AND $folderColumn LIKE ?"
        val selectionArgs = arrayOf(lastSeenSec.toString(), meetingAudioPattern)
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} ASC"

        var insertedCount = 0
        var hasInsertFailure = false
        val batchLimiter = MediaStoreBatchLimiter()
        var maxDateAddedMs = lastSeenMs

        val scanned = queryMediaStore(
            uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection = projection,
            selection = selection,
            selectionArgs = selectionArgs,
            sortOrder = sortOrder,
            onError = { e ->
                logger.e(TAG, "meeting audio MediaStore query failed", e)
                sourceStatusRepository.recordSyncError(SourceType.MEETING, e.message ?: "query failed", now)
            },
        ) { cursor ->
            val idxId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val idxDateAdded = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val idxDuration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val idxDisplayName = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val idxFolder = cursor.getColumnIndex(folderColumn)
            val idxIsPending = cursor.getColumnIndex(MEDIASTORE_IS_PENDING)
            val idxSize = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)

            while (cursor.moveToNext()) {
                val row = readVoiceRow(
                    cursor = cursor,
                    idxId = idxId,
                    idxDateAdded = idxDateAdded,
                    idxDuration = idxDuration,
                    idxDisplayName = idxDisplayName,
                    idxFolder = idxFolder,
                    idxIsPending = idxIsPending,
                    clientEventIdPrefix = MEETING_AUDIO_CLIENT_EVENT_ID_PREFIX,
                    idxSize = idxSize.takeIf { it >= 0 },
                ).let { mediaRow ->
                    val key = mediaRow.meetingAudioFingerprintKey()
                    val legacyKey = "meeting:audio:${mediaRow.displayName}"
                    mediaRow.copy(
                        clientEventId = stableClientEventId(key),
                        legacyClientEventId = legacyKey,
                    )
                }
                if (batchLimiter.shouldStopBefore(row.dateAddedSec)) break
                logger.d(
                    TAG,
                        "meeting audio row nameHash=${redact(row.displayName)} durationSec=${row.durationSec} " +
                            "dateAddedSec=${row.dateAddedSec}",
                )
                when (val decision = row.ingestionDecision(SourceType.MEETING)) {
                    AudioMediaStoreDecision.Process -> Unit
                    is AudioMediaStoreDecision.Defer -> {
                        hasInsertFailure = true
                        logger.d(TAG, "meeting audio row deferred reason=${decision.reason} dateAddedSec=${row.dateAddedSec}")
                        continue
                    }
                    is AudioMediaStoreDecision.Ignore -> {
                        maxDateAddedMs = maxOf(maxDateAddedMs, row.dateAddedSec * 1_000L)
                        logger.d(TAG, "meeting audio row ignored reason=${decision.reason} dateAddedSec=${row.dateAddedSec}")
                        continue
                    }
                }

                when (
                    val insertResult = insertVoiceRow(
                        row = row,
                        userId = userId,
                        sourceType = SourceType.MEETING,
                        eventTitle = row.displayName,
                    )
                ) {
                    VoiceInsertResult.Failed -> {
                        hasInsertFailure = true
                        continue
                    }
                    VoiceInsertResult.DedupSkip -> continue
                    is VoiceInsertResult.Fresh -> {
                        val rowMs = row.dateAddedSec * 1_000L
                        if (rowMs > maxDateAddedMs) maxDateAddedMs = rowMs
                        insertedCount++
                    }
                    is VoiceInsertResult.Dedup -> {
                        val rowMs = row.dateAddedSec * 1_000L
                        if (rowMs > maxDateAddedMs) maxDateAddedMs = rowMs
                        if (!enqueueAudioReviewOrUpload(insertResult.id, row.audioUri, insertResult.syncStatus, wasFresh = false)) {
                            hasInsertFailure = true
                        }
                    }
                }
            }
        }
        if (!scanned) return MeetingIngestOutcome.ScanFailed

        if (!hasInsertFailure && maxDateAddedMs > lastSeenMs) {
            syncCursorStore.setMediaStoreLastSeen(MediaStoreWorker.KIND_MEETING, maxDateAddedMs)
        }
        sourceStatusRepository.recordSyncSuccess(SourceType.MEETING, now)
        logger.d(TAG, "meeting audio inserted=$insertedCount hasMore=${batchLimiter.hasMore}")
        return MeetingIngestOutcome.Success(insertedCount = insertedCount, hasMore = batchLimiter.hasMore)
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

    private fun mediaStoreAudioProjection(
        folderColumn: String,
        includeTitle: Boolean = false,
    ): Array<String> = buildList {
        add(MediaStore.Audio.Media._ID)
        add(MediaStore.Audio.Media.DATE_ADDED)
        add(MediaStore.Audio.Media.DURATION)
        add(MediaStore.Audio.Media.DISPLAY_NAME)
        if (includeTitle) add(MediaStore.Audio.Media.TITLE)
        add(folderColumn)
        add(MediaStore.Audio.Media.SIZE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(MEDIASTORE_IS_PENDING)
    }.toTypedArray()

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
        val folderPath: String,
        val isPending: Boolean,
        val audioUri: String,
        val clientEventId: String,
        val legacyClientEventId: String,
        val sizeBytes: Long? = null,
        /**
         * `MediaStore.Audio.Media.TITLE` when projected by the caller, otherwise null.
         * Populated only by the call-recording branch per ING-001 contract
         * (`event_title: MediaStore TITLE`). Voice branch leaves this null because
         * its pre-existing behavior is byte-identical and the voice `event_title`
         * story is tracked by a separate plan doc.
         */
        val title: String? = null,
    )

    private class MediaStoreBatchLimiter {
        private var processedRows: Int = 0
        private var boundaryDateAddedSec: Long? = null
        var hasMore: Boolean = false
            private set

        fun shouldStopBefore(dateAddedSec: Long): Boolean {
            val boundary = boundaryDateAddedSec
            if (boundary != null && dateAddedSec > boundary) {
                hasMore = true
                return true
            }
            if (processedRows == MediaStoreWorker.MEDIASTORE_SCAN_BATCH_SIZE - 1) {
                boundaryDateAddedSec = dateAddedSec
            }
            processedRows += 1
            return false
        }
    }

    /**
     * insertVoiceRow 결과를 판별한 sealed 타입.
     * - [Fresh]     : DAO insert 성공, 신규 row. insertedCount 증가 대상.
     * - [Dedup]     : UNIQUE index 충돌로 기존 row 재사용 (cursor 진행).
     * - [DedupSkip] : 기존 row가 재업로드 부적격 상태 — cursor 진행 금지.
     * - [Failed]    : DAO insert 중 예외 — cursor 진행 금지 + hasInsertFailure=true.
     */
    private sealed interface VoiceInsertResult {
        data class Fresh(val id: String) : VoiceInsertResult
        data class Dedup(val id: String, val syncStatus: String) : VoiceInsertResult
        data object DedupSkip : VoiceInsertResult
        data object Failed : VoiceInsertResult
    }

    /**
     * 커서에서 한 행의 컬럼을 읽어 [VoiceRow]로 반환한다.
     *
     * - DURATION은 MediaStore 규약에 따라 밀리초이므로 정수 초로 변환한다.
     * - 표시 이름이 null이면 빈 문자열로 표준화한다 (원본과 동일 — PII hash 계산 안정성 보존).
     * - audioUri는 원본 생성 식과 byte-identical 하며 clientEventId는 해당 source key의
     *   deterministic UUID다.
     * - [clientEventIdPrefix] 는 voice 분기에서는 `"mediastore:voice:"` (기본값),
     *   call_recording 분기에서는 `"mediastore:call_recording:"` 를 주입받는다.
     *   두 prefix 는 서로 다른 Railway dedup key space 를 구성해 voice/call_recording
     *   rows 가 동일 mediaId 를 공유하지 않음을 보장한다 (현실적으로는 동일 MediaStore
     *   table 을 공유하므로 mediaId 는 고유하지만, prefix 를 분리해 방어한다).
     * - meeting/call rows enter speaker preview first; generic voice rows still enqueue
     *   direct audio extraction.
     */
    private fun readVoiceRow(
        cursor: Cursor,
        idxId: Int,
        idxDateAdded: Int,
        idxDuration: Int,
        idxDisplayName: Int,
        idxFolder: Int,
        idxIsPending: Int,
        clientEventIdPrefix: String = "mediastore:voice:",
        idxTitle: Int? = null,
        idxSize: Int? = null,
    ): VoiceRow {
        val mediaId = cursor.getLong(idxId)
        val dateAddedSec = cursor.getLong(idxDateAdded)
        val durationMs = cursor.getLong(idxDuration)
        val durationSec = (durationMs / 1_000L).toInt()
        val displayName = cursor.getString(idxDisplayName) ?: ""
        val folderPath = idxFolder.takeIf { it >= 0 }?.let { cursor.getString(it) }.orEmpty()
        val isPending = idxIsPending.takeIf { it >= 0 }?.let { cursor.getInt(it) == 1 } ?: false
        val sizeBytes = idxSize?.let { cursor.getLong(it).takeIf { value -> value > 0L } }
        val audioUri = ContentUris.withAppendedId(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            mediaId,
        ).toString()
        // TITLE is only projected by the call-recording branch (ING-001 contract). When
        // [idxTitle] is null the voice branch's byte-identical behavior is preserved.
        val title = idxTitle?.let { cursor.getString(it) }
        val legacyClientEventId = "$clientEventIdPrefix$mediaId"
        return VoiceRow(
            mediaId = mediaId,
            dateAddedSec = dateAddedSec,
            durationSec = durationSec,
            displayName = displayName,
            folderPath = folderPath,
            isPending = isPending,
            audioUri = audioUri,
            // Server-compatible deterministic UUID derived from the legacy source key.
            clientEventId = stableClientEventId(legacyClientEventId),
            legacyClientEventId = legacyClientEventId,
            sizeBytes = sizeBytes,
            title = title,
        )
    }

    private fun VoiceRow.meetingAudioFingerprintKey(): String =
        listOf(
            MEETING_AUDIO_CLIENT_EVENT_ID_PREFIX.trimEnd(':'),
            displayName,
            folderPath,
            dateAddedSec.toString(),
            durationSec.toString(),
            sizeBytes?.toString() ?: "unknown-size",
        ).joinToString(":")

    private fun VoiceRow.ingestionDecision(sourceType: String): AudioMediaStoreDecision =
        AudioMediaStoreIngestionPolicy.classify(
            sourceType = sourceType,
            path = folderPath,
            displayName = displayName,
            durationSec = durationSec,
            isPending = isPending,
        )

    /**
     * DAO insert를 시도하고 결과를 [VoiceInsertResult]로 분류한다.
     *
     * - insert 성공(rowId != -1L)  → [VoiceInsertResult.Fresh]
     * - UNIQUE 충돌(rowId == -1L) → 기존 row를 조회해 업로드 재개 가능 여부 판정
     *     - 재개 가능 (pending/preview_pending & extracted=0) → [VoiceInsertResult.Dedup]
     *     - 재개 불가 (없거나 상태 불일치)     → [VoiceInsertResult.DedupSkip]
     * - 예외 (CancellationException 제외) → 로그 남기고 [VoiceInsertResult.Failed]
     *
     * 에러 로그 문자열("DAO insert failed mediaId=$mediaId nameHash=${redact(displayName)}")은
     * 원본 ingestVoiceRecordings 구현과 완전히 동일하게 유지한다.
     *
     * 새로 감지한 파일은 항상 [RawIngestionSyncStatus.DETECTED_PENDING_CONFIRMATION]
     * 상태로만 저장한다. 사용자가 파일별 처리 승인을 누르기 전에는 업로드, STT,
     * diarization worker가 enqueue되면 안 된다.
     *
     * [sourceType] 과 [counterpartyRef] 는 분기별로 주입된다 — voice 분기는
     * ([SourceType.VOICE], null), call_recording 분기는
     * ([SourceType.CALL_RECORDING], E.164 또는 null).
     */
    private suspend fun insertVoiceRow(
        row: VoiceRow,
        userId: String,
        sourceType: String = SourceType.VOICE,
        counterpartyRef: String? = null,
        eventTitle: String? = null,
    ): VoiceInsertResult {
        rawIngestionEventDao.findByClientEventId(userId, row.legacyClientEventId)?.let { legacy ->
            return classifyExistingVoiceRow(legacy)
        }
        val stableLegacyClientEventId = stableClientEventId(row.legacyClientEventId)
        if (stableLegacyClientEventId != row.legacyClientEventId && stableLegacyClientEventId != row.clientEventId) {
            rawIngestionEventDao.findByClientEventId(userId, stableLegacyClientEventId)?.let { legacy ->
                return classifyExistingVoiceRow(legacy)
            }
        }

        val entity = RawIngestionEventEntity(
            id = UUID.randomUUID().toString(),
            userId = userId,
            clientEventId = row.clientEventId,
            sourceType = sourceType,
            sourceRef = row.audioUri,
            counterpartyRef = counterpartyRef,
            eventTitle = eventTitle ?: row.displayName.takeIf { it.isNotBlank() },
            durationSeconds = row.durationSec,
            timestamp = Instant.fromEpochSeconds(row.dateAddedSec),
            syncStatus = RawIngestionSyncStatus.DETECTED_PENDING_CONFIRMATION,
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
        return existing?.let(::classifyExistingVoiceRow) ?: VoiceInsertResult.DedupSkip
    }

    private fun classifyExistingVoiceRow(existing: RawIngestionEventEntity): VoiceInsertResult =
        when {
            existing.syncStatus == RawIngestionSyncStatus.DETECTED_PENDING_CONFIRMATION ->
                VoiceInsertResult.Dedup(existing.id, existing.syncStatus)
            existing.syncStatus in RESUMABLE_AUDIO_SYNC_STATUSES &&
                existing.processingConfirmedAt != null &&
                existing.commitmentsExtractedCount == 0 &&
                existing.lastAttemptAt == null ->
                VoiceInsertResult.Dedup(existing.id, existing.syncStatus)
            else -> VoiceInsertResult.DedupSkip
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

    private fun enqueueAudioReviewOrUpload(
        enqueueId: String,
        audioUri: String,
        syncStatus: String,
        wasFresh: Boolean,
    ): Boolean =
        when (syncStatus) {
            RawIngestionSyncStatus.DETECTED_PENDING_CONFIRMATION -> true
            MeetingSpeakerPreviewStatus.PENDING -> enqueueSpeakerPreview(enqueueId, audioUri, wasFresh)
            else -> enqueueVoice(enqueueId, audioUri, wasFresh)
        }

    private fun enqueueSpeakerPreview(enqueueId: String, audioUri: String, wasFresh: Boolean): Boolean {
        return try {
            workScheduler.enqueueMeetingSpeakerPreview(enqueueId, audioUri)
            logger.d(TAG, "speaker preview enqueued id=${redact(enqueueId)} fresh=$wasFresh")
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.e(TAG, "enqueueMeetingSpeakerPreview failed id=${redact(enqueueId)}", e)
            false
        }
    }

    private companion object {
        private const val TAG = "MediaStoreWorker"
        private const val MEDIASTORE_IS_PENDING = "is_pending"

        /**
         * Client-event-id prefix for call_recording rows. Disjoint from the voice branch's
         * default `"mediastore:voice:"` so a row never collides across source_types at the
         * Railway UNIQUE (user_id, client_event_id) constraint. Verified to match the
         * expectation baked into the plan doc §5.1.
         */
        private const val CALL_RECORDING_CLIENT_EVENT_ID_PREFIX = "mediastore:call_recording:"
        private const val MEETING_AUDIO_CLIENT_EVENT_ID_PREFIX = "mediastore:meeting_audio:"
        private val RESUMABLE_AUDIO_SYNC_STATUSES = setOf(
            RawIngestionSyncStatus.PENDING,
            MeetingSpeakerPreviewStatus.PENDING,
        )
    }
}

/**
 * Outcome of [VoiceMediaStoreProbe.ingestCallRecordings]. Distinguishes a clean scan
 * (with whatever row count resulted) from a ContentResolver query exception that was
 * logged + recorded in [SourceStatusRepository] but never reached WorkManager.
 *
 * Introduced to close the ING-001 data-loss gap flagged during Wave 2 review: without
 * this signal, an exception in the `Recordings/Call/` scan would be translated into
 * `Int = 0` and the worker would return [androidx.work.ListenableWorker.Result.success],
 * preventing the retry/backoff path that fault-tolerant ingestion depends on.
 */
internal sealed interface CallRecordingIngestOutcome {

    /** Scan completed without a query exception; [insertedCount] new rows were written. */
    data class Success(val insertedCount: Int, val hasMore: Boolean = false) : CallRecordingIngestOutcome

    /**
     * ContentResolver query threw before any row could be read. Caller must map to
     * [androidx.work.ListenableWorker.Result.retry] so WorkManager re-attempts with
     * backoff. No cursor advance has taken place, so retry is safe.
     */
    data object ScanFailed : CallRecordingIngestOutcome
}

internal data class VoiceIngestOutcome(
    val insertedCount: Int,
    val hasMore: Boolean,
)
