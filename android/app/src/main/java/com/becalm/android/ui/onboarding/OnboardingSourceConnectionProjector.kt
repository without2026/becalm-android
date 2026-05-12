package com.becalm.android.ui.onboarding

import androidx.annotation.StringRes
import com.becalm.android.R
import com.becalm.android.data.local.datastore.EmailPipaProvider

internal object SourceConnectionProjector {
    fun sourceConnectionItems(
        stepStates: Map<OnboardingStep, StepStatus>,
        transientStates: Map<OnboardingSourceProvider, SourceConnectionState>,
        respectStepStates: Boolean = true,
        includeCalendarSources: Boolean = true,
        stringFor: (Int) -> String,
    ): List<SourceConnectionItemUi> =
        sourceSpecs
            .filter { spec -> includeCalendarSources || spec.category != SourceConnectionCategory.Calendar }
            .map { spec ->
                SourceConnectionItemUi(
                    provider = spec.provider,
                    category = spec.category,
                    title = stringFor(spec.titleRes),
                    description = stringFor(spec.descriptionRes),
                    consentCopy = spec.consentRes?.let(stringFor),
                    state = sourceStateFor(
                        provider = spec.provider,
                        stepStates = stepStates,
                        transientStates = transientStates,
                        respectStepStates = respectStepStates,
                        defaultState = spec.defaultState,
                    ),
                )
            }

    fun sourceStateFor(
        provider: OnboardingSourceProvider,
        stepStates: Map<OnboardingStep, StepStatus>,
        transientStates: Map<OnboardingSourceProvider, SourceConnectionState>,
        respectStepStates: Boolean,
        defaultState: SourceConnectionState = SourceConnectionState.Idle,
    ): SourceConnectionState {
        if (!respectStepStates) return transientStates[provider] ?: defaultState
        return when (stepStates[provider.step] ?: StepStatus.NOT_STARTED) {
            StepStatus.GRANTED,
            StepStatus.COMPLETE,
            -> SourceConnectionState.Connected
            StepStatus.SKIPPED,
            StepStatus.DENIED,
            -> SourceConnectionState.Skipped
            StepStatus.IN_PROGRESS,
            StepStatus.NOT_STARTED,
            -> transientStates[provider] ?: defaultState
        }
    }

    @StringRes
    fun emailErrorMessageRes(
        provider: EmailPipaProvider,
        errorCode: String,
    ): Int =
        when (provider) {
            EmailPipaProvider.GMAIL -> when (errorCode) {
                "network_error",
                "network",
                -> R.string.onb_gmail_error_network
                "scope_denied" -> R.string.onb_gmail_error_permission_denied
                "pipa_consent_missing" -> R.string.onb_sources_consent_write_failed
                else -> R.string.onb_gmail_error_unknown
            }
            EmailPipaProvider.OUTLOOK_MAIL -> when (errorCode) {
                "network_error",
                "network",
                -> R.string.onb_outlook_error_network
                "scope_denied" -> R.string.onb_outlook_error_permission_denied
                "pipa_consent_missing" -> R.string.onb_sources_consent_write_failed
                else -> R.string.onb_outlook_error_unknown
            }
            EmailPipaProvider.NAVER_IMAP,
            EmailPipaProvider.DAUM_IMAP,
            -> R.string.onb_imap_error_save_failed
        }

    @StringRes
    fun calendarErrorMessageRes(
        provider: CalendarOAuthProvider,
        errorCode: String,
    ): Int =
        when (provider) {
            CalendarOAuthProvider.GOOGLE_CALENDAR -> when (errorCode) {
                "not_implemented",
                "oauth_not_configured",
                -> R.string.onb_gcal_error_unavailable
                else -> R.string.onb_gcal_error_unknown
            }
            CalendarOAuthProvider.OUTLOOK_CALENDAR -> when (errorCode) {
                "not_implemented",
                "oauth_not_configured",
                -> R.string.onb_outlook_cal_error_unavailable
                else -> R.string.onb_outlook_cal_error_unknown
            }
        }

    private val sourceSpecs = listOf(
        SourceConnectionSpec(
            provider = OnboardingSourceProvider.GMAIL,
            category = SourceConnectionCategory.Mail,
            titleRes = R.string.onb_gmail_title,
            descriptionRes = R.string.onb_gmail_body,
            consentRes = R.string.onb_sources_mail_consent_body,
            defaultState = SourceConnectionState.ConsentRequired,
        ),
        SourceConnectionSpec(
            provider = OnboardingSourceProvider.OUTLOOK_MAIL,
            category = SourceConnectionCategory.Mail,
            titleRes = R.string.onb_outlook_mail_title,
            descriptionRes = R.string.onb_outlook_mail_body,
            consentRes = R.string.onb_sources_mail_consent_body,
            defaultState = SourceConnectionState.ConsentRequired,
        ),
        SourceConnectionSpec(
            provider = OnboardingSourceProvider.GOOGLE_CALENDAR,
            category = SourceConnectionCategory.Calendar,
            titleRes = R.string.onb_gcal_title,
            descriptionRes = R.string.onb_gcal_body,
            consentRes = null,
            defaultState = SourceConnectionState.Idle,
        ),
        SourceConnectionSpec(
            provider = OnboardingSourceProvider.OUTLOOK_CALENDAR,
            category = SourceConnectionCategory.Calendar,
            titleRes = R.string.onb_outlook_cal_title,
            descriptionRes = R.string.onb_outlook_cal_body,
            consentRes = null,
            defaultState = SourceConnectionState.Idle,
        ),
    )
}

internal object SourceConnectionCopy {
    fun copyFor(entryPoint: SourceConnectionsEntryPoint): SourceConnectionsCopyRes =
        when (entryPoint) {
            SourceConnectionsEntryPoint.Setup -> SourceConnectionsCopyRes(
                titleRes = R.string.onb_setup_title,
                headlineRes = R.string.onb_setup_headline,
                bodyRes = R.string.onb_setup_body,
            )
            SourceConnectionsEntryPoint.Onboarding -> SourceConnectionsCopyRes(
                titleRes = R.string.onb_sources_title,
                headlineRes = R.string.onb_sources_headline,
                bodyRes = R.string.onb_sources_body,
            )
            SourceConnectionsEntryPoint.Settings -> SourceConnectionsCopyRes(
                titleRes = R.string.settings_source_connections_title,
                headlineRes = R.string.settings_source_connections_headline,
                bodyRes = R.string.settings_source_connections_body,
            )
        }

    fun continueLabelRes(
        entryPoint: SourceConnectionsEntryPoint,
        hasIncomplete: Boolean,
    ): Int =
        when {
            entryPoint == SourceConnectionsEntryPoint.Setup -> R.string.onb_setup_start
            entryPoint == SourceConnectionsEntryPoint.Settings -> R.string.settings_source_connections_done
            hasIncomplete -> R.string.onb_sources_skip_remaining
            else -> R.string.onb_sources_continue
        }

    fun skipLabelRes(entryPoint: SourceConnectionsEntryPoint): Int =
        when (entryPoint) {
            SourceConnectionsEntryPoint.Setup,
            SourceConnectionsEntryPoint.Onboarding,
            -> R.string.action_skip
            SourceConnectionsEntryPoint.Settings -> R.string.settings_source_connections_not_now
        }
}

internal data class SourceConnectionsCopyRes(
    @StringRes val titleRes: Int,
    @StringRes val headlineRes: Int,
    @StringRes val bodyRes: Int,
)

private data class SourceConnectionSpec(
    val provider: OnboardingSourceProvider,
    val category: SourceConnectionCategory,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    @StringRes val consentRes: Int?,
    val defaultState: SourceConnectionState,
)
