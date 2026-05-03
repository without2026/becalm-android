package com.becalm.android.data.repository

import android.content.Context
import com.becalm.android.data.local.db.BeCalmDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

public data class SourceArchiveWrite(
    val relativePath: String,
    val sha256: String,
    val byteSize: Long,
)

public data class SourceArchiveRead(
    val text: String,
    val truncated: Boolean,
)

@Singleton
public class SourceArchiveStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    public fun writeMarkdown(
        userId: String,
        sourceType: String,
        rawEventId: String,
        occurredAt: Instant,
        markdown: String,
    ): SourceArchiveWrite {
        val bytes = markdown.toByteArray(Charsets.UTF_8)
        val relativePath = relativePath(
            userId = userId,
            sourceType = sourceType,
            rawEventId = rawEventId,
            occurredAt = occurredAt,
        )
        val file = resolve(relativePath)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return SourceArchiveWrite(
            relativePath = relativePath,
            sha256 = sha256(bytes),
            byteSize = bytes.size.toLong(),
        )
    }

    public fun readText(relativePath: String, maxChars: Int): SourceArchiveRead? {
        val file = resolve(relativePath)
        if (!file.exists() || !file.isFile) return null
        if (maxChars <= 0) return SourceArchiveRead(text = "", truncated = file.length() > 0L)
        val buffer = CharArray(maxChars + 1)
        val read = file.bufferedReader(Charsets.UTF_8).use { it.read(buffer) }
        if (read <= 0) return SourceArchiveRead(text = "", truncated = false)
        val truncated = read > maxChars
        val textLength = if (truncated) maxChars else read
        return SourceArchiveRead(text = String(buffer, 0, textLength), truncated = truncated)
    }

    public fun delete(relativePath: String): Boolean {
        val file = resolve(relativePath)
        return !file.exists() || file.delete()
    }

    public fun deleteUserArchive(userId: String) {
        val userHash = BeCalmDatabase.deriveUserIdHash(userId)
        resolve("$ROOT/$userHash").deleteRecursively()
    }

    private fun relativePath(
        userId: String,
        sourceType: String,
        rawEventId: String,
        occurredAt: Instant,
    ): String {
        val userHash = BeCalmDatabase.deriveUserIdHash(userId)
        val date = occurredAt.toLocalDateTime(TimeZone.of("Asia/Seoul")).date
        val family = when {
            sourceType.contains("imap") || sourceType.contains("mail") || sourceType == "gmail" -> "email"
            sourceType.contains("calendar") || sourceType == "meeting" -> "meeting"
            sourceType == "voice" || sourceType == "call_recording" -> "call"
            else -> sourceType.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        }
        return "$ROOT/$userHash/$family/${date.year}/${date.monthNumber.toString().padStart(2, '0')}/$rawEventId.md"
    }

    private fun resolve(relativePath: String): File {
        val cleanPath = relativePath.split('/').filter { it.isNotBlank() && it != ".." }
        return cleanPath.fold(context.filesDir) { parent, child -> File(parent, child) }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        private const val ROOT = "source_archive"
    }
}
