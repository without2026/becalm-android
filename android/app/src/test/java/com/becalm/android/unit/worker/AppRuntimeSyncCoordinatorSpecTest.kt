package com.becalm.android.unit.worker

import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.ui.sources.ContactsPermissionChecker
import com.becalm.android.worker.AppRuntimeSyncCoordinator
import com.becalm.android.worker.ContentObserverBootstrap
import com.becalm.android.worker.ForegroundCatchUpScheduler
import com.becalm.android.worker.MediaAudioPermissionChecker
import com.becalm.android.worker.WorkScheduler
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AppRuntimeSyncCoordinatorSpecTest {

    private val foregroundCatchUpScheduler: ForegroundCatchUpScheduler = mockk(relaxed = true)
    private val contentObserverBootstrap: ContentObserverBootstrap = mockk(relaxed = true)
    private val workScheduler: WorkScheduler = mockk(relaxed = true)
    private val userPrefsStore: UserPrefsStore = mockk(relaxed = true)
    private val contactsPermissionChecker: ContactsPermissionChecker = mockk(relaxed = true)
    private val mediaAudioPermissionChecker: MediaAudioPermissionChecker = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)

    @Test
    fun `startup starts foreground catch-up and schedules periodic redundancy when capabilities are available`() = runTest {
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
        every { userPrefsStore.observeSourceEnabled(SourceType.VOICE) } returns flowOf(true)
        every { userPrefsStore.observeRecordingFolderTreeUri() } returns flowOf("content://tree/recordings")
        every { contactsPermissionChecker.isGranted() } returns true
        every { mediaAudioPermissionChecker.isGranted() } returns true

        val coordinator = buildCoordinator()

        coordinator.start()

        verify(exactly = 1) { foregroundCatchUpScheduler.start() }
        verify(exactly = 1) { contentObserverBootstrap.start() }
        verify(exactly = 1) { workScheduler.scheduleUploadRedundancy() }
        verify(exactly = 1) { workScheduler.scheduleEnrichmentSweep() }
        verify(exactly = 1) { workScheduler.enqueuePeriodic(SourceType.GMAIL) }
        verify(exactly = 1) { workScheduler.enqueuePeriodic(SourceType.NAVER_IMAP) }
        verify(exactly = 1) { workScheduler.enqueuePeriodic(SourceType.DAUM_IMAP) }
        verify(exactly = 1) { workScheduler.enqueuePeriodic(SourceType.OUTLOOK_MAIL) }
        verify(exactly = 1) { workScheduler.enqueuePeriodic(SourceType.GOOGLE_CALENDAR) }
        verify(exactly = 1) { workScheduler.enqueuePeriodic(SourceType.OUTLOOK_CALENDAR) }
        verify(exactly = 0) { contentObserverBootstrap.stop() }
        verify(exactly = 0) { workScheduler.cancelEnrichmentSweep() }
    }

    @Test
    fun `startup stops observer and cancels periodic enrichment when permissions are absent`() = runTest {
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
        every { userPrefsStore.observeSourceEnabled(SourceType.VOICE) } returns flowOf(true)
        every { userPrefsStore.observeRecordingFolderTreeUri() } returns flowOf("content://tree/recordings")
        every { contactsPermissionChecker.isGranted() } returns false
        every { mediaAudioPermissionChecker.isGranted() } returns false

        val coordinator = buildCoordinator()

        coordinator.start()

        verify(exactly = 1) { foregroundCatchUpScheduler.start() }
        verify(exactly = 1) { contentObserverBootstrap.stop() }
        verify(exactly = 1) { workScheduler.cancelEnrichmentSweep() }
        verify(exactly = 0) { contentObserverBootstrap.start() }
        verify(exactly = 0) { workScheduler.scheduleEnrichmentSweep() }
        verify(exactly = 1) { workScheduler.scheduleUploadRedundancy() }
    }

    @Test
    fun `startup keeps observer stopped when voice source is disabled even if permission exists`() = runTest {
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
        every { userPrefsStore.observeSourceEnabled(SourceType.VOICE) } returns flowOf(false)
        every { userPrefsStore.observeRecordingFolderTreeUri() } returns flowOf("content://tree/recordings")
        every { contactsPermissionChecker.isGranted() } returns true
        every { mediaAudioPermissionChecker.isGranted() } returns true

        val coordinator = buildCoordinator()

        coordinator.start()

        verify(exactly = 1) { contentObserverBootstrap.stop() }
        verify(exactly = 0) { contentObserverBootstrap.start() }
        verify(exactly = 1) { workScheduler.scheduleEnrichmentSweep() }
    }

    @Test
    fun `startup keeps observer stopped when SAF tree grant is missing even if voice and permission exist`() = runTest {
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
        every { userPrefsStore.observeSourceEnabled(SourceType.VOICE) } returns flowOf(true)
        every { userPrefsStore.observeRecordingFolderTreeUri() } returns flowOf(null)
        every { contactsPermissionChecker.isGranted() } returns true
        every { mediaAudioPermissionChecker.isGranted() } returns true

        val coordinator = buildCoordinator()

        coordinator.start()

        verify(exactly = 1) { contentObserverBootstrap.stop() }
        verify(exactly = 0) { contentObserverBootstrap.start() }
        verify(exactly = 1) { workScheduler.scheduleEnrichmentSweep() }
    }

    @Test
    fun `startup does not enroll periodic chains without a signed in user`() = runTest {
        every { userPrefsStore.observeCurrentUserId() } returns flowOf(null)

        val coordinator = buildCoordinator()

        coordinator.start()

        verify(exactly = 1) { foregroundCatchUpScheduler.start() }
        verify(exactly = 1) { contentObserverBootstrap.stop() }
        verify(exactly = 1) { workScheduler.cancelEnrichmentSweep() }
        verify(exactly = 0) { workScheduler.scheduleUploadRedundancy() }
        verify(exactly = 0) { workScheduler.enqueuePeriodic(any()) }
    }

    private fun buildCoordinator(): AppRuntimeSyncCoordinator = AppRuntimeSyncCoordinator(
        foregroundCatchUpScheduler = foregroundCatchUpScheduler,
        contentObserverBootstrap = contentObserverBootstrap,
        workScheduler = workScheduler,
        userPrefsStore = userPrefsStore,
        contactsPermissionChecker = contactsPermissionChecker,
        mediaAudioPermissionChecker = mediaAudioPermissionChecker,
        logger = logger,
    )
}
