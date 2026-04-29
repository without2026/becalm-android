package com.becalm.android.ui.onboarding

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import com.becalm.android.core.util.Logger
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.ErrorEnvelopeDto
import com.squareup.moshi.Moshi
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Thin production seam for calendar OAuth launches.
 *
 * The real Google / Microsoft interactive flows are external-integration owners and are
 * intentionally left out of the local test surface. Production therefore depends on this
 * explicit connector rather than faking success inside the screen.
 */
@Singleton
public class CalendarOAuthConnector @Inject constructor(
    private val railwayApiProvider: Provider<RailwayApi>,
    private val moshi: Moshi,
    private val logger: Logger,
) {

    private val railwayApi: RailwayApi
        get() = railwayApiProvider.get()

    public suspend fun startSignIn(
        provider: CalendarOAuthProvider,
        activity: Activity,
    ): CalendarOAuthResult {
        val startResponse = railwayApi.startCalendarOAuth(provider.sourceType)
        if (!startResponse.isSuccessful) {
            return CalendarOAuthResult.Failed(errorCode = parseErrorCode(startResponse.errorBody()?.string()))
        }
        val authorizationUrl = startResponse.body()?.authorizationUrl
            ?: return CalendarOAuthResult.Failed(errorCode = "oauth_start_failed")

        try {
            activity.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(authorizationUrl)),
            )
        } catch (_: ActivityNotFoundException) {
            return CalendarOAuthResult.Failed(errorCode = "browser_unavailable")
        }

        return CalendarOAuthResult.NotConnected
    }

    /**
     * Re-checks backend OAuth state without launching the browser.
     *
     * Screens call this on foreground resume so a completed external-browser callback can
     * advance onboarding/settings even if the original polling coroutine was interrupted.
     */
    public suspend fun refreshConnectionStatus(provider: CalendarOAuthProvider): CalendarOAuthResult {
        val statusResponse = railwayApi.getCalendarOAuthStatus(provider.sourceType)
        if (!statusResponse.isSuccessful) {
            return CalendarOAuthResult.Failed(errorCode = parseErrorCode(statusResponse.errorBody()?.string()))
        }
        val statusBody = statusResponse.body() ?: return CalendarOAuthResult.Failed(errorCode = "oauth_status_empty")
        if (!statusBody.connected) return CalendarOAuthResult.NotConnected

        val syncResponse = railwayApi.syncCalendarEvents()
        if (!syncResponse.isSuccessful) {
            logger.w(TAG, "calendar sync after connect failed code=${syncResponse.code()}")
        }
        return CalendarOAuthResult.Connected
    }

    private fun parseErrorCode(rawBody: String?): String {
        if (rawBody.isNullOrBlank()) return "unknown"
        return runCatching {
            moshi.adapter(ErrorEnvelopeDto::class.java).fromJson(rawBody)?.error
        }.getOrNull() ?: "unknown"
    }

    private companion object {
        private const val TAG = "CalendarOAuthConnector"
    }
}

public enum class CalendarOAuthProvider(
    public val sourceType: String,
    public val step: OnboardingStep,
) {
    GOOGLE_CALENDAR(
        sourceType = com.becalm.android.data.remote.dto.SourceType.GOOGLE_CALENDAR,
        step = OnboardingStep.LINK_GOOGLE_CALENDAR,
    ),
    OUTLOOK_CALENDAR(
        sourceType = com.becalm.android.data.remote.dto.SourceType.OUTLOOK_CALENDAR,
        step = OnboardingStep.LINK_OUTLOOK_CALENDAR,
    ),
}

public sealed interface CalendarOAuthResult {
    public data object Connected : CalendarOAuthResult

    public data object NotConnected : CalendarOAuthResult

    public data class Failed(
        val errorCode: String,
    ) : CalendarOAuthResult
}
