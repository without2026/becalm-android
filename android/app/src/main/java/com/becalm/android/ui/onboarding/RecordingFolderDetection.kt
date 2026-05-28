package com.becalm.android.ui.onboarding

public data class RecordingFolderDetection(
    val displayPath: String,
    val voiceFolderDetected: Boolean,
    val callFolderDetected: Boolean,
    val meetingFolderDetected: Boolean,
    val usedFallbackPath: Boolean,
)

internal object RecordingFolderDetector {
    private const val PRIMARY_PATH = "/storage/emulated/0/Recordings"
    private const val PRIMARY_VOICE_PATH = "/storage/emulated/0/Recordings/Voice Recorder"
    private const val PRIMARY_CALL_PATH = "/storage/emulated/0/Recordings/Call"
    private const val PRIMARY_MEETING_PATH = "/storage/emulated/0/Recordings/BeCalm Meetings/Audio"
    private const val FALLBACK_PATH = "/storage/emulated/0/VoiceRecorder"

    fun fallback(): RecordingFolderDetection =
        RecordingFolderDetection(
            displayPath = PRIMARY_PATH,
            voiceFolderDetected = false,
            callFolderDetected = false,
            meetingFolderDetected = false,
            usedFallbackPath = false,
        )

    fun detect(pathExists: (String) -> Boolean): RecordingFolderDetection {
        if (pathExists(PRIMARY_PATH)) {
            return RecordingFolderDetection(
                displayPath = PRIMARY_PATH,
                voiceFolderDetected = pathExists(PRIMARY_VOICE_PATH),
                callFolderDetected = pathExists(PRIMARY_CALL_PATH),
                meetingFolderDetected = pathExists(PRIMARY_MEETING_PATH),
                usedFallbackPath = false,
            )
        }

        if (pathExists(FALLBACK_PATH)) {
            return RecordingFolderDetection(
                displayPath = FALLBACK_PATH,
                voiceFolderDetected = true,
                callFolderDetected = false,
                meetingFolderDetected = false,
                usedFallbackPath = true,
            )
        }

        return fallback()
    }
}
