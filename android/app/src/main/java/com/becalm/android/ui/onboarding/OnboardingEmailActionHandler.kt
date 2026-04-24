package com.becalm.android.ui.onboarding

import android.app.Activity
import com.becalm.android.core.observability.ObservabilityClient
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.EmailPipaProvider
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.secure.ImapCredentialStore
import com.becalm.android.data.local.secure.ImapCredentials
import com.becalm.android.data.remote.gmail.FailureReason
import com.becalm.android.data.remote.gmail.GoogleAuthTokenProviderImpl
import com.becalm.android.data.remote.gmail.OAuthSignInResult
import com.becalm.android.data.remote.msgraph.MsGraphTokenProviderImpl
import kotlinx.coroutines.flow.first

internal class OnboardingEmailActionHandler(
    private val userPrefsStore: UserPrefsStore,
    private val googleAuthTokenProvider: GoogleAuthTokenProviderImpl,
    private val msGraphTokenProvider: MsGraphTokenProviderImpl,
    private val imapCredentialStore: ImapCredentialStore,
    private val observability: ObservabilityClient,
    private val logger: Logger,
) {

    suspend fun persistEmailPipaConsent(
        providers: List<EmailPipaProvider>,
        granted: Boolean,
        updateState: ((OnboardingUiState) -> OnboardingUiState) -> Unit,
        setError: (String) -> Unit,
    ): Boolean {
        require(providers.isNotEmpty()) { "providers must be non-empty" }
        return try {
            userPrefsStore.setEmailPipaConsents(providers, granted)
            for (provider in providers) {
                logger.i(TAG, "pipa email consent ${provider.storageKey}=$granted")
                observability.captureMessage(
                    message = "onboarding_pipa_email_consent",
                    tags = mapOf(
                        "provider" to provider.storageKey,
                        "granted" to granted.toString(),
                    ),
                )
            }
            if (!granted) {
                updateState { state ->
                    val skipped = providers.map(::linkStepFor).toSet()
                    state.copy(
                        stepStates = state.stepStates + skipped.associateWith { StepStatus.SKIPPED },
                    )
                }
            }
            true
        } catch (e: Exception) {
            logger.e(TAG, "pipa email consent write failed", e)
            setError(e.message ?: "consent write failed")
            false
        }
    }

    suspend fun connectEmailProvider(
        provider: EmailPipaProvider,
        activity: Activity,
        updateState: ((OnboardingUiState) -> OnboardingUiState) -> Unit,
        emitEvent: suspend (EmailConnectEvent) -> Unit,
        reportStepFailed: (OnboardingStep, String) -> Unit,
    ) {
        require(
            provider == EmailPipaProvider.GMAIL ||
                provider == EmailPipaProvider.OUTLOOK_MAIL,
        ) {
            "${provider.storageKey} uses saveImapCredentials(), not onConnectEmailProvider()"
        }

        val consented = userPrefsStore.observeEmailPipaConsent(provider).first()
        if (!consented) {
            val step = linkStepFor(provider)
            reportStepFailed(step, "pipa_consent_missing")
            updateState {
                it.copy(stepStates = it.stepStates + (step to StepStatus.SKIPPED))
            }
            emitEvent(EmailConnectEvent.Failed(provider, "pipa_consent_missing"))
            return
        }

        val result = runCatching {
            when (provider) {
                EmailPipaProvider.GMAIL -> googleAuthTokenProvider.startSignIn(activity)
                EmailPipaProvider.OUTLOOK_MAIL -> msGraphTokenProvider.startSignIn(activity)
                EmailPipaProvider.NAVER_IMAP,
                EmailPipaProvider.DAUM_IMAP -> error("unreachable — filtered by require guard")
            }
        }.getOrElse { t ->
            logger.e(TAG, "OAuth startSignIn threw for ${provider.storageKey}", t)
            OAuthSignInResult.Failure(FailureReason.UNKNOWN, t)
        }

        handleOAuthResult(
            provider = provider,
            result = result,
            updateState = updateState,
            emitEvent = emitEvent,
            reportStepFailed = reportStepFailed,
        )
    }

    suspend fun saveImapCredentials(
        sourceType: String,
        credentials: ImapCredentials,
        updateState: ((OnboardingUiState) -> OnboardingUiState) -> Unit,
        emitEvent: suspend (EmailConnectEvent) -> Unit,
        reportStepFailed: (OnboardingStep, String) -> Unit,
    ) {
        val pipaProvider = imapProviderFor(sourceType)
        if (pipaProvider == null) {
            reportStepFailed(OnboardingStep.LINK_IMAP, "unknown_provider")
            updateState {
                it.copy(stepStates = it.stepStates + (OnboardingStep.LINK_IMAP to StepStatus.SKIPPED))
            }
            emitEvent(EmailConnectEvent.Failed(EmailPipaProvider.NAVER_IMAP, "unknown_provider"))
            return
        }

        val consented = userPrefsStore.observeEmailPipaConsent(pipaProvider).first()
        if (!consented) {
            reportStepFailed(OnboardingStep.LINK_IMAP, "pipa_consent_missing")
            updateState {
                it.copy(stepStates = it.stepStates + (OnboardingStep.LINK_IMAP to StepStatus.SKIPPED))
            }
            emitEvent(EmailConnectEvent.Failed(pipaProvider, "pipa_consent_missing"))
            return
        }

        val result = runCatching { imapCredentialStore.save(sourceType, credentials) }
        if (result.isSuccess) {
            userPrefsStore.setEmailSourceConnected(pipaProvider, true)
            updateState {
                it.copy(stepStates = it.stepStates + (OnboardingStep.LINK_IMAP to StepStatus.COMPLETE))
            }
            observability.captureMessage(
                message = "onboarding_email_connected",
                tags = mapOf("provider" to pipaProvider.storageKey, "source_type" to sourceType),
            )
            emitEvent(EmailConnectEvent.Connected(pipaProvider))
        } else {
            val throwable = result.exceptionOrNull()
            val errorCode = when (throwable) {
                is IllegalArgumentException -> "unknown_provider"
                is java.io.IOException -> "network"
                else -> "save_failed"
            }
            logger.e(TAG, "IMAP save failed sourceType=$sourceType", throwable)
            reportStepFailed(OnboardingStep.LINK_IMAP, errorCode)
            updateState {
                it.copy(stepStates = it.stepStates + (OnboardingStep.LINK_IMAP to StepStatus.SKIPPED))
            }
            emitEvent(EmailConnectEvent.Failed(pipaProvider, errorCode))
        }
    }

    private suspend fun handleOAuthResult(
        provider: EmailPipaProvider,
        result: OAuthSignInResult,
        updateState: ((OnboardingUiState) -> OnboardingUiState) -> Unit,
        emitEvent: suspend (EmailConnectEvent) -> Unit,
        reportStepFailed: (OnboardingStep, String) -> Unit,
    ) {
        when (result) {
            is OAuthSignInResult.Success -> {
                userPrefsStore.setEmailSourceConnected(provider, true)
                val step = linkStepFor(provider)
                updateState {
                    it.copy(stepStates = it.stepStates + (step to StepStatus.COMPLETE))
                }
                observability.captureMessage(
                    message = "onboarding_email_connected",
                    tags = mapOf("provider" to provider.storageKey),
                )
                emitEvent(EmailConnectEvent.Connected(provider))
            }
            is OAuthSignInResult.ResolutionRequired -> {
                emitEvent(EmailConnectEvent.PendingIntentRequired(provider, result.pendingIntent))
            }
            is OAuthSignInResult.Failure -> {
                val errorCode = failureReasonCode(result.reason)
                val step = linkStepFor(provider)
                reportStepFailed(step, errorCode)
                updateState {
                    it.copy(stepStates = it.stepStates + (step to StepStatus.SKIPPED))
                }
                emitEvent(EmailConnectEvent.Failed(provider, errorCode))
            }
        }
    }

    private fun failureReasonCode(reason: FailureReason): String = when (reason) {
        FailureReason.USER_CANCELLED -> "user_cancelled"
        FailureReason.PLAY_SERVICES_UNAVAILABLE -> "play_services_unavailable"
        FailureReason.NETWORK -> "network"
        FailureReason.SCOPE_DENIED -> "scope_denied"
        FailureReason.UNKNOWN -> "unknown"
    }

    private fun linkStepFor(provider: EmailPipaProvider): OnboardingStep = when (provider) {
        EmailPipaProvider.GMAIL -> OnboardingStep.LINK_GMAIL
        EmailPipaProvider.OUTLOOK_MAIL -> OnboardingStep.LINK_OUTLOOK_MAIL
        EmailPipaProvider.NAVER_IMAP, EmailPipaProvider.DAUM_IMAP -> OnboardingStep.LINK_IMAP
    }

    private fun imapProviderFor(sourceType: String): EmailPipaProvider? = when (sourceType) {
        com.becalm.android.data.remote.dto.SourceType.NAVER_IMAP -> EmailPipaProvider.NAVER_IMAP
        com.becalm.android.data.remote.dto.SourceType.DAUM_IMAP -> EmailPipaProvider.DAUM_IMAP
        else -> null
    }

    private companion object {
        const val TAG: String = "OnboardingViewModel"
    }
}
