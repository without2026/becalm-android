package com.becalm.android.ui.onboarding

internal object OnboardingTerminalStatusPolicy {
    private val terminalStatuses = setOf(
        StepStatus.GRANTED,
        StepStatus.COMPLETE,
        StepStatus.SKIPPED,
        StepStatus.DENIED,
    )

    fun isTerminal(status: StepStatus): Boolean = status in terminalStatuses
}
