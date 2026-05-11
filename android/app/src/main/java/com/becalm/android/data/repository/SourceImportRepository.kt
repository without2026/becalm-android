package com.becalm.android.data.repository

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.worker.WorkScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

public data class SourceImportResult(
    val rawEventId: String,
    val savedUri: String,
)

@Singleton
public class SourceImportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPrefsStore: UserPrefsStore,
    private val rawIngestionRepository: RawIngestionRepository,
    private val meetingImportRepository: MeetingImportRepository,
    private val workScheduler: WorkScheduler,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    public suspend fun importMeetingAudio(uri: Uri): BecalmResult<MeetingImportResult> =
        meetingImportRepository.importAudio(uri)

    public suspend fun importMessageScreenshot(uri: Uri): BecalmResult<SourceImportResult> =
        withContext(ioDispatcher) {
            try {
                val resolver = context.contentResolver
                val meta = resolver.readOpenableMeta(uri, fallbackName = "message-screenshot")
                val mimeType = resolver.getType(uri)
                if (!isAllowedImage(mimeType, meta.displayName)) {
                    return@withContext BecalmResult.Failure(
                        BecalmError.Validation("file", "unsupported message screenshot format"),
                    )
                }
                if (meta.byteSize != null && meta.byteSize > MessageScreenshotImageNormalizer.MAX_SOURCE_BYTES) {
                    return@withContext BecalmResult.Failure(
                        BecalmError.Validation("file", "message screenshot exceeds 25 MiB"),
                    )
                }

                val userId = userPrefsStore.observeCurrentUserId().first()
                    ?: return@withContext BecalmResult.Failure(BecalmError.Unauthorized)
                val occurredAt = Clock.System.now()
                val savedFile = copyIntoMessageScreenshotFolder(
                    resolver = resolver,
                    sourceUri = uri,
                    displayName = meta.displayName,
                    occurredAtMillis = occurredAt.toEpochMilliseconds(),
                )
                val syncStatus = if (userPrefsStore.observeThirdPartyProvisionConsent().first()) {
                    STATUS_PENDING
                } else {
                    STATUS_AWAITING_CONSENT
                }
                val rawEvent = RawIngestionEventEntity(
                    id = UUID.randomUUID().toString(),
                    userId = userId,
                    clientEventId = deterministicClientEventId(savedFile.displayName),
                    sourceType = SourceType.MESSAGE_SCREENSHOT,
                    sourceRef = Uri.fromFile(savedFile.file).toString(),
                    eventTitle = meta.displayName,
                    timestamp = occurredAt,
                    syncStatus = syncStatus,
                )
                when (val inserted = rawIngestionRepository.insertLocal(rawEvent)) {
                    is BecalmResult.Failure -> return@withContext inserted
                    is BecalmResult.Success -> Unit
                }
                if (syncStatus == STATUS_PENDING) {
                    workScheduler.enqueueMessageScreenshotUpload(rawEvent.id)
                }
                BecalmResult.Success(
                    SourceImportResult(
                        rawEventId = rawEvent.id,
                        savedUri = Uri.fromFile(savedFile.file).toString(),
                    ),
                )
            } catch (e: MessageScreenshotImportValidationException) {
                BecalmResult.Failure(BecalmError.Validation(e.field, e.validationMessage))
            } catch (e: IOException) {
                BecalmResult.Failure(BecalmError.Io(e::class.simpleName ?: "I/O error"))
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                BecalmResult.Failure(BecalmError.Unknown(t))
            }
        }

    private fun copyIntoMessageScreenshotFolder(
        resolver: ContentResolver,
        sourceUri: Uri,
        displayName: String,
        occurredAtMillis: Long,
    ): SavedLocalFile {
        val targetDir = File(context.filesDir, MESSAGE_SCREENSHOT_DIR).apply {
            if (!exists() && !mkdirs()) throw IOException("Unable to create screenshot import folder")
        }
        val targetName = normalizedTargetName(
            occurredAtMillis = occurredAtMillis,
            displayName = displayName,
        )
        val target = File(targetDir, targetName)
        try {
            resolver.openInputStream(sourceUri).use { input ->
                if (input == null) throw IOException("Unable to open screenshot import stream")
                MessageScreenshotImageNormalizer.normalize(input, target)
            }
        } catch (t: Throwable) {
            target.delete()
            throw t
        }
        return SavedLocalFile(file = target, displayName = targetName)
    }

    private fun ContentResolver.readOpenableMeta(uri: Uri, fallbackName: String): OpenableMeta {
        query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null).use { cursor ->
            if (cursor != null && cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else null
                return OpenableMeta(
                    displayName = sanitizeFileName(name?.takeIf { it.isNotBlank() } ?: fallbackName),
                    byteSize = size,
                )
            }
        }
        return OpenableMeta(displayName = fallbackName, byteSize = null)
    }

    private fun isAllowedImage(mimeType: String?, displayName: String): Boolean {
        val normalizedMime = mimeType?.lowercase()
        val normalizedName = displayName.lowercase()
        return normalizedMime in IMAGE_MIME_TYPES ||
            IMAGE_EXTENSIONS.any { normalizedName.endsWith(it) }
    }

    private fun deterministicClientEventId(savedDisplayName: String): String =
        UUID.nameUUIDFromBytes("message_screenshot:$savedDisplayName".toByteArray(StandardCharsets.UTF_8)).toString()

    private fun normalizedTargetName(occurredAtMillis: Long, displayName: String): String {
        val sanitized = sanitizeFileName(displayName)
        val baseName = sanitized.substringBeforeLast('.', sanitized)
            .ifBlank { "message-screenshot" }
            .take(MAX_FILE_NAME_CHARS - MessageScreenshotImageNormalizer.OUTPUT_EXTENSION.length - 1)
        return "$occurredAtMillis-$baseName.${MessageScreenshotImageNormalizer.OUTPUT_EXTENSION}"
    }

    private fun sanitizeFileName(value: String): String =
        value.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim()
            .ifEmpty { "message-screenshot" }
            .take(MAX_FILE_NAME_CHARS)

    private data class OpenableMeta(
        val displayName: String,
        val byteSize: Long?,
    )

    private data class SavedLocalFile(
        val file: File,
        val displayName: String,
    )

    private companion object {
        private const val STATUS_PENDING = "pending"
        private const val STATUS_AWAITING_CONSENT = "awaiting_consent"
        private const val MESSAGE_SCREENSHOT_DIR = "source_imports/message_screenshots"
        private const val MAX_FILE_NAME_CHARS = 96
        private val IMAGE_MIME_TYPES = setOf("image/png", "image/jpeg", "image/jpg", "image/webp")
        private val IMAGE_EXTENSIONS = listOf(".png", ".jpg", ".jpeg", ".webp")
    }
}
