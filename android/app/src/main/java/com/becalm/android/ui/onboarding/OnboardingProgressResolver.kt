package com.becalm.android.ui.onboarding

import com.becalm.android.ui.navigation.BecalmRoute

internal object OnboardingProgressResolver {
    fun decodeStepStatuses(raw: Map<String, String>): Map<OnboardingStep, StepStatus> =
        buildMap {
            raw.forEach { (stepName, statusName) ->
                val step = runCatching { OnboardingStep.valueOf(stepName) }.getOrNull()
                val status = runCatching { StepStatus.valueOf(statusName) }.getOrNull()
                if (step != null && status != null) {
                    put(step, status)
                }
            }
        }

    fun encodeStepStatuses(statuses: Map<OnboardingStep, StepStatus>): Map<String, String> =
        statuses.mapKeys { (step, _) -> step.name }
            .mapValues { (_, status) -> status.name }

    fun hydrateStepStates(
        persisted: Map<String, String>,
        termsAccepted: Boolean,
        signedIn: Boolean,
    ): Map<OnboardingStep, StepStatus> {
        val decoded = decodeStepStatuses(persisted)
        return OnboardingStep.entries.associateWith { step ->
            when {
                step == OnboardingStep.TERMS && termsAccepted ->
                    decoded[step]?.takeIf(OnboardingTerminalStatusPolicy::isTerminal) ?: StepStatus.GRANTED
                step == OnboardingStep.LOGIN && signedIn ->
                    decoded[step]?.takeIf(OnboardingTerminalStatusPolicy::isTerminal) ?: StepStatus.GRANTED
                else -> decoded[step] ?: StepStatus.NOT_STARTED
            }
        }
    }

    fun firstIncompleteStep(stepStates: Map<OnboardingStep, StepStatus>): OnboardingStep =
        OnboardingStep.entries.firstOrNull { step ->
            !OnboardingTerminalStatusPolicy.isTerminal(stepStates[step] ?: StepStatus.NOT_STARTED)
        } ?: OnboardingStep.COLD_SYNC

    fun resumeRoute(
        stepStates: Map<OnboardingStep, StepStatus>,
        setupRoute: String? = null,
    ): String = when (val firstIncomplete = firstIncompleteStep(stepStates)) {
        OnboardingStep.TERMS -> BecalmRoute.Terms.path
        OnboardingStep.LOGIN -> BecalmRoute.Login.path
        else -> setupRoute
            ?.takeIf(OnboardingSetupDestination::isSetupRoute)
            ?: if (hasPostLoginProgress(stepStates)) {
                OnboardingSetupDestination.defaultForFirstIncompleteStep(firstIncomplete).routePath
            } else {
                OnboardingSetupDestination.defaultRoutePath
            }
    }

    private fun hasPostLoginProgress(stepStates: Map<OnboardingStep, StepStatus>): Boolean =
        stepStates
            .filterKeys { it != OnboardingStep.TERMS && it != OnboardingStep.LOGIN }
            .values
            .any { it != StepStatus.NOT_STARTED }
}
