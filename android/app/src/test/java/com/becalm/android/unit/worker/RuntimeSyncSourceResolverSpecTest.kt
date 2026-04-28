package com.becalm.android.unit.worker

import com.becalm.android.data.local.datastore.EmailPipaProvider
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.secure.ImapCredentialStore
import com.becalm.android.data.local.secure.ImapCredentials
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.worker.DefaultRuntimeSyncSourceResolver
import com.becalm.android.worker.MediaAudioPermissionChecker
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeSyncSourceResolverSpecTest {

    private val userPrefsStore: UserPrefsStore = mockk()
    private val imapCredentialStore: ImapCredentialStore = mockk()
    private val mediaAudioPermissionChecker: MediaAudioPermissionChecker = mockk()

    @Test
    fun `filters stale local imap flags before enqueueing workers`() = runTest {
        stubSourceFlags()
        every { userPrefsStore.observeSourceEnabled(SourceType.VOICE) } returns flowOf(true)
        every { userPrefsStore.observeRecordingFolderTreeUri() } returns flowOf("content://tree/recordings")
        every { mediaAudioPermissionChecker.isGranted() } returns true
        every { userPrefsStore.observeSourceEnabled(SourceType.GOOGLE_CALENDAR) } returns flowOf(true)
        every { userPrefsStore.observeSourceEnabled(SourceType.OUTLOOK_CALENDAR) } returns flowOf(false)
        every { userPrefsStore.observeEmailSourceConnected(EmailPipaProvider.NAVER_IMAP) } returns flowOf(true)
        every { userPrefsStore.observeEmailSourceManagedByBackend(EmailPipaProvider.NAVER_IMAP) } returns flowOf(false)
        every { userPrefsStore.observeEmailSourceConnected(EmailPipaProvider.DAUM_IMAP) } returns flowOf(true)
        every { userPrefsStore.observeEmailSourceManagedByBackend(EmailPipaProvider.DAUM_IMAP) } returns flowOf(false)
        coEvery { imapCredentialStore.load(SourceType.NAVER_IMAP) } returns null
        coEvery { imapCredentialStore.load(SourceType.DAUM_IMAP) } returns
            ImapCredentials("user@daum.net", "app-password", "imap.daum.net", 993)

        val resolver = buildResolver()

        assertEquals(
            setOf(SourceType.DAUM_IMAP, SourceType.GOOGLE_CALENDAR),
            resolver.periodicSources(),
        )
        assertEquals(
            setOf(SourceType.VOICE, SourceType.DAUM_IMAP, SourceType.GOOGLE_CALENDAR),
            resolver.foregroundSources(),
        )
    }

    @Test
    fun `detects backend mail only for connected backend-managed providers`() = runTest {
        stubSourceFlags()
        every { userPrefsStore.observeEmailSourceConnected(EmailPipaProvider.GMAIL) } returns flowOf(true)
        every { userPrefsStore.observeEmailSourceManagedByBackend(EmailPipaProvider.GMAIL) } returns flowOf(true)

        assertTrue(buildResolver().hasBackendMailSource())
    }

    private fun stubSourceFlags() {
        every { userPrefsStore.observeSourceEnabled(any()) } returns flowOf(false)
        every { userPrefsStore.observeRecordingFolderTreeUri() } returns flowOf(null)
        every { mediaAudioPermissionChecker.isGranted() } returns false
        EmailPipaProvider.entries.forEach { provider ->
            every { userPrefsStore.observeEmailSourceConnected(provider) } returns flowOf(false)
            every { userPrefsStore.observeEmailSourceManagedByBackend(provider) } returns flowOf(false)
        }
        coEvery { imapCredentialStore.load(any()) } returns null
    }

    private fun buildResolver(): DefaultRuntimeSyncSourceResolver =
        DefaultRuntimeSyncSourceResolver(
            userPrefsStore = userPrefsStore,
            imapCredentialStore = imapCredentialStore,
            mediaAudioPermissionChecker = mediaAudioPermissionChecker,
        )
}
