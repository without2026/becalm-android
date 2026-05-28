package com.becalm.android.ui.onboarding

import com.becalm.android.data.remote.dto.SourceType

/**
 * App-owned MediaStore path presets for local recording ingestion.
 *
 * These values are not SAF grants. They are persisted as local selection markers so the
 * runtime can enable MediaStore.Audio scans while the worker still filters to recorder paths.
 * Legacy `content://tree/...` values remain valid as selected markers for existing users.
 */
public object RecordingPathSelection {
    public const val COMMON: String = "mediastore://recordings/common"
    public const val VOICE: String = "mediastore://recordings/voice"
    public const val CALL_RECORDING: String = "mediastore://recordings/call"
    public const val MEETING: String = "mediastore://recordings/meeting"

    public fun forSourceType(sourceType: String?): String =
        when (sourceType) {
            SourceType.VOICE -> VOICE
            SourceType.CALL_RECORDING -> CALL_RECORDING
            SourceType.MEETING -> MEETING
            else -> COMMON
        }
}
