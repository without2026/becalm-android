package com.becalm.android.worker.ingestion

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import com.becalm.android.core.util.Logger
import com.becalm.android.core.util.redact
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.MeetingTranscriptIngestionFinalizer
import com.becalm.android.data.repository.SourceArtifactRepository
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.domain.meeting.MeetingImportFilePolicy
import com.becalm.android.domain.meeting.MeetingImportFolderKind
import com.becalm.android.domain.meeting.MeetingImportFolders
import com.becalm.android.worker.WorkScheduler
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Instant

internal class MeetingDocumentTreeProbe(
    private val appContext: Context,
    private val syncCursorStore: SyncCursorStore,
    private val sourceStatusRepository: SourceStatusRepository,
    private val rawIngestionEventDao: RawIngestionEventDao,
    private val sourceArtifactRepository: SourceArtifactRepository,
    private val userPrefsStore: UserPrefsStore,
    private val workScheduler: WorkScheduler,
    private val logger: Logger,
) {
    suspend fun ingestMeetingTranscripts(now: Instant, lookbackDays: Int? = null): MeetingIngestOutcome {
        val userId = userPrefsStore.observeCurrentUserId().first()
        if (userId == null) {
            logger.w(TAG, "userId null — skipping meeting transcript ingestion this cycle")
            return MeetingIngestOutcome.Success(insertedCount = 0)
        }
        val treeUriString = userPrefsStore.observeRecordingFolderTreeUri().first()
        if (treeUriString.isNullOrBlank()) return MeetingIngestOutcome.Success(insertedCount = 0)

        val pipaConsented = userPrefsStore.observeThirdPartyProvisionConsent().first()
        val persistedCursorMs = syncCursorStore.observeMediaStoreLastSeen(MediaStoreWorker.KIND_MEETING_TRANSCRIPT).first()
        val lookbackCursorMs = lookbackDays?.let { days ->
            now.toEpochMilliseconds() - days * 86_400_000L
        } ?: 0L
        val lastSeenMs = maxOf(persistedCursorMs ?: 0L, lookbackCursorMs)
        var maxLastModifiedMs = lastSeenMs
        var insertedCount = 0
        var hasInsertFailure = false
        val candidates = mutableListOf<TranscriptCandidate>()

        val treeUri = Uri.parse(treeUriString)
        val transcriptDir = findMeetingTranscriptDirectory(treeUri)
            ?: run {
                sourceStatusRepository.recordSyncSuccess(SourceType.MEETING, now)
                return MeetingIngestOutcome.Success(insertedCount = 0)
            }

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getDocumentId(transcriptDir),
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        )

        val scanned = queryDocuments(childrenUri, projection) { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val modifiedIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)

            while (cursor.moveToNext()) {
                val mimeType = cursor.getString(mimeIdx)
                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) continue

                val displayName = cursor.getString(nameIdx) ?: "meeting-transcript"
                if (!MeetingImportFilePolicy.isAllowedTranscript(mimeType, displayName)) continue

                val lastModifiedMs = cursor.getLongOrNull(modifiedIdx) ?: 0L
                if (lastModifiedMs in 1..lastSeenMs) continue

                val byteSize = cursor.getLongOrNull(sizeIdx)
                if (byteSize != null && byteSize > MAX_TRANSCRIPT_BYTES) continue

                val documentUri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    cursor.getString(idIdx),
                )
                candidates += TranscriptCandidate(
                    displayName = displayName,
                    documentUri = documentUri,
                    occurredAt = if (lastModifiedMs > 0) Instant.fromEpochMilliseconds(lastModifiedMs) else now,
                    syncStatus = if (pipaConsented) "pending" else "awaiting_consent",
                    lastModifiedMs = lastModifiedMs,
                    isText = MeetingImportFilePolicy.isTextTranscript(mimeType, displayName),
                )
            }
        }
        if (!scanned) return MeetingIngestOutcome.ScanFailed

        for (candidate in candidates) {
            val result = insertTranscriptRow(
                userId = userId,
                displayName = candidate.displayName,
                documentUri = candidate.documentUri,
                occurredAt = candidate.occurredAt,
                syncStatus = candidate.syncStatus,
                snippet = if (candidate.isText) {
                    readUtf8Preview(candidate.documentUri)?.take(SNIPPET_CHARS)
                } else {
                    null
                },
            )
            when (result) {
                InsertResult.Failed -> {
                    hasInsertFailure = true
                    continue
                }
                InsertResult.DedupSkip -> continue
                is InsertResult.Fresh -> {
                    insertedCount++
                    if (candidate.isText) {
                        meetingTranscriptFinalizer().archiveAndEnqueue(
                            userId = userId,
                            rawEventId = result.id,
                            sourceRef = candidate.documentUri.toString(),
                            occurredAt = candidate.occurredAt,
                            title = candidate.displayName,
                            text = readUtf8Preview(candidate.documentUri),
                            syncStatus = candidate.syncStatus,
                        )
                    }
                    if (candidate.lastModifiedMs > maxLastModifiedMs) {
                        maxLastModifiedMs = candidate.lastModifiedMs
                    }
                }
                is InsertResult.Dedup -> {
                    if (candidate.isText && candidate.syncStatus == "pending") {
                        workScheduler.enqueueMeetingTranscriptUpload(result.id)
                    }
                    if (candidate.lastModifiedMs > maxLastModifiedMs) {
                        maxLastModifiedMs = candidate.lastModifiedMs
                    }
                }
            }
        }

        if (!hasInsertFailure && maxLastModifiedMs > lastSeenMs) {
            syncCursorStore.setMediaStoreLastSeen(MediaStoreWorker.KIND_MEETING_TRANSCRIPT, maxLastModifiedMs)
        }
        sourceStatusRepository.recordSyncSuccess(SourceType.MEETING, now)
        logger.d(TAG, "meeting transcripts inserted=$insertedCount")
        return MeetingIngestOutcome.Success(insertedCount = insertedCount)
    }

    private fun findMeetingTranscriptDirectory(treeUri: Uri): Uri? {
        val root = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val meetings = findChildDirectory(treeUri, root, MeetingImportFolders.MEETINGS_DIR) ?: return null
        return findChildDirectory(
            treeUri = treeUri,
            parentUri = meetings,
            name = MeetingImportFolders.targetDirectoryName(MeetingImportFolderKind.Transcript),
        )
    }

    private fun findChildDirectory(treeUri: Uri, parentUri: Uri, name: String): Uri? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getDocumentId(parentUri),
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        var result: Uri? = null
        queryDocuments(childrenUri, projection) { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                if (
                    cursor.getString(nameIdx) == name &&
                    cursor.getString(mimeIdx) == DocumentsContract.Document.MIME_TYPE_DIR
                ) {
                    result = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idIdx))
                    return@queryDocuments
                }
            }
        }
        return result
    }

    private inline fun queryDocuments(
        uri: Uri,
        projection: Array<String>,
        onCursor: (Cursor) -> Unit,
    ): Boolean {
        return try {
            appContext.contentResolver.query(uri, projection, null, null, null)?.use(onCursor)
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.e(TAG, "meeting transcript DocumentsContract query failed", e)
            false
        }
    }

    private suspend fun insertTranscriptRow(
        userId: String,
        displayName: String,
        documentUri: Uri,
        occurredAt: Instant,
        syncStatus: String,
        snippet: String?,
    ): InsertResult {
        val clientEventId = deterministicTranscriptClientEventId(displayName)
        rawIngestionEventDao.findByClientEventId(userId, clientEventId)?.let {
            return classifyExisting(it)
        }
        val entity = RawIngestionEventEntity(
            id = UUID.randomUUID().toString(),
            userId = userId,
            clientEventId = clientEventId,
            sourceType = SourceType.MEETING,
            sourceRef = documentUri.toString(),
            eventTitle = displayName,
            eventSnippet = snippet,
            timestamp = occurredAt,
            syncStatus = syncStatus,
        )
        val rowId = try {
            rawIngestionEventDao.insert(entity)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.e(TAG, "meeting transcript DAO insert failed nameHash=${redact(displayName)}", e)
            return InsertResult.Failed
        }
        if (rowId != -1L) return InsertResult.Fresh(entity.id)
        val existing = rawIngestionEventDao.findByClientEventId(userId, clientEventId)
        return existing?.let(::classifyExisting) ?: InsertResult.DedupSkip
    }

    private fun meetingTranscriptFinalizer(): MeetingTranscriptIngestionFinalizer =
        MeetingTranscriptIngestionFinalizer(
            sourceArtifactRepository = sourceArtifactRepository,
            workScheduler = workScheduler,
        )

    private fun readUtf8Preview(uri: Uri): String? {
        val bytes = appContext.contentResolver.openInputStream(uri)?.use { input ->
            val raw = input.readBytes()
            if (raw.size > MAX_TRANSCRIPT_BYTES) return null
            raw
        } ?: return null
        return bytes.toString(Charsets.UTF_8)
    }

    private fun Cursor.getLongOrNull(index: Int): Long? =
        if (index < 0 || isNull(index)) null else getLong(index)

    private fun deterministicTranscriptClientEventId(displayName: String): String =
        UUID.nameUUIDFromBytes("meeting:transcript:$displayName".toByteArray(StandardCharsets.UTF_8)).toString()

    private fun classifyExisting(existing: RawIngestionEventEntity): InsertResult =
        if (
            existing.syncStatus != "pending" ||
            existing.commitmentsExtractedCount > 0 ||
            existing.lastAttemptAt != null
        ) {
            InsertResult.DedupSkip
        } else {
            InsertResult.Dedup(existing.id)
        }

    private data class TranscriptCandidate(
        val displayName: String,
        val documentUri: Uri,
        val occurredAt: Instant,
        val syncStatus: String,
        val lastModifiedMs: Long,
        val isText: Boolean,
    )

    private sealed interface InsertResult {
        data class Fresh(val id: String) : InsertResult
        data class Dedup(val id: String) : InsertResult
        data object DedupSkip : InsertResult
        data object Failed : InsertResult
    }

    private companion object {
        private const val TAG = "MediaStoreWorker"
        private const val SNIPPET_CHARS = 200
        private const val MAX_TRANSCRIPT_BYTES = 10L * 1024L * 1024L
    }
}

internal sealed interface MeetingIngestOutcome {
    data class Success(val insertedCount: Int) : MeetingIngestOutcome
    data object ScanFailed : MeetingIngestOutcome
}
