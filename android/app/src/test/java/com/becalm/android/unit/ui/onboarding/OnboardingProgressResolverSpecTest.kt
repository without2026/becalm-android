package com.becalm.android.ui.onboarding

import com.becalm.android.ui.navigation.BecalmRoute
import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingProgressResolverSpecTest {

    @Test
    // spec: RUX-011
    fun `decode ignores unknown persisted step names and statuses`() {
        val decoded = OnboardingProgressResolver.decodeStepStatuses(
            mapOf(
                "TERMS" to "GRANTED",
                "NO_LONGER_EXISTS" to "COMPLETE",
                "LOGIN" to "BROKEN",
            ),
        )

        assertEquals(mapOf(OnboardingStep.TERMS to StepStatus.GRANTED), decoded)
    }

    @Test
    fun `hydrate upgrades terms and login only when persisted value is not terminal`() {
        val hydrated = OnboardingProgressResolver.hydrateStepStates(
            persisted = mapOf(
                "TERMS" to "IN_PROGRESS",
                "LOGIN" to "NOT_STARTED",
                "CONTACTS_PERM" to "DENIED",
            ),
            termsAccepted = true,
            signedIn = true,
        )

        assertEquals(StepStatus.GRANTED, hydrated.getValue(OnboardingStep.TERMS))
        assertEquals(StepStatus.GRANTED, hydrated.getValue(OnboardingStep.LOGIN))
        assertEquals(StepStatus.DENIED, hydrated.getValue(OnboardingStep.CONTACTS_PERM))
    }

    @Test
    // spec: RUX-011
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
