package com.becalm.android.unit.ui.onboarding

import app.cash.turbine.test
import com.becalm.android.R
import com.becalm.android.core.observability.ObservabilityClient
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.EmailPipaProvider
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.local.db.entity.SelfIdentityAnchorEntity
import com.becalm.android.data.local.db.entity.SourceConnectionEntity
import com.becalm.android.data.local.db.entity.UserProfileEntity
import com.becalm.android.data.local.secure.ImapCredentialStore
import com.becalm.android.data.local.secure.ImapCredentials
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.remote.dto.CalendarSyncResponse
import com.becalm.android.data.remote.supabase.SupabaseAuthProvider
import com.becalm.android.data.remote.supabase.SupabaseSession
import com.becalm.android.data.remote.supabase.SupabaseSessionStore
import com.becalm.android.data.repository.CalendarEventRepository
import com.becalm.android.data.repository.CommitmentParticipantRepository
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.SelfIdentityRepository
import com.becalm.android.data.repository.FirstMemoryRepository
import com.becalm.android.data.repository.FirstMemorySaveResult
import com.becalm.android.data.repository.OnboardingActivationPreview
import com.becalm.android.data.repository.OnboardingActivationProgress
import com.becalm.android.data.repository.OnboardingActivationPreviewRepository
import com.becalm.android.data.repository.OnboardingActivationPreviewResult
import com.becalm.android.data.repository.OnboardingSelfIdentityCommit
import com.becalm.android.data.repository.PersonEnrichmentRepository
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.ScheduleEventLinkRepository
import com.becalm.android.data.repository.SourceConnectionRepository
import com.becalm.android.data.repository.SourceEventParticipantRepository
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.data.repository.UserProfileRepository
import com.becalm.android.domain.onboarding.FirstMemoryKind
import com.becalm.android.domain.onboarding.FirstMemoryOrigin
import com.becalm.android.ui.onboarding.ContactsPermissionEffect
import com.becalm.android.ui.onboarding.CalendarConnectEvent
import com.becalm.android.ui.onboarding.CalendarOAuthConnector
import com.becalm.android.ui.onboarding.CalendarOAuthProvider
import com.becalm.android.ui.onboarding.CalendarOAuthResult
import com.becalm.android.ui.onboarding.EmailOAuthConnector
import com.becalm.android.ui.onboarding.EmailConnectEvent
import com.becalm.android.ui.onboarding.EmailOAuthProvider
import com.becalm.android.ui.onboarding.EmailOAuthResult
import com.becalm.android.ui.onboarding.GmailActivationPreviewStatus
import com.becalm.android.ui.onboarding.OnboardingStep
import com.becalm.android.ui.onboarding.OnboardingSourceProvider
import com.becalm.android.ui.onboarding.OnboardingSetupDestination
import com.becalm.android.ui.onboarding.OnboardingSetupEffect
import com.becalm.android.ui.onboarding.OnboardingSetupStage
import com.becalm.android.ui.onboarding.OnboardingViewModel
import com.becalm.android.ui.onboarding.PipaConsentEvent
import com.becalm.android.ui.onboarding.RecordingPathSelection
import com.becalm.android.ui.onboarding.StepStatus
import com.becalm.android.ui.onboarding.ONBOARDING_INTRO_PAGE_COUNT
import com.becalm.android.worker.AppRuntimeSyncCoordinator
import com.becalm.android.worker.WorkScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelSpecTest {

    private val testDispatcher = StandardTestDispatcher()
    private val userPrefsStore: UserPrefsStore = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)
    private val observability: ObservabilityClient = mockk(relaxed = true)
    private val imapCredentialStore: ImapCredentialStore = mockk(relaxed = true)
    private val emailOAuthConnector: EmailOAuthConnector = mockk(relaxed = true)
    private val calendarOAuthConnector: CalendarOAuthConnector = mockk(relaxed = true)
    private val appRuntimeSyncCoordinator: AppRuntimeSyncCoordinator = mockk(relaxed = true)
    private val sourceStatusRepository: SourceStatusRepository = mockk(relaxed = true)
    private val sourceConnectionRepository: SourceConnectionRepository = mockk(relaxed = true)
    private val selfIdentityRepository: SelfIdentityRepository = mockk(relaxed = true)
    private val userProfileRepository: UserProfileRepository = mockk(relaxed = true)
    private val sessionStore: SupabaseSessionStore = mockk(relaxed = true)
    private val calendarEventRepository: CalendarEventRepository = mockk(relaxed = true)
    private val commitmentRepository: CommitmentRepository = mockk(relaxed = true)
    private val sourceEventParticipantRepository: SourceEventParticipantRepository = mockk(relaxed = true)
    private val commitmentParticipantRepository: CommitmentParticipantRepository = mockk(relaxed = true)
    private val scheduleEventLinkRepository: ScheduleEventLinkRepository = mockk(relaxed = true)
    private val personEnrichmentRepository: PersonEnrichmentRepository = mockk(relaxed = true)
    private val firstMemoryRepository: FirstMemoryRepository = mockk(relaxed = true)
    private val onboardingActivationPreviewRepository: OnboardingActivationPreviewRepository = mockk(relaxed = true)
    private val rawIngestionRepository: RawIngestionRepository = mockk(relaxed = true)
    private val workScheduler: WorkScheduler = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-123")
        every { userPrefsStore.observeTermsAccepted() } returns flowOf(true)
        every { userPrefsStore.observeOnboardingStepStatuses() } returns flowOf(emptyMap())
        every { userPrefsStore.observeSourceEnabled(any()) } returns flowOf(false)
        every { userPrefsStore.observeRecordingFolderTreeUri(any<String>()) } returns flowOf(null)
        EmailPipaProvider.entries.forEach { provider ->
            every { userPrefsStore.observeEmailPipaConsent(provider) } returns flowOf(false)
        }
        coEvery { sourceStatusRepository.refreshFromServer() } returns BecalmResult.Success(Unit)
        coEvery { sourceConnectionRepository.refresh("user-123") } returns BecalmResult.Success(emptyList())
        coEvery { rawIngestionRepository.releaseAwaitingConsentVoiceAndReturnIds("user-123") } returns
            BecalmResult.Success(emptyList())
        every { sourceConnectionRepository.observeAll("user-123") } returns flowOf(emptyList())
        every { selfIdentityRepository.observeAll("user-123") } returns flowOf(emptyList())
        coEvery { selfIdentityRepository.refresh("user-123") } returns BecalmResult.Success(emptyList())
        coEvery { sessionStore.load() } returns null
        every { personEnrichmentRepository.observeAll() } returns flowOf(emptyList())
        every { calendarEventRepository.observeForUser(any(), any(), any()) } returns flowOf(emptyList())
        coEvery { calendarEventRepository.triggerServerSync() } returns
            BecalmResult.Success(CalendarSyncResponse(synced = 0, status = "succeeded"))
        coEvery { calendarEventRepository.refreshSince(any(), any(), any(), any()) } returns
            BecalmResult.Success(CalendarEventRepository.RefreshStats(0, 0, false, null))
        coEvery { commitmentRepository.refreshSince(any(), any(), any(), any(), any()) } returns
            BecalmResult.Success(CommitmentRepository.RefreshStats(0, 0, false, null))
        coEvery { sourceEventParticipantRepository.refreshSince(any(), any(), any()) } returns
            BecalmResult.Success(SourceEventParticipantRepository.RefreshStats(0, 0, false, null))
        coEvery { commitmentParticipantRepository.refreshSince(any(), any(), any(), any()) } returns
            BecalmResult.Success(CommitmentParticipantRepository.RefreshStats(0, 0, false, null))
        coEvery { scheduleEventLinkRepository.refreshSince(any(), any(), any()) } returns
            BecalmResult.Success(ScheduleEventLinkRepository.RefreshStats(0, 0, false, null))
        coEvery { userProfileRepository.find("user-123") } returns null
        coEvery { userProfileRepository.upsertLocal(any(), any(), any()) } answers {
            userProfile(displayName = secondArg<String?>().orEmpty(), phone = thirdArg<String?>())
        }
        coEvery { userProfileRepository.upsertLocal(any(), any(), any(), any(), any()) } answers {
            userProfile(displayName = secondArg<String?>().orEmpty(), phone = thirdArg<String?>())
        }
        coEvery { userProfileRepository.updateRemote(any(), any(), any(), any()) } answers {
            BecalmResult.Success(userProfile(displayName = secondArg<String?>().orEmpty(), phone = thirdArg<String?>()))
        }
        coEvery {
            selfIdentityRepository.commitOnboardingSelfIdentity(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        } answers {
            BecalmResult.Success(
                identityCommit(
                    displayName = arg(1),
                    email = arg(4),
                    phone = arg(6),
                    alias = arg(9),
                ),
            )
        }
        coEvery { userProfileRepository.markOnboardingCompleted("user-123", any()) } returns
            BecalmResult.Success(userProfile(displayName = "민홍", phone = "+821012345678"))
        coEvery {
            selfIdentityRepository.upsertLocalAnchor(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            selfAnchor(id = "local-${secondArg<String>()}-${thirdArg<String>()}", type = secondArg(), value = thirdArg())
        }
        coEvery {
            selfIdentityRepository.upsertLocalAnchor(any(), any(), any(), any(), any())
        } answers {
            selfAnchor(id = "local-${secondArg<String>()}-${thirdArg<String>()}", type = secondArg(), value = thirdArg())
        }
        coEvery {
            selfIdentityRepository.createAnchor(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            BecalmResult.Success(selfAnchor(id = "remote-${secondArg<String>()}-${thirdArg<String>()}", type = secondArg(), value = thirdArg()))
        }
        coEvery {
            selfIdentityRepository.createAnchor(any(), any(), any(), any(), any())
        } answers {
            BecalmResult.Success(selfAnchor(id = "remote-${secondArg<String>()}-${thirdArg<String>()}", type = secondArg(), value = thirdArg()))
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `constructor hydrates durable progress after state flows are initialized`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))

        val viewModel = buildViewModel()

        assertEquals(OnboardingStep.PIPA_CONSENT, viewModel.steps[viewModel.uiState.value.currentStepIndex])
    }

    @Test
    fun `ONB-PIPA granted persists consent emits event and advances to recording folder`() = runTest {
        val viewModel = buildViewModel()

        viewModel.pipaConsentEvents.test {
            viewModel.onPipaConsentGranted()
            advanceUntilIdle()

            assertEquals(PipaConsentEvent.PipaConsentSaved(true), awaitItem())
            assertEquals(
                OnboardingStep.RECORDING_FOLDER,
                viewModel.steps[viewModel.uiState.value.currentStepIndex],
            )
            assertEquals(
                StepStatus.GRANTED,
                viewModel.uiState.value.stepStates.getValue(OnboardingStep.PIPA_CONSENT),
            )
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { userPrefsStore.setThirdPartyProvisionConsent(true) }
    }

    @Test
    fun `ONB-PIPA and ONB-002 declined consent skips recording folder and advances to contacts`() = runTest {
        val viewModel = buildViewModel()

        viewModel.pipaConsentEvents.test {
            viewModel.onPipaConsentDeclined()
            advanceUntilIdle()

            assertEquals(PipaConsentEvent.PipaConsentSaved(false), awaitItem())
            assertEquals(
                OnboardingStep.CONTACTS_PERM,
                viewModel.steps[viewModel.uiState.value.currentStepIndex],
            )
            assertEquals(
                StepStatus.DENIED,
                viewModel.uiState.value.stepStates.getValue(OnboardingStep.PIPA_CONSENT),
            )
            assertEquals(
                StepStatus.SKIPPED,
                viewModel.uiState.value.stepStates.getValue(OnboardingStep.RECORDING_FOLDER),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ONB-005 skipping battery optimization still advances to cold sync`() = runTest {
        val viewModel = buildViewModel()

        viewModel.onSkipStep(OnboardingStep.BATTERY_OPT)
        advanceUntilIdle()

        assertEquals(
            StepStatus.SKIPPED,
            viewModel.uiState.value.stepStates.getValue(OnboardingStep.BATTERY_OPT),
        )
        assertEquals(
            OnboardingStep.COLD_SYNC,
            viewModel.steps[viewModel.uiState.value.currentStepIndex],
        )
    }

    @Test
    fun `ONB-003 recording path permission denied clears path selection and disables recordings sources`() = runTest {
        val viewModel = buildViewModel()

        viewModel.onRecordingFolderPermissionResult(granted = false)
        advanceUntilIdle()

        coVerify(exactly = 1) { userPrefsStore.setRecordingFolderTreeUri(null) }
        coVerify(exactly = 1) { userPrefsStore.setRecordingFolderTreeUri(SourceType.VOICE, null) }
        coVerify(exactly = 1) { userPrefsStore.setRecordingFolderTreeUri(SourceType.CALL_RECORDING, null) }
        coVerify(exactly = 1) { userPrefsStore.setRecordingFolderTreeUri(SourceType.MEETING, null) }
        coVerify(exactly = 1) { userPrefsStore.setSourceEnabled(com.becalm.android.data.remote.dto.SourceType.VOICE, false) }
        coVerify(exactly = 1) { userPrefsStore.setSourceEnabled(com.becalm.android.data.remote.dto.SourceType.CALL_RECORDING, false) }
        coVerify(exactly = 1) { userPrefsStore.setSourceEnabled(com.becalm.android.data.remote.dto.SourceType.MEETING, false) }
        verify(exactly = 1) { appRuntimeSyncCoordinator.refresh() }
        assertEquals(
            StepStatus.DENIED,
            viewModel.uiState.value.stepStates.getValue(OnboardingStep.RECORDING_FOLDER),
        )
    }

    @Test
    fun `ONB-003 recording path selection persists MediaStore preset enables recordings sources and marks recording folder granted`() = runTest {
        val viewModel = buildViewModel()

        viewModel.onRecordingPathSelected()
        advanceUntilIdle()

        coVerify(exactly = 1) { userPrefsStore.setRecordingFolderTreeUri(RecordingPathSelection.COMMON) }
        coVerify(exactly = 1) { userPrefsStore.setRecordingFolderTreeUri(SourceType.VOICE, RecordingPathSelection.VOICE) }
        coVerify(exactly = 1) {
            userPrefsStore.setRecordingFolderTreeUri(SourceType.CALL_RECORDING, RecordingPathSelection.CALL_RECORDING)
        }
        coVerify(exactly = 1) { userPrefsStore.setRecordingFolderTreeUri(SourceType.MEETING, RecordingPathSelection.MEETING) }
        coVerify(exactly = 1) { userPrefsStore.setSourceEnabled(com.becalm.android.data.remote.dto.SourceType.VOICE, true) }
        coVerify(exactly = 1) { userPrefsStore.setSourceEnabled(com.becalm.android.data.remote.dto.SourceType.CALL_RECORDING, true) }
        coVerify(exactly = 1) { userPrefsStore.setSourceEnabled(com.becalm.android.data.remote.dto.SourceType.MEETING, true) }
        coVerify(exactly = 1) { userPrefsStore.setThirdPartyProvisionConsent(true) }
        coVerify(exactly = 1) { rawIngestionRepository.releaseAwaitingConsentVoiceAndReturnIds("user-123") }
        verify(exactly = 1) { appRuntimeSyncCoordinator.refresh() }
        verify(exactly = 1) { workScheduler.enqueueMediaStoreOneShotNow(30) }
        assertEquals(
            StepStatus.GRANTED,
            viewModel.uiState.value.stepStates.getValue(OnboardingStep.PIPA_CONSENT),
        )
        assertEquals(
            StepStatus.GRANTED,
            viewModel.uiState.value.stepStates.getValue(OnboardingStep.RECORDING_FOLDER),
        )
    }

    @Test
    fun `recording path selection releases awaiting consent rows and reenqueues by source type`() = runTest {
        coEvery { rawIngestionRepository.releaseAwaitingConsentVoiceAndReturnIds("user-123") } returns
            BecalmResult.Success(listOf("raw-voice", "raw-meeting", "raw-message", "raw-missing-ref"))
        coEvery { rawIngestionRepository.findById("raw-voice", "user-123") } returns
            rawEvent("raw-voice", SourceType.VOICE, "content://voice/1")
        coEvery { rawIngestionRepository.findById("raw-meeting", "user-123") } returns
            rawEvent("raw-meeting", SourceType.MEETING, "content://meeting/1")
        coEvery { rawIngestionRepository.findById("raw-message", "user-123") } returns
            rawEvent("raw-message", SourceType.MESSAGE_SCREENSHOT, "file:///message/1.png")
        coEvery { rawIngestionRepository.findById("raw-missing-ref", "user-123") } returns
            rawEvent("raw-missing-ref", SourceType.CALL_RECORDING, null)
        val viewModel = buildViewModel()

        viewModel.onRecordingPathSelected()
        advanceUntilIdle()

        verify(exactly = 1) {
            workScheduler.enqueueVoiceUpload(rawEventId = "raw-voice", audioUri = "content://voice/1")
        }
        verify(exactly = 1) {
            workScheduler.enqueueMeetingSpeakerPreview(rawEventId = "raw-meeting", audioUri = "content://meeting/1")
        }
        verify(exactly = 1) {
            workScheduler.enqueueMessageScreenshotUpload(rawEventId = "raw-message")
        }
        verify(exactly = 0) {
            workScheduler.enqueueMeetingSpeakerPreview(rawEventId = "raw-missing-ref", audioUri = any())
        }
    }

    @Test
    fun `settings recording reconnect enables only the selected recording source`() = runTest {
        val viewModel = buildViewModel()

        viewModel.onRecordingPathSelected(targetSourceType = SourceType.CALL_RECORDING)
        advanceUntilIdle()

        coVerify(exactly = 0) { userPrefsStore.setRecordingFolderTreeUri(RecordingPathSelection.COMMON) }
        coVerify(exactly = 1) {
            userPrefsStore.setRecordingFolderTreeUri(SourceType.CALL_RECORDING, RecordingPathSelection.CALL_RECORDING)
        }
        coVerify(exactly = 0) { userPrefsStore.setRecordingFolderTreeUri(SourceType.VOICE, RecordingPathSelection.VOICE) }
        coVerify(exactly = 0) { userPrefsStore.setRecordingFolderTreeUri(SourceType.MEETING, RecordingPathSelection.MEETING) }
        coVerify(exactly = 0) { userPrefsStore.setSourceEnabled(SourceType.VOICE, true) }
        coVerify(exactly = 1) { userPrefsStore.setSourceEnabled(SourceType.CALL_RECORDING, true) }
        coVerify(exactly = 0) { userPrefsStore.setSourceEnabled(SourceType.MEETING, true) }
        coVerify(exactly = 1) { userPrefsStore.setThirdPartyProvisionConsent(true) }
        coVerify(exactly = 1) { rawIngestionRepository.releaseAwaitingConsentVoiceAndReturnIds("user-123") }
        verify(exactly = 1) { appRuntimeSyncCoordinator.refresh() }
        verify(exactly = 1) { workScheduler.enqueueMediaStoreOneShotNow(30) }
        assertTrue(
            viewModel.uiState.value.stepStates[OnboardingStep.RECORDING_FOLDER] != StepStatus.GRANTED,
        )
    }

    @Test
    fun `ONB email PIPA deny marks provider steps skipped and returns success`() = runTest {
        val viewModel = buildViewModel()

        val result = viewModel.onEmailPipaConsent(
            providers = listOf(EmailPipaProvider.GMAIL, EmailPipaProvider.OUTLOOK_MAIL),
            granted = false,
        )

        assertTrue(result)
        assertEquals(
            StepStatus.SKIPPED,
            viewModel.uiState.value.stepStates.getValue(OnboardingStep.LINK_GMAIL),
        )
        assertEquals(
            StepStatus.SKIPPED,
            viewModel.uiState.value.stepStates.getValue(OnboardingStep.LINK_OUTLOOK_MAIL),
        )
        coVerify(exactly = 1) {
            userPrefsStore.setEmailPipaConsents(
                listOf(EmailPipaProvider.GMAIL, EmailPipaProvider.OUTLOOK_MAIL),
                false,
            )
        }
    }

    @Test
    fun `ONB email PIPA IMAP group grant writes a single batched consent record`() = runTest {
        val viewModel = buildViewModel()

        val result = viewModel.onEmailPipaConsent(EmailPipaProvider.IMAP_GROUP, granted = true)

        assertTrue(result)
        coVerify(exactly = 1) { userPrefsStore.setEmailPipaConsents(EmailPipaProvider.IMAP_GROUP, true) }
        assertEquals(
            StepStatus.NOT_STARTED,
            viewModel.uiState.value.stepStates.getValue(OnboardingStep.LINK_IMAP),
        )
    }

    @Test
    fun `source connection continue skips unfinished source steps including IMAP`() = runTest {
        val viewModel = buildViewModel()

        viewModel.onMarkStepStatus(OnboardingStep.LINK_GMAIL, StepStatus.COMPLETE)
        viewModel.onSkipRemainingSourceConnections()
        advanceUntilIdle()

        assertEquals(
            StepStatus.COMPLETE,
            viewModel.uiState.value.stepStates.getValue(OnboardingStep.LINK_GMAIL),
        )
        assertEquals(
            StepStatus.SKIPPED,
            viewModel.uiState.value.stepStates.getValue(OnboardingStep.LINK_OUTLOOK_MAIL),
        )
        assertEquals(
            StepStatus.SKIPPED,
            viewModel.uiState.value.stepStates.getValue(OnboardingStep.LINK_IMAP),
        )
        assertEquals(
            StepStatus.SKIPPED,
            viewModel.uiState.value.stepStates.getValue(OnboardingStep.LINK_GOOGLE_CALENDAR),
        )
        assertEquals(
            StepStatus.SKIPPED,
            viewModel.uiState.value.stepStates.getValue(OnboardingStep.LINK_OUTLOOK_CALENDAR),
        )
    }

    @Test
    fun `source connection skip disables calendar provider and marks step skipped`() = runTest {
        val viewModel = buildViewModel()

        viewModel.onSkipSourceProvider(OnboardingSourceProvider.GOOGLE_CALENDAR)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            userPrefsStore.setSourceEnabled(
                com.becalm.android.data.remote.dto.SourceType.GOOGLE_CALENDAR,
                false,
            )
        }
        assertEquals(
            StepStatus.SKIPPED,
            viewModel.uiState.value.stepStates.getValue(OnboardingStep.LINK_GOOGLE_CALENDAR),
        )
    }

    @Test
    fun `ONB email PIPA batch failure returns false and leaves IMAP step untouched`() = runTest {
        coEvery { userPrefsStore.setEmailPipaConsents(EmailPipaProvider.IMAP_GROUP, true) } throws
            java.io.IOException("disk full")
        val viewModel = buildViewModel()

        val result = viewModel.onEmailPipaConsent(EmailPipaProvider.IMAP_GROUP, granted = true)

        assertFalse(result)
        assertEquals(
            StepStatus.NOT_STARTED,
            viewModel.uiState.value.stepStates.getValue(OnboardingStep.LINK_IMAP),
        )
        verify(exactly = 0) {
            observability.captureMessage(
                message = "onboarding_pipa_email_consent",
                tags = any(),
            )
        }
    }

    @Test
    fun `ONB-CONTACTS denied remains terminal when all other steps are terminal`() = runTest {
        val viewModel = buildViewModel()
        OnboardingStep.entries.forEach { step ->
            viewModel.onMarkStepStatus(step, StepStatus.COMPLETE)
        }
        viewModel.onMarkStepStatus(OnboardingStep.PIPA_CONSENT, StepStatus.DENIED)
        viewModel.onMarkStepStatus(OnboardingStep.RECORDING_FOLDER, StepStatus.SKIPPED)
        viewModel.onMarkStepStatus(OnboardingStep.CONTACTS_PERM, StepStatus.DENIED)

        viewModel.onCompleteOnboarding()
        advanceUntilIdle()

        coVerify(exactly = 1) { userPrefsStore.setOnboardingCompleted(true) }
        assertEquals(null, viewModel.uiState.value.error)
    }

    @Test
    fun `ONB-004 skip marks requested oauth step skipped and advances to next step`() = runTest {
        val viewModel = buildViewModel()

        viewModel.onSkipStep(OnboardingStep.LINK_GMAIL)
        advanceUntilIdle()

        assertEquals(
            StepStatus.SKIPPED,
            viewModel.uiState.value.stepStates.getValue(OnboardingStep.LINK_GMAIL),
        )
        assertEquals(
            OnboardingStep.LINK_OUTLOOK_MAIL,
            viewModel.steps[viewModel.uiState.value.currentStepIndex],
        )
        coVerify(exactly = 1) {
            userPrefsStore.setOnboardingStepStatuses(mapOf("LINK_GMAIL" to "SKIPPED"))
        }
    }

    @Test
    fun `ONB-009 process recreation restores durable terminal step states`() = runTest {
        every { userPrefsStore.observeOnboardingStepStatuses() } returns flowOf(
            mapOf(
                "PIPA_CONSENT" to "GRANTED",
                "RECORDING_FOLDER" to "GRANTED",
                "CALL_LOG_MATCHING" to "GRANTED",
                "CONTACTS_PERM" to "DENIED",
                "LINK_GMAIL" to "COMPLETE",
            ),
        )

        val viewModel = buildViewModel()
        advanceUntilIdle()

        assertEquals(
            StepStatus.GRANTED,
            viewModel.uiState.value.stepStates.getValue(OnboardingStep.TERMS),
        )
        assertEquals(
            StepStatus.GRANTED,
            viewModel.uiState.value.stepStates.getValue(OnboardingStep.LOGIN),
        )
        assertEquals(
            StepStatus.COMPLETE,
            viewModel.uiState.value.stepStates.getValue(OnboardingStep.LINK_GMAIL),
        )
        assertEquals(
            OnboardingStep.LINK_OUTLOOK_MAIL,
            viewModel.steps[viewModel.uiState.value.currentStepIndex],
        )
    }

    @Test
    fun `ENR-001 allow contacts emits RequestSystemPermission effect`() = runTest {
        val viewModel = buildViewModel()

        viewModel.contactsPermissionEffects.test {
            viewModel.onAllowContacts()

            assertEquals(ContactsPermissionEffect.RequestSystemPermission, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ENR-002 denied contacts permission marks contacts denied and navigates to sources`() = runTest {
        val viewModel = buildViewModel()

        viewModel.contactsPermissionEffects.test {
            viewModel.onContactsPermissionResult(granted = false)

            assertEquals(
                ContactsPermissionEffect.NavigateToSources,
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(
            StepStatus.DENIED,
            viewModel.uiState.value.stepStates.getValue(OnboardingStep.CONTACTS_PERM),
        )
    }

    @Test
    fun `ENR-002 skipping contacts marks contacts skipped and navigates to sources`() = runTest {
        val viewModel = buildViewModel()

        viewModel.contactsPermissionEffects.test {
            viewModel.onSkipContacts()
            advanceUntilIdle()

            assertEquals(
                ContactsPermissionEffect.NavigateToSources,
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(
            StepStatus.SKIPPED,
            viewModel.uiState.value.stepStates.getValue(OnboardingStep.CONTACTS_PERM),
        )
    }

    @Test
    fun `ONB gmail oauth success marks gmail complete and persists connected flag`() = runTest {
        val activity = mockk<android.app.Activity>(relaxed = true)
        coEvery { userPrefsStore.observeEmailPipaConsent(EmailPipaProvider.GMAIL) } returns flowOf(true)
        coEvery { emailOAuthConnector.startSignIn(EmailOAuthProvider.GMAIL, activity) } returns EmailOAuthResult.Connected
        coEvery { onboardingActivationPreviewRepository.syncGmailAndLoadPreview("user-123", any()) } returns
            OnboardingActivationPreviewResult.Empty
        val viewModel = buildViewModel()

        viewModel.onConnectEmailProvider(EmailPipaProvider.GMAIL, activity)
        advanceUntilIdle()

        coVerify(exactly = 1) { userPrefsStore.setEmailSourceConnected(EmailPipaProvider.GMAIL, true) }
        coVerify(exactly = 1) { userPrefsStore.setEmailSourceManagedByBackend(EmailPipaProvider.GMAIL, true) }
        coVerify(exactly = 1) { sourceStatusRepository.recordSyncSuccess(SourceType.GMAIL, any()) }
        assertEquals(
            StepStatus.COMPLETE,
            viewModel.uiState.value.stepStates.getValue(OnboardingStep.LINK_GMAIL),
        )
        verify(exactly = 1) {
            observability.captureMessage(
                message = "onboarding_email_connected",
                tags = mapOf("provider" to "gmail", "owner" to "backend"),
            )
        }
    }

    @Test
    fun `ONB gmail status refresh success emits connected event for browser callback return`() = runTest {
        coEvery { userPrefsStore.observeEmailPipaConsent(EmailPipaProvider.GMAIL) } returns flowOf(true)
        coEvery { emailOAuthConnector.refreshConnectionStatus(EmailOAuthProvider.GMAIL) } returns EmailOAuthResult.Connected
        coEvery { onboardingActivationPreviewRepository.syncGmailAndLoadPreview("user-123", any()) } returns
            OnboardingActivationPreviewResult.Empty
        val viewModel = buildViewModel()

        viewModel.emailConnectEvents.test {
            viewModel.refreshEmailProviderConnection(EmailPipaProvider.GMAIL)
            advanceUntilIdle()

            assertEquals(EmailConnectEvent.Syncing(EmailPipaProvider.GMAIL), awaitItem())
            assertEquals(EmailConnectEvent.Connected(EmailPipaProvider.GMAIL), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) { userPrefsStore.setEmailSourceConnected(EmailPipaProvider.GMAIL, true) }
        coVerify(exactly = 1) { userPrefsStore.setEmailSourceManagedByBackend(EmailPipaProvider.GMAIL, true) }
        coVerify(exactly = 1) { sourceStatusRepository.recordSyncSuccess(SourceType.GMAIL, any()) }
        assertEquals(
            StepStatus.COMPLETE,
            viewModel.uiState.value.stepStates.getValue(OnboardingStep.LINK_GMAIL),
        )
    }

    @Test
    fun `ONB gmail status refresh failure emits failed event for browser callback return`() = runTest {
        coEvery { userPrefsStore.observeEmailPipaConsent(EmailPipaProvider.GMAIL) } returns flowOf(true)
        coEvery { emailOAuthConnector.refreshConnectionStatus(EmailOAuthProvider.GMAIL) } returns
            EmailOAuthResult.Failed("network_error")
        val viewModel = buildViewModel()

        viewModel.emailConnectEvents.test {
            viewModel.refreshEmailProviderConnection(EmailPipaProvider.GMAIL)
            advanceUntilIdle()

            assertEquals(EmailConnectEvent.Failed(EmailPipaProvider.GMAIL, "network_error"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 0) { userPrefsStore.setEmailSourceConnected(EmailPipaProvider.GMAIL, true) }
        verify(exactly = 1) {
            observability.captureMessage(
                message = "onboarding_step_failed",
                tags = mapOf(
                    "step" to "LINK_GMAIL",
                    "error_code" to "network_error",
                ),
            )
        }
    }

    @Test
    fun `ONB outlook oauth failure marks skipped and reports network failure`() = runTest {
        val activity = mockk<android.app.Activity>(relaxed = true)
        coEvery { userPrefsStore.observeEmailPipaConsent(EmailPipaProvider.OUTLOOK_MAIL) } returns flowOf(true)
        coEvery { emailOAuthConnector.startSignIn(EmailOAuthProvider.OUTLOOK_MAIL, activity) } returns
            EmailOAuthResult.Failed("network")
        val viewModel = buildViewModel()

        viewModel.onConnectEmailProvider(EmailPipaProvider.OUTLOOK_MAIL, activity)
        advanceUntilIdle()

        assertEquals(
            StepStatus.SKIPPED,
            viewModel.uiState.value.stepStates.getValue(OnboardingStep.LINK_OUTLOOK_MAIL),
        )
        coVerify(exactly = 0) { userPrefsStore.setEmailSourceConnected(EmailPipaProvider.OUTLOOK_MAIL, true) }
        verify(exactly = 1) {
            observability.captureMessage(
                message = "onboarding_step_failed",
                tags = mapOf(
                    "step" to "LINK_OUTLOOK_MAIL",
                    "error_code" to "network",
                ),
            )
        }
    }

    @Test
    fun `calendar status refresh success emits connected event for browser callback return`() = runTest {
        coEvery {
            calendarOAuthConnector.refreshConnectionStatus(CalendarOAuthProvider.GOOGLE_CALENDAR)
        } returns CalendarOAuthResult.Connected
        val viewModel = buildViewModel()

	        viewModel.calendarConnectEvents.test {
	            viewModel.refreshCalendarProviderConnection(CalendarOAuthProvider.GOOGLE_CALENDAR)
	            advanceUntilIdle()

	            assertEquals(CalendarConnectEvent.Syncing(CalendarOAuthProvider.GOOGLE_CALENDAR), awaitItem())
	            assertEquals(CalendarConnectEvent.Connected(CalendarOAuthProvider.GOOGLE_CALENDAR), awaitItem())
	            cancelAndIgnoreRemainingEvents()
	        }

        coVerify(exactly = 1) {
            userPrefsStore.setSourceEnabled(CalendarOAuthProvider.GOOGLE_CALENDAR.sourceType, true)
        }
	        coVerify(exactly = 1) {
	            sourceStatusRepository.recordSyncSuccess(SourceType.GOOGLE_CALENDAR, any())
	        }
	        coVerify(exactly = 1) {
	            calendarEventRepository.refreshSince(
	                userId = "user-123",
	                since = null,
	                rangeStart = null,
	                rangeEnd = null,
	            )
	        }
	        coVerify(exactly = 1) { commitmentRepository.refreshSince("user-123", null, null, null, null) }
	        coVerify(exactly = 1) {
	            sourceEventParticipantRepository.refreshSince(
	                userId = "user-123",
	                sourceType = SourceType.GOOGLE_CALENDAR,
	                since = null,
	            )
	        }
	        assertEquals(
	            StepStatus.COMPLETE,
	            viewModel.uiState.value.stepStates.getValue(OnboardingStep.LINK_GOOGLE_CALENDAR),
	        )
	    }

	    @Test
	    fun `calendar status refresh not connected clears pending browser callback state`() = runTest {
	        coEvery {
	            calendarOAuthConnector.refreshConnectionStatus(CalendarOAuthProvider.GOOGLE_CALENDAR)
	        } returns CalendarOAuthResult.NotConnected
	        val viewModel = buildViewModel()

	        viewModel.calendarConnectEvents.test {
	            viewModel.refreshCalendarProviderConnection(CalendarOAuthProvider.GOOGLE_CALENDAR)
	            advanceUntilIdle()

	            assertEquals(CalendarConnectEvent.NotConnected(CalendarOAuthProvider.GOOGLE_CALENDAR), awaitItem())
	            cancelAndIgnoreRemainingEvents()
	        }

	        coVerify(exactly = 0) {
	            userPrefsStore.setSourceEnabled(CalendarOAuthProvider.GOOGLE_CALENDAR.sourceType, true)
	        }
	    }

	    @Test
	    fun `calendar status refresh not connected clears durable in progress state`() = runTest {
	        coEvery {
	            calendarOAuthConnector.refreshConnectionStatus(CalendarOAuthProvider.GOOGLE_CALENDAR)
	        } returns CalendarOAuthResult.NotConnected
	        val viewModel = buildViewModel()
	        viewModel.onMarkStepStatus(OnboardingStep.LINK_GOOGLE_CALENDAR, StepStatus.IN_PROGRESS)
	        advanceUntilIdle()

	        viewModel.calendarConnectEvents.test {
	            viewModel.refreshCalendarProviderConnection(CalendarOAuthProvider.GOOGLE_CALENDAR)
	            advanceUntilIdle()

	            assertEquals(CalendarConnectEvent.NotConnected(CalendarOAuthProvider.GOOGLE_CALENDAR), awaitItem())
	            cancelAndIgnoreRemainingEvents()
	        }

	        assertEquals(
	            StepStatus.NOT_STARTED,
	            viewModel.uiState.value.stepStates.getValue(OnboardingStep.LINK_GOOGLE_CALENDAR),
	        )
	    }

	    @Test
	    fun `calendar status refresh keeps step in progress when server sync is still running`() = runTest {
	        coEvery {
	            calendarOAuthConnector.refreshConnectionStatus(CalendarOAuthProvider.GOOGLE_CALENDAR)
	        } returns CalendarOAuthResult.Connected
	        coEvery { calendarEventRepository.triggerServerSync() } returns
	            BecalmResult.Success(CalendarSyncResponse(synced = 0, status = "pending", accepted = true))
	        val viewModel = buildViewModel()

	        viewModel.calendarConnectEvents.test {
	            viewModel.refreshCalendarProviderConnection(CalendarOAuthProvider.GOOGLE_CALENDAR)
	            advanceUntilIdle()

	            assertEquals(CalendarConnectEvent.Syncing(CalendarOAuthProvider.GOOGLE_CALENDAR), awaitItem())
	            expectNoEvents()
	            cancelAndIgnoreRemainingEvents()
	        }

	        assertEquals(
	            StepStatus.IN_PROGRESS,
	            viewModel.uiState.value.stepStates.getValue(OnboardingStep.LINK_GOOGLE_CALENDAR),
	        )
	        coVerify(exactly = 0) {
	            sourceStatusRepository.recordSyncSuccess(SourceType.GOOGLE_CALENDAR, any())
	        }
	    }

    @Test
    fun `calendar status refresh failure emits failed event for browser callback return`() = runTest {
        coEvery {
            calendarOAuthConnector.refreshConnectionStatus(CalendarOAuthProvider.GOOGLE_CALENDAR)
        } returns CalendarOAuthResult.Failed("network_error")
        val viewModel = buildViewModel()

        viewModel.calendarConnectEvents.test {
            viewModel.refreshCalendarProviderConnection(CalendarOAuthProvider.GOOGLE_CALENDAR)
            advanceUntilIdle()

            assertEquals(
                CalendarConnectEvent.Failed(CalendarOAuthProvider.GOOGLE_CALENDAR, "network_error"),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 0) {
            userPrefsStore.setSourceEnabled(CalendarOAuthProvider.GOOGLE_CALENDAR.sourceType, true)
        }
        verify(exactly = 1) {
            observability.captureMessage(
                message = "onboarding_step_failed",
                tags = mapOf(
                    "step" to "LINK_GOOGLE_CALENDAR",
                    "error_code" to "network_error",
                ),
            )
        }
    }

    @Test
    fun `calendar oauth connect failure emits failed event and does not fake connected success`() = runTest {
        val activity = mockk<android.app.Activity>(relaxed = true)
        coEvery {
            calendarOAuthConnector.startSignIn(CalendarOAuthProvider.GOOGLE_CALENDAR, activity)
        } returns CalendarOAuthResult.Failed("not_implemented")
        val viewModel = buildViewModel()

        viewModel.calendarConnectEvents.test {
            viewModel.onConnectCalendarProvider(CalendarOAuthProvider.GOOGLE_CALENDAR, activity)
            advanceUntilIdle()

            assertEquals(
                CalendarConnectEvent.Failed(CalendarOAuthProvider.GOOGLE_CALENDAR, "not_implemented"),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 0) { userPrefsStore.setSourceEnabled(any(), true) }
        assertEquals(
            StepStatus.NOT_STARTED,
            viewModel.uiState.value.stepStates.getValue(OnboardingStep.LINK_GOOGLE_CALENDAR),
        )
        verify(exactly = 1) {
            observability.captureMessage(
                message = "onboarding_step_failed",
                tags = mapOf(
                    "step" to "LINK_GOOGLE_CALENDAR",
                    "error_code" to "not_implemented",
                ),
            )
        }
    }

    @Test
    fun `ONB email oauth is gated on persisted PIPA consent`() = runTest {
        val activity = mockk<android.app.Activity>(relaxed = true)
        coEvery { userPrefsStore.observeEmailPipaConsent(EmailPipaProvider.GMAIL) } returns flowOf(false)
        val viewModel = buildViewModel()

        viewModel.onConnectEmailProvider(EmailPipaProvider.GMAIL, activity)
        advanceUntilIdle()

        coVerify(exactly = 0) { emailOAuthConnector.startSignIn(EmailOAuthProvider.GMAIL, activity) }
        assertEquals(
            StepStatus.SKIPPED,
            viewModel.uiState.value.stepStates.getValue(OnboardingStep.LINK_GMAIL),
        )
        verify(exactly = 1) {
            observability.captureMessage(
                message = "onboarding_step_failed",
                tags = mapOf("step" to "LINK_GMAIL", "error_code" to "pipa_consent_missing"),
            )
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ONB connect email provider rejects IMAP recipients`() {
        val activity = mockk<android.app.Activity>(relaxed = true)
        val viewModel = buildViewModel()

        viewModel.onConnectEmailProvider(EmailPipaProvider.NAVER_IMAP, activity)
    }

    @Test
    fun `ONB IMAP credential save success marks LINK_IMAP complete and persists connected flag`() = runTest {
        val creds = ImapCredentials(
            host = "imap.naver.com",
            port = 993,
            username = "alice",
            appPassword = "app-pw",
        )
        coEvery { userPrefsStore.observeEmailPipaConsent(EmailPipaProvider.NAVER_IMAP) } returns flowOf(true)
        coEvery { imapCredentialStore.save(com.becalm.android.data.remote.dto.SourceType.NAVER_IMAP, creds) } returns Unit
        val viewModel = buildViewModel()

        viewModel.saveImapCredentials(com.becalm.android.data.remote.dto.SourceType.NAVER_IMAP, creds)
        advanceUntilIdle()

        coVerify(exactly = 1) { imapCredentialStore.save(com.becalm.android.data.remote.dto.SourceType.NAVER_IMAP, creds) }
        coVerify(exactly = 1) { userPrefsStore.setEmailSourceConnected(EmailPipaProvider.NAVER_IMAP, true) }
        coVerify(exactly = 1) { sourceStatusRepository.clear(com.becalm.android.data.remote.dto.SourceType.NAVER_IMAP) }
        verify(exactly = 1) { appRuntimeSyncCoordinator.refresh() }
        assertEquals(
            StepStatus.COMPLETE,
            viewModel.uiState.value.stepStates.getValue(OnboardingStep.LINK_IMAP),
        )
    }

    @Test
    fun `ONB IMAP credential save failure marks skipped and reports network error`() = runTest {
        val creds = ImapCredentials(
            host = "imap.daum.net",
            port = 993,
            username = "bob",
            appPassword = "app-pw",
        )
        coEvery { userPrefsStore.observeEmailPipaConsent(EmailPipaProvider.DAUM_IMAP) } returns flowOf(true)
        coEvery { imapCredentialStore.save(com.becalm.android.data.remote.dto.SourceType.DAUM_IMAP, creds) } throws
            java.io.IOException("disk full")
        val viewModel = buildViewModel()

        viewModel.saveImapCredentials(com.becalm.android.data.remote.dto.SourceType.DAUM_IMAP, creds)
        advanceUntilIdle()

        assertEquals(
            StepStatus.SKIPPED,
            viewModel.uiState.value.stepStates.getValue(OnboardingStep.LINK_IMAP),
        )
        verify(exactly = 1) {
            observability.captureMessage(
                message = "onboarding_step_failed",
                tags = mapOf(
                    "step" to "LINK_IMAP",
                    "error_code" to "network",
                ),
            )
        }
    }

    @Test
    fun `ONB IMAP credential save rejects unknown provider before store write`() = runTest {
        val creds = ImapCredentials(
            host = "mail.example.com",
            port = 993,
            username = "sam",
            appPassword = "app-pw",
        )
        val viewModel = buildViewModel()

        viewModel.saveImapCredentials("bogus_source", creds)
        advanceUntilIdle()

        coVerify(exactly = 0) { imapCredentialStore.save(any(), creds) }
        verify(exactly = 1) {
            observability.captureMessage(
                message = "onboarding_step_failed",
                tags = mapOf(
                    "step" to "LINK_IMAP",
                    "error_code" to "unknown_provider",
                ),
            )
        }
    }

    @Test
    fun `ONB IMAP credential save is gated on recipient PIPA consent`() = runTest {
        val creds = ImapCredentials(
            host = "imap.naver.com",
            port = 993,
            username = "alice",
            appPassword = "app-pw",
        )
        coEvery { userPrefsStore.observeEmailPipaConsent(EmailPipaProvider.NAVER_IMAP) } returns flowOf(false)
        val viewModel = buildViewModel()

        viewModel.saveImapCredentials(com.becalm.android.data.remote.dto.SourceType.NAVER_IMAP, creds)
        advanceUntilIdle()

        coVerify(exactly = 0) { imapCredentialStore.save(com.becalm.android.data.remote.dto.SourceType.NAVER_IMAP, creds) }
        verify(exactly = 1) {
            observability.captureMessage(
                message = "onboarding_step_failed",
                tags = mapOf(
                    "step" to "LINK_IMAP",
                    "error_code" to "pipa_consent_missing",
                ),
            )
        }
    }

    @Test
    fun `ONB-007 failed onboarding step is reported with step and error tags`() {
        val viewModel = buildViewModel()

        viewModel.reportOnboardingStepFailed(OnboardingStep.LINK_GMAIL, "user_cancelled")

        verify(exactly = 1) {
            observability.captureMessage(
                message = "onboarding_step_failed",
                tags = mapOf(
                    "step" to "LINK_GMAIL",
                    "error_code" to "user_cancelled",
                ),
            )
        }
    }

    @Test
    fun `ONB-008 complete onboarding is blocked when any step is not terminal`() = runTest {
        val viewModel = buildViewModel()
        viewModel.onMarkStepStatus(OnboardingStep.TERMS, StepStatus.GRANTED)

        viewModel.onCompleteOnboarding()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isCompleting)
        assertEquals(R.string.onb_error_complete_steps, viewModel.uiState.value.error?.resId)
        coVerify(exactly = 0) { userPrefsStore.setOnboardingCompleted(true) }
    }

    @Test
    fun `ONB-008 complete onboarding persists when every step is terminal`() = runTest {
        val viewModel = buildViewModel()
        OnboardingStep.entries.forEach { step ->
            viewModel.onMarkStepStatus(step, StepStatus.COMPLETE)
        }

        viewModel.onCompleteOnboarding()
        advanceUntilIdle()

        coVerify(exactly = 1) { userPrefsStore.setOnboardingCompleted(true) }
        assertEquals(
            StepStatus.COMPLETE,
            viewModel.uiState.value.stepStates.getValue(OnboardingStep.COLD_SYNC),
        )
        assertEquals(false, viewModel.uiState.value.isCompleting)
    }

    @Test
    fun `setup completion marks unfinished optional steps terminal and completes onboarding`() = runTest {
        val viewModel = buildViewModel()
        viewModel.onMarkStepStatus(OnboardingStep.TERMS, StepStatus.GRANTED)
        viewModel.onMarkStepStatus(OnboardingStep.LOGIN, StepStatus.GRANTED)
        viewModel.onMarkStepStatus(OnboardingStep.CONTACTS_PERM, StepStatus.GRANTED)
        viewModel.onSelfDisplayNameChange("민홍")
        viewModel.onSelfPhoneChange("+821012345678")
        viewModel.onSaveSelfIdentity()
        advanceUntilIdle()

        viewModel.setupEffects.test {
            viewModel.onCompleteSetup()
            advanceUntilIdle()
            assertEquals(OnboardingSetupEffect.NavigateToCompletion(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) { userPrefsStore.setOnboardingCompleted(true) }
        assertEquals(StepStatus.GRANTED, viewModel.uiState.value.stepStates.getValue(OnboardingStep.CONTACTS_PERM))
        assertEquals(StepStatus.SKIPPED, viewModel.uiState.value.stepStates.getValue(OnboardingStep.PIPA_CONSENT))
        assertEquals(StepStatus.SKIPPED, viewModel.uiState.value.stepStates.getValue(OnboardingStep.RECORDING_FOLDER))
        assertEquals(StepStatus.SKIPPED, viewModel.uiState.value.stepStates.getValue(OnboardingStep.BATTERY_OPT))
        assertEquals(StepStatus.COMPLETE, viewModel.uiState.value.stepStates.getValue(OnboardingStep.COLD_SYNC))
    }

    @Test
    fun `first memory save completes setup and opens completion surface`() = runTest {
        val viewModel = buildViewModel()
        coEvery { firstMemoryRepository.save(any()) } returns BecalmResult.Success(
            FirstMemorySaveResult(
                personId = "person-minji",
                commitmentId = "commitment-1",
                sourceRef = "manual_memory:client-1",
                syncedRemotely = true,
            ),
        )

        viewModel.onFirstMemoryOriginChange(FirstMemoryOrigin.EMAIL)
        viewModel.onFirstMemoryPersonNameChange("민지")
        viewModel.onFirstMemoryPromiseTextChange("금요일까지 제안서 초안 보내기")
        viewModel.onFirstMemoryKindChange(FirstMemoryKind.MY_ACTION)
        advanceUntilIdle()

        viewModel.setupEffects.test {
            viewModel.onSaveFirstMemory()
            advanceUntilIdle()
            assertEquals(OnboardingSetupEffect.NavigateToCompletion("person-minji"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) { userPrefsStore.setOnboardingCompleted(true) }
        coVerify(exactly = 1) { firstMemoryRepository.save(any()) }
        assertEquals(false, viewModel.uiState.value.firstMemory.saving)
        assertEquals(StepStatus.COMPLETE, viewModel.uiState.value.stepStates.getValue(OnboardingStep.COLD_SYNC))
    }

    @Test
    fun `post intro routes to first memory only when no onboarding source is connected`() = runTest {
        val viewModel = buildViewModel()
        advanceUntilIdle()

        repeat(ONBOARDING_INTRO_PAGE_COUNT) {
            viewModel.onIntroNext()
        }

        assertEquals(OnboardingSetupStage.FIRST_MEMORY, viewModel.uiState.value.setupStage)
        coVerify(exactly = 0) { userPrefsStore.setOnboardingCompleted(true) }
    }

    @Test
    fun `post intro routes to first memory when only contacts were connected`() = runTest {
        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onMarkStepStatus(OnboardingStep.CONTACTS_PERM, StepStatus.GRANTED)

        repeat(ONBOARDING_INTRO_PAGE_COUNT) {
            viewModel.onIntroNext()
        }
        advanceUntilIdle()

        assertEquals(OnboardingSetupStage.FIRST_MEMORY, viewModel.uiState.value.setupStage)
        coVerify(exactly = 0) { userPrefsStore.setOnboardingCompleted(true) }
    }

    @Test
    fun `dirty first memory back request prompts and can keep or discard draft`() = runTest {
        val viewModel = buildViewModel()

        viewModel.onUseManualFirstMemory()
        viewModel.onFirstMemoryOriginChange(FirstMemoryOrigin.EMAIL)
        viewModel.onFirstMemoryPersonNameChange("민지")
        viewModel.onFirstMemoryPromiseTextChange("금요일까지 제안서 초안 보내기")

        viewModel.onSetupBackRequested()

        assertEquals(OnboardingSetupStage.FIRST_MEMORY, viewModel.uiState.value.setupStage)
        assertTrue(viewModel.uiState.value.firstMemoryExitPromptVisible)
        assertEquals("민지", viewModel.uiState.value.firstMemory.personName)

        viewModel.onKeepFirstMemoryDraft()

        assertFalse(viewModel.uiState.value.firstMemoryExitPromptVisible)
        assertEquals("금요일까지 제안서 초안 보내기", viewModel.uiState.value.firstMemory.promiseText)

        viewModel.onSetupBackRequested()
        viewModel.onDiscardFirstMemoryDraft()

        assertEquals(OnboardingSetupStage.INTRO, viewModel.uiState.value.setupStage)
        assertFalse(viewModel.uiState.value.firstMemoryExitPromptVisible)
        assertEquals(null, viewModel.uiState.value.firstMemory.origin)
        assertEquals("", viewModel.uiState.value.firstMemory.personName)
        assertEquals("", viewModel.uiState.value.firstMemory.promiseText)
    }

    @Test
    fun `first memory invalid save shows validation and does not call repository`() = runTest {
        val viewModel = buildViewModel()

        viewModel.onUseManualFirstMemory()
        viewModel.onFirstMemoryOriginChange(FirstMemoryOrigin.EMAIL)
        viewModel.onFirstMemoryPersonNameChange("민지")
        viewModel.onFirstMemoryPromiseTextChange("금요일까지 제안서 초안 보내기")
        viewModel.onSaveFirstMemory()
        advanceUntilIdle()

        coVerify(exactly = 0) { firstMemoryRepository.save(any()) }
        assertEquals(R.string.first_memory_error_required, viewModel.uiState.value.firstMemory.errorMessageRes)
        assertEquals(false, viewModel.uiState.value.firstMemory.saving)
    }

    @Test
    fun `first memory double save is idempotent while first request is in flight`() = runTest {
        val viewModel = buildViewModel()
        coEvery { firstMemoryRepository.save(any()) } returns BecalmResult.Success(
            FirstMemorySaveResult(
                personId = "person-minji",
                commitmentId = "commitment-1",
                sourceRef = "manual_memory:client-1",
                syncedRemotely = true,
            ),
        )

        viewModel.onFirstMemoryOriginChange(FirstMemoryOrigin.EMAIL)
        viewModel.onFirstMemoryPersonNameChange("민지")
        viewModel.onFirstMemoryPromiseTextChange("금요일까지 제안서 초안 보내기")
        viewModel.onFirstMemoryKindChange(FirstMemoryKind.MY_ACTION)

        viewModel.onSaveFirstMemory()
        viewModel.onSaveFirstMemory()
        advanceUntilIdle()

        coVerify(exactly = 1) { firstMemoryRepository.save(any()) }
        coVerify(exactly = 1) { userPrefsStore.setOnboardingCompleted(true) }
    }

    @Test
    fun `gmail activation preview uses extracted commitment to complete onboarding`() = runTest {
        val viewModel = buildViewModel()
        coEvery { onboardingActivationPreviewRepository.syncGmailAndLoadPreview("user-123", any()) } returns
            OnboardingActivationPreviewResult.Ready(
                listOf(activationPreview()),
            )

        viewModel.onGmailConnectedForActivation()
        advanceUntilIdle()

        assertEquals(OnboardingSetupStage.INTRO, viewModel.uiState.value.setupStage)
        assertEquals("금요일까지 제안서 초안 보내기", viewModel.uiState.value.gmailActivationPreview.previews.firstOrNull()?.title)

        viewModel.setupEffects.test {
            viewModel.onUseGmailActivationPreview()
            advanceUntilIdle()
            assertEquals(OnboardingSetupEffect.NavigateToCompletion("person-minji"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) { userPrefsStore.setOnboardingCompleted(true) }
    }

    @Test
    fun `gmail activation preview stays on gmail surface when sync is pending`() = runTest {
        val viewModel = buildViewModel()
        coEvery { onboardingActivationPreviewRepository.syncGmailAndLoadPreview("user-123", any()) } returns
            OnboardingActivationPreviewResult.Pending()

        viewModel.onGmailConnectedForActivation()
        advanceUntilIdle()

        assertEquals(OnboardingSetupStage.INTRO, viewModel.uiState.value.setupStage)
        assertEquals(null, viewModel.uiState.value.gmailActivationPreview.preview)
        assertEquals(
            GmailActivationPreviewStatus.StillProcessing,
            viewModel.uiState.value.gmailActivationPreview.status,
        )
        assertEquals(null, viewModel.uiState.value.notice)
        coVerify(exactly = 0) { userPrefsStore.setOnboardingCompleted(true) }
    }

    @Test
    fun `gmail activation preview pending state stays inline on current setup route`() = runTest {
        val viewModel = buildViewModel()
        coEvery { onboardingActivationPreviewRepository.syncGmailAndLoadPreview("user-123", any()) } returns
            OnboardingActivationPreviewResult.Pending()

        viewModel.onGmailConnectedForActivation()
        advanceUntilIdle()

        assertEquals(OnboardingSetupStage.INTRO, viewModel.uiState.value.setupStage)
        assertEquals(
            GmailActivationPreviewStatus.StillProcessing,
            viewModel.uiState.value.gmailActivationPreview.status,
        )
    }

    @Test
    fun `gmail activation preview surfaces progress message while waiting`() = runTest {
        val progress = OnboardingActivationProgress(
            stage = "extracting",
            progress = 0.55,
            message = "메일 속 약속 후보를 확인하고 있습니다",
        )
        coEvery { onboardingActivationPreviewRepository.syncGmailAndLoadPreview("user-123", any()) } coAnswers {
            val onProgress = secondArg<suspend (OnboardingActivationProgress) -> Unit>()
            onProgress(progress)
            OnboardingActivationPreviewResult.Pending(progress)
        }
        val viewModel = buildViewModel()

        viewModel.onGmailConnectedForActivation()
        advanceUntilIdle()

        assertEquals(
            GmailActivationPreviewStatus.StillProcessing,
            viewModel.uiState.value.gmailActivationPreview.status,
        )
        assertEquals(0.55f, viewModel.uiState.value.gmailActivationPreview.progress)
        assertEquals(
            "메일 속 약속 후보를 확인하고 있습니다",
            viewModel.uiState.value.gmailActivationPreview.progressMessage,
        )
    }

    @Test
    fun `gmail activation preview can start BeCalm without manual memory when still processing`() = runTest {
        val viewModel = buildViewModel()
        coEvery { onboardingActivationPreviewRepository.syncGmailAndLoadPreview("user-123", any()) } returns
            OnboardingActivationPreviewResult.Pending()

        viewModel.onGmailConnectedForActivation()
        advanceUntilIdle()

        viewModel.setupEffects.test {
            viewModel.onStartWithoutGmailActivationPreview()
            advanceUntilIdle()

            assertEquals(
                OnboardingSetupEffect.NavigateToSetupRoute(OnboardingSetupDestination.FirstMemory.routePath),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 0) { userPrefsStore.setOnboardingCompleted(true) }
    }

    @Test
    fun `setup completion is blocked until self identity is confirmed`() = runTest {
        val viewModel = buildViewModel()

        viewModel.onCompleteSetup()
        advanceUntilIdle()

        coVerify(exactly = 0) { userPrefsStore.setOnboardingCompleted(true) }
        assertEquals(R.string.onb_error_self_identity_required, viewModel.uiState.value.error?.resId)
    }

    @Test
    fun `self identity save requires display name plus at least one identity hint`() = runTest {
        val viewModel = buildViewModel()

        viewModel.onSelfAliasChange("MH")
        viewModel.onSaveSelfIdentity()
        advanceUntilIdle()

        coVerify(exactly = 0) {
            selfIdentityRepository.commitOnboardingSelfIdentity(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        assertEquals(R.string.onb_error_self_identity_required, viewModel.uiState.value.error?.resId)
        assertFalse(viewModel.uiState.value.selfIdentityConfirmed)
    }

    @Test
    fun `saving self identity commits profile and anchors once and opens setup gate`() = runTest {
        val viewModel = buildViewModel()

        viewModel.onSelfDisplayNameChange("민홍")
        viewModel.onSelfEmailChange("me@example.com")
        viewModel.onSelfPhoneChange("+821012345678")
        viewModel.onSelfAliasChange("민홍")
        viewModel.onSaveSelfIdentity()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            selfIdentityRepository.commitOnboardingSelfIdentity(
                userId = "user-123",
                displayName = "민홍",
                displayNameSource = "manual",
                displayNameReadOnly = false,
                email = "me@example.com",
                emailReadOnly = false,
                phoneE164 = "+821012345678",
                phoneReadOnly = false,
                phoneVerified = false,
                alias = "민홍",
                authProvider = "email",
            )
        }
        coVerify(exactly = 0) { userProfileRepository.updateRemote(any(), any(), any(), any()) }
        coVerify(exactly = 0) { selfIdentityRepository.createAnchor(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { selfIdentityRepository.refresh("user-123") }
        assertEquals("민홍", viewModel.uiState.value.selfDisplayName)
        assertEquals("me@example.com", viewModel.uiState.value.selfEmail)
        assertEquals("+821012345678", viewModel.uiState.value.selfPhone)
        assertEquals("민홍", viewModel.uiState.value.selfAlias)
        assertTrue(viewModel.uiState.value.selfIdentityConfirmed)
        assertFalse(viewModel.uiState.value.isSavingSelfIdentity)
        assertEquals(R.string.onb_setup_identity_saved, viewModel.uiState.value.notice?.resId)
    }

    @Test
    fun `saving self identity does not confirm when commit fails`() = runTest {
        val viewModel = buildViewModel()
        coEvery {
            selfIdentityRepository.commitOnboardingSelfIdentity(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        } returns BecalmResult.Failure(BecalmError.ServerError(503, "upstream_unavailable"))

        viewModel.onSelfDisplayNameChange("민홍")
        viewModel.onSelfEmailChange("me@example.com")
        viewModel.onSelfPhoneChange("+821012345678")
        viewModel.onSaveSelfIdentity()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            selfIdentityRepository.commitOnboardingSelfIdentity(
                userId = "user-123",
                displayName = "민홍",
                displayNameSource = "manual",
                displayNameReadOnly = false,
                email = "me@example.com",
                emailReadOnly = false,
                phoneE164 = "+821012345678",
                phoneReadOnly = false,
                phoneVerified = false,
                alias = null,
                authProvider = "email",
            )
        }
        assertFalse(viewModel.uiState.value.selfIdentityConfirmed)
        assertFalse(viewModel.uiState.value.isSavingSelfIdentity)
        assertEquals(null, viewModel.uiState.value.notice)
        assertEquals(R.string.settings_identity_error_save_profile, viewModel.uiState.value.error?.resId)
    }

    @Test
    fun `saving self identity normalizes Korean local phone before commit`() = runTest {
        val viewModel = buildViewModel()

        viewModel.onSelfDisplayNameChange("민홍")
        viewModel.onSelfPhoneChange("010-1234-5678")
        viewModel.onSaveSelfIdentity()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            selfIdentityRepository.commitOnboardingSelfIdentity(
                userId = "user-123",
                displayName = "민홍",
                displayNameSource = "manual",
                displayNameReadOnly = false,
                email = null,
                emailReadOnly = false,
                phoneE164 = "+821012345678",
                phoneReadOnly = false,
                phoneVerified = false,
                alias = null,
                authProvider = "email",
            )
        }
        assertEquals("+821012345678", viewModel.uiState.value.selfPhone)
        assertTrue(viewModel.uiState.value.selfIdentityConfirmed)
    }

    @Test
    fun `google prefilled name and email are readonly and committed with auth source`() = runTest {
        coEvery { sessionStore.load() } returns SupabaseSession(
            accessToken = "access",
            refreshToken = "refresh",
            userId = "user-123",
            email = "me@example.com",
            expiresAt = Instant.parse("2026-05-16T00:00:00Z"),
            authProvider = SupabaseAuthProvider.GOOGLE,
            profileName = "김지훈",
        )

        val viewModel = buildViewModel()
        advanceUntilIdle()

        assertEquals("김지훈", viewModel.uiState.value.selfDisplayName)
        assertEquals("me@example.com", viewModel.uiState.value.selfEmail)
        assertTrue(viewModel.uiState.value.selfDisplayNameReadOnly)
        assertTrue(viewModel.uiState.value.selfEmailReadOnly)

        viewModel.onSelfDisplayNameChange("수정")
        viewModel.onSelfEmailChange("other@example.com")
        viewModel.onSaveSelfIdentity()
        advanceUntilIdle()

        assertEquals("김지훈", viewModel.uiState.value.selfDisplayName)
        assertEquals("me@example.com", viewModel.uiState.value.selfEmail)
        coVerify(exactly = 1) {
            selfIdentityRepository.commitOnboardingSelfIdentity(
                userId = "user-123",
                displayName = "김지훈",
                displayNameSource = "google_auth",
                displayNameReadOnly = true,
                email = "me@example.com",
                emailReadOnly = true,
                phoneE164 = null,
                phoneReadOnly = false,
                phoneVerified = false,
                alias = null,
                authProvider = "google",
            )
        }
    }

    @Test
    fun `self identity save keeps setup blocked when commit throws`() = runTest {
        val viewModel = buildViewModel()
        coEvery {
            selfIdentityRepository.commitOnboardingSelfIdentity(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        } throws IllegalStateException("network parser failed")

        viewModel.onSelfDisplayNameChange("민홍")
        viewModel.onSelfPhoneChange("+821012345678")
        viewModel.onSaveSelfIdentity()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isSavingSelfIdentity)
        assertFalse(viewModel.uiState.value.selfIdentityConfirmed)
        assertEquals(R.string.settings_identity_error_save_profile, viewModel.uiState.value.error?.resId)
    }

    @Test
    fun `onboarding hydrates self email and alias anchors from local mirror`() = runTest {
        coEvery { userProfileRepository.find("user-123") } returns userProfile(displayName = "민홍")
        every { selfIdentityRepository.observeAll("user-123") } returns flowOf(
            listOf(
                selfAnchor(id = "anchor-email", type = "email", value = "me@example.com"),
                selfAnchor(id = "anchor-alias", type = "alias", value = "MH"),
            ),
        )

        val viewModel = buildViewModel()
        advanceUntilIdle()

        assertEquals("me@example.com", viewModel.uiState.value.selfEmail)
        assertEquals("MH", viewModel.uiState.value.selfAlias)
        assertTrue(viewModel.uiState.value.selfIdentityConfirmed)
    }

    @Test
    fun `onboarding hydrates connected source ownership rows from local mirror`() = runTest {
        every { sourceConnectionRepository.observeAll("user-123") } returns flowOf(
            listOf(
                sourceConnection(
                    id = "conn-gmail",
                    provider = "google",
                    capability = "mail",
                    accountIdentifier = "work@example.com",
                    accountDisplayName = "Work",
                    ownership = "unknown",
                ),
                sourceConnection(
                    id = "conn-calendar",
                    provider = "google",
                    capability = "calendar",
                    accountIdentifier = "work@example.com",
                    accountDisplayName = "Work Calendar",
                    ownership = "self",
                ),
            ),
        )

        val viewModel = buildViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.sourceOwnerships.size)
        assertTrue(state.sourceOwnerships.any { it.title == "Gmail" && it.accountLabel == "Work" })
        assertTrue(state.sourceOwnerships.any { it.title == "Google Calendar" && it.accountLabel == "Work Calendar" })
        assertEquals(StepStatus.COMPLETE, state.stepStates.getValue(OnboardingStep.LINK_GMAIL))
        assertEquals(StepStatus.COMPLETE, state.stepStates.getValue(OnboardingStep.LINK_GOOGLE_CALENDAR))
    }

    @Test
    fun `onboarding source ownership update writes repository and refreshes self anchors`() = runTest {
        val viewModel = buildViewModel()
        coEvery {
            sourceConnectionRepository.setOwnership("user-123", "conn-gmail", "self", null)
        } returns BecalmResult.Success(sourceConnection(id = "conn-gmail", ownership = "self"))

        viewModel.onSetSourceConnectionOwnership("conn-gmail", "self")
        advanceUntilIdle()

        coVerify(exactly = 1) {
            sourceConnectionRepository.setOwnership("user-123", "conn-gmail", "self", null)
        }
        coVerify(atLeast = 1) { selfIdentityRepository.refresh("user-123") }
        assertEquals(null, viewModel.uiState.value.updatingSourceOwnershipId)
        assertEquals(null, viewModel.uiState.value.error)
    }

    @Test
    fun `source ownership update exception resets row loading and keeps setup retryable`() = runTest {
        val viewModel = buildViewModel()
        coEvery {
            sourceConnectionRepository.setOwnership("user-123", "conn-gmail", "self", null)
        } throws IllegalStateException("patch failed")

        viewModel.onSetSourceConnectionOwnership("conn-gmail", "self")
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.updatingSourceOwnershipId)
        assertEquals(R.string.settings_identity_error_update_connection, viewModel.uiState.value.error?.resId)
    }

    @Test
    fun `setup completion does not block on unknown source ownership during first run`() = runTest {
        coEvery { userProfileRepository.find("user-123") } returns userProfile(
            displayName = "민홍",
            phone = "+821012345678",
        )
        every { sourceConnectionRepository.observeAll("user-123") } returns flowOf(
            listOf(sourceConnection(id = "conn-gmail", ownership = "unknown")),
        )

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.setupEffects.test {
            viewModel.onCompleteSetup()
            advanceUntilIdle()

            assertEquals(OnboardingSetupEffect.NavigateToCompletion(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) { userPrefsStore.setOnboardingCompleted(true) }
        assertEquals(null, viewModel.uiState.value.error)
    }

    @Test
    fun `setup completion does not block when source ownership hydration fails`() = runTest {
        coEvery { userProfileRepository.find("user-123") } returns userProfile(
            displayName = "민홍",
            phone = "+821012345678",
        )
        every { sourceConnectionRepository.observeAll("user-123") } returns flow {
            throw IllegalStateException("room failed")
        }

        val viewModel = buildViewModel()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.sourceOwnershipsLoaded)
        assertTrue(viewModel.uiState.value.sourceOwnershipLoadFailed)

        viewModel.setupEffects.test {
            viewModel.onCompleteSetup()
            advanceUntilIdle()

            assertEquals(OnboardingSetupEffect.NavigateToCompletion(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) { userPrefsStore.setOnboardingCompleted(true) }
        assertEquals(null, viewModel.uiState.value.error)
    }

    private fun buildViewModel(): OnboardingViewModel = OnboardingViewModel(
        userPrefsStore = userPrefsStore,
        sessionStore = sessionStore,
        logger = logger,
        observability = observability,
        imapCredentialStore = imapCredentialStore,
        emailOAuthConnector = emailOAuthConnector,
        calendarOAuthConnector = calendarOAuthConnector,
        appRuntimeSyncCoordinator = appRuntimeSyncCoordinator,
        sourceStatusRepository = sourceStatusRepository,
        sourceConnectionRepository = sourceConnectionRepository,
        selfIdentityRepository = selfIdentityRepository,
        userProfileRepository = userProfileRepository,
        calendarEventRepository = calendarEventRepository,
        commitmentRepository = commitmentRepository,
        sourceEventParticipantRepository = sourceEventParticipantRepository,
        commitmentParticipantRepository = commitmentParticipantRepository,
        scheduleEventLinkRepository = scheduleEventLinkRepository,
        personEnrichmentRepository = personEnrichmentRepository,
        firstMemoryRepository = firstMemoryRepository,
        onboardingActivationPreviewRepository = onboardingActivationPreviewRepository,
        rawIngestionRepository = rawIngestionRepository,
        workScheduler = workScheduler,
    )

    private fun activationPreview(): OnboardingActivationPreview =
        OnboardingActivationPreview(
            commitmentId = "commitment-1",
            personId = "person-minji",
            personName = "민지",
            participantId = "participant-minji",
            participantName = "민지",
            participantEmail = "minji@example.com",
            participantPhone = null,
            contactMatched = true,
            title = "금요일까지 제안서 초안 보내기",
            itemType = "action",
            direction = "give",
            scheduleStatus = null,
            decisionStatus = null,
            dueAt = null,
            dueHint = "금요일",
            sourceType = SourceType.GMAIL,
            sourceTitle = "Re: BeCalm 사업 소개서 공유",
            sourceEventOccurredAt = Instant.parse("2026-05-26T00:00:00Z"),
            confidence = 0.9,
        )

    private fun userProfile(
        displayName: String,
        phone: String? = null,
        displayNameSource: String? = "manual",
    ): UserProfileEntity =
        UserProfileEntity(
            userId = "user-123",
            displayNameOverride = displayName,
            phoneE164Self = phone,
            displayNameSource = displayNameSource,
            timezone = "Asia/Seoul",
            preferredLocale = "ko",
            createdAt = Instant.parse("2026-05-16T00:00:00Z"),
            updatedAt = Instant.parse("2026-05-16T00:00:00Z"),
        )

    private fun sourceConnection(
        id: String,
        provider: String = "google",
        capability: String = "mail",
        accountIdentifier: String? = "me@example.com",
        accountDisplayName: String? = null,
        ownership: String = "unknown",
        status: String = "connected",
    ): SourceConnectionEntity =
        SourceConnectionEntity(
            id = id,
            userId = "user-123",
            provider = provider,
            capability = capability,
            accountIdentifier = accountIdentifier,
            accountDisplayName = accountDisplayName,
            ownership = ownership,
            status = status,
            linkedSelfAnchorId = null,
            lastSyncAt = null,
            lastError = null,
        )

    private fun rawEvent(
        id: String,
        sourceType: String,
        sourceRef: String?,
    ): RawIngestionEventEntity =
        RawIngestionEventEntity(
            id = id,
            userId = "user-123",
            clientEventId = "client-$id",
            sourceType = sourceType,
            sourceRef = sourceRef,
            timestamp = Instant.parse("2026-05-26T00:00:00Z"),
            syncStatus = "pending",
        )

    private fun identityCommit(
        displayName: String,
        email: String? = null,
        phone: String? = null,
        alias: String? = null,
        displayNameSource: String = "manual",
    ): OnboardingSelfIdentityCommit {
        val anchors = buildList {
            if (!email.isNullOrBlank()) {
                add(selfAnchor(id = "anchor-email", type = "email", value = email))
            }
            if (!phone.isNullOrBlank()) {
                add(selfAnchor(id = "anchor-phone", type = "phone", value = phone))
            }
            if (!alias.isNullOrBlank()) {
                add(selfAnchor(id = "anchor-alias", type = "alias", value = alias))
            }
        }
        return OnboardingSelfIdentityCommit(
            profile = userProfile(displayName = displayName, phone = phone, displayNameSource = displayNameSource),
            anchors = anchors,
            emailConnectionRecommendation = null,
        )
    }

    private fun selfAnchor(
        id: String,
        type: String,
        value: String,
        status: String = "active",
    ): SelfIdentityAnchorEntity =
        SelfIdentityAnchorEntity(
            id = id,
            userId = "user-123",
            anchorType = type,
            normalizedValue = value,
            displayValue = value,
            source = "manual",
            scope = "global",
            sourceConnectionId = null,
            sourceEventId = null,
            trust = "user_confirmed",
            status = status,
            createdAt = Instant.parse("2026-05-16T00:00:00Z"),
            updatedAt = Instant.parse("2026-05-16T00:00:00Z"),
        )
}
