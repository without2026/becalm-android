package com.becalm.android.ui.onboarding

import com.becalm.android.ui.navigation.BecalmRoute
import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingProgressResolverSpecTest {

    @Test
    fun `post login incomplete progress resumes to unified setup`() {
        val states = OnboardingStep.entries.associateWith { StepStatus.NOT_STARTED } +
            mapOf(
                OnboardingStep.TERMS to StepStatus.GRANTED,
                OnboardingStep.LOGIN to StepStatus.GRANTED,
                OnboardingStep.PIPA_CONSENT to StepStatus.NOT_STARTED,
            )

        assertEquals(BecalmRoute.OnboardingSetup.path, OnboardingProgressResolver.resumeRoute(states))
    }

    @Test
    fun `legacy half completed provider progress still resumes to unified setup`() {
        val states = OnboardingStep.entries.associateWith { StepStatus.NOT_STARTED } +
            mapOf(
                OnboardingStep.TERMS to StepStatus.GRANTED,
                OnboardingStep.LOGIN to StepStatus.GRANTED,
                OnboardingStep.PIPA_CONSENT to StepStatus.GRANTED,
                OnboardingStep.RECORDING_FOLDER to StepStatus.SKIPPED,
                OnboardingStep.CONTACTS_PERM to StepStatus.GRANTED,
                OnboardingStep.LINK_GMAIL to StepStatus.COMPLETE,
            )

        assertEquals(BecalmRoute.OnboardingSetup.path, OnboardingProgressResolver.resumeRoute(states))
    }
}
