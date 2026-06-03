package com.becalm.android.data.repository

internal object MirrorCursorKeys {
    fun commitments(userId: String): String =
        "commitments_cursor:v4_user:${userId.stableCursorUserKey()}:source_event_anchor"

    fun commitmentParticipants(userId: String): String =
        "commitment_participants:v2_user:${userId.stableCursorUserKey()}"

    fun rawEvents(userId: String, sourceType: String?): String =
        "raw_ingestion_events:v4_user:${userId.stableCursorUserKey()}:source_event_anchor:${sourceType ?: "all"}"

    fun calendarEvents(userId: String): String =
        "calendar_events:v2_user:${userId.stableCursorUserKey()}"

    fun sourceEventParticipants(userId: String, sourceType: String?): String =
        "source_event_participants:v2_user:${userId.stableCursorUserKey()}:${sourceType ?: "all"}"

    fun scheduleEventLinks(userId: String): String =
        "schedule_event_links:v2_user:${userId.stableCursorUserKey()}"

    fun userCorrections(userId: String): String =
        "user_corrections:v1_user:${userId.stableCursorUserKey()}"

    private fun String.stableCursorUserKey(): String =
        trim().lowercase().takeIf { it.isNotBlank() } ?: "unknown"
}
