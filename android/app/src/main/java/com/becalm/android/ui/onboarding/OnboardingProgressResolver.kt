package com.becalm.android.ui.onboarding

import com.becalm.android.ui.navigation.BecalmRoute

internal object OnboardingProgressResolver {
    private val terminalStatuses = setOf(
        StepStatus.GRANTED,
        StepStatus.COMPLETE,
        StepStatus.SKIPPED,
        StepStatus.DENIED,
    )

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
                    decoded[step]?.takeIf { it in terminalStatuses } ?: StepStatus.GRANTED
                step == OnboardingStep.LOGIN && signedIn ->
                    decoded[step]?.takeIf { it in terminalStatuses } ?: StepStatus.GRANTED
                else -> decoded[step] ?: StepStatus.NOT_STARTED
            }
        }
    }

    fun firstIncompleteStep(stepStates: Map<OnboardingStep, StepStatus>): OnboardingStep =
        OnboardingStep.entries.firstOrNull { step ->
            (stepStates[step] ?: StepStatus.NOT_STARTED) !in terminalStatuses
        } ?: OnboardingStep.COLD_SYNC

    fun resumeRoute(stepStates: Map<OnboardingStep, StepStatus>): String = when (firstIncompleteStep(stepStates)) {
        OnboardingStep.TERMS -> BecalmRoute.Terms.path
        OnboardingStep.LOGIN -> BecalmRoute.Login.path
        OnboardingStep.PIPA_CONSENT -> BecalmRoute.OnboardingPipaConsent.path
        OnboardingStep.RECORDING_FOLDER -> BecalmRoute.OnboardingRecordingFolder.path
        OnboardingStep.CALL_LOG_MATCHING -> BecalmRoute.OnboardingCallLogMatching.path
        OnboardingStep.CONTACTS_PERM -> BecalmRoute.OnboardingContacts.path
        OnboardingStep.LINK_GMAIL,
        OnboardingStep.LINK_OUTLOOK_MAIL,
        OnboardingStep.LINK_IMAP,
        OnboardingStep.LINK_GOOGLE_CALENDAR,
        OnboardingStep.LINK_OUTLOOK_CALENDAR,
        -> BecalmRoute.OnboardingSources.path
        OnboardingStep.NOTIFICATION_PERM -> BecalmRoute.OnboardingNotificationPerm.path
        OnboardingStep.BATTERY_OPT -> BecalmRoute.OnboardingBattery.path
        OnboardingStep.COLD_SYNC -> BecalmRoute.OnboardingColdSync.path
    }
}
