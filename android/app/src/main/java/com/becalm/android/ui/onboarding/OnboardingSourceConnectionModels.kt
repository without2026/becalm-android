package com.becalm.android.ui.onboarding

import com.becalm.android.data.local.datastore.EmailPipaProvider

public enum class OnboardingSourceProvider(
    public val step: OnboardingStep,
) {
    GMAIL(OnboardingStep.LINK_GMAIL),
    OUTLOOK_MAIL(OnboardingStep.LINK_OUTLOOK_MAIL),
    GOOGLE_CALENDAR(OnboardingStep.LINK_GOOGLE_CALENDAR),
    OUTLOOK_CALENDAR(OnboardingStep.LINK_OUTLOOK_CALENDAR),
}

internal val OnboardingSourceProvider.emailProvider: EmailPipaProvider?
    get() = when (this) {
        OnboardingSourceProvider.GMAIL -> EmailPipaProvider.GMAIL
        OnboardingSourceProvider.OUTLOOK_MAIL -> EmailPipaProvider.OUTLOOK_MAIL
        OnboardingSourceProvider.GOOGLE_CALENDAR,
        OnboardingSourceProvider.OUTLOOK_CALENDAR,
        -> null
    }

internal val OnboardingSourceProvider.calendarProvider: CalendarOAuthProvider?
    get() = when (this) {
        OnboardingSourceProvider.GOOGLE_CALENDAR -> CalendarOAuthProvider.GOOGLE_CALENDAR
        OnboardingSourceProvider.OUTLOOK_CALENDAR -> CalendarOAuthProvider.OUTLOOK_CALENDAR
        OnboardingSourceProvider.GMAIL,
        OnboardingSourceProvider.OUTLOOK_MAIL,
        -> null
    }

internal fun EmailPipaProvider.onboardingSourceProvider(): OnboardingSourceProvider? =
    when (this) {
        EmailPipaProvider.GMAIL -> OnboardingSourceProvider.GMAIL
        EmailPipaProvider.OUTLOOK_MAIL -> OnboardingSourceProvider.OUTLOOK_MAIL
        EmailPipaProvider.NAVER_IMAP,
        EmailPipaProvider.DAUM_IMAP,
        -> null
    }

internal fun CalendarOAuthProvider.onboardingSourceProvider(): OnboardingSourceProvider =
    when (this) {
        CalendarOAuthProvider.GOOGLE_CALENDAR -> OnboardingSourceProvider.GOOGLE_CALENDAR
        CalendarOAuthProvider.OUTLOOK_CALENDAR -> OnboardingSourceProvider.OUTLOOK_CALENDAR
    }

public enum class SourceConnectionCategory {
    Mail,
    Calendar,
}

public enum class SourceConnectionState {
    Idle,
    ConsentRequired,
    Connecting,
    PendingExternalAuth,
    Connected,
    Skipped,
    Failed,
}

public data class SourceConnectionItemUi(
    val provider: OnboardingSourceProvider,
    val category: SourceConnectionCategory,
    val title: String,
    val description: String,
    val consentCopy: String?,
    val state: SourceConnectionState,
)
