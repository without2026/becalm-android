package com.becalm.android.ui.onboarding

import android.net.Uri
import android.provider.DocumentsContract
import com.becalm.android.data.remote.dto.SourceType

internal object RecordingFolderSelection {
    private val commonDocumentIds = setOf(
        "primary:Recordings",
        "primary:VoiceRecorder",
    )
    private val voiceDocumentIds = setOf(
        "primary:Recordings/Voice Recorder",
        "primary:Voice Recorder",
        "primary:VoiceRecorder",
    )
    private val callRecordingDocumentIds = setOf("primary:Recordings/Call")
    private val meetingDocumentIds = setOf(
        "primary:Recordings",
        "primary:Recordings/BeCalm Meetings",
        "primary:Recordings/BeCalm Meetings/Audio",
    )

    fun isSupportedTree(uri: Uri, targetSourceType: String? = null): Boolean =
        runCatching { DocumentsContract.getTreeDocumentId(uri) }
            .getOrNull()
            ?.let { isSupportedDocumentId(it, targetSourceType) }
            ?: false

    fun isSupportedDocumentId(documentId: String, targetSourceType: String? = null): Boolean =
        documentId in supportedDocumentIds(targetSourceType)

    fun preferredDocumentId(targetSourceType: String?, detection: RecordingFolderDetection): String? =
        when (targetSourceType) {
            SourceType.VOICE -> if (detection.usedFallbackPath) {
                "primary:VoiceRecorder"
            } else {
                "primary:Recordings/Voice Recorder"
            }
            SourceType.CALL_RECORDING -> "primary:Recordings/Call"
            SourceType.MEETING -> "primary:Recordings/BeCalm Meetings/Audio"
            else -> detection.preferredDocumentId
        }

    private fun supportedDocumentIds(targetSourceType: String?): Set<String> =
        when (targetSourceType) {
            SourceType.VOICE -> voiceDocumentIds
            SourceType.CALL_RECORDING -> callRecordingDocumentIds
            SourceType.MEETING -> meetingDocumentIds
            else -> commonDocumentIds
        }
}
