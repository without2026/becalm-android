package com.becalm.android.data.repository

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.CommitmentDao
import com.becalm.android.data.local.db.dao.MeetingSpeakerAliasDao
import com.becalm.android.data.local.db.dao.MeetingSpeakerPreviewDao
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.local.db.dao.SelfIdentityAnchorDao
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.CommitmentLifecycleLegacy
import com.becalm.android.data.local.db.entity.CommitmentScheduleStatus
import com.becalm.android.data.local.db.entity.MeetingSpeakerAliasEntity
import com.becalm.android.data.local.db.entity.MeetingSpeakerPreviewEntity
import com.becalm.android.data.local.db.entity.MeetingSpeakerPreviewStatus
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.local.db.entity.SelfIdentityAnchorEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.domain.meeting.MeetingImportFilePolicy
import com.becalm.android.domain.meeting.MeetingImportFolderKind
import com.becalm.android.domain.meeting.MeetingImportFolders
import com.becalm.android.domain.person.PersonIdentityTypes
import com.becalm.android.worker.WorkScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

public data class MeetingImportResult(
    val rawEventId: String,
    val savedUri: String,
    val queuedExtraction: Boolean = true,
)

public data class MeetingSpeakerReviewContext(
    val selfSpeakerId: String,
    val speakerMappingsJson: String,
    val speakerPreviewId: String,
)

@Singleton
public class MeetingImportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPrefsStore: UserPrefsStore,
    private val rawIngestionRepository: RawIngestionRepository,
    private val commitmentDao: CommitmentDao,
    private val rawIngestionEventDao: RawIngestionEventDao,
    private val meetingSpeakerPreviewDao: MeetingSpeakerPreviewDao,
    private val meetingSpeakerAliasDao: MeetingSpeakerAliasDao,
    private val selfIdentityAnchorDao: SelfIdentityAnchorDao,
    private val workScheduler: WorkScheduler,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    public suspend fun importAudio(
        uri: Uri,
        speakerReviewContext: MeetingSpeakerReviewContext? = null,
    ): BecalmResult<MeetingImportResult> =
        import(kind = ImportKind.Audio, uri = uri, speakerReviewContext = speakerReviewContext)

    public suspend fun stageAudioForSpeakerPreview(uri: Uri): BecalmResult<MeetingImportResult> =
        import(kind = ImportKind.Audio, uri = uri, speakerReviewContext = null)

    public suspend fun confirmSpeakerReview(
        rawEventId: String,
        speakerReviewContext: MeetingSpeakerReviewContext,
    ): BecalmResult<MeetingImportResult> =
        withContext(ioDispatcher) {
            try {
                val userId = userPrefsStore.observeCurrentUserId().first()
                    ?: return@withContext BecalmResult.Failure(BecalmError.Unauthorized)
                val rawEvent = rawIngestionEventDao.findById(rawEventId, userId)
                    ?: return@withContext BecalmResult.Failure(BecalmError.Validation("rawEventId", "meeting event not found"))
                val sourceRef = rawEvent.sourceRef
                    ?: return@withContext BecalmResult.Failure(BecalmError.Validation("sourceRef", "meeting audio missing"))
                val now = Clock.System.now()
                val preview = meetingSpeakerPreviewDao.findByRawEventId(rawEvent.id)
                if (preview?.expiresAt != null && preview.expiresAt <= now) {
                    meetingSpeakerPreviewDao.markStatus(
                        rawEventId = rawEvent.id,
                        status = MeetingSpeakerPreviewStatus.PENDING,
                        lastError = "speaker_preview_expired",
                        updatedAt = now,
                    )
                    rawIngestionEventDao.updateSyncStatus(
                        id = rawEvent.id,
                        status = MeetingSpeakerPreviewStatus.PENDING,
                        now = now,
                        lastError = "speaker_preview_expired",
                    )
                    workScheduler.enqueueMeetingSpeakerPreview(rawEvent.id, sourceRef)
                    return@withContext BecalmResult.Success(
                        MeetingImportResult(rawEvent.id, sourceRef, queuedExtraction = false),
                    )
                }
                selfIdentityAnchorDao.upsert(
                    speakerReviewContext.toSelfSpeakerAnchor(
                        userId = userId,
                        rawEventId = rawEvent.id,
                        now = now,
                    ),
                )
                meetingSpeakerAliasDao.upsert(
                    speakerReviewContext.toSelfSpeakerAlias(
                        userId = userId,
                        rawEventId = rawEvent.id,
                        now = now,
                    ),
                )
                meetingSpeakerPreviewDao.markSelected(
                    rawEventId = rawEvent.id,
                    selectedSelfSpeakerId = speakerReviewContext.selfSpeakerId,
                    status = MeetingSpeakerPreviewStatus.EXTRACT_PENDING,
                    updatedAt = now,
                )
                rawIngestionEventDao.updateSyncStatus(
                    id = rawEvent.id,
                    status = STATUS_PENDING,
                    now = now,
                    lastError = null,
                )
                workScheduler.enqueueVoiceUpload(
                    rawEventId = rawEvent.id,
                    audioUri = sourceRef,
                    selfSpeakerId = speakerReviewContext.selfSpeakerId,
                    speakerMappingsJson = speakerReviewContext.speakerMappingsJson,
                    speakerPreviewId = speakerReviewContext.speakerPreviewId,
                )
                BecalmResult.Success(MeetingImportResult(rawEvent.id, sourceRef))
            } catch (e: IOException) {
                BecalmResult.Failure(BecalmError.Io(e::class.simpleName ?: "I/O error"))
            } catch (t: Throwable) {
                BecalmResult.Failure(BecalmError.Unknown(t))
            }
        }

    public suspend fun ensureTargetFolder(kind: MeetingImportFolderKind): BecalmResult<String> =
        withContext(ioDispatcher) {
            try {
                val treeUriString = userPrefsStore.observeRecordingFolderTreeUri(SourceType.MEETING).first()
                    ?: return@withContext BecalmResult.Failure(
                        BecalmError.Permission("Recordings folder"),
                    )
                val target = ensureTargetDirectory(
                    resolver = context.contentResolver,
                    treeUri = Uri.parse(treeUriString),
                    kind = kind,
                )
                BecalmResult.Success(target.toString())
            } catch (e: IOException) {
                BecalmResult.Failure(BecalmError.Io(e::class.simpleName ?: "I/O error"))
            } catch (t: Throwable) {
                BecalmResult.Failure(BecalmError.Unknown(t))
            }
        }

    private suspend fun import(
        kind: ImportKind,
        uri: Uri,
        speakerReviewContext: MeetingSpeakerReviewContext?,
    ): BecalmResult<MeetingImportResult> =
        withContext(ioDispatcher) {
            try {
                val resolver = context.contentResolver
                val meta = resolver.readOpenableMeta(uri)
                val mimeType = resolver.getType(uri)
                val valid = MeetingImportFilePolicy.isAllowedAudio(mimeType, meta.displayName)
                if (!valid) {
                    return@withContext BecalmResult.Failure(
                        BecalmError.Validation("file", "unsupported meeting file format"),
                    )
                }

                val userId = userPrefsStore.observeCurrentUserId().first()
                    ?: return@withContext BecalmResult.Failure(BecalmError.Unauthorized)
                val treeUriString = userPrefsStore.observeRecordingFolderTreeUri(SourceType.MEETING).first()
                    ?: return@withContext BecalmResult.Failure(
                        BecalmError.Permission("Recordings folder"),
                    )
                val treeUri = Uri.parse(treeUriString)
                val importedAt = Clock.System.now()
                val recordedAt = resolver.resolveMeetingRecordedAt(uri, meta.displayName, importedAt)
                val durationSeconds = resolver.resolveAudioDurationSeconds(uri)
                val thirdPartyConsentGranted = userPrefsStore.observeThirdPartyProvisionConsent().first()
                val shouldPreviewSpeakers = speakerReviewContext == null
                val savedFile = copyIntoMeetingsFolder(
                    resolver = resolver,
                    sourceUri = uri,
                    treeUri = treeUri,
                    kind = kind,
                    displayName = meta.displayName,
                    mimeType = mimeType ?: fallbackMimeType(kind),
                    occurredAt = importedAt,
                )
                val rawEvent = buildRawEvent(
                    userId = userId,
                    clientEventId = deterministicClientEventId(kind, savedFile.displayName),
                    sourceRef = savedFile.uri.toString(),
                    title = meta.displayName,
                    occurredAt = recordedAt,
                    durationSeconds = durationSeconds,
                    syncStatus = if (shouldPreviewSpeakers && thirdPartyConsentGranted) {
                        MeetingSpeakerPreviewStatus.PENDING
                    } else if (thirdPartyConsentGranted) {
                        STATUS_PENDING
                    } else {
                        STATUS_AWAITING_CONSENT
                    },
                    snippet = null,
                )
                val inserted = rawIngestionRepository.insertLocal(rawEvent)
                if (inserted is BecalmResult.Failure) return@withContext inserted
                commitmentDao.insert(buildMeetingScheduleAnchor(userId, rawEvent, importedAt))
                if (shouldPreviewSpeakers) {
                    meetingSpeakerPreviewDao.upsert(
                        MeetingSpeakerPreviewEntity(
                            id = UUID.nameUUIDFromBytes("meeting-preview:${rawEvent.id}".toByteArray(Charsets.UTF_8))
                                .toString(),
                            userId = userId,
                            rawEventId = rawEvent.id,
                            sourceRef = savedFile.uri.toString(),
                            speakerPreviewId = null,
                            speakersJson = "[]",
                            transcriptSegmentsJson = null,
                            billableSeconds = 0,
                            status = rawEvent.syncStatus,
                            selectedSelfSpeakerId = null,
                            lastError = null,
                            expiresAt = null,
                            createdAt = importedAt,
                            updatedAt = importedAt,
                        ),
                    )
                }
                speakerReviewContext?.let { review ->
                    selfIdentityAnchorDao.upsert(
                        review.toSelfSpeakerAnchor(
                            userId = userId,
                            rawEventId = rawEvent.id,
                            now = importedAt,
                        ),
                    )
                    meetingSpeakerAliasDao.upsert(
                        review.toSelfSpeakerAlias(
                            userId = userId,
                            rawEventId = rawEvent.id,
                            now = importedAt,
                        ),
                    )
                }

                if (shouldPreviewSpeakers && rawEvent.syncStatus == MeetingSpeakerPreviewStatus.PENDING) {
                    workScheduler.enqueueMeetingSpeakerPreview(
                        rawEventId = rawEvent.id,
                        audioUri = savedFile.uri.toString(),
                    )
                } else if (rawEvent.syncStatus == STATUS_PENDING) {
                    workScheduler.enqueueVoiceUpload(
                        rawEventId = rawEvent.id,
                        audioUri = savedFile.uri.toString(),
                        selfSpeakerId = speakerReviewContext?.selfSpeakerId,
                        speakerMappingsJson = speakerReviewContext?.speakerMappingsJson,
                        speakerPreviewId = speakerReviewContext?.speakerPreviewId,
                    )
                }

                BecalmResult.Success(MeetingImportResult(rawEvent.id, savedFile.uri.toString()))
            } catch (e: IOException) {
                BecalmResult.Failure(BecalmError.Io(e::class.simpleName ?: "I/O error"))
            } catch (t: Throwable) {
                BecalmResult.Failure(BecalmError.Unknown(t))
            }
        }

    private fun buildRawEvent(
        userId: String,
        clientEventId: String,
        sourceRef: String,
        title: String,
        occurredAt: Instant,
        durationSeconds: Int?,
        syncStatus: String,
        snippet: String?,
    ): RawIngestionEventEntity {
        val id = UUID.randomUUID().toString()
        return RawIngestionEventEntity(
            id = id,
            userId = userId,
            clientEventId = clientEventId,
            sourceType = SourceType.MEETING,
            sourceRef = sourceRef,
            eventTitle = title,
            eventSnippet = snippet,
            durationSeconds = durationSeconds,
            timestamp = occurredAt,
            syncStatus = syncStatus,
        )
    }

    private fun buildMeetingScheduleAnchor(
        userId: String,
        rawEvent: RawIngestionEventEntity,
        now: Instant,
    ): CommitmentEntity =
        CommitmentEntity(
            id = UUID.nameUUIDFromBytes("meeting-schedule:${rawEvent.id}".toByteArray(Charsets.UTF_8)).toString(),
            userId = userId,
            itemType = CommitmentItemType.SCHEDULE,
            direction = null,
            scheduleStatus = CommitmentScheduleStatus.CONFIRMED,
            decisionStatus = null,
            counterpartyRaw = null,
            counterpartyRef = null,
            title = rawEvent.eventTitle?.takeIf { it.isNotBlank() } ?: DEFAULT_MEETING_TITLE,
            description = null,
            quote = rawEvent.eventTitle?.takeIf { it.isNotBlank() } ?: DEFAULT_MEETING_TITLE,
            sourceEventTitle = rawEvent.eventTitle,
            sourceEventOccurredAt = rawEvent.timestamp,
            dueAt = rawEvent.timestamp,
            dueHint = null,
            dueIsApproximate = false,
            actionState = "pending",
            sourceType = rawEvent.sourceType,
            sourceRef = rawEvent.sourceRef,
            confidence = 1.0,
            commitmentState = CommitmentLifecycleLegacy.DRAFT,
            syncStatus = STATUS_PENDING,
            createdAt = now,
            updatedAt = now,
        )

    private fun MeetingSpeakerReviewContext.toSelfSpeakerAnchor(
        userId: String,
        rawEventId: String,
        now: Instant,
    ): SelfIdentityAnchorEntity {
        val normalized = normalizeSpeakerLabel(selfSpeakerId)
        return SelfIdentityAnchorEntity(
            id = UUID.nameUUIDFromBytes(
                "self-anchor:$userId:${PersonIdentityTypes.SPEAKER_LABEL}:$normalized:$rawEventId"
                    .toByteArray(Charsets.UTF_8),
            ).toString(),
            userId = userId,
            anchorType = PersonIdentityTypes.SPEAKER_LABEL,
            normalizedValue = normalized,
            displayValue = selfSpeakerId,
            source = "meeting_review",
            scope = "source_event",
            sourceConnectionId = null,
            sourceEventId = rawEventId,
            trust = "user_confirmed",
            status = "active",
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun MeetingSpeakerReviewContext.toSelfSpeakerAlias(
        userId: String,
        rawEventId: String,
        now: Instant,
    ): MeetingSpeakerAliasEntity =
        MeetingSpeakerAliasEntity(
            userId = userId,
            rawEventId = rawEventId,
            speakerId = selfSpeakerId.trim(),
            displayName = DEFAULT_SELF_SPEAKER_ALIAS,
            updatedAt = now,
        )

    private fun normalizeSpeakerLabel(value: String): String =
        value
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun copyIntoMeetingsFolder(
        resolver: ContentResolver,
        sourceUri: Uri,
        treeUri: Uri,
        kind: ImportKind,
        displayName: String,
        mimeType: String,
        occurredAt: Instant,
    ): SavedMeetingFile {
        val targetDir = ensureTargetDirectory(
            resolver = resolver,
            treeUri = treeUri,
            kind = kind.folderKind,
        )
        val targetName = "${occurredAt.toEpochMilliseconds()}-${sanitizeFileName(displayName)}"
        val target = DocumentsContract.createDocument(resolver, targetDir, mimeType, targetName)
            ?: throw IOException("Unable to create meeting import target")
        resolver.openInputStream(sourceUri).use { input ->
            resolver.openOutputStream(target, "w").use { output ->
                if (input == null || output == null) throw IOException("Unable to open meeting import streams")
                input.copyTo(output)
            }
        }
        return SavedMeetingFile(uri = target, displayName = targetName)
    }

    private fun ensureTargetDirectory(
        resolver: ContentResolver,
        treeUri: Uri,
        kind: MeetingImportFolderKind,
    ): Uri {
        val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val root = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            treeDocumentId,
        )
        val treeLocation = classifyMeetingTree(treeDocumentId, kind)
        if (treeLocation == MeetingTreeLocation.TargetDirectory) return root
        val meetings = if (treeLocation == MeetingTreeLocation.MeetingsDirectory) {
            root
        } else {
            resolver.findOrCreateDirectory(treeUri, root, MeetingImportFolders.MEETINGS_DIR)
        }
        return resolver.findOrCreateDirectory(
            treeUri = treeUri,
            parentUri = meetings,
            name = MeetingImportFolders.targetDirectoryName(kind),
        )
    }

    private fun classifyMeetingTree(
        documentId: String,
        kind: MeetingImportFolderKind,
    ): MeetingTreeLocation {
        val segments = Uri.decode(documentId)
            .trimEnd('/')
            .split('/', ':')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val targetDirectoryName = MeetingImportFolders.targetDirectoryName(kind)
        return when {
            segments.takeLast(2) == listOf(MeetingImportFolders.MEETINGS_DIR, targetDirectoryName) ->
                MeetingTreeLocation.TargetDirectory
            segments.lastOrNull() == MeetingImportFolders.MEETINGS_DIR ->
                MeetingTreeLocation.MeetingsDirectory
            else -> MeetingTreeLocation.RecordingsRoot
        }
    }

    private fun ContentResolver.findOrCreateDirectory(treeUri: Uri, parentUri: Uri, name: String): Uri {
        val existing = findChild(treeUri, parentUri, name, DocumentsContract.Document.MIME_TYPE_DIR)
        if (existing != null) return existing
        return DocumentsContract.createDocument(this, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: throw IOException("Unable to create $name")
    }

    private fun ContentResolver.findChild(treeUri: Uri, parentUri: Uri, name: String, mimeType: String): Uri? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getDocumentId(parentUri),
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        query(childrenUri, projection, null, null, null).use { cursor ->
            if (cursor == null) return null
            val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIdx) == name && cursor.getString(mimeIdx) == mimeType) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idIdx))
                }
            }
        }
        return null
    }

    private fun ContentResolver.readOpenableMeta(uri: Uri): OpenableMeta {
        query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null).use { cursor ->
            if (cursor != null && cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else null
                return OpenableMeta(
                    displayName = sanitizeFileName(name?.takeIf { it.isNotBlank() } ?: "meeting-file"),
                    byteSize = size,
                )
            }
        }
        return OpenableMeta(displayName = "meeting-file", byteSize = null)
    }

    private fun ContentResolver.resolveMeetingRecordedAt(
        uri: Uri,
        displayName: String,
        importedAt: Instant,
    ): Instant {
        displayName.toFilenameRecordingInstant(importedAt)?.let { return it }
        readEmbeddedAudioRecordedAt(uri, importedAt)?.let { return it }
        readLongColumn(uri, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            ?.toValidEpochMillis(importedAt)
            ?.let { return it }
        readLongColumn(uri, MediaStore.MediaColumns.DATE_MODIFIED)
            ?.toValidEpochSeconds(importedAt)
            ?.let { return it }
        readLongColumn(uri, MediaStore.MediaColumns.DATE_ADDED)
            ?.toValidEpochSeconds(importedAt)
            ?.let { return it }
        return importedAt
    }

    private fun readEmbeddedAudioRecordedAt(uri: Uri, importedAt: Instant): Instant? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
                ?.toEmbeddedRecordingInstant(importedAt)
        } catch (_: RuntimeException) {
            null
        } finally {
            retriever.releaseSafely()
        }
    }

    private fun ContentResolver.resolveAudioDurationSeconds(uri: Uri): Int? =
        readEmbeddedAudioDurationSeconds(uri)
            ?: readLongColumn(uri, MediaStore.Audio.Media.DURATION)
                ?.toPositiveDurationSecondsFromMillis()

    private fun readEmbeddedAudioDurationSeconds(uri: Uri): Int? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.toPositiveDurationSecondsFromMillis()
        } catch (_: RuntimeException) {
            null
        } finally {
            retriever.releaseSafely()
        }
    }

    private fun MediaMetadataRetriever.releaseSafely() {
        try {
            release()
        } catch (_: RuntimeException) {
            // Audio metadata is best effort; release failures should not block import.
        }
    }

    private fun ContentResolver.readLongColumn(uri: Uri, columnName: String): Long? =
        try {
            query(uri, arrayOf(columnName), null, null, null).use { cursor ->
                if (cursor == null || !cursor.moveToFirst()) return null
                val index = cursor.getColumnIndex(columnName)
                if (index < 0 || cursor.isNull(index)) return null
                cursor.getLong(index)
            }
        } catch (_: RuntimeException) {
            null
        }

    private fun String.toFilenameRecordingInstant(importedAt: Instant): Instant? {
        val normalized = substringBeforeLast('.', this)
        return FILENAME_FOUR_DIGIT_YEAR_DATES.firstNotNullOfOrNull { regex ->
            regex.findAll(normalized).firstNotNullOfOrNull { match ->
                match.toFilenameEpochMillis(yearOffset = 0)
                    ?.toValidEpochMillis(importedAt)
            }
        } ?: FILENAME_TWO_DIGIT_YEAR_DATES.firstNotNullOfOrNull { regex ->
            regex.findAll(normalized).firstNotNullOfOrNull { match ->
                match.toFilenameEpochMillis(yearOffset = 2_000)
                    ?.toValidEpochMillis(importedAt)
            }
        }
    }

    private fun MatchResult.toFilenameEpochMillis(yearOffset: Int): Long? =
        try {
            val year = groupValues[1].toInt() + yearOffset
            val second = groupValues.getOrNull(6)
                ?.takeIf { it.isNotBlank() }
                ?.toInt()
                ?: 0
            LocalDateTime.of(
                year,
                groupValues[2].toInt(),
                groupValues[3].toInt(),
                groupValues[4].toInt(),
                groupValues[5].toInt(),
                second,
            ).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (_: DateTimeException) {
            null
        } catch (_: NumberFormatException) {
            null
        }

    private fun String.toEmbeddedRecordingInstant(importedAt: Instant): Instant? =
        trim()
            .takeIf { it.isNotEmpty() }
            ?.parseEmbeddedRecordingEpochMillis()
            ?.toValidEpochMillis(importedAt)

    private fun String.parseEmbeddedRecordingEpochMillis(): Long? =
        parseEpochRecordingMillis()
            ?: parseOffsetRecordingMillis()
            ?: parseCompactRecordingMillis()
            ?: parseLocalRecordingMillis()

    private fun String.parseEpochRecordingMillis(): Long? {
        if (!all { it.isDigit() }) return null
        val value = toLongOrNull() ?: return null
        return when (length) {
            in 13..Long.MAX_VALUE.toString().length -> value
            10 -> value.takeIf { it <= Long.MAX_VALUE / 1_000L }?.times(1_000L)
            else -> null
        }
    }

    private fun String.parseOffsetRecordingMillis(): Long? =
        try {
            OffsetDateTime.parse(this).toInstant().toEpochMilli()
        } catch (_: DateTimeException) {
            try {
                java.time.Instant.parse(this).toEpochMilli()
            } catch (_: DateTimeException) {
                null
            }
        }

    private fun String.parseCompactRecordingMillis(): Long? {
        val match = COMPACT_RECORDING_DATE.matchEntire(this) ?: return null
        return try {
            val dateTime = LocalDateTime.of(
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt(),
                match.groupValues[4].toInt(),
                match.groupValues[5].toInt(),
                match.groupValues[6].toInt(),
            )
            val offset = match.groupValues[7].takeIf { it.isNotBlank() }?.toZoneOffset()
            if (offset != null) {
                dateTime.toInstant(offset).toEpochMilli()
            } else {
                dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }
        } catch (_: DateTimeException) {
            null
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun String.toZoneOffset(): ZoneOffset? =
        try {
            when {
                this == "Z" -> ZoneOffset.UTC
                length == 5 && (this[0] == '+' || this[0] == '-') ->
                    ZoneOffset.of("${substring(0, 3)}:${substring(3, 5)}")
                else -> ZoneOffset.of(this)
            }
        } catch (_: DateTimeException) {
            null
        }

    private fun String.parseLocalRecordingMillis(): Long? {
        val normalized = replace(' ', 'T')
        return LOCAL_RECORDING_DATE_FORMATTERS.firstNotNullOfOrNull { formatter ->
            try {
                LocalDateTime.parse(normalized, formatter)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            } catch (_: DateTimeException) {
                null
            }
        }
    }

    private fun Long.toValidEpochMillis(importedAt: Instant): Instant? {
        if (this < MIN_REASONABLE_RECORDING_EPOCH_MILLIS) return null
        val importedAtMillis = importedAt.toEpochMilliseconds()
        if (this > importedAtMillis + MAX_FUTURE_METADATA_SKEW_MILLIS) return null
        return Instant.fromEpochMilliseconds(this)
    }

    private fun Long.toValidEpochSeconds(importedAt: Instant): Instant? {
        if (this <= 0L || this > Long.MAX_VALUE / 1_000L) return null
        return (this * 1_000L).toValidEpochMillis(importedAt)
    }

    private fun Long.toPositiveDurationSecondsFromMillis(): Int? {
        if (this <= 0L) return null
        val seconds = (this + 999L) / 1_000L
        if (seconds <= 0L || seconds > Int.MAX_VALUE) return null
        return seconds.toInt()
    }

    private fun ContentResolver.readUtf8Preview(uri: Uri, maxBytes: Long): String? {
        val bytes = openInputStream(uri)?.use { input ->
            val raw = input.readBytes()
            if (raw.size > maxBytes) return null
            raw
        } ?: return null
        return bytes.toString(Charsets.UTF_8)
    }

    private fun sanitizeFileName(value: String): String =
        value.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim()
            .ifEmpty { "meeting-file" }
            .take(MAX_FILE_NAME_CHARS)

    private fun fallbackMimeType(kind: ImportKind): String =
        "audio/m4a"

    private fun deterministicClientEventId(kind: ImportKind, savedDisplayName: String): String {
        val sourceKey = "meeting:audio:$savedDisplayName"
        return UUID.nameUUIDFromBytes(sourceKey.toByteArray(StandardCharsets.UTF_8)).toString()
    }

    private enum class ImportKind {
        Audio,
        ;

        val folderKind: MeetingImportFolderKind
            get() = when (this) {
                Audio -> MeetingImportFolderKind.Audio
            }
    }

    private enum class MeetingTreeLocation {
        RecordingsRoot,
        MeetingsDirectory,
        TargetDirectory,
    }

    private data class OpenableMeta(
        val displayName: String,
        val byteSize: Long?,
    )

    private data class SavedMeetingFile(
        val uri: Uri,
        val displayName: String,
    )

    private companion object {
        const val MAX_FILE_NAME_CHARS = 96
        const val SNIPPET_CHARS = 200
        const val MAX_TRANSCRIPT_BYTES = 10L * 1024L * 1024L
        private const val STATUS_PENDING = "pending"
        private const val STATUS_AWAITING_CONSENT = "awaiting_consent"
        private const val DEFAULT_MEETING_TITLE = "회의 녹음"
        private const val DEFAULT_SELF_SPEAKER_ALIAS = "나"
        private const val MAX_FUTURE_METADATA_SKEW_MILLIS = 5L * 60L * 1_000L
        private const val MIN_REASONABLE_RECORDING_EPOCH_MILLIS = 946_684_800_000L
        private val FILENAME_FOUR_DIGIT_YEAR_DATES = listOf(
            Regex("""(?<!\d)(20\d{2}|19\d{2})[-_. ]?(\d{2})[-_. ]?(\d{2})[T _.-]+(\d{2})[-_.:]?(\d{2})(?:[-_.:]?(\d{2}))?(?!\d)"""),
        )
        private val FILENAME_TWO_DIGIT_YEAR_DATES = listOf(
            Regex("""(?<!\d)(\d{2})(\d{2})(\d{2})[T _.-]+(\d{2})(\d{2})(\d{2})(?!\d)"""),
        )
        private val COMPACT_RECORDING_DATE = Regex(
            "^(\\d{4})(\\d{2})(\\d{2})T(\\d{2})(\\d{2})(\\d{2})(?:\\.\\d+)?(Z|[+-]\\d{2}:?\\d{2})?$",
        )
        private val LOCAL_RECORDING_DATE_FORMATTERS = listOf(
            java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            java.time.format.DateTimeFormatter.ofPattern("yyyy:MM:dd'T'HH:mm:ss"),
        )
    }
}
