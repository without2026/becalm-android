package com.becalm.android.unit.ui.onboarding

import com.becalm.android.ui.onboarding.RecordingFolderDetector
import com.becalm.android.ui.onboarding.RecordingFolderSelection
import com.becalm.android.data.remote.dto.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingFolderDetectionSpecTest {

    @Test
    fun `ONB-002 prefers Samsung Recordings root and reports voice plus call subfolders`() {
        val existing = setOf(
            "/storage/emulated/0/Recordings",
            "/storage/emulated/0/Recordings/Voice Recorder",
            "/storage/emulated/0/Recordings/Call",
            "/storage/emulated/0/Recordings/BeCalm Meetings/Audio",
        )

        val result = RecordingFolderDetector.detect(existing::contains)

        assertEquals("/storage/emulated/0/Recordings", result.displayPath)
        assertTrue(result.voiceFolderDetected)
        assertTrue(result.callFolderDetected)
        assertTrue(result.meetingFolderDetected)
        assertFalse(result.usedFallbackPath)
        assertFalse(result.requiresManualPicker)
    }

    @Test
    fun `ONB-002 falls back to legacy VoiceRecorder path when Recordings root is absent`() {
        val existing = setOf("/storage/emulated/0/VoiceRecorder")

        val result = RecordingFolderDetector.detect(existing::contains)

        assertEquals("/storage/emulated/0/VoiceRecorder", result.displayPath)
        assertTrue(result.voiceFolderDetected)
        assertFalse(result.callFolderDetected)
        assertFalse(result.meetingFolderDetected)
        assertTrue(result.usedFallbackPath)
        assertFalse(result.requiresManualPicker)
    }

    @Test
    fun `ONB-002 marks manual picker fallback when neither auto-detect path exists`() {
        val result = RecordingFolderDetector.detect { false }

        assertEquals("/storage/emulated/0/Recordings", result.displayPath)
        assertFalse(result.voiceFolderDetected)
        assertFalse(result.callFolderDetected)
        assertFalse(result.meetingFolderDetected)
        assertFalse(result.usedFallbackPath)
        assertTrue(result.requiresManualPicker)
    }

    @Test
    fun `ONB-002 accepts only useful recording folder tree selections`() {
        assertTrue(
            RecordingFolderSelection.isSupportedDocumentId("primary:Recordings"),
        )
        assertTrue(
            RecordingFolderSelection.isSupportedDocumentId("primary:VoiceRecorder"),
        )
        assertFalse(
            RecordingFolderSelection.isSupportedDocumentId("primary:Download"),
        )
        assertFalse(
            RecordingFolderSelection.isSupportedDocumentId("primary:Recordings/Call"),
        )
    }

    @Test
    fun `settings reconnect accepts only the exact folder for the selected recording source`() {
        assertTrue(
            RecordingFolderSelection.isSupportedDocumentId(
                "primary:Recordings/Call",
                SourceType.CALL_RECORDING,
            ),
        )
        assertFalse(
            RecordingFolderSelection.isSupportedDocumentId(
                "primary:Recordings",
                SourceType.CALL_RECORDING,
            ),
        )
        assertTrue(
            RecordingFolderSelection.isSupportedDocumentId(
                "primary:Recordings",
                SourceType.MEETING,
            ),
        )
        assertTrue(
            RecordingFolderSelection.isSupportedDocumentId(
                "primary:Recordings/BeCalm Meetings",
                SourceType.MEETING,
            ),
        )
        assertTrue(
            RecordingFolderSelection.isSupportedDocumentId(
                "primary:Recordings/BeCalm Meetings/Audio",
                SourceType.MEETING,
            ),
        )
        assertFalse(
            RecordingFolderSelection.isSupportedDocumentId(
                "primary:Recordings/Call",
                SourceType.MEETING,
            ),
        )
    }
}
