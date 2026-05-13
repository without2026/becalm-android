package com.becalm.android.ui.onboarding

/**
 * Pure onboarding state transitions shared by [OnboardingViewModel].
 *
 * Keeping these reducers free of logging, coroutines, and persistence makes the step
 * machine easier to reason about and keeps the ViewModel focused on side effects.
 */
internal object OnboardingStateReducer {
    fun next(
        state: OnboardingUiState,
        steps: List<OnboardingStep>,
    ): OnboardingUiState {
        val next = (state.currentStepIndex + 1).coerceAtMost(steps.lastIndex)
        return state.copy(currentStepIndex = next, error = null)
    }

    fun previous(
        state: OnboardingUiState,
        steps: List<OnboardingStep>,
    ): OnboardingUiState {
        val previous = (state.currentStepIndex - 1).coerceAtLeast(0)
        return state.copy(currentStepIndex = previous, error = null)
    }

    fun skipStep(
        state: OnboardingUiState,
        step: OnboardingStep,
        steps: List<OnboardingStep>,
    ): OnboardingUiState {
        val targetIndex = steps.indexOf(step).coerceAtLeast(0)
        val next = (targetIndex + 1).coerceAtMost(steps.lastIndex)
        return state.copy(
            currentStepIndex = next,
            stepStates = state.stepStates + (step to StepStatus.SKIPPED),
            error = null,
        )
    }

    fun markStepStatus(
        state: OnboardingUiState,
        step: OnboardingStep,
        status: StepStatus,
    ): OnboardingUiState =
        state.copy(stepStates = state.stepStates + (step to status), error = null)

    fun applyPipaConsent(
        state: OnboardingUiState,
        granted: Boolean,
        steps: List<OnboardingStep>,
    ): OnboardingUiState {
        val pipaIndex = steps.indexOf(OnboardingStep.PIPA_CONSENT)
        return if (granted) {
            val recordingFolderIndex = steps.indexOf(OnboardingStep.RECORDING_FOLDER)
            state.copy(
                currentStepIndex = recordingFolderIndex.coerceAtLeast(pipaIndex + 1),
                stepStates = state.stepStates + (OnboardingStep.PIPA_CONSENT to StepStatus.GRANTED),
                error = null,
            )
        } else {
            val contactsIndex = steps.indexOf(OnboardingStep.CONTACTS_PERM)
            val targetIndex = if (contactsIndex >= 0) contactsIndex else pipaIndex + 2
            state.copy(
                currentStepIndex = targetIndex.coerceAtMost(steps.lastIndex),
                stepStates = state.stepStates +
                    (OnboardingStep.PIPA_CONSENT to StepStatus.DENIED) +
                    (OnboardingStep.RECORDING_FOLDER to StepStatus.SKIPPED) +
                    (OnboardingStep.CALL_LOG_MATCHING to StepStatus.SKIPPED),
                error = null,
            )
        }
    }

    fun isTerminalGatePassed(stepStates: Map<OnboardingStep, StepStatus>): Boolean {
        return OnboardingStep.entries
            .all { step -> OnboardingTerminalStatusPolicy.isTerminal(stepStates[step] ?: StepStatus.NOT_STARTED) }
    }
}
