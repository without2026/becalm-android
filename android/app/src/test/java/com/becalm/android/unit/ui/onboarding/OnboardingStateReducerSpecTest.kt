package com.becalm.android.unit.ui.onboarding

import com.becalm.android.ui.onboarding.OnboardingStateReducer
import com.becalm.android.ui.onboarding.OnboardingStep
import com.becalm.android.ui.onboarding.StepStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingStateReducerSpecTest {

    @Test
    // spec: RUX-011
    fun `terminal gate uses shared terminal status policy`() {
        val terminal = OnboardingStep.entries.associateWith { StepStatus.SKIPPED } +
            mapOf(
                OnboardingStep.TERMS to StepStatus.GRANTED,
                OnboardingStep.LOGIN to StepStatus.GRANTED,
                OnboardingStep.LINK_GMAIL to StepStatus.COMPLETE,
                OnboardingStep.CONTACTS_PERM to StepStatus.DENIED,
            )
        val inProgress = terminal + (OnboardingStep.COLD_SYNC to StepStatus.IN_PROGRESS)

        assertTrue(OnboardingStateReducer.isTerminalGatePassed(terminal))
        assertFalse(OnboardingStateReducer.isTerminalGatePassed(inProgress))
    }
}
