package com.becalm.android.ui.onboarding

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import com.becalm.android.core.analytics.NoopProductAnalyticsClient
import com.becalm.android.core.analytics.ProductAnalyticsClient
import com.becalm.android.core.analytics.ProductAnalyticsEvent
import com.becalm.android.core.analytics.ProductAnalyticsEvents
import com.becalm.android.core.util.Logger
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.ErrorEnvelopeDto
import com.squareup.moshi.Moshi
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.datetime.Clock

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
    private val productAnalytics: ProductAnalyticsClient = NoopProductAnalyticsClient(),
) {

    private val railwayApi: RailwayApi
        get() = railwayApiProvider.get()

    public suspend fun startSignIn(
        provider: CalendarOAuthProvider,
        activity: Activity,
    ): CalendarOAuthResult {
        logger.i(TAG, "calendar OAuth start request provider=${provider.sourceType}")
        trackOAuthEvent(
            eventName = ProductAnalyticsEvents.SOURCE_OAUTH_STARTED,
            provider = provider.sourceType,
            phase = "start",
        )
        val startResponse = try {
            railwayApi.startCalendarOAuth(provider.sourceType)
        } catch (e: IOException) {
            // Mirror EmailOAuthConnector — network errors must convert to a Failed
            // result, never propagate as uncaught exceptions to viewModelScope.
            logger.w(TAG, "calendar OAuth start network error provider=${provider.sourceType} error=${e.javaClass.simpleName}", e)
            return CalendarOAuthResult.Failed(errorCode = "network_error")
        }
        logger.i(
            TAG,
            "calendar OAuth start response provider=${provider.sourceType} code=${startResponse.code()} success=${startResponse.isSuccessful}",
        )
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
            trackOAuthStatus(provider.sourceType, phase = "browser_open", connected = false, result = "browser_unavailable")
            return CalendarOAuthResult.Failed(errorCode = "browser_unavailable")
        }

        logger.i(TAG, "calendar OAuth browser launched provider=${provider.sourceType}")
        trackOAuthEvent(
            eventName = ProductAnalyticsEvents.SOURCE_OAUTH_BROWSER_OPENED,
            provider = provider.sourceType,
            phase = "browser_open",
        )
        return CalendarOAuthResult.NotConnected
    }

    /**
     * Re-checks backend OAuth state without launching the browser.
     *
     * Screens call this on foreground resume so a completed external-browser callback can
     * advance onboarding/settings even if the original polling coroutine was interrupted.
     */
    public suspend fun refreshConnectionStatus(provider: CalendarOAuthProvider): CalendarOAuthResult {
        logger.i(TAG, "calendar OAuth status request provider=${provider.sourceType}")
        val statusResponse = try {
            railwayApi.getCalendarOAuthStatus(provider.sourceType)
        } catch (e: IOException) {
            logger.w(TAG, "calendar OAuth status network error provider=${provider.sourceType} error=${e.javaClass.simpleName}", e)
            trackOAuthStatus(provider.sourceType, phase = "status", connected = false, result = "network_error")
            return CalendarOAuthResult.Failed(errorCode = "network_error")
        }
        logger.i(
            TAG,
            "calendar OAuth status response provider=${provider.sourceType} code=${statusResponse.code()} success=${statusResponse.isSuccessful}",
        )
        if (!statusResponse.isSuccessful) {
            val errorCode = parseErrorCode(statusResponse.errorBody()?.string())
            trackOAuthStatus(provider.sourceType, phase = "status", connected = false, result = errorCode)
            return CalendarOAuthResult.Failed(errorCode = errorCode)
        }
        val statusBody = statusResponse.body()
            ?: run {
                trackOAuthStatus(provider.sourceType, phase = "status", connected = false, result = "oauth_status_empty")
                return CalendarOAuthResult.Failed(errorCode = "oauth_status_empty")
            }
        logger.i(TAG, "calendar OAuth status body provider=${provider.sourceType} connected=${statusBody.connected}")
        trackOAuthStatus(
            provider = provider.sourceType,
            phase = "status",
            connected = statusBody.connected,
            result = if (statusBody.connected) "connected" else "not_connected",
        )
        if (!statusBody.connected) return CalendarOAuthResult.NotConnected

        return CalendarOAuthResult.Connected
    }

    private fun trackOAuthEvent(eventName: String, provider: String, phase: String) {
        productAnalytics.track(
            ProductAnalyticsEvent(
                eventId = UUID.randomUUID().toString(),
                eventName = eventName,
                occurredAt = Clock.System.now(),
                properties = mapOf(
                    "source_type" to provider,
                    "provider_family" to "calendar",
                    "phase" to phase,
                ),
            ),
        )
    }

    private fun trackOAuthStatus(provider: String, phase: String, connected: Boolean, result: String) {
        productAnalytics.track(
            ProductAnalyticsEvent(
                eventId = UUID.randomUUID().toString(),
                eventName = ProductAnalyticsEvents.SOURCE_OAUTH_STATUS_CHECKED,
                occurredAt = Clock.System.now(),
                properties = mapOf(
                    "source_type" to provider,
                    "provider_family" to "calendar",
                    "phase" to phase,
                    "connected" to connected,
                    "result" to result,
                ),
            ),
        )
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
