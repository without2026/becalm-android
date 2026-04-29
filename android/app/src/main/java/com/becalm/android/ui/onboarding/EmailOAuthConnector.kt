package com.becalm.android.ui.onboarding

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.EmailPipaProvider
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.ErrorEnvelopeDto
import com.becalm.android.data.remote.dto.SourceType
import com.squareup.moshi.Moshi
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.delay

/**
 * Thin production seam for backend-managed mail OAuth launches.
 */
@Singleton
public class EmailOAuthConnector @Inject constructor(
    private val railwayApiProvider: Provider<RailwayApi>,
    private val moshi: Moshi,
    private val logger: Logger,
) {

    private val railwayApi: RailwayApi
        get() = railwayApiProvider.get()

    public suspend fun startSignIn(
        provider: EmailOAuthProvider,
        activity: Activity,
    ): EmailOAuthResult {
        val startResponse = railwayApi.startMailOAuth(provider.sourceType)
        if (!startResponse.isSuccessful) {
            return EmailOAuthResult.Failed(errorCode = parseErrorCode(startResponse.errorBody()?.string()))
        }
        val authorizationUrl = startResponse.body()?.authorizationUrl
            ?: return EmailOAuthResult.Failed(errorCode = "oauth_start_failed")

        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authorizationUrl)))
        } catch (_: ActivityNotFoundException) {
            return EmailOAuthResult.Failed(errorCode = "browser_unavailable")
        }

        repeat(POLL_ATTEMPTS) {
            delay(POLL_INTERVAL_MILLIS)
            when (val status = refreshConnectionStatus(provider)) {
                EmailOAuthResult.Connected -> return EmailOAuthResult.Connected
                EmailOAuthResult.NotConnected -> return@repeat
                is EmailOAuthResult.Failed -> {
                    logger.w(TAG, "mail OAuth status poll failed error=${status.errorCode}")
                    return@repeat
                }
            }
        }

        return EmailOAuthResult.Failed(errorCode = "oauth_timeout")
    }

    /**
     * Re-checks backend OAuth state without opening the browser.
     *
     * This is used when the user returns from the external browser callback. The original
     * polling coroutine may have been paused, cancelled, or timed out while the app was in
     * background, so foreground resume must be able to recover a successful connection.
     */
    public suspend fun refreshConnectionStatus(provider: EmailOAuthProvider): EmailOAuthResult {
        val statusResponse = railwayApi.getMailOAuthStatus(provider.sourceType)
        if (!statusResponse.isSuccessful) {
            return EmailOAuthResult.Failed(errorCode = parseErrorCode(statusResponse.errorBody()?.string()))
        }
        val statusBody = statusResponse.body() ?: return EmailOAuthResult.Failed(errorCode = "oauth_status_empty")
        if (!statusBody.connected) return EmailOAuthResult.NotConnected

        val syncResponse = railwayApi.syncMailSource(provider.sourceType)
        if (!syncResponse.isSuccessful) {
            logger.w(TAG, "mail sync after connect failed code=${syncResponse.code()}")
        }
        return EmailOAuthResult.Connected
    }

    private fun parseErrorCode(rawBody: String?): String {
        if (rawBody.isNullOrBlank()) return "unknown"
        return runCatching {
            moshi.adapter(ErrorEnvelopeDto::class.java).fromJson(rawBody)?.error
        }.getOrNull() ?: "unknown"
    }

    private companion object {
        private const val TAG = "EmailOAuthConnector"
        private const val POLL_INTERVAL_MILLIS: Long = 2_000L
        private const val POLL_ATTEMPTS: Int = 60
    }
}

public enum class EmailOAuthProvider(
    public val sourceType: String,
    public val step: OnboardingStep,
    public val pipaProvider: EmailPipaProvider,
) {
    GMAIL(
        sourceType = SourceType.GMAIL,
        step = OnboardingStep.LINK_GMAIL,
        pipaProvider = EmailPipaProvider.GMAIL,
    ),
    OUTLOOK_MAIL(
        sourceType = SourceType.OUTLOOK_MAIL,
        step = OnboardingStep.LINK_OUTLOOK_MAIL,
        pipaProvider = EmailPipaProvider.OUTLOOK_MAIL,
    ),
}

public sealed interface EmailOAuthResult {
    public data object Connected : EmailOAuthResult

    public data object NotConnected : EmailOAuthResult

    public data class Failed(
        val errorCode: String,
    ) : EmailOAuthResult
}
