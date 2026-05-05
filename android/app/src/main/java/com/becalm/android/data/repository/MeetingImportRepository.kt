package com.becalm.android.data.repository

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.domain.meeting.MeetingImportFilePolicy
import com.becalm.android.domain.meeting.MeetingImportFolderKind
import com.becalm.android.domain.meeting.MeetingImportFolders
import com.becalm.android.worker.WorkScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.nio.charset.StandardCharsets
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
)

@Singleton
public class MeetingImportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPrefsStore: UserPrefsStore,
    private val rawIngestionRepository: RawIngestionRepository,
    private val sourceArtifactRepository: SourceArtifactRepository,
    private val workScheduler: WorkScheduler,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    public suspend fun importAudio(uri: Uri): BecalmResult<MeetingImportResult> =
        import(kind = ImportKind.Audio, uri = uri)

    public suspend fun importTranscript(uri: Uri): BecalmResult<MeetingImportResult> =
        import(kind = ImportKind.Transcript, uri = uri)

    public suspend fun ensureTargetFolder(kind: MeetingImportFolderKind): BecalmResult<String> =
        withContext(ioDispatcher) {
            try {
                val treeUriString = userPrefsStore.observeRecordingFolderTreeUri().first()
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

    private suspend fun import(kind: ImportKind, uri: Uri): BecalmResult<MeetingImportResult> =
        withContext(ioDispatcher) {
            try {
                val resolver = context.contentResolver
                val meta = resolver.readOpenableMeta(uri)
                val mimeType = resolver.getType(uri)
                val valid = when (kind) {
                    ImportKind.Audio -> MeetingImportFilePolicy.isAllowedAudio(mimeType, meta.displayName)
                    ImportKind.Transcript -> MeetingImportFilePolicy.isAllowedTranscript(mimeType, meta.displayName)
                }
                if (!valid) {
                    return@withContext BecalmResult.Failure(
                        BecalmError.Validation("file", "unsupported meeting file format"),
                    )
                }
                if (kind == ImportKind.Transcript && meta.byteSize != null && meta.byteSize > MAX_TRANSCRIPT_BYTES) {
                    return@withContext BecalmResult.Failure(
                        BecalmError.Validation("file", "transcript exceeds 10 MiB"),
                    )
                }

                val userId = userPrefsStore.observeCurrentUserId().first()
                    ?: return@withContext BecalmResult.Failure(BecalmError.Unauthorized)
                val treeUriString = userPrefsStore.observeRecordingFolderTreeUri().first()
                    ?: return@withContext BecalmResult.Failure(
                        BecalmError.Permission("Recordings folder"),
                    )
                val treeUri = Uri.parse(treeUriString)
                val occurredAt = Clock.System.now()
                val savedFile = copyIntoMeetingsFolder(
                    resolver = resolver,
                    sourceUri = uri,
                    treeUri = treeUri,
                    kind = kind,
                    displayName = meta.displayName,
                    mimeType = mimeType ?: fallbackMimeType(kind),
                    occurredAt = occurredAt,
                )
                val rawEvent = buildRawEvent(
                    userId = userId,
                    clientEventId = deterministicClientEventId(kind, savedFile.displayName),
                    sourceRef = savedFile.uri.toString(),
                    title = meta.displayName,
                    occurredAt = occurredAt,
                    syncStatus = if (userPrefsStore.observeThirdPartyProvisionConsent().first()) {
                        "pending"
                    } else {
                        "awaiting_consent"
                    },
                    snippet = if (
                        kind == ImportKind.Transcript &&
                        MeetingImportFilePolicy.isTextTranscript(mimeType, meta.displayName)
                    ) {
                        resolver.readUtf8Preview(uri, MAX_TRANSCRIPT_BYTES)?.take(SNIPPET_CHARS)
                    } else {
                        null
                    },
                )
                val inserted = rawIngestionRepository.insertLocal(rawEvent)
                if (inserted is BecalmResult.Failure) return@withContext inserted

                if (
                    kind == ImportKind.Transcript &&
                    MeetingImportFilePolicy.isTextTranscript(mimeType, meta.displayName)
                ) {
                    val text = resolver.readUtf8Preview(uri, MAX_TRANSCRIPT_BYTES)
                    if (!text.isNullOrBlank()) {
                        sourceArtifactRepository.archiveMeetingTranscript(
                            MeetingTranscriptArchiveInput(
                                userId = userId,
                                rawEventId = rawEvent.id,
                                sourceRef = savedFile.uri.toString(),
                                occurredAt = occurredAt,
                                title = meta.displayName,
                                text = text,
                            ),
                        )
                    }
                    if (rawEvent.syncStatus == "pending") {
                        workScheduler.enqueueMeetingTranscriptUpload(rawEvent.id)
                    }
                } else if (rawEvent.syncStatus == "pending") {
                    workScheduler.enqueueVoiceUpload(rawEvent.id, savedFile.uri.toString())
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
            timestamp = occurredAt,
            syncStatus = syncStatus,
        )
    }

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
        val root = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val meetings = resolver.findOrCreateDirectory(treeUri, root, MeetingImportFolders.MEETINGS_DIR)
        return resolver.findOrCreateDirectory(
            treeUri = treeUri,
            parentUri = meetings,
            name = MeetingImportFolders.targetDirectoryName(kind),
        )
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
        if (kind == ImportKind.Audio) "audio/m4a" else "text/plain"

    private fun deterministicClientEventId(kind: ImportKind, savedDisplayName: String): String {
        val sourceKey = when (kind) {
            ImportKind.Audio -> "meeting:audio:$savedDisplayName"
            ImportKind.Transcript -> "meeting:transcript:$savedDisplayName"
        }
        return UUID.nameUUIDFromBytes(sourceKey.toByteArray(StandardCharsets.UTF_8)).toString()
    }

    private enum class ImportKind {
        Audio,
        Transcript,
        ;

        val folderKind: MeetingImportFolderKind
            get() = when (this) {
                Audio -> MeetingImportFolderKind.Audio
                Transcript -> MeetingImportFolderKind.Transcript
            }
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
    }
}
