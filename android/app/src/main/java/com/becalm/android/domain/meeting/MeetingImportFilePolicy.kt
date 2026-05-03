package com.becalm.android.domain.meeting

public enum class MeetingImportFileKind {
    Audio,
    Transcript,
    Rejected,
}

public object MeetingImportFilePolicy {
    public val AUDIO_MIME_TYPES: Array<String> = arrayOf(
        "audio/m4a",
        "audio/mp4",
        "audio/aac",
        "audio/mpeg",
        "audio/wav",
        "audio/x-wav",
    )

    public val TRANSCRIPT_MIME_TYPES: Array<String> = arrayOf(
        "text/plain",
        "text/markdown",
        "application/pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    )

    private val audioExtensions = setOf("m4a", "mp4", "aac", "mp3", "wav")
    private val transcriptExtensions = setOf("txt", "md", "pdf", "docx")

    public fun isAllowedAudio(mimeType: String?, displayName: String?): Boolean =
        normalizedMime(mimeType) in AUDIO_MIME_TYPES &&
            extension(displayName) in audioExtensions

    public fun isAllowedTranscript(mimeType: String?, displayName: String?): Boolean =
        normalizedMime(mimeType) in TRANSCRIPT_MIME_TYPES &&
            extension(displayName) in transcriptExtensions

    public fun isTextTranscript(mimeType: String?, displayName: String?): Boolean =
        normalizedMime(mimeType) in setOf("text/plain", "text/markdown") &&
            extension(displayName) in setOf("txt", "md")

    public fun classify(mimeType: String?, displayName: String?): MeetingImportFileKind =
        when {
            isAllowedAudio(mimeType, displayName) -> MeetingImportFileKind.Audio
            isAllowedTranscript(mimeType, displayName) -> MeetingImportFileKind.Transcript
            else -> MeetingImportFileKind.Rejected
        }

    private fun normalizedMime(value: String?): String =
        value.orEmpty().substringBefore(';').trim().lowercase()

    private fun extension(displayName: String?): String =
        displayName.orEmpty().substringAfterLast('.', missingDelimiterValue = "").lowercase()
}
