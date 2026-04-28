package com.becalm.android.unit.ui.onboarding

import app.cash.turbine.test
import com.becalm.android.core.observability.ObservabilityClient
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.EmailPipaProvider
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.secure.ImapCredentialStore
import com.becalm.android.data.local.secure.ImapCredentials
import com.becalm.android.ui.onboarding.ContactsPermissionEffect
import com.becalm.android.ui.onboarding.CalendarConnectEvent
import com.becalm.android.ui.onboarding.CalendarOAuthConnector
import com.becalm.android.ui.onboarding.CalendarOAuthProvider
import com.becalm.android.ui.onboarding.CalendarOAuthResult
import com.becalm.android.ui.onboarding.EmailOAuthConnector
import com.becalm.android.ui.onboarding.EmailOAuthProvider
import com.becalm.android.ui.onboarding.EmailOAuthResult
import com.becalm.android.ui.onboarding.OnboardingStep
import com.becalm.android.ui.onboarding.OnboardingViewModel
import com.becalm.android.ui.onboarding.PipaConsentEvent
import com.becalm.android.ui.onboarding.StepStatus
import com.becalm.android.worker.AppRuntimeSyncCoordinator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-123")
        every { userPrefsStore.observeTermsAccepted() } returns flowOf(true)
        every { userPrefsStore.observeOnboardingStepStatuses() } returns flowOf(emptyMap())
        EmailPipaProvider.entries.forEach { provider ->
            every { userPrefsStore.observeEmailPipaConsent(provider) } returns flowOf(false)
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
    fun `ONB-003 recording folder permission denied clears SAF tree and disables voice`() = runTest {
        val viewModel = buildViewModel()

        viewModel.onRecordingFolderPermissionResult(granted = false)
        advanceUntilIdle()

        coVerify(exactly = 1) { userPrefsStore.setRecordingFolderTreeUri(null) }
        coVerify(exactly = 1) { userPrefsStore.setSourceEnabled(com.becalm.android.data.remote.dto.SourceType.VOICE, false) }
        verify(exactly = 1) { appRuntimeSyncCoordinator.refresh() }
        assertEquals(
            StepStatus.DENIED,
            viewModel.uiState.value.stepStates.getValue(OnboardingStep.RECORDING_FOLDER),
        )
    }

    @Test
    fun `ONB-003 SAF tree grant persists URI enables voice and marks recording folder granted`() = runTest {
        val viewModel = buildViewModel()

        viewModel.onRecordingFolderTreeGranted("content://tree/recordings")
        advanceUntilIdle()

        coVerify(exactly = 1) { userPrefsStore.setRecordingFolderTreeUri("content://tree/recordings") }
        coVerify(exactly = 1) { userPrefsStore.setSourceEnabled(com.becalm.android.data.remote.dto.SourceType.VOICE, true) }
        verify(exactly = 1) { appRuntimeSyncCoordinator.refresh() }
        assertEquals(
            StepStatus.GRANTED,
            viewModel.uiState.value.stepStates.getValue(OnboardingStep.RECORDING_FOLDER),
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
    fun `ENR-002 denied contacts permission marks contacts denied and navigates to Gmail email PIPA`() = runTest {
        val viewModel = buildViewModel()

        viewModel.contactsPermissionEffects.test {
            viewModel.onContactsPermissionResult(granted = false)

            assertEquals(
                ContactsPermissionEffect.NavigateToEmailPipa(EmailPipaProvider.GMAIL),
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
    fun `ENR-002 skipping contacts marks contacts skipped and navigates to Gmail email PIPA`() = runTest {
        val viewModel = buildViewModel()

        viewModel.contactsPermissionEffects.test {
            viewModel.onSkipContacts()
            advanceUntilIdle()

            assertEquals(
                ContactsPermissionEffect.NavigateToEmailPipa(EmailPipaProvider.GMAIL),
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
        val viewModel = buildViewModel()

        viewModel.onConnectEmailProvider(EmailPipaProvider.GMAIL, activity)
        advanceUntilIdle()

        coVerify(exactly = 1) { userPrefsStore.setEmailSourceConnected(EmailPipaProvider.GMAIL, true) }
        coVerify(exactly = 1) { userPrefsStore.setEmailSourceManagedByBackend(EmailPipaProvider.GMAIL, true) }
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
        assertTrue(viewModel.uiState.value.error?.contains("Please complete all steps") == true)
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

    private fun buildViewModel(): OnboardingViewModel = OnboardingViewModel(
        userPrefsStore = userPrefsStore,
        logger = logger,
        observability = observability,
        imapCredentialStore = imapCredentialStore,
        emailOAuthConnector = emailOAuthConnector,
        calendarOAuthConnector = calendarOAuthConnector,
        appRuntimeSyncCoordinator = appRuntimeSyncCoordinator,
    )
}
