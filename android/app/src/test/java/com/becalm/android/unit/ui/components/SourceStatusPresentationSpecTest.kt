package com.becalm.android.ui.components

import com.becalm.android.R
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.SourceConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SourceStatusPresentationSpecTest {

    @Test
    fun `repository source status maps to typed UI status once`() {
        assertEquals(SourceSyncStatus.Connected, sourceSyncStatusFor(SourceConnectionStatus.CONNECTED))
        assertEquals(SourceSyncStatus.Syncing, sourceSyncStatusFor(SourceConnectionStatus.SYNCING))
        assertEquals(SourceSyncStatus.Error, sourceSyncStatusFor(SourceConnectionStatus.ERROR))
        assertEquals(SourceSyncStatus.Disconnected, sourceSyncStatusFor(SourceConnectionStatus.NEVER_CONNECTED))
        assertEquals(SourceSyncStatus.Unknown, sourceSyncStatusFor(null))

        assertNotEquals(
            sourceSyncStatusFor(SourceConnectionStatus.SYNCING),
            sourceSyncStatusFor(SourceConnectionStatus.CONNECTED),
        )
        assertNotEquals(
            sourceSyncStatusFor(SourceConnectionStatus.NEVER_CONNECTED),
            sourceSyncStatusFor(null),
        )
    }

    @Test
    fun `source state presentation owns label tone and recommended action`() {
        assertEquals(R.string.sources_status_connected, sourceStatePresentationFor(SourceSyncStatus.Connected).labelRes)
        assertEquals(StatusTone.Success, sourceStatePresentationFor(SourceSyncStatus.Connected).tone)
        assertEquals(null, sourceStatePresentationFor(SourceSyncStatus.Connected).recommendedCtaRes)

        assertEquals(R.string.sources_status_syncing, sourceStatePresentationFor(SourceSyncStatus.Syncing).labelRes)
        assertEquals(StatusTone.Progress, sourceStatePresentationFor(SourceSyncStatus.Syncing).tone)

        assertEquals(R.string.action_reconnect, sourceStatePresentationFor(SourceSyncStatus.Error).recommendedCtaRes)
        assertEquals(StatusTone.Error, sourceStatePresentationFor(SourceSyncStatus.Error).tone)
        assertEquals(true, sourceStatePresentationFor(SourceSyncStatus.Error).actionRequired)

        assertEquals(R.string.action_connect, sourceStatePresentationFor(SourceSyncStatus.Disconnected).recommendedCtaRes)
        assertEquals(StatusTone.Muted, sourceStatePresentationFor(SourceSyncStatus.Disconnected).tone)
        assertEquals(true, sourceStatePresentationFor(SourceSyncStatus.Disconnected).terminal)
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
