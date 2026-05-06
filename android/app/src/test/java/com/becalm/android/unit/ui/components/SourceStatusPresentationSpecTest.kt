package com.becalm.android.ui.components

import com.becalm.android.R
import com.becalm.android.data.remote.dto.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SourceStatusPresentationSpecTest {

    @Test
    fun `source sync status keeps active disconnected and unknown states distinct`() {
        assertEquals(SourceSyncStatus.Connected, statusStringToSyncStatus("CONNECTED"))
        assertEquals(SourceSyncStatus.Syncing, statusStringToSyncStatus("SYNCING"))
        assertEquals(SourceSyncStatus.Error, statusStringToSyncStatus("ERROR"))
        assertEquals(SourceSyncStatus.Disconnected, statusStringToSyncStatus("NEVER_CONNECTED"))
        assertEquals(SourceSyncStatus.Unknown, statusStringToSyncStatus(""))

        assertNotEquals(
            statusStringToSyncStatus("SYNCING"),
            statusStringToSyncStatus("CONNECTED"),
        )
        assertNotEquals(
            statusStringToSyncStatus("NEVER_CONNECTED"),
            statusStringToSyncStatus(""),
        )
    }

    @Test
    fun `source presentation resolves provider labels from one mapping`() {
        assertEquals(R.string.raw_event_source_badge_gmail, sourcePresentationFor(SourceType.GMAIL).labelRes)
        assertEquals(R.string.raw_event_source_badge_outlook_mail, sourcePresentationFor(SourceType.OUTLOOK_MAIL).labelRes)
        assertEquals(R.string.raw_event_source_badge_naver_imap, sourcePresentationFor(SourceType.NAVER_IMAP).labelRes)
        assertEquals(R.string.raw_event_source_badge_daum_imap, sourcePresentationFor(SourceType.DAUM_IMAP).labelRes)
        assertEquals(R.string.raw_event_source_badge_unknown, sourcePresentationFor("gmail_raw").labelRes)
    }
}
