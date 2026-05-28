package com.becalm.android.worker.ingestion

import com.becalm.android.data.remote.dto.SourceType

internal object AudioMediaStoreIngestionPolicy {
    private const val MIN_AUDIO_DURATION_SECONDS = 3
    private val transientFileSuffixes = listOf(".tmp", ".temp", ".part", ".pending", ".download")

    fun classify(
        sourceType: String,
        path: String,
        displayName: String,
        durationSec: Int,
        isPending: Boolean,
    ): AudioMediaStoreDecision {
        if (isPending) return AudioMediaStoreDecision.Defer("mediastore_pending")
        if (displayName.isBlank()) return AudioMediaStoreDecision.Ignore("blank_display_name")
        if (durationSec in 0 until MIN_AUDIO_DURATION_SECONDS) {
            return AudioMediaStoreDecision.Ignore("audio_too_short")
        }
        if (transientFileSuffixes.any { displayName.endsWith(it, ignoreCase = true) }) {
            return AudioMediaStoreDecision.Ignore("transient_file")
        }
        return when (sourceType) {
            SourceType.VOICE -> if (isVoicePath(path)) {
                AudioMediaStoreDecision.Process
            } else {
                AudioMediaStoreDecision.Ignore("unsupported_voice_path")
            }
            SourceType.CALL_RECORDING -> if (isCallRecordingPath(path)) {
                AudioMediaStoreDecision.Process
            } else {
                AudioMediaStoreDecision.Ignore("unsupported_call_path")
            }
            SourceType.MEETING -> if (isMeetingAudioPath(path)) {
                AudioMediaStoreDecision.Process
            } else {
                AudioMediaStoreDecision.Ignore("unsupported_meeting_path")
            }
            else -> AudioMediaStoreDecision.Ignore("unsupported_source_type")
        }
    }

    private fun isVoicePath(rawPath: String): Boolean {
        val path = normalizePath(rawPath)
        if (path.isBlank()) return false
        if (path.startsWith("Voice Recorder/") || path.startsWith("VoiceRecorder/")) return true
        val afterRecordings = segmentAfter(path, "Recordings/") ?: return false
        if (afterRecordings.startsWith("Call/")) return false
        if (afterRecordings.startsWith("BeCalm Meetings/")) return false
        if (afterRecordings.isBlank()) return true
        if (afterRecordings.startsWith("Voice Recorder/") || afterRecordings.startsWith("VoiceRecorder/")) {
            return true
        }
        // RELATIVE_PATH values end with "/" and represent a folder, not a file. Only the
        // direct Recordings/ folder and known recorder subfolders are recorder-owned.
        if (path.endsWith("/")) return false
        return !afterRecordings.contains("/")
    }

    private fun isCallRecordingPath(rawPath: String): Boolean =
        segmentAfter(normalizePath(rawPath), "Recordings/Call/") != null

    private fun isMeetingAudioPath(rawPath: String): Boolean =
        segmentAfter(normalizePath(rawPath), "Recordings/BeCalm Meetings/Audio/") != null

    private fun normalizePath(rawPath: String): String =
        rawPath.replace('\\', '/').trim().trimStart('/')

    private fun segmentAfter(path: String, segment: String): String? {
        val index = path.indexOf(segment)
        if (index < 0) return null
        return path.substring(index + segment.length)
    }
}

internal sealed interface AudioMediaStoreDecision {
    data object Process : AudioMediaStoreDecision
    data class Defer(val reason: String) : AudioMediaStoreDecision
    data class Ignore(val reason: String) : AudioMediaStoreDecision
}
