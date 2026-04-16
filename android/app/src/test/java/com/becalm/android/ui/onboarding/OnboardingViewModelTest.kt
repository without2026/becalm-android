package com.becalm.android.ui.onboarding

import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var userPrefsStore: UserPrefsStore
    private lateinit var logger: Logger
    private lateinit var viewModel: OnboardingViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        userPrefsStore = mockk(relaxed = true)
        logger = mockk(relaxed = true)
        viewModel = OnboardingViewModel(userPrefsStore, logger)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ─── Step navigation ───────────────────────────────────────────────────────

    @Test
    fun `onNext advances currentStepIndex by one`() {
        val initialIndex = viewModel.uiState.value.currentStepIndex
        assertEquals(0, initialIndex)

        viewModel.onNext()

        assertEquals(1, viewModel.uiState.value.currentStepIndex)
    }

    @Test
    fun `onBack decrements currentStepIndex and clamps at zero`() {
        // Advance to step 2 first
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

    // ─── onMarkStepStatus ─────────────────────────────────────────────────────

    @Test
    fun `onMarkStepStatus updates the stepStates map for the given step`() {
        viewModel.onMarkStepStatus(OnboardingStep.SMS_PERM, StepStatus.GRANTED)

        val states = viewModel.uiState.value.stepStates
        assertEquals(StepStatus.GRANTED, states[OnboardingStep.SMS_PERM])
        // Other steps remain NOT_STARTED
        assertEquals(StepStatus.NOT_STARTED, states[OnboardingStep.CALL_PERM])
    }

    @Test
    fun `onMarkStepStatus for CONTACTS_PERM records DENIED correctly`() {
        viewModel.onMarkStepStatus(OnboardingStep.CONTACTS_PERM, StepStatus.DENIED)

        assertEquals(StepStatus.DENIED, viewModel.uiState.value.stepStates[OnboardingStep.CONTACTS_PERM])
    }

    // ─── onCompleteOnboarding ─────────────────────────────────────────────────

    @Test
    fun `onCompleteOnboarding calls UserPrefsStore setOnboardingCompleted true`() = runTest {
        viewModel.onCompleteOnboarding()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { userPrefsStore.setOnboardingCompleted(true) }
    }

    @Test
    fun `onCompleteOnboarding marks COMPLETE step as COMPLETE in stepStates`() = runTest {
        viewModel.onCompleteOnboarding()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("isCompleting should be false after completion", !state.isCompleting)
        assertEquals(StepStatus.COMPLETE, state.stepStates[OnboardingStep.COMPLETE])
    }

    @Test
    fun `onSkipStep marks current step as SKIPPED and advances index`() {
        val step = viewModel.steps[viewModel.uiState.value.currentStepIndex]
        viewModel.onSkipStep()

        val state = viewModel.uiState.value
        assertEquals(StepStatus.SKIPPED, state.stepStates[step])
        assertEquals(1, state.currentStepIndex)
    }
}
