package com.becalm.android.data.repository

import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.db.entity.SourceConnectionEntity
import com.becalm.android.data.remote.dto.SourceType

internal object SourceMirrorCursorReset {
    suspend fun clearForConnection(
        syncCursorStore: SyncCursorStore,
        connection: SourceConnectionEntity,
    ) {
        val sourceType = connection.toMirrorSourceType() ?: return
        clearForSourceType(syncCursorStore, sourceType)
    }

    suspend fun clearForSourceType(
        syncCursorStore: SyncCursorStore,
        sourceType: String,
    ) {
        syncCursorStore.clearCursor("source_event_participants:$sourceType")
        syncCursorStore.clearCursor("source_event_participants:all")
        syncCursorStore.clearCursor("commitments_cursor")
        syncCursorStore.clearCursor("commitment_participants")
        syncCursorStore.clearCursor("schedule_event_links")
        if (sourceType in RAW_MIRROR_SOURCE_TYPES) {
            syncCursorStore.clearCursor("raw_ingestion_events:$sourceType")
            syncCursorStore.clearCursor("raw_ingestion_events:all")
        }
        if (sourceType in CALENDAR_SOURCE_TYPES) {
            syncCursorStore.clearCursor("calendar_events")
        }
    }

    fun shouldResetAfterRefresh(
        previous: SourceConnectionEntity?,
        next: SourceConnectionEntity,
    ): Boolean {
        if (next.toMirrorSourceType() == null || next.status != CONNECTED) return false
        return previous == null || previous.status != CONNECTED
    }

    private fun SourceConnectionEntity.toMirrorSourceType(): String? =
        when {
            provider == "google" && capability == "mail" -> SourceType.GMAIL
            provider == "outlook" && capability == "mail" -> SourceType.OUTLOOK_MAIL
            provider == "google" && capability == "calendar" -> SourceType.GOOGLE_CALENDAR
            provider == "outlook" && capability == "calendar" -> SourceType.OUTLOOK_CALENDAR
            else -> null
        }

    private val RAW_MIRROR_SOURCE_TYPES = setOf(
        SourceType.VOICE,
        SourceType.CALL_RECORDING,
        SourceType.MEETING,
        SourceType.MESSAGE_SCREENSHOT,
        SourceType.GMAIL,
        SourceType.OUTLOOK_MAIL,
        SourceType.NAVER_IMAP,
        SourceType.DAUM_IMAP,
    )
    private val CALENDAR_SOURCE_TYPES = setOf(
        SourceType.GOOGLE_CALENDAR,
        SourceType.OUTLOOK_CALENDAR,
    )
    private const val CONNECTED = "connected"
}
