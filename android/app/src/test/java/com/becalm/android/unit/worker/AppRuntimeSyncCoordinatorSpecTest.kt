package com.becalm.android.unit.worker

import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.ui.sources.ContactsPermissionChecker
import com.becalm.android.worker.AppRuntimeSyncCoordinator
import com.becalm.android.worker.ContentObserverBootstrap
import com.becalm.android.worker.ForegroundCatchUpScheduler
import com.becalm.android.worker.MediaAudioPermissionChecker
import com.becalm.android.worker.RuntimeSyncSourceResolver
import com.becalm.android.worker.WorkScheduler
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AppRuntimeSyncCoordinatorSpecTest {

    private val foregroundCatchUpScheduler: ForegroundCatchUpScheduler = mockk(relaxed = true)
    private val contentObserverBootstrap: ContentObserverBootstrap = mockk(relaxed = true)
    private val workScheduler: WorkScheduler = mockk(relaxed = true)
    private val userPrefsStore: UserPrefsStore = mockk(relaxed = true)
    private val runtimeSyncSourceResolver: RuntimeSyncSourceResolver = mockk(relaxed = true)
    private val contactsPermissionChecker: ContactsPermissionChecker = mockk(relaxed = true)
    private val mediaAudioPermissionChecker: MediaAudioPermissionChecker = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)

    @Test
    fun `startup starts foreground catch-up and schedules periodic redundancy when capabilities are available`() = runTest {
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
        every { userPrefsStore.observeSourceEnabled(SourceType.VOICE) } returns flowOf(true)
        every { userPrefsStore.observeSourceEnabled(SourceType.MEETING) } returns flowOf(false)
        every { userPrefsStore.observeRecordingFolderTreeUri() } returns flowOf("content://tree/recordings")
        every { contactsPermissionChecker.isGranted() } returns true
        every { mediaAudioPermissionChecker.isGranted() } returns true
        coEvery { runtimeSyncSourceResolver.periodicSources() } returns setOf(
            SourceType.NAVER_IMAP,
            SourceType.DAUM_IMAP,
            SourceType.GOOGLE_CALENDAR,
            SourceType.OUTLOOK_CALENDAR,
        )
        coEvery { runtimeSyncSourceResolver.hasBackendMailSource() } returns true

        val coordinator = buildCoordinator()

        coordinator.start()

        verify(exactly = 1) { foregroundCatchUpScheduler.start() }
        verify(exactly = 1) { contentObserverBootstrap.start() }
        verify(exactly = 1) { workScheduler.scheduleBackendMailSync() }
        verify(exactly = 1) { workScheduler.scheduleUploadRedundancy() }
        verify(exactly = 1) { workScheduler.scheduleRetentionSweep() }
        verify(exactly = 1) { workScheduler.scheduleOverdueSweep() }
        verify(exactly = 1) { workScheduler.scheduleEnrichmentSweep() }
        verify(exactly = 1) { workScheduler.enqueuePeriodic(SourceType.NAVER_IMAP) }
        verify(exactly = 1) { workScheduler.enqueuePeriodic(SourceType.DAUM_IMAP) }
        verify(exactly = 1) { workScheduler.enqueuePeriodic(SourceType.GOOGLE_CALENDAR) }
        verify(exactly = 1) { workScheduler.enqueuePeriodic(SourceType.OUTLOOK_CALENDAR) }
        verify(exactly = 0) { contentObserverBootstrap.stop() }
    }

    @Test
    // spec: VOI-005
    fun `startup stops observer and cancels periodic enrichment when permissions are absent`() = runTest {
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
        every { userPrefsStore.observeSourceEnabled(SourceType.VOICE) } returns flowOf(true)
        every { userPrefsStore.observeSourceEnabled(SourceType.MEETING) } returns flowOf(false)
        every { userPrefsStore.observeRecordingFolderTreeUri() } returns flowOf("content://tree/recordings")
        every { contactsPermissionChecker.isGranted() } returns false
        every { mediaAudioPermissionChecker.isGranted() } returns false
        coEvery { runtimeSyncSourceResolver.periodicSources() } returns emptySet()
        coEvery { runtimeSyncSourceResolver.hasBackendMailSource() } returns false

        val coordinator = buildCoordinator()

        coordinator.start()

        verify(exactly = 1) { foregroundCatchUpScheduler.start() }
        verify(exactly = 1) { contentObserverBootstrap.stop() }
        verify(exactly = 0) { contentObserverBootstrap.start() }
        verify(exactly = 0) { workScheduler.scheduleEnrichmentSweep() }
        verify(exactly = 1) { workScheduler.scheduleUploadRedundancy() }
        verify(exactly = 1) { workScheduler.scheduleRetentionSweep() }
        verify(exactly = 1) { workScheduler.scheduleOverdueSweep() }
    }

    @Test
    fun `startup keeps observer stopped when voice source is disabled even if permission exists`() = runTest {
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
        every { userPrefsStore.observeSourceEnabled(SourceType.VOICE) } returns flowOf(false)
        every { userPrefsStore.observeSourceEnabled(SourceType.MEETING) } returns flowOf(false)
        every { userPrefsStore.observeRecordingFolderTreeUri() } returns flowOf("content://tree/recordings")
        every { contactsPermissionChecker.isGranted() } returns true
        every { mediaAudioPermissionChecker.isGranted() } returns true
        coEvery { runtimeSyncSourceResolver.periodicSources() } returns emptySet()
        coEvery { runtimeSyncSourceResolver.hasBackendMailSource() } returns false

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
        every { userPrefsStore.observeSourceEnabled(SourceType.MEETING) } returns flowOf(false)
        every { userPrefsStore.observeRecordingFolderTreeUri() } returns flowOf(null)
        every { contactsPermissionChecker.isGranted() } returns true
        every { mediaAudioPermissionChecker.isGranted() } returns true
        coEvery { runtimeSyncSourceResolver.periodicSources() } returns emptySet()
        coEvery { runtimeSyncSourceResolver.hasBackendMailSource() } returns false

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
        verify(exactly = 0) { workScheduler.scheduleUploadRedundancy() }
        verify(exactly = 0) { workScheduler.scheduleBackendMailSync() }
        verify(exactly = 0) { workScheduler.scheduleRetentionSweep() }
        verify(exactly = 0) { workScheduler.scheduleOverdueSweep() }
        verify(exactly = 0) { workScheduler.enqueuePeriodic(any()) }
        verify(exactly = 0) { workScheduler.scheduleEnrichmentSweep() }
    }

    @Test
    fun `refresh schedules newly enabled sources after initial recurring enrollment`() = runTest {
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
        every { userPrefsStore.observeSourceEnabled(SourceType.VOICE) } returns flowOf(false)
        every { userPrefsStore.observeSourceEnabled(SourceType.MEETING) } returns flowOf(false)
        every { userPrefsStore.observeRecordingFolderTreeUri() } returns flowOf(null)
        every { contactsPermissionChecker.isGranted() } returns false
        every { mediaAudioPermissionChecker.isGranted() } returns false
        coEvery { runtimeSyncSourceResolver.periodicSources() } returnsMany listOf(
            emptySet(),
            setOf(SourceType.GOOGLE_CALENDAR),
        )
        coEvery { runtimeSyncSourceResolver.hasBackendMailSource() } returnsMany listOf(
            false,
            true,
        )

        val coordinator = buildCoordinator()

        coordinator.start()
        coordinator.refresh()

        verify(exactly = 1) { workScheduler.enqueuePeriodic(SourceType.GOOGLE_CALENDAR) }
        verify(exactly = 1) { workScheduler.scheduleBackendMailSync() }
        verify(exactly = 1) { workScheduler.scheduleUploadRedundancy() }
        verify(exactly = 1) { workScheduler.scheduleRetentionSweep() }
        verify(exactly = 1) { workScheduler.scheduleOverdueSweep() }
    }

    private fun buildCoordinator(): AppRuntimeSyncCoordinator =
        AppRuntimeSyncCoordinator(
            scope = CoroutineScope(Dispatchers.Unconfined),
            foregroundCatchUpScheduler = foregroundCatchUpScheduler,
            contentObserverBootstrap = contentObserverBootstrap,
            workScheduler = workScheduler,
            userPrefsStore = userPrefsStore,
            runtimeSyncSourceResolver = runtimeSyncSourceResolver,
            contactsPermissionChecker = contactsPermissionChecker,
            mediaAudioPermissionChecker = mediaAudioPermissionChecker,
            logger = logger,
            ioDispatcher = Dispatchers.Unconfined,
            mainDispatcher = Dispatchers.Unconfined,
        )
}
