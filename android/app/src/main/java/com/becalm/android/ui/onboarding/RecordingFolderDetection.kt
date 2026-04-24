package com.becalm.android.ui.onboarding

public data class RecordingFolderDetection(
    val displayPath: String,
    val preferredDocumentId: String?,
    val voiceFolderDetected: Boolean,
    val callFolderDetected: Boolean,
    val usedFallbackPath: Boolean,
    val requiresManualPicker: Boolean,
)

internal object RecordingFolderDetector {
    private const val PRIMARY_PATH = "/storage/emulated/0/Recordings"
    private const val PRIMARY_VOICE_PATH = "/storage/emulated/0/Recordings/Voice Recorder"
    private const val PRIMARY_CALL_PATH = "/storage/emulated/0/Recordings/Call"
    private const val FALLBACK_PATH = "/storage/emulated/0/VoiceRecorder"

    fun detect(pathExists: (String) -> Boolean): RecordingFolderDetection {
        if (pathExists(PRIMARY_PATH)) {
            return RecordingFolderDetection(
                displayPath = PRIMARY_PATH,
                preferredDocumentId = "primary:Recordings",
                voiceFolderDetected = pathExists(PRIMARY_VOICE_PATH),
                callFolderDetected = pathExists(PRIMARY_CALL_PATH),
                usedFallbackPath = false,
                requiresManualPicker = false,
            )
        }

        if (pathExists(FALLBACK_PATH)) {
            return RecordingFolderDetection(
                displayPath = FALLBACK_PATH,
                preferredDocumentId = "primary:VoiceRecorder",
                voiceFolderDetected = true,
                callFolderDetected = false,
                usedFallbackPath = true,
                requiresManualPicker = false,
            )
        }

        return RecordingFolderDetection(
            displayPath = PRIMARY_PATH,
            preferredDocumentId = "primary:Recordings",
            voiceFolderDetected = false,
            callFolderDetected = false,
            usedFallbackPath = false,
            requiresManualPicker = true,
        )
    }
}
