package com.becalm.android.unit.ui.onboarding

import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.ui.onboarding.RecordingFolderDetector
import com.becalm.android.ui.onboarding.RecordingPathSelection
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
    }

    @Test
    fun `ONB-002 still presents default Recordings preset when neither auto-detect path exists`() {
        val result = RecordingFolderDetector.detect { false }

        assertEquals("/storage/emulated/0/Recordings", result.displayPath)
        assertFalse(result.voiceFolderDetected)
        assertFalse(result.callFolderDetected)
        assertFalse(result.meetingFolderDetected)
        assertFalse(result.usedFallbackPath)
    }

    @Test
    fun `ONB-003 maps recording sources to app-owned MediaStore path selections`() {
        assertEquals(RecordingPathSelection.COMMON, RecordingPathSelection.forSourceType(null))
        assertEquals(RecordingPathSelection.VOICE, RecordingPathSelection.forSourceType(SourceType.VOICE))
        assertEquals(
            RecordingPathSelection.CALL_RECORDING,
            RecordingPathSelection.forSourceType(SourceType.CALL_RECORDING),
        )
        assertEquals(RecordingPathSelection.MEETING, RecordingPathSelection.forSourceType(SourceType.MEETING))
    }
}
