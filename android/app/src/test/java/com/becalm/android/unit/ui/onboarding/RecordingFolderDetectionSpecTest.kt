package com.becalm.android.unit.ui.onboarding

import com.becalm.android.ui.onboarding.RecordingFolderDetector
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
        )

        val result = RecordingFolderDetector.detect(existing::contains)

        assertEquals("/storage/emulated/0/Recordings", result.displayPath)
        assertTrue(result.voiceFolderDetected)
        assertTrue(result.callFolderDetected)
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
        assertTrue(result.usedFallbackPath)
        assertFalse(result.requiresManualPicker)
    }

    @Test
    fun `ONB-002 marks manual picker fallback when neither auto-detect path exists`() {
        val result = RecordingFolderDetector.detect { false }

        assertEquals("/storage/emulated/0/Recordings", result.displayPath)
        assertFalse(result.voiceFolderDetected)
        assertFalse(result.callFolderDetected)
        assertFalse(result.usedFallbackPath)
        assertTrue(result.requiresManualPicker)
    }
}
