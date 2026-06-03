package com.becalm.android.unit.ui.sources

import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.EmailPipaProvider
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.secure.ImapCredentialStore
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.ui.sources.DefaultSourceAdministrationPort
import com.becalm.android.worker.ingestion.ImapNaverWorker
import com.becalm.android.worker.ingestion.MediaStoreWorker
import io.mockk.every
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceAdministrationPortSpecTest {

    private val sourceStatusRepository: SourceStatusRepository = mockk(relaxed = true)
    private val syncCursorStore: SyncCursorStore = mockk(relaxed = true)
    private val userPrefsStore: UserPrefsStore = mockk(relaxed = true)
    private val imapCredentialStore: ImapCredentialStore = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)

    init {
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
    }

    @Test
    fun `disconnecting IMAP source clears provider and mirror cursors`() = runTest {
        coEvery { sourceStatusRepository.clear(SourceType.NAVER_IMAP) } returns BecalmResult.Success(Unit)

        val result = subject().disconnect(SourceType.NAVER_IMAP)

        assertTrue(result is BecalmResult.Success)
        coVerify(exactly = 1) { syncCursorStore.setImapState(ImapNaverWorker.MAILBOX_NAVER_INBOX, null) }
        coVerify(exactly = 1) { syncCursorStore.setImapState(ImapNaverWorker.MAILBOX_NAVER_SENT, null) }
        coVerify(exactly = 1) { imapCredentialStore.clear(SourceType.NAVER_IMAP) }
        coVerify(exactly = 1) { userPrefsStore.setEmailSourceConnected(EmailPipaProvider.NAVER_IMAP, false) }
        coVerify(exactly = 1) { userPrefsStore.setEmailSourceManagedByBackend(EmailPipaProvider.NAVER_IMAP, false) }
        verifyRawMirrorCursorsCleared(SourceType.NAVER_IMAP)
    }

    @Test
    fun `disconnecting local audio source clears media and mirror cursors`() = runTest {
        coEvery { sourceStatusRepository.clear(SourceType.VOICE) } returns BecalmResult.Success(Unit)

        val result = subject().disconnect(SourceType.VOICE)

        assertTrue(result is BecalmResult.Success)
        coVerify(exactly = 1) { syncCursorStore.setMediaStoreLastSeen(MediaStoreWorker.KIND_VOICE, null) }
        coVerify(exactly = 1) { userPrefsStore.setSourceEnabled(SourceType.VOICE, false) }
        verifyRawMirrorCursorsCleared(SourceType.VOICE)
    }

    @Test
    fun `disconnecting calendar source clears calendar and shared mirror cursors`() = runTest {
        coEvery { sourceStatusRepository.clear(SourceType.GOOGLE_CALENDAR) } returns BecalmResult.Success(Unit)

        val result = subject().disconnect(SourceType.GOOGLE_CALENDAR)

        assertTrue(result is BecalmResult.Success)
        coVerify(exactly = 1) { userPrefsStore.setSourceEnabled(SourceType.GOOGLE_CALENDAR, false) }
        coVerify(exactly = 1) { syncCursorStore.clearCursor("calendar_events") }
        coVerify(exactly = 1) { syncCursorStore.clearCursor("source_event_participants:google_calendar") }
        coVerify(exactly = 1) { syncCursorStore.clearCursor("source_event_participants:all") }
        coVerify(exactly = 1) { syncCursorStore.clearCursor("commitments_cursor") }
        coVerify(exactly = 1) { syncCursorStore.clearCursor("commitment_participants") }
        coVerify(exactly = 1) { syncCursorStore.clearCursor("schedule_event_links") }
    }

    private fun subject(): DefaultSourceAdministrationPort =
        DefaultSourceAdministrationPort(
            sourceStatusRepository = sourceStatusRepository,
            syncCursorStore = syncCursorStore,
            userPrefsStore = userPrefsStore,
            imapCredentialStore = imapCredentialStore,
            logger = logger,
        )

    private fun verifyRawMirrorCursorsCleared(sourceType: String) {
        coVerify(exactly = 1) { syncCursorStore.clearCursor("source_event_participants:$sourceType") }
        coVerify(exactly = 1) { syncCursorStore.clearCursor("source_event_participants:all") }
        coVerify(exactly = 1) { syncCursorStore.clearCursor("commitments_cursor") }
        coVerify(exactly = 1) { syncCursorStore.clearCursor("commitment_participants") }
        coVerify(exactly = 1) { syncCursorStore.clearCursor("schedule_event_links") }
        coVerify(exactly = 1) { syncCursorStore.clearCursor("raw_ingestion_events:$sourceType") }
        coVerify(exactly = 1) { syncCursorStore.clearCursor("raw_ingestion_events:all") }
    }
}
