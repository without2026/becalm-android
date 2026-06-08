package com.becalm.android.unit.worker

import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.ManualMemoryOutboxDao
import com.becalm.android.data.local.db.dao.MeetingSpeakerPreviewDao
import com.becalm.android.data.local.db.dao.PersonIndexDao
import com.becalm.android.data.local.db.dao.PersonIndexStaleLinkedSourceRow
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.local.db.entity.PendingSourceParticipantMirrorEntity
import com.becalm.android.data.local.db.entity.ManualMemoryOutboxEntity
import com.becalm.android.data.repository.ProcessingPhase
import com.becalm.android.data.repository.ProcessingSourceState
import com.becalm.android.data.repository.ProcessingStatusRepository
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.ui.onboarding.RecordingPathSelection
import com.becalm.android.ui.sources.ContactsPermissionChecker
import com.becalm.android.worker.AppRuntimeSyncCoordinator
import com.becalm.android.worker.ContentObserverBootstrap
import com.becalm.android.worker.ForegroundCatchUpScheduler
import com.becalm.android.worker.MediaAudioPermissionChecker
import com.becalm.android.worker.RuntimeSyncSourceResolver
import com.becalm.android.worker.WorkScheduler
import io.mockk.coEvery
import io.mockk.coVerify
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
    private val rawIngestionEventDao: RawIngestionEventDao = mockk(relaxed = true)
    private val manualMemoryOutboxDao: ManualMemoryOutboxDao = mockk(relaxed = true)
    private val meetingSpeakerPreviewDao: MeetingSpeakerPreviewDao = mockk(relaxed = true)
    private val personIndexDao: PersonIndexDao = mockk(relaxed = true)
    private val processingStatusRepository: ProcessingStatusRepository = mockk(relaxed = true)
    private val runtimeSyncSourceResolver: RuntimeSyncSourceResolver = mockk(relaxed = true)
    private val contactsPermissionChecker: ContactsPermissionChecker = mockk(relaxed = true)
    private val mediaAudioPermissionChecker: MediaAudioPermissionChecker = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)

    init {
        every { userPrefsStore.observeSourceEnabled(SourceType.CALL_RECORDING) } returns flowOf(false)
        every { userPrefsStore.observeRecordingFolderTreeUri(any()) } returns flowOf(null)
        every { userPrefsStore.observeContactsConsent() } returns flowOf(true)
        coEvery { manualMemoryOutboxDao.findPendingForUser(any(), any()) } returns emptyList()
        coEvery { personIndexDao.findPendingSourceParticipantMirrors(any(), any()) } returns emptyList()
        coEvery { personIndexDao.findStaleLinkedSourceProjectionRows(any(), any()) } returns emptyList()
        coEvery { personIndexDao.findStaleRawSourceProjectionRows(any(), any()) } returns emptyList()
        every { processingStatusRepository.observeAll() } returns flowOf(emptyList())
        coEvery { rawIngestionEventDao.countActiveProcessingForSource(any(), any()) } returns 0
        coEvery { rawIngestionEventDao.countFailedProcessingItemsForSource(any(), any()) } returns 0
        coEvery { meetingSpeakerPreviewDao.countProcessingForSource(any(), any()) } returns 0
        coEvery { meetingSpeakerPreviewDao.markProcessingDoneForSyncedRawEvents(any(), any()) } returns 0
        coEvery { meetingSpeakerPreviewDao.markProcessingFailedForFailedRawEvents(any(), any(), any()) } returns 0
    }

    @Test
    fun `startup starts foreground catch-up and schedules periodic redundancy when capabilities are available`() = runTest {
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
        every { userPrefsStore.observeSourceEnabled(SourceType.VOICE) } returns flowOf(true)
        every { userPrefsStore.observeSourceEnabled(SourceType.MEETING) } returns flowOf(false)
        every { userPrefsStore.observeRecordingFolderTreeUri() } returns flowOf("content://tree/recordings")
        every { userPrefsStore.observeRecordingFolderTreeUri(SourceType.VOICE) } returns flowOf("content://tree/voice")
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
        verify(exactly = 1) { workScheduler.enqueueEnrichment() }
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
        every { userPrefsStore.observeRecordingFolderTreeUri(SourceType.VOICE) } returns flowOf("content://tree/voice")
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
        every { userPrefsStore.observeRecordingFolderTreeUri(SourceType.VOICE) } returns flowOf("content://tree/voice")
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
    fun `startup starts observer when only call recording source is enabled`() = runTest {
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
        every { userPrefsStore.observeSourceEnabled(SourceType.VOICE) } returns flowOf(false)
        every { userPrefsStore.observeSourceEnabled(SourceType.CALL_RECORDING) } returns flowOf(true)
        every { userPrefsStore.observeSourceEnabled(SourceType.MEETING) } returns flowOf(false)
        every { userPrefsStore.observeRecordingFolderTreeUri() } returns flowOf("content://tree/recordings")
        every {
            userPrefsStore.observeRecordingFolderTreeUri(SourceType.CALL_RECORDING)
        } returns flowOf("content://tree/call")
        every { contactsPermissionChecker.isGranted() } returns true
        every { mediaAudioPermissionChecker.isGranted() } returns true
        coEvery { runtimeSyncSourceResolver.periodicSources() } returns emptySet()
        coEvery { runtimeSyncSourceResolver.hasBackendMailSource() } returns false

        val coordinator = buildCoordinator()

        coordinator.start()

        verify(exactly = 1) { contentObserverBootstrap.start() }
        verify(exactly = 0) { contentObserverBootstrap.stop() }
    }

    @Test
    fun `startup starts observer with app recording path selection even when SAF tree grant is missing`() = runTest {
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
        every { userPrefsStore.observeSourceEnabled(SourceType.VOICE) } returns flowOf(true)
        every { userPrefsStore.observeSourceEnabled(SourceType.MEETING) } returns flowOf(false)
        every { userPrefsStore.observeRecordingFolderTreeUri() } returns flowOf(null)
        every { userPrefsStore.observeRecordingFolderTreeUri(SourceType.VOICE) } returns flowOf(RecordingPathSelection.VOICE)
        every { contactsPermissionChecker.isGranted() } returns true
        every { mediaAudioPermissionChecker.isGranted() } returns true
        coEvery { runtimeSyncSourceResolver.periodicSources() } returns emptySet()
        coEvery { runtimeSyncSourceResolver.hasBackendMailSource() } returns false

        val coordinator = buildCoordinator()

        coordinator.start()

        verify(exactly = 0) { contentObserverBootstrap.stop() }
        verify(exactly = 1) { contentObserverBootstrap.start() }
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

    @Test
    fun `startup re-enqueues pending source participant mirror queue for signed in user`() = runTest {
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
        every { userPrefsStore.observeSourceEnabled(SourceType.VOICE) } returns flowOf(false)
        every { userPrefsStore.observeSourceEnabled(SourceType.MEETING) } returns flowOf(false)
        every { userPrefsStore.observeRecordingFolderTreeUri() } returns flowOf(null)
        every { contactsPermissionChecker.isGranted() } returns false
        every { mediaAudioPermissionChecker.isGranted() } returns false
        coEvery { runtimeSyncSourceResolver.periodicSources() } returns emptySet()
        coEvery { runtimeSyncSourceResolver.hasBackendMailSource() } returns false
        coEvery {
            personIndexDao.findPendingSourceParticipantMirrors(userId = "user-1", limit = 1)
        } returns listOf(mockk<PendingSourceParticipantMirrorEntity>(relaxed = true))

        val coordinator = buildCoordinator()

        coordinator.start()

        verify(exactly = 1) { workScheduler.enqueueSourceParticipantMirrorRetry() }
    }

    @Test
    fun `startup re-enqueues pending manual memory outbox for signed in user`() = runTest {
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
        every { userPrefsStore.observeSourceEnabled(SourceType.VOICE) } returns flowOf(false)
        every { userPrefsStore.observeSourceEnabled(SourceType.MEETING) } returns flowOf(false)
        every { userPrefsStore.observeRecordingFolderTreeUri() } returns flowOf(null)
        every { contactsPermissionChecker.isGranted() } returns false
        every { mediaAudioPermissionChecker.isGranted() } returns false
        coEvery { runtimeSyncSourceResolver.periodicSources() } returns emptySet()
        coEvery { runtimeSyncSourceResolver.hasBackendMailSource() } returns false
        coEvery {
            manualMemoryOutboxDao.findPendingForUser(userId = "user-1", limit = 1)
        } returns listOf(mockk<ManualMemoryOutboxEntity>(relaxed = true))

        val coordinator = buildCoordinator()

        coordinator.start()

        verify(exactly = 1) { workScheduler.enqueueManualMemoryOutboxRetry() }
    }

    @Test
    fun `refresh does not repeatedly replace pending source participant mirror work in same process`() = runTest {
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
        every { userPrefsStore.observeSourceEnabled(SourceType.VOICE) } returns flowOf(false)
        every { userPrefsStore.observeSourceEnabled(SourceType.MEETING) } returns flowOf(false)
        every { userPrefsStore.observeRecordingFolderTreeUri() } returns flowOf(null)
        every { contactsPermissionChecker.isGranted() } returns false
        every { mediaAudioPermissionChecker.isGranted() } returns false
        coEvery { runtimeSyncSourceResolver.periodicSources() } returns emptySet()
        coEvery { runtimeSyncSourceResolver.hasBackendMailSource() } returns false
        coEvery {
            personIndexDao.findPendingSourceParticipantMirrors(userId = "user-1", limit = 1)
        } returns listOf(mockk<PendingSourceParticipantMirrorEntity>(relaxed = true))

        val coordinator = buildCoordinator()

        coordinator.start()
        coordinator.refresh()

        verify(exactly = 1) { workScheduler.enqueueSourceParticipantMirrorRetry() }
    }

    @Test
    fun `startup does not enqueue source participant mirror retry when queue is empty`() = runTest {
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
        every { userPrefsStore.observeSourceEnabled(SourceType.VOICE) } returns flowOf(false)
        every { userPrefsStore.observeSourceEnabled(SourceType.MEETING) } returns flowOf(false)
        every { userPrefsStore.observeRecordingFolderTreeUri() } returns flowOf(null)
        every { contactsPermissionChecker.isGranted() } returns false
        every { mediaAudioPermissionChecker.isGranted() } returns false
        coEvery { runtimeSyncSourceResolver.periodicSources() } returns emptySet()
        coEvery { runtimeSyncSourceResolver.hasBackendMailSource() } returns false

        val coordinator = buildCoordinator()

        coordinator.start()

        verify(exactly = 0) { workScheduler.enqueueSourceParticipantMirrorRetry() }
        verify(exactly = 0) { workScheduler.enqueueManualMemoryOutboxRetry() }
    }

    @Test
    fun `startup queues stale linked source projection repair and reindexes people`() = runTest {
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
        every { userPrefsStore.observeSourceEnabled(SourceType.VOICE) } returns flowOf(false)
        every { userPrefsStore.observeSourceEnabled(SourceType.MEETING) } returns flowOf(false)
        every { userPrefsStore.observeRecordingFolderTreeUri() } returns flowOf(null)
        every { contactsPermissionChecker.isGranted() } returns false
        every { mediaAudioPermissionChecker.isGranted() } returns false
        coEvery { runtimeSyncSourceResolver.periodicSources() } returns emptySet()
        coEvery { runtimeSyncSourceResolver.hasBackendMailSource() } returns false
        coEvery {
            personIndexDao.findStaleLinkedSourceProjectionRows(userId = "user-1", limit = 500)
        } returns listOf(
            PersonIndexStaleLinkedSourceRow(sourceType = SourceType.NAVER_IMAP, sourceEventId = "raw-naver-1"),
            PersonIndexStaleLinkedSourceRow(sourceType = SourceType.GMAIL, sourceEventId = "raw-gmail-1"),
        )

        val coordinator = buildCoordinator()

        coordinator.start()

        coVerify(exactly = 1) {
            personIndexDao.upsertDirtySources(
                match { rows ->
                    rows.size == 2 &&
                        rows.any {
                            it.userId == "user-1" &&
                                it.sourceType == SourceType.NAVER_IMAP &&
                                it.sourceRef == "raw:raw-naver-1" &&
                                it.interactionKind == "email" &&
                                it.reason == "stale_linked_source_projection_repair"
                        } &&
                        rows.any {
                            it.userId == "user-1" &&
                                it.sourceType == SourceType.GMAIL &&
                                it.sourceRef == "raw:raw-gmail-1" &&
                                it.interactionKind == "email" &&
                                it.reason == "stale_linked_source_projection_repair"
                        }
                },
            )
        }
        verify(exactly = 1) { workScheduler.enqueuePersonInteractionIndex(initialDelaySeconds = 0L) }
    }

    @Test
    fun `startup queues stale raw source projection repair and reindexes people`() = runTest {
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
        every { userPrefsStore.observeSourceEnabled(SourceType.VOICE) } returns flowOf(false)
        every { userPrefsStore.observeSourceEnabled(SourceType.MEETING) } returns flowOf(false)
        every { userPrefsStore.observeRecordingFolderTreeUri() } returns flowOf(null)
        every { contactsPermissionChecker.isGranted() } returns false
        every { mediaAudioPermissionChecker.isGranted() } returns false
        coEvery { runtimeSyncSourceResolver.periodicSources() } returns emptySet()
        coEvery { runtimeSyncSourceResolver.hasBackendMailSource() } returns false
        coEvery {
            personIndexDao.findStaleRawSourceProjectionRows(userId = "user-1", limit = 500)
        } returns listOf(
            PersonIndexStaleLinkedSourceRow(sourceType = SourceType.NAVER_IMAP, sourceEventId = "server-source-event-1"),
        )

        val coordinator = buildCoordinator()

        coordinator.start()

        coVerify(exactly = 1) {
            personIndexDao.upsertDirtySources(
                match { rows ->
                    rows.size == 1 &&
                        rows.single().userId == "user-1" &&
                        rows.single().sourceType == SourceType.NAVER_IMAP &&
                        rows.single().sourceRef == "raw:server-source-event-1" &&
                        rows.single().interactionKind == "email" &&
                        rows.single().reason == "stale_raw_source_projection_repair"
                },
            )
        }
        verify(exactly = 1) { workScheduler.enqueuePersonInteractionIndex(initialDelaySeconds = 0L) }
    }

    @Test
    fun `startup repairs completed meeting preview rows and clears stale active processing`() = runTest {
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
        every { userPrefsStore.observeSourceEnabled(SourceType.VOICE) } returns flowOf(false)
        every { userPrefsStore.observeSourceEnabled(SourceType.MEETING) } returns flowOf(false)
        every { userPrefsStore.observeRecordingFolderTreeUri() } returns flowOf(null)
        every { contactsPermissionChecker.isGranted() } returns false
        every { mediaAudioPermissionChecker.isGranted() } returns false
        every { processingStatusRepository.observeAll() } returns flowOf(
            listOf(
                ProcessingSourceState(
                    sourceType = SourceType.MEETING,
                    phase = ProcessingPhase.GEMINI,
                    message = "내용 정리 중",
                ),
            ),
        )
        coEvery { runtimeSyncSourceResolver.periodicSources() } returns emptySet()
        coEvery { runtimeSyncSourceResolver.hasBackendMailSource() } returns false
        coEvery { meetingSpeakerPreviewDao.markProcessingDoneForSyncedRawEvents("user-1", any()) } returns 1

        val coordinator = buildCoordinator()

        coordinator.start()

        coVerify(exactly = 1) {
            meetingSpeakerPreviewDao.markProcessingDoneForSyncedRawEvents("user-1", any())
        }
        coVerify(exactly = 1) {
            processingStatusRepository.recordSynced(SourceType.MEETING)
        }
    }

    @Test
    fun `startup turns stale active voice status into failure when only failed rows remain`() = runTest {
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
        every { userPrefsStore.observeSourceEnabled(SourceType.VOICE) } returns flowOf(false)
        every { userPrefsStore.observeSourceEnabled(SourceType.MEETING) } returns flowOf(false)
        every { userPrefsStore.observeRecordingFolderTreeUri() } returns flowOf(null)
        every { contactsPermissionChecker.isGranted() } returns false
        every { mediaAudioPermissionChecker.isGranted() } returns false
        every { processingStatusRepository.observeAll() } returns flowOf(
            listOf(
                ProcessingSourceState(
                    sourceType = SourceType.VOICE,
                    phase = ProcessingPhase.GEMINI,
                    message = "내용 정리 중",
                ),
            ),
        )
        coEvery { runtimeSyncSourceResolver.periodicSources() } returns emptySet()
        coEvery { runtimeSyncSourceResolver.hasBackendMailSource() } returns false
        coEvery { rawIngestionEventDao.countFailedProcessingItemsForSource("user-1", SourceType.VOICE) } returns 3

        val coordinator = buildCoordinator()

        coordinator.start()

        coVerify(exactly = 1) {
            processingStatusRepository.recordError(SourceType.VOICE, itemCount = 3)
        }
    }

    private fun buildCoordinator(): AppRuntimeSyncCoordinator =
        AppRuntimeSyncCoordinator(
            scope = CoroutineScope(Dispatchers.Unconfined),
            foregroundCatchUpScheduler = foregroundCatchUpScheduler,
            contentObserverBootstrap = contentObserverBootstrap,
            workScheduler = workScheduler,
            userPrefsStore = userPrefsStore,
            rawIngestionEventDao = rawIngestionEventDao,
            manualMemoryOutboxDao = manualMemoryOutboxDao,
            meetingSpeakerPreviewDao = meetingSpeakerPreviewDao,
            personIndexDao = personIndexDao,
            processingStatusRepository = processingStatusRepository,
            runtimeSyncSourceResolver = runtimeSyncSourceResolver,
            contactsPermissionChecker = contactsPermissionChecker,
            mediaAudioPermissionChecker = mediaAudioPermissionChecker,
            logger = logger,
            ioDispatcher = Dispatchers.Unconfined,
            mainDispatcher = Dispatchers.Unconfined,
        )
}
