package com.becalm.android.domain.meeting

public enum class MeetingImportFileKind {
    Audio,
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

    private val audioExtensions = setOf("m4a", "mp4", "aac", "mp3", "wav")

    public fun isAllowedAudio(mimeType: String?, displayName: String?): Boolean =
        normalizedMime(mimeType) in AUDIO_MIME_TYPES &&
            extension(displayName) in audioExtensions

    public fun classify(mimeType: String?, displayName: String?): MeetingImportFileKind =
        when {
            isAllowedAudio(mimeType, displayName) -> MeetingImportFileKind.Audio
            else -> MeetingImportFileKind.Rejected
        }

    private fun normalizedMime(value: String?): String =
        value.orEmpty().substringBefore(';').trim().lowercase()

    private fun extension(displayName: String?): String =
        displayName.orEmpty().substringAfterLast('.', missingDelimiterValue = "").lowercase()
}
