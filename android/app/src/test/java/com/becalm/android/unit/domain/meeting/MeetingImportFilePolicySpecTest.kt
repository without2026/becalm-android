package com.becalm.android.unit.domain.meeting

import com.becalm.android.domain.meeting.MeetingImportFileKind
import com.becalm.android.domain.meeting.MeetingImportFilePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingImportFilePolicySpecTest {

    @Test
    fun `MTG-001 audio import accepts only contracted audio formats`() {
        assertTrue(MeetingImportFilePolicy.isAllowedAudio("audio/m4a", "meeting.m4a"))
        assertTrue(MeetingImportFilePolicy.isAllowedAudio("audio/mp4", "meeting.mp4"))
        assertTrue(MeetingImportFilePolicy.isAllowedAudio("audio/mpeg", "meeting.mp3"))
        assertTrue(MeetingImportFilePolicy.isAllowedAudio("audio/wav", "meeting.wav"))
        assertTrue(MeetingImportFilePolicy.isAllowedAudio("audio/aac", "meeting.aac"))

        assertFalse(MeetingImportFilePolicy.isAllowedAudio("video/mp4", "meeting.mp4"))
        assertFalse(MeetingImportFilePolicy.isAllowedAudio("audio/flac", "meeting.flac"))
        assertFalse(MeetingImportFilePolicy.isAllowedAudio("text/plain", "meeting.txt"))
    }

    @Test
    fun `MTG-001 transcript import accepts only contracted transcript formats`() {
        assertTrue(MeetingImportFilePolicy.isAllowedTranscript("text/plain", "meeting.txt"))
        assertTrue(MeetingImportFilePolicy.isAllowedTranscript("text/markdown", "meeting.md"))
        assertTrue(MeetingImportFilePolicy.isAllowedTranscript("application/pdf", "meeting.pdf"))
        assertTrue(
            MeetingImportFilePolicy.isAllowedTranscript(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "meeting.docx",
            ),
        )

        assertFalse(MeetingImportFilePolicy.isAllowedTranscript("text/html", "meeting.html"))
        assertFalse(MeetingImportFilePolicy.isAllowedTranscript("application/rtf", "meeting.rtf"))
        assertFalse(MeetingImportFilePolicy.isAllowedTranscript("audio/m4a", "meeting.m4a"))
    }

    @Test
    fun `MTG-001 classify returns rejected when MIME and extension do not match contract`() {
        assertEquals(
            MeetingImportFileKind.Audio,
            MeetingImportFilePolicy.classify("audio/m4a", "customer-sync.m4a"),
        )
        assertEquals(
            MeetingImportFileKind.Transcript,
            MeetingImportFilePolicy.classify("text/plain", "customer-sync.txt"),
        )
        assertEquals(
            MeetingImportFileKind.Rejected,
            MeetingImportFilePolicy.classify("application/octet-stream", "customer-sync.bin"),
        )
    }
}
