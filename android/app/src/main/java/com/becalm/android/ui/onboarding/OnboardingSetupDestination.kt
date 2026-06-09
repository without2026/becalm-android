package com.becalm.android.ui.onboarding

import com.becalm.android.ui.navigation.BecalmRoute

internal enum class OnboardingSetupDestination(
    val routePath: String,
    val introPageIndex: Int?,
    val setupStage: OnboardingSetupStage,
) {
    Welcome(
        routePath = BecalmRoute.OnboardingSetupWelcome.path,
        introPageIndex = 0,
        setupStage = OnboardingSetupStage.INTRO,
    ),
    Identity(
        routePath = BecalmRoute.OnboardingSetupIdentity.path,
        introPageIndex = 1,
        setupStage = OnboardingSetupStage.INTRO,
    ),
    DeviceSources(
        routePath = BecalmRoute.OnboardingSetupDeviceSources.path,
        introPageIndex = 2,
        setupStage = OnboardingSetupStage.INTRO,
    ),
    Calendar(
        routePath = BecalmRoute.OnboardingSetupCalendar.path,
        introPageIndex = 3,
        setupStage = OnboardingSetupStage.INTRO,
    ),
    Email(
        routePath = BecalmRoute.OnboardingSetupEmail.path,
        introPageIndex = 4,
        setupStage = OnboardingSetupStage.INTRO,
    ),
    ReadyToStart(
        routePath = BecalmRoute.OnboardingSetupReadyToStart.path,
        introPageIndex = null,
        setupStage = OnboardingSetupStage.READY_TO_START,
    ),
    GmailPreview(
        routePath = BecalmRoute.OnboardingSetupGmailPreview.path,
        introPageIndex = null,
        setupStage = OnboardingSetupStage.GMAIL_PREVIEW,
    ),
    FirstMemory(
        routePath = BecalmRoute.OnboardingSetupFirstMemory.path,
        introPageIndex = null,
        setupStage = OnboardingSetupStage.FIRST_MEMORY,
    );

    companion object {
        val defaultRoutePath: String = Welcome.routePath

        fun fromRoutePath(routePath: String?): OnboardingSetupDestination? =
            entries.firstOrNull { it.routePath == routePath }

        fun isSetupRoute(routePath: String?): Boolean =
            fromRoutePath(routePath) != null

        fun fromIntroPageIndex(index: Int): OnboardingSetupDestination =
            when (index.coerceIn(0, ONBOARDING_INTRO_PAGE_COUNT - 1)) {
                0 -> Welcome
                1 -> Identity
                2 -> DeviceSources
                3 -> Calendar
                else -> Email
            }

        fun fromState(state: OnboardingUiState): OnboardingSetupDestination =
            when (state.setupStage) {
                OnboardingSetupStage.INTRO -> fromIntroPageIndex(state.introPageIndex)
                OnboardingSetupStage.READY_TO_START -> ReadyToStart
                OnboardingSetupStage.GMAIL_PREVIEW -> GmailPreview
                OnboardingSetupStage.FIRST_MEMORY -> FirstMemory
            }

        fun defaultForFirstIncompleteStep(step: OnboardingStep): OnboardingSetupDestination =
            when (step) {
                OnboardingStep.TERMS,
                OnboardingStep.LOGIN,
                -> Welcome
                OnboardingStep.PIPA_CONSENT,
                OnboardingStep.RECORDING_FOLDER,
                OnboardingStep.CALL_LOG_MATCHING,
                OnboardingStep.CONTACTS_PERM,
                -> DeviceSources
                OnboardingStep.LINK_GOOGLE_CALENDAR,
                OnboardingStep.LINK_OUTLOOK_CALENDAR,
                -> Calendar
                OnboardingStep.LINK_GMAIL,
                OnboardingStep.LINK_OUTLOOK_MAIL,
                OnboardingStep.LINK_IMAP,
                OnboardingStep.NOTIFICATION_PERM,
                OnboardingStep.BATTERY_OPT,
                OnboardingStep.COLD_SYNC,
                -> Email
            }
    }
}
