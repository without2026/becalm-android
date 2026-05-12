package com.becalm.android.ui.components

import com.becalm.android.data.remote.dto.SourceType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceTypeCategorySpecTest {

    @Test
    fun `source category helpers keep UI filters and next-action inference aligned`() {
        assertTrue(SourceType.GMAIL.isEmailSource())
        assertTrue(SourceType.NAVER_IMAP.isEmailSource())
        assertFalse(SourceType.VOICE.isEmailSource())

        assertTrue(SourceType.VOICE.isCallSource())
        assertTrue(SourceType.CALL_RECORDING.isCallSource())
        assertFalse(SourceType.GMAIL.isCallSource())

        assertTrue(SourceType.GOOGLE_CALENDAR.isCalendarSource())
        assertTrue(SourceType.OUTLOOK_CALENDAR.isCalendarSource())
        assertFalse(SourceType.MEETING.isCalendarSource())
        assertFalse(SourceType.NAVER_IMAP.isCalendarSource())

        assertTrue(SourceType.MEETING.isMeetingSource())
        assertTrue(SourceType.GOOGLE_CALENDAR.isMeetingTimelineSource())
        assertTrue(SourceType.OUTLOOK_CALENDAR.isMeetingTimelineSource())
        assertTrue(SourceType.MEETING.isMeetingTimelineSource())
        assertFalse(SourceType.GMAIL.isMeetingTimelineSource())
    }
}
