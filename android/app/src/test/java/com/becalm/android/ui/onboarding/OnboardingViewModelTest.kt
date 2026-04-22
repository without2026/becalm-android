package com.becalm.android.ui.onboarding

import com.becalm.android.core.observability.ObservabilityClient
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.EmailPipaProvider
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.secure.ImapCredentialStore
import com.becalm.android.data.local.secure.ImapCredentials
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.remote.gmail.GoogleAuthTokenProviderImpl
import com.becalm.android.data.remote.msgraph.MsGraphTokenProviderImpl
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [OnboardingViewModel] against the canonical 13-step onboarding flow
 * (post-S6-E: NOTIFICATION_PERM inserted between LINK_OUTLOOK_CALENDAR and BATTERY_OPT).
 *
 * Step ordering (1-indexed):
 * 1 TERMS → 2 LOGIN → 3 PIPA_CONSENT → 4 RECORDING_FOLDER → 5 CONTACTS_PERM
 *   → 6 LINK_GMAIL → 7 LINK_OUTLOOK_MAIL → 8 LINK_IMAP
 *   → 9 LINK_GOOGLE_CALENDAR → 10 LINK_OUTLOOK_CALENDAR
 *   → 11 NOTIFICATION_PERM → 12 BATTERY_OPT → 13 COLD_SYNC
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var userPrefsStore: UserPrefsStore
    private lateinit var logger: Logger
    private lateinit var observability: ObservabilityClient
    private lateinit var googleAuthTokenProvider: GoogleAuthTokenProviderImpl
    private lateinit var msGraphTokenProvider: MsGraphTokenProviderImpl
    private lateinit var imapCredentialStore: ImapCredentialStore
    private lateinit var viewModel: OnboardingViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        userPrefsStore = mockk(relaxed = true)
        logger = mockk(relaxed = true)
        observability = mockk(relaxed = true)
        googleAuthTokenProvider = mockk(relaxed = true)
        msGraphTokenProvider = mockk(relaxed = true)
        imapCredentialStore = mockk(relaxed = true)
        viewModel = OnboardingViewModel(
            userPrefsStore = userPrefsStore,
            logger = logger,
            observability = observability,
            googleAuthTokenProvider = googleAuthTokenProvider,
            msGraphTokenProvider = msGraphTokenProvider,
            imapCredentialStore = imapCredentialStore,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ─── Enum shape & ordering ────────────────────────────────────────────────

    @Test
    fun `canonical onboarding has exactly 13 steps`() {
        assertEquals(13, OnboardingStep.entries.size)
        assertEquals(13, viewModel.steps.size)
    }

    @Test
    fun `first step is TERMS and last step is COLD_SYNC`() {
        assertEquals(OnboardingStep.TERMS, viewModel.steps.first())
        assertEquals(OnboardingStep.COLD_SYNC, viewModel.steps.last())
    }

    @Test
    fun `canonical step order matches the spec invariant`() {
        val expected = listOf(
            OnboardingStep.TERMS,
            OnboardingStep.LOGIN,
            OnboardingStep.PIPA_CONSENT,
            OnboardingStep.RECORDING_FOLDER,
            OnboardingStep.CONTACTS_PERM,
            OnboardingStep.LINK_GMAIL,
            OnboardingStep.LINK_OUTLOOK_MAIL,
            OnboardingStep.LINK_IMAP,
            OnboardingStep.LINK_GOOGLE_CALENDAR,
            OnboardingStep.LINK_OUTLOOK_CALENDAR,
            OnboardingStep.NOTIFICATION_PERM,
            OnboardingStep.BATTERY_OPT,
            OnboardingStep.COLD_SYNC,
        )
        assertEquals(expected, viewModel.steps)
    }

    // ─── First-run bootstrap ──────────────────────────────────────────────────

    @Test
    fun `first-run onboarding starts at TERMS with index 0`() {
        val state = viewModel.uiState.value
        assertEquals(0, state.currentStepIndex)
        assertEquals(OnboardingStep.TERMS, viewModel.steps[state.currentStepIndex])
        // All steps begin NOT_STARTED
        OnboardingStep.entries.forEach { step ->
            assertEquals(
                "Expected $step to start as NOT_STARTED",
                StepStatus.NOT_STARTED,
                state.stepStates[step],
            )
        }
    }

    // ─── Sequential navigation ────────────────────────────────────────────────

    @Test
    fun `onNext advances currentStepIndex by one`() {
        val initialIndex = viewModel.uiState.value.currentStepIndex
        assertEquals(0, initialIndex)

        viewModel.onNext()

        assertEquals(1, viewModel.uiState.value.currentStepIndex)
    }

    @Test
    fun `onNext advances sequentially through all 13 steps`() {
        // From index 0 (TERMS), 12 onNext() calls should land at COLD_SYNC (index 12).
        repeat(12) { viewModel.onNext() }
        assertEquals(12, viewModel.uiState.value.currentStepIndex)
        assertEquals(OnboardingStep.COLD_SYNC, viewModel.steps[viewModel.uiState.value.currentStepIndex])
    }

    @Test
    fun `onBack decrements currentStepIndex and clamps at zero`() {
        viewModel.onNext()
        viewModel.onNext()
        assertEquals(2, viewModel.uiState.value.currentStepIndex)

        viewModel.onBack()
        assertEquals(1, viewModel.uiState.value.currentStepIndex)

        // Clamp at zero
        viewModel.onBack()
        viewModel.onBack()
        assertEquals(0, viewModel.uiState.value.currentStepIndex)
    }

    @Test
    fun `onNext does not advance past the last step`() {
        val lastIndex = viewModel.steps.lastIndex
        repeat(lastIndex + 5) { viewModel.onNext() }

        assertEquals(lastIndex, viewModel.uiState.value.currentStepIndex)
    }

    // ─── Progress arithmetic ("N/13" display, post-S6-E) ──────────────────────

    @Test
    fun `progress index plus one over steps size displays as one-based N over 13`() {
        assertEquals(13, viewModel.steps.size)
        // Step 1 of 13 at first render
        assertEquals(1, viewModel.uiState.value.currentStepIndex + 1)
        viewModel.onNext()
        assertEquals(2, viewModel.uiState.value.currentStepIndex + 1)
        // Skip ahead to the terminal step
        repeat(viewModel.steps.lastIndex) { viewModel.onNext() }
        assertEquals(13, viewModel.uiState.value.currentStepIndex + 1)
    }

    // ─── onMarkStepStatus ─────────────────────────────────────────────────────

    @Test
    fun `onMarkStepStatus updates stepStates map for the given step`() {
        viewModel.onMarkStepStatus(OnboardingStep.LINK_GMAIL, StepStatus.GRANTED)

        val states = viewModel.uiState.value.stepStates
        assertEquals(StepStatus.GRANTED, states[OnboardingStep.LINK_GMAIL])
        // Other steps remain NOT_STARTED
        assertEquals(StepStatus.NOT_STARTED, states[OnboardingStep.LINK_IMAP])
    }

    @Test
    fun `onMarkStepStatus records DENIED for CONTACTS_PERM graceful skip`() {
        // ONB-CONTACTS: "나중에" or denied system dialog must not block onboarding.
        viewModel.onMarkStepStatus(OnboardingStep.CONTACTS_PERM, StepStatus.DENIED)

        assertEquals(
            StepStatus.DENIED,
            viewModel.uiState.value.stepStates[OnboardingStep.CONTACTS_PERM],
        )
    }

    // ─── onSkipStep (OAuth skip) ──────────────────────────────────────────────

    @Test
    fun `onSkipStep marks the explicit step as SKIPPED regardless of currentStepIndex`() {
        // Skip LINK_GMAIL even though currentStepIndex is still 0 (TERMS) — the ONB-008
        // terminal gate fails if the skip path leaks back to whatever step the index
        // happened to be pointing at.
        viewModel.onSkipStep(OnboardingStep.LINK_GMAIL)

        val state = viewModel.uiState.value
        assertEquals(StepStatus.SKIPPED, state.stepStates[OnboardingStep.LINK_GMAIL])
        assertEquals(
            "TERMS must remain untouched",
            StepStatus.NOT_STARTED,
            state.stepStates[OnboardingStep.TERMS],
        )
    }

    @Test
    fun `onSkipStep advances currentStepIndex to the next step after the skipped one`() {
        viewModel.onSkipStep(OnboardingStep.LINK_GMAIL)

        val index = viewModel.uiState.value.currentStepIndex
        assertEquals(
            "index must follow the skipped step so the next screen reads it correctly",
            OnboardingStep.LINK_OUTLOOK_MAIL,
            viewModel.steps[index],
        )
    }

    @Test
    fun `mixed grant and skip decisions all reach onCompleteOnboarding terminal gate`() = runTest {
        // Realistic mixed path: grant TERMS/LOGIN/PIPA/RECORDING, grant contacts,
        // skip Gmail, skip Outlook, skip IMAP, connect both calendars, skip
        // notifications, grant battery, finish ColdSync. Every step has an
        // explicit mark; none rely on the stale index. The terminal gate must
        // accept the resulting mix (GRANTED / SKIPPED / COMPLETE) per ONB-008.
        viewModel.onMarkStepStatus(OnboardingStep.TERMS, StepStatus.GRANTED)
        viewModel.onMarkStepStatus(OnboardingStep.LOGIN, StepStatus.GRANTED)
        viewModel.onMarkStepStatus(OnboardingStep.PIPA_CONSENT, StepStatus.DENIED)
        viewModel.onMarkStepStatus(OnboardingStep.RECORDING_FOLDER, StepStatus.SKIPPED)
        viewModel.onMarkStepStatus(OnboardingStep.CONTACTS_PERM, StepStatus.GRANTED)
        viewModel.onSkipStep(OnboardingStep.LINK_GMAIL)
        viewModel.onSkipStep(OnboardingStep.LINK_OUTLOOK_MAIL)
        viewModel.onSkipStep(OnboardingStep.LINK_IMAP)
        viewModel.onMarkStepStatus(OnboardingStep.LINK_GOOGLE_CALENDAR, StepStatus.COMPLETE)
        viewModel.onMarkStepStatus(OnboardingStep.LINK_OUTLOOK_CALENDAR, StepStatus.COMPLETE)
        viewModel.onSkipStep(OnboardingStep.NOTIFICATION_PERM)
        viewModel.onMarkStepStatus(OnboardingStep.BATTERY_OPT, StepStatus.COMPLETE)
        viewModel.onMarkStepStatus(OnboardingStep.COLD_SYNC, StepStatus.COMPLETE)

        viewModel.onCompleteOnboarding()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse("terminal gate must accept the mixed path", state.isCompleting)
        assertEquals(null, state.error)
        coVerify(exactly = 1) { userPrefsStore.setOnboardingCompleted(true) }
    }

    // ─── PIPA consent: denied path skips RECORDING_FOLDER ─────────────────────

    @Test
    fun `PIPA consent denied skips RECORDING_FOLDER and advances to CONTACTS_PERM`() = runTest {
        // Position VM at PIPA_CONSENT (index 2)
        viewModel.onNext()
        viewModel.onNext()
        assertEquals(OnboardingStep.PIPA_CONSENT, viewModel.steps[viewModel.uiState.value.currentStepIndex])

        viewModel.onPipaConsentDeclined()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(
            OnboardingStep.CONTACTS_PERM,
            viewModel.steps[state.currentStepIndex],
        )
        assertEquals(StepStatus.DENIED, state.stepStates[OnboardingStep.PIPA_CONSENT])
        assertEquals(
            "RECORDING_FOLDER must be marked SKIPPED when PIPA declined",
            StepStatus.SKIPPED,
            state.stepStates[OnboardingStep.RECORDING_FOLDER],
        )

        coVerify(exactly = 1) { userPrefsStore.setThirdPartyProvisionConsent(false) }
    }

    @Test
    fun `PIPA consent granted advances to RECORDING_FOLDER`() = runTest {
        viewModel.onNext()
        viewModel.onNext()
        assertEquals(OnboardingStep.PIPA_CONSENT, viewModel.steps[viewModel.uiState.value.currentStepIndex])

        viewModel.onPipaConsentGranted()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(
            OnboardingStep.RECORDING_FOLDER,
            viewModel.steps[state.currentStepIndex],
        )
        assertEquals(StepStatus.GRANTED, state.stepStates[OnboardingStep.PIPA_CONSENT])
        coVerify(exactly = 1) { userPrefsStore.setThirdPartyProvisionConsent(true) }
    }

    // ─── Terminal gate ────────────────────────────────────────────────────────

    @Test
    fun `isTerminalGatePassed blocks completion while any step is NOT_STARTED`() = runTest {
        viewModel.onCompleteOnboarding()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse("should not be in-flight after gate rejection", state.isCompleting)
        assertNotNull("gate rejection must surface an error", state.error)
        // COLD_SYNC should NOT be marked COMPLETE — DataStore write was never attempted.
        assertEquals(
            StepStatus.NOT_STARTED,
            state.stepStates[OnboardingStep.COLD_SYNC],
        )
        coVerify(exactly = 0) { userPrefsStore.setOnboardingCompleted(true) }
    }

    @Test
    fun `onCompleteOnboarding passes gate and marks COLD_SYNC COMPLETE once every step is terminal`() = runTest {
        // Drive every step to a terminal status. Mix GRANTED / SKIPPED / DENIED to exercise
        // the full terminal set allowed by the gate.
        val terminalAssignments = mapOf(
            OnboardingStep.TERMS to StepStatus.GRANTED,
            OnboardingStep.LOGIN to StepStatus.GRANTED,
            OnboardingStep.PIPA_CONSENT to StepStatus.DENIED,
            OnboardingStep.RECORDING_FOLDER to StepStatus.SKIPPED,
            OnboardingStep.CONTACTS_PERM to StepStatus.DENIED,
            OnboardingStep.LINK_GMAIL to StepStatus.SKIPPED,
            OnboardingStep.LINK_OUTLOOK_MAIL to StepStatus.SKIPPED,
            OnboardingStep.LINK_IMAP to StepStatus.SKIPPED,
            OnboardingStep.LINK_GOOGLE_CALENDAR to StepStatus.SKIPPED,
            OnboardingStep.LINK_OUTLOOK_CALENDAR to StepStatus.SKIPPED,
            OnboardingStep.NOTIFICATION_PERM to StepStatus.SKIPPED,
            OnboardingStep.BATTERY_OPT to StepStatus.COMPLETE,
            // COLD_SYNC starts NOT_STARTED in this setup — gate must reject here.
        )
        terminalAssignments.forEach { (step, status) -> viewModel.onMarkStepStatus(step, status) }

        // Gate should still reject because COLD_SYNC is NOT_STARTED.
        viewModel.onCompleteOnboarding()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNotNull(
            "gate must still reject when terminal step COLD_SYNC is NOT_STARTED",
            viewModel.uiState.value.error,
        )
        coVerify(exactly = 0) { userPrefsStore.setOnboardingCompleted(true) }

        // Mark COLD_SYNC terminal (the ColdSyncScreen signals this on sync-done or dismiss).
        viewModel.onMarkStepStatus(OnboardingStep.COLD_SYNC, StepStatus.COMPLETE)

        viewModel.onCompleteOnboarding()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse("isCompleting must clear after persistence", state.isCompleting)
        assertEquals(
            "COLD_SYNC must reflect COMPLETE after successful write",
            StepStatus.COMPLETE,
            state.stepStates[OnboardingStep.COLD_SYNC],
        )
        coVerify(exactly = 1) { userPrefsStore.setOnboardingCompleted(true) }
    }

    // ─── Sentry observability (S6-E, spec ONB-007) ────────────────────────────

    @Test
    fun `reportOnboardingStepFailed emits onboarding_step_failed with step and error_code tags`() {
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
    fun `reportOnboardingStepFailed does not emit captureException`() {
        viewModel.reportOnboardingStepFailed(OnboardingStep.LINK_IMAP, "invalid_credentials")

        verify(exactly = 0) { observability.captureException(any(), any()) }
    }

    // ─── S6-D email PIPA consent (plan docs/plans/ui-onboarding-pipa-email-consent.md) ─

    @Test
    fun `onEmailPipaConsent granted persists timestamped consent and emits audit event`() = runTest {
        val ok = viewModel.onEmailPipaConsent(listOf(EmailPipaProvider.GMAIL), granted = true)

        assertEquals(true, ok)
        coVerify(exactly = 1) {
            userPrefsStore.setEmailPipaConsent(EmailPipaProvider.GMAIL, true)
        }
        verify(exactly = 1) {
            observability.captureMessage(
                message = "onboarding_pipa_email_consent",
                tags = mapOf(
                    "provider" to "gmail",
                    "granted" to "true",
                ),
            )
        }
        // Step-status stays NOT_STARTED on grant — the OAuth screen owns the final COMPLETE.
        assertEquals(
            StepStatus.NOT_STARTED,
            viewModel.uiState.value.stepStates[OnboardingStep.LINK_GMAIL],
        )
    }

    @Test
    fun `onEmailPipaConsent denied marks downstream OAuth step SKIPPED so terminal gate passes`() = runTest {
        val ok = viewModel.onEmailPipaConsent(listOf(EmailPipaProvider.OUTLOOK_MAIL), granted = false)

        assertEquals(true, ok)
        coVerify(exactly = 1) {
            userPrefsStore.setEmailPipaConsent(EmailPipaProvider.OUTLOOK_MAIL, false)
        }
        assertEquals(
            "denying outlook consent must skip LINK_OUTLOOK_MAIL so ONB-008 gate accepts the flow",
            StepStatus.SKIPPED,
            viewModel.uiState.value.stepStates[OnboardingStep.LINK_OUTLOOK_MAIL],
        )
    }

    @Test
    fun `onEmailPipaConsent imap grant writes BOTH recipient records atomically`() = runTest {
        val ok = viewModel.onEmailPipaConsent(EmailPipaProvider.IMAP_GROUP, granted = true)

        assertEquals(true, ok)
        // Per-recipient consent (PIPA Article 17): Naver Corp and Kakao Corp are distinct.
        coVerify(exactly = 1) {
            userPrefsStore.setEmailPipaConsent(EmailPipaProvider.NAVER_IMAP, true)
        }
        coVerify(exactly = 1) {
            userPrefsStore.setEmailPipaConsent(EmailPipaProvider.DAUM_IMAP, true)
        }
    }

    @Test
    fun `onEmailPipaConsent imap denial skips LINK_IMAP and records deny on both recipients`() = runTest {
        val ok = viewModel.onEmailPipaConsent(EmailPipaProvider.IMAP_GROUP, granted = false)

        assertEquals(true, ok)
        coVerify(exactly = 1) {
            userPrefsStore.setEmailPipaConsent(EmailPipaProvider.NAVER_IMAP, false)
        }
        coVerify(exactly = 1) {
            userPrefsStore.setEmailPipaConsent(EmailPipaProvider.DAUM_IMAP, false)
        }
        assertEquals(
            StepStatus.SKIPPED,
            viewModel.uiState.value.stepStates[OnboardingStep.LINK_IMAP],
        )
        // Unrelated email steps must remain untouched.
        assertEquals(
            StepStatus.NOT_STARTED,
            viewModel.uiState.value.stepStates[OnboardingStep.LINK_GMAIL],
        )
    }

    // ─── S6-F / S6-G email OAuth ─────────────────────────────────────────────

    @Test
    fun `onConnectEmailProvider success persists connected flag and marks step COMPLETE`() = runTest {
        val activity = mockk<android.app.Activity>(relaxed = true)
        coEvery { userPrefsStore.observeEmailPipaConsent(EmailPipaProvider.GMAIL) } returns
            kotlinx.coroutines.flow.flowOf(true)
        coEvery { googleAuthTokenProvider.startSignIn(activity) } returns
            com.becalm.android.data.remote.gmail.OAuthSignInResult.Success

        viewModel.onConnectEmailProvider(EmailPipaProvider.GMAIL, activity)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) {
            userPrefsStore.setEmailSourceConnected(EmailPipaProvider.GMAIL, true)
        }
        assertEquals(
            StepStatus.COMPLETE,
            viewModel.uiState.value.stepStates[OnboardingStep.LINK_GMAIL],
        )
        verify(exactly = 1) {
            observability.captureMessage(
                message = "onboarding_email_connected",
                tags = mapOf("provider" to "gmail"),
            )
        }
    }

    @Test
    fun `onConnectEmailProvider failure emits onboarding_step_failed and marks SKIPPED`() = runTest {
        val activity = mockk<android.app.Activity>(relaxed = true)
        coEvery { userPrefsStore.observeEmailPipaConsent(EmailPipaProvider.OUTLOOK_MAIL) } returns
            kotlinx.coroutines.flow.flowOf(true)
        coEvery { msGraphTokenProvider.startSignIn(activity) } returns
            com.becalm.android.data.remote.gmail.OAuthSignInResult.Failure(
                com.becalm.android.data.remote.gmail.FailureReason.NETWORK,
            )

        viewModel.onConnectEmailProvider(EmailPipaProvider.OUTLOOK_MAIL, activity)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            StepStatus.SKIPPED,
            viewModel.uiState.value.stepStates[OnboardingStep.LINK_OUTLOOK_MAIL],
        )
        verify(exactly = 1) {
            observability.captureMessage(
                message = "onboarding_step_failed",
                tags = mapOf(
                    "step" to "LINK_OUTLOOK_MAIL",
                    "error_code" to "network",
                ),
            )
        }
        coVerify(exactly = 0) {
            userPrefsStore.setEmailSourceConnected(EmailPipaProvider.OUTLOOK_MAIL, true)
        }
    }

    @Test
    fun `onConnectEmailProvider is gated on persisted PIPA consent (hard fail)`() = runTest {
        val activity = mockk<android.app.Activity>(relaxed = true)
        coEvery { userPrefsStore.observeEmailPipaConsent(EmailPipaProvider.GMAIL) } returns
            kotlinx.coroutines.flow.flowOf(false)

        viewModel.onConnectEmailProvider(EmailPipaProvider.GMAIL, activity)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { googleAuthTokenProvider.startSignIn(activity) }
        verify(exactly = 1) {
            observability.captureMessage(
                message = "onboarding_step_failed",
                tags = mapOf("step" to "LINK_GMAIL", "error_code" to "pipa_consent_missing"),
            )
        }
        assertEquals(
            StepStatus.SKIPPED,
            viewModel.uiState.value.stepStates[OnboardingStep.LINK_GMAIL],
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `onConnectEmailProvider rejects IMAP recipients (they use credential save path)`() {
        val activity = mockk<android.app.Activity>(relaxed = true)
        viewModel.onConnectEmailProvider(EmailPipaProvider.NAVER_IMAP, activity)
    }

    // ─── S6-H IMAP credential save ───────────────────────────────────────────

    @Test
    fun `saveImapCredentials success persists and marks LINK_IMAP COMPLETE`() = runTest {
        val creds = ImapCredentials(
            host = "imap.naver.com",
            port = 993,
            username = "alice",
            appPassword = "app-pw",
        )
        coEvery { userPrefsStore.observeEmailPipaConsent(EmailPipaProvider.NAVER_IMAP) } returns
            kotlinx.coroutines.flow.flowOf(true)
        coEvery { imapCredentialStore.save(SourceType.NAVER_IMAP, creds) } returns Unit

        viewModel.saveImapCredentials(SourceType.NAVER_IMAP, creds)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { imapCredentialStore.save(SourceType.NAVER_IMAP, creds) }
        assertEquals(
            StepStatus.COMPLETE,
            viewModel.uiState.value.stepStates[OnboardingStep.LINK_IMAP],
        )
        coVerify(exactly = 1) {
            userPrefsStore.setEmailSourceConnected(EmailPipaProvider.NAVER_IMAP, true)
        }
    }

    @Test
    fun `saveImapCredentials IOException failure emits network error and SKIPPED`() = runTest {
        val creds = ImapCredentials(
            host = "imap.daum.net",
            port = 993,
            username = "bob",
            appPassword = "app-pw",
        )
        coEvery { userPrefsStore.observeEmailPipaConsent(EmailPipaProvider.DAUM_IMAP) } returns
            kotlinx.coroutines.flow.flowOf(true)
        coEvery {
            imapCredentialStore.save(SourceType.DAUM_IMAP, creds)
        } throws java.io.IOException("disk full")

        viewModel.saveImapCredentials(SourceType.DAUM_IMAP, creds)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            StepStatus.SKIPPED,
            viewModel.uiState.value.stepStates[OnboardingStep.LINK_IMAP],
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
    fun `saveImapCredentials unknown provider surfaces unknown_provider code before any save`() = runTest {
        val creds = ImapCredentials(
            host = "mail.example.com",
            port = 993,
            username = "sam",
            appPassword = "app-pw",
        )

        viewModel.saveImapCredentials("bogus_source", creds)
        testDispatcher.scheduler.advanceUntilIdle()

        verify(exactly = 1) {
            observability.captureMessage(
                message = "onboarding_step_failed",
                tags = mapOf(
                    "step" to "LINK_IMAP",
                    "error_code" to "unknown_provider",
                ),
            )
        }
        // Unknown sourceType must short-circuit before the store sees the save call.
        coVerify(exactly = 0) { imapCredentialStore.save(any(), creds) }
        coVerify(exactly = 0) {
            userPrefsStore.setEmailSourceConnected(EmailPipaProvider.NAVER_IMAP, true)
        }
        coVerify(exactly = 0) {
            userPrefsStore.setEmailSourceConnected(EmailPipaProvider.DAUM_IMAP, true)
        }
    }

    @Test
    fun `saveImapCredentials is gated on PIPA consent for the specific recipient`() = runTest {
        val creds = ImapCredentials(
            host = "imap.naver.com",
            port = 993,
            username = "alice",
            appPassword = "app-pw",
        )
        coEvery { userPrefsStore.observeEmailPipaConsent(EmailPipaProvider.NAVER_IMAP) } returns
            kotlinx.coroutines.flow.flowOf(false)

        viewModel.saveImapCredentials(SourceType.NAVER_IMAP, creds)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { imapCredentialStore.save(SourceType.NAVER_IMAP, creds) }
        verify(exactly = 1) {
            observability.captureMessage(
                message = "onboarding_step_failed",
                tags = mapOf("step" to "LINK_IMAP", "error_code" to "pipa_consent_missing"),
            )
        }
    }
}
