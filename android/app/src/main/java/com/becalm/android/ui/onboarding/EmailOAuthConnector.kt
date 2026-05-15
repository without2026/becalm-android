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
import java.io.IOException
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

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
        logger.i(TAG, "mail OAuth start request provider=${provider.sourceType}")
        val startResponse = try {
            railwayApi.startMailOAuth(provider.sourceType)
        } catch (e: IOException) {
            // Network failure (SocketTimeoutException, UnknownHostException, etc.)
            // must not propagate to the caller's coroutine — viewModelScope.launch
            // does not catch and the app crashes on the main thread. Convert to
            // a Failed result so the calling ViewModel can surface a Snackbar.
            logger.w(TAG, "mail OAuth start network error provider=${provider.sourceType} error=${e.javaClass.simpleName}", e)
            return EmailOAuthResult.Failed(errorCode = "network_error")
        }
        logger.i(
            TAG,
            "mail OAuth start response provider=${provider.sourceType} code=${startResponse.code()} success=${startResponse.isSuccessful}",
        )
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

        logger.i(TAG, "mail OAuth browser launched provider=${provider.sourceType}")
        return EmailOAuthResult.NotConnected
    }

    /**
     * Re-checks backend OAuth state without opening the browser.
     *
     * This is used when the user returns from the external browser callback. The original
     * polling coroutine may have been paused, cancelled, or timed out while the app was in
     * background, so foreground resume must be able to recover a successful connection.
     */
    public suspend fun refreshConnectionStatus(provider: EmailOAuthProvider): EmailOAuthResult {
        logger.i(TAG, "mail OAuth status request provider=${provider.sourceType}")
        val statusResponse = try {
            railwayApi.getMailOAuthStatus(provider.sourceType)
        } catch (e: IOException) {
            logger.w(TAG, "mail OAuth status network error provider=${provider.sourceType} error=${e.javaClass.simpleName}", e)
            return EmailOAuthResult.Failed(errorCode = "network_error")
        }
        logger.i(
            TAG,
            "mail OAuth status response provider=${provider.sourceType} code=${statusResponse.code()} success=${statusResponse.isSuccessful}",
        )
        if (!statusResponse.isSuccessful) {
            return EmailOAuthResult.Failed(errorCode = parseErrorCode(statusResponse.errorBody()?.string()))
        }
        val statusBody = statusResponse.body() ?: return EmailOAuthResult.Failed(errorCode = "oauth_status_empty")
        logger.i(TAG, "mail OAuth status body provider=${provider.sourceType} connected=${statusBody.connected}")
        if (!statusBody.connected) return EmailOAuthResult.NotConnected

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
