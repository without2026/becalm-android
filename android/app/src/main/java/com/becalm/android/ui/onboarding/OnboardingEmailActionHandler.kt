package com.becalm.android.ui.onboarding

import com.becalm.android.core.observability.ObservabilityClient
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.EmailPipaProvider
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.secure.ImapCredentialStore
import com.becalm.android.data.local.secure.ImapCredentials
import kotlinx.coroutines.flow.first

internal class OnboardingEmailActionHandler(
    private val userPrefsStore: UserPrefsStore,
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
                val skipped = providers.map(::linkStepFor).toSet()
                userPrefsStore.setOnboardingStepStatuses(
                    OnboardingProgressResolver.encodeStepStatuses(
                        skipped.associateWith { StepStatus.SKIPPED },
                    ),
                )
                updateState { state ->
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
            persistImapStepStatus(StepStatus.SKIPPED)
            updateState {
                it.copy(stepStates = it.stepStates + (OnboardingStep.LINK_IMAP to StepStatus.SKIPPED))
            }
            emitEvent(EmailConnectEvent.Failed(EmailPipaProvider.NAVER_IMAP, "unknown_provider"))
            return
        }

        val consented = userPrefsStore.observeEmailPipaConsent(pipaProvider).first()
        if (!consented) {
            reportStepFailed(OnboardingStep.LINK_IMAP, "pipa_consent_missing")
            persistImapStepStatus(StepStatus.SKIPPED)
            updateState {
                it.copy(stepStates = it.stepStates + (OnboardingStep.LINK_IMAP to StepStatus.SKIPPED))
            }
            emitEvent(EmailConnectEvent.Failed(pipaProvider, "pipa_consent_missing"))
            return
        }

        val result = runCatching { imapCredentialStore.save(sourceType, credentials) }
        if (result.isSuccess) {
            userPrefsStore.setEmailSourceConnected(pipaProvider, true)
            userPrefsStore.setEmailSourceManagedByBackend(pipaProvider, false)
            persistImapStepStatus(StepStatus.COMPLETE)
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
            persistImapStepStatus(StepStatus.SKIPPED)
            updateState {
                it.copy(stepStates = it.stepStates + (OnboardingStep.LINK_IMAP to StepStatus.SKIPPED))
            }
            emitEvent(EmailConnectEvent.Failed(pipaProvider, errorCode))
        }
    }

    private fun imapProviderFor(sourceType: String): EmailPipaProvider? = when (sourceType) {
        com.becalm.android.data.remote.dto.SourceType.NAVER_IMAP -> EmailPipaProvider.NAVER_IMAP
        com.becalm.android.data.remote.dto.SourceType.DAUM_IMAP -> EmailPipaProvider.DAUM_IMAP
        else -> null
    }

    private fun linkStepFor(provider: EmailPipaProvider): OnboardingStep = when (provider) {
        EmailPipaProvider.GMAIL -> OnboardingStep.LINK_GMAIL
        EmailPipaProvider.OUTLOOK_MAIL -> OnboardingStep.LINK_OUTLOOK_MAIL
        EmailPipaProvider.NAVER_IMAP,
        EmailPipaProvider.DAUM_IMAP,
        -> OnboardingStep.LINK_IMAP
    }

    private suspend fun persistImapStepStatus(status: StepStatus) {
        userPrefsStore.setOnboardingStepStatuses(
            OnboardingProgressResolver.encodeStepStatuses(
                mapOf(OnboardingStep.LINK_IMAP to status),
            ),
        )
    }

    private companion object {
        const val TAG: String = "OnboardingViewModel"
    }
}
