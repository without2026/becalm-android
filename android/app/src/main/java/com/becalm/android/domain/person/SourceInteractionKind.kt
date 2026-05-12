package com.becalm.android.domain.person

public object SourceInteractionKind {
    public const val COMMITMENT: String = "commitment"

    public fun forSourceType(sourceType: String): String = when {
        sourceType.contains("calendar") -> "calendar"
        sourceType.contains("mail") || sourceType.contains("imap") || sourceType == "gmail" -> "email"
        sourceType == "voice" || sourceType == "call_recording" -> "call"
        sourceType == "meeting" -> "meeting"
        else -> sourceType
    }
}
